package eu.llembas.android.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.RangeReader
import eu.llembas.android.api.sampleFor
import eu.llembas.android.lembas
import eu.llembas.android.ui.files.FileCache
import eu.llembas.android.ui.files.copyInto
import eu.llembas.android.ui.files.stepped
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.unreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// The viewers that need the file's bytes: a picture, a PDF, sound and video. The bytes come
// from the instance the app is signed in to, with the token in the Authorization header -- the
// address handed to nothing: the platform's player reads through [RemoteMedia], which asks the
// app's own HTTP client for each piece. No player library is added for this.

/** The most a picture's file may weigh to be decoded on the phone, and a PDF's to be copied for its pages. */
const val IMAGE_MAX_BYTES = 64L * 1024 * 1024
const val PDF_MAX_BYTES = 256L * 1024 * 1024

/** How a viewer's bytes came: on their way ([done] of [total], -1 unknown), there, or not shown. */
sealed interface Fetched<out T> {
    data class Loading(val done: Long = 0, val total: Long = -1) : Fetched<Nothing>

    data class Ready<T>(val value: T) : Fetched<T>

    /** [message]: the server's sentence or the network's; null: this phone could not draw it. */
    data class Failed(val message: String?) : Fetched<Nothing>
}

/**
 * [file] decoded for the screen: its bounds are read first, and it is sampled down
 * ([sampleFor]) so neither edge nor the pixel count is more than a phone shows -- a picture
 * that claims a huge size costs a bounded amount of memory, or is not decoded at all.
 */
fun decodeCapped(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = sampleFor(bounds.outWidth, bounds.outHeight)
    repeat(3) {
        try {
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            sample *= 2
        }
    }
    return null
}

/** A picture of the tree: copied into the private cache (at most [IMAGE_MAX_BYTES]), decoded small, and the copy deleted at once. */
@Composable
fun ImageBody(path: String, name: String, size: Long, fallback: @Composable (String?) -> Unit) {
    val context = LocalContext.current
    val picture by produceState<Fetched<ImageBitmap>>(Fetched.Loading(), path) {
        if (size > IMAGE_MAX_BYTES) {
            value = Fetched.Failed(null)
            return@produceState
        }
        val copy = FileCache.viewing(context, name)
        value = try {
            val api = context.lembas.instances.client() ?: throw IOException(context.getString(R.string.assist_signed_out))
            api.copyInto(api.rawFile(path), copy, IMAGE_MAX_BYTES, stepped { done, total -> value = Fetched.Loading(done, total) })
            val bitmap = withContext(Dispatchers.Default) { decodeCapped(copy) }
            if (bitmap == null) Fetched.Failed(null) else Fetched.Ready(bitmap.asImageBitmap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Fetched.Failed(viewerSays(context, e))
        } finally {
            copy.delete()
        }
    }
    when (val now = picture) {
        is Fetched.Loading -> Progress(now)
        is Fetched.Failed -> fallback(now.message)
        is Fetched.Ready -> ZoomableImage(now.value, name)
    }
}

/** A transfer under way, in the middle of the page: how far, when the size is known. */
@Composable
internal fun Progress(now: Fetched.Loading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (now.total > 0) CircularProgressIndicator(progress = { (now.done.toFloat() / now.total).coerceIn(0f, 1f) }) else CircularProgressIndicator()
    }
}

/** A PDF's pages, drawn by the platform's own renderer from a copy in the private cache. One page is open at a time. */
class PdfPages(private val copy: File) {
    private val descriptor = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Throwable) {
        descriptor.close()
        throw e
    }
    private var closed = false
    val count: Int = renderer.pageCount

    /** Page [index] as pixels [width] wide; null once closed or when it cannot be drawn. */
    @Synchronized
    fun page(index: Int, width: Int): ImageBitmap? {
        if (closed || index !in 0 until count || width <= 0) return null
        return runCatching {
            renderer.openPage(index).use { page ->
                var w = width
                var h = (page.height.toLong() * w / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                // A page of an odd shape (a long receipt) is drawn smaller rather than as a huge bitmap.
                while (w.toLong() * h > PAGE_MAX_PIXELS) {
                    w /= 2
                    h /= 2
                }
                val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                // The paper: a PDF draws on transparent, and print is read on white in either theme.
                bitmap.eraseColor(PAPER)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }.getOrNull()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        copy.delete()
    }

    private companion object {
        const val PAGE_MAX_PIXELS = 6_000_000L
        const val PAPER = android.graphics.Color.WHITE
    }
}

/** A PDF, page by page, as wide as the screen. */
@Composable
fun PdfBody(path: String, name: String, size: Long, fallback: @Composable (String?) -> Unit) {
    val context = LocalContext.current
    var pages by remember(path) { mutableStateOf<Fetched<PdfPages>>(Fetched.Loading()) }
    DisposableEffect(path) {
        onDispose { (pages as? Fetched.Ready)?.value?.close() }
    }
    LaunchedEffect(path) {
        if (size > PDF_MAX_BYTES) {
            pages = Fetched.Failed(null)
            return@LaunchedEffect
        }
        val copy = FileCache.viewing(context, name)
        pages = try {
            val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
            api.copyInto(api.rawFile(path), copy, PDF_MAX_BYTES, stepped { done, total -> pages = Fetched.Loading(done, total) })
            Fetched.Ready(withContext(Dispatchers.IO) { PdfPages(copy) })
        } catch (e: CancellationException) {
            copy.delete()
            throw e
        } catch (e: Exception) {
            copy.delete()
            // A password, or not a PDF after all: the renderer's own words are for a developer.
            Fetched.Failed(if (e is ApiException || unreachable(e)) viewerSays(context, e) else null)
        }
    }
    when (val now = pages) {
        is Fetched.Loading -> Progress(now)
        is Fetched.Failed -> fallback(now.message)
        is Fetched.Ready -> PdfPagesList(now.value)
    }
}

@Composable
private fun PdfPagesList(pages: PdfPages) {
    val palette = LocalPalette.current
    val list = rememberLazyListState()
    // Read through a derived state: the line is drawn again when the page changes, not at every pixel of a scroll.
    val first by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    Column(Modifier.fillMaxSize().background(palette.bgSunken)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(1, 1600)
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pages.count) { index ->
                    val page by produceState<ImageBitmap?>(null, pages, index, width) {
                        value = withContext(Dispatchers.Default) { pages.page(index, width) }
                    }
                    val drawn = page
                    if (drawn != null) {
                        Image(drawn, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                    } else {
                        // Until it is drawn: a sheet of the usual shape, so the list does not jump far.
                        Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(palette.surfaceRaised))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.viewer_page, first.toString(), pages.count.toString()),
            style = MaterialTheme.typography.labelMedium,
            color = palette.inkMuted,
            modifier = Modifier.fillMaxWidth().background(palette.bg).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A file on the instance as the platform's player reads it: piece by piece, each a `Range`
 * request made by the app's own HTTP client with the token in its header. The player is never
 * given an address, so the token is never in one.
 */
class RemoteMedia(api: ApiClient, path: String, private val length: Long) : MediaDataSource() {
    private val reader = RangeReader(length) { from, to -> api.rawBytes(path, from, to) }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.read(position, buffer, offset, size)

    override fun getSize(): Long = length

    override fun close() = reader.close()
}

/**
 * Sound or video of the tree, streamed: it starts before the file is there, and seeking asks
 * for the piece it lands in. Leaving the app pauses it; leaving the viewer lets go of it.
 */
@Composable
fun MediaBody(path: String, size: Long, video: Boolean, fallback: @Composable (String?) -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var ui by remember(path) { mutableStateOf(PlayerUi()) }
    var failed by remember(path) { mutableStateOf(false) }
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember(path) { mutableStateOf<SurfaceHolder?>(null) }
    var ratio by remember(path) { mutableStateOf(16f / 9f) }

    DisposableEffect(path) {
        var held: MediaPlayer? = null
        var source: RemoteMedia? = null
        val starting = scope.launch {
            val api = context.lembas.instances.client()
            if (api == null || size <= 0) {
                failed = true
                return@launch
            }
            try {
                val media = RemoteMedia(api, path, size).also { source = it }
                val made = MediaPlayer().also { held = it }
                made.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                made.setDataSource(media)
                made.setOnPreparedListener {
                    ui = ui.copy(ready = true, durationMs = it.duration.coerceAtLeast(0))
                    player = it
                }
                made.setOnCompletionListener { ui = ui.copy(playing = false, positionMs = ui.durationMs) }
                made.setOnErrorListener { _, _, _ ->
                    failed = true
                    true
                }
                made.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) ratio = (w.toFloat() / h).coerceIn(0.3f, 4f) }
                made.prepareAsync()
            } catch (e: Exception) {
                failed = true
            }
        }
        onDispose {
            starting.cancel()
            player = null
            held?.runCatching { release() }
            source?.runCatching { close() }
        }
    }

    // The picture goes where the surface is, for as long as there is one.
    LaunchedEffect(player, surface) { player?.runCatching { setDisplay(surface) } }
    LaunchedEffect(player, ui.playing) {
        while (ui.playing) {
            player?.runCatching { currentPosition }?.getOrNull()?.let { ui = ui.copy(positionMs = it) }
            delay(500)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player?.runCatching { if (isPlaying) pause() }
        ui = ui.copy(playing = false)
    }

    if (failed) {
        fallback(null)
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (video) {
            AndroidView(
                factory = { viewContext ->
                    SurfaceView(viewContext).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    surface = holder
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    player?.runCatching { setDisplay(null) }
                                    surface = null
                                }
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(ratio),
            )
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.padding(24.dp).size(72.dp))
        }
        PlayerControls(
            ui,
            onToggle = {
                val now = player ?: return@PlayerControls
                runCatching {
                    if (now.isPlaying) {
                        now.pause()
                        ui = ui.copy(playing = false)
                    } else {
                        if (ui.durationMs > 0 && ui.positionMs >= ui.durationMs) now.seekTo(0)
                        now.start()
                        ui = ui.copy(playing = true)
                    }
                }
            },
            onSeek = { to ->
                player?.runCatching { seekTo(to) }
                ui = ui.copy(positionMs = to)
            },
        )
    }
}
