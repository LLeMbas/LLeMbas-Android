package eu.llembas.android.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.GeneratedImage
import eu.llembas.android.api.GeneratedMedia
import eu.llembas.android.api.Raw
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The pictures the instance made, fetched the two ways the app fetches a chat's files: by
 * attachment id, or -- for a generated file with no attachment -- by its node id in the
 * person's Files; always from the instance it is signed in to, with its token. There is no URL
 * to follow -- a model's words can name an address, but never a picture this draws -- and
 * nothing bigger than [MAX_BYTES] is decoded.
 */
private object Pictures {
    /** Longest edge decoded: enough for a phone held either way, small enough to keep a few. */
    private const val EDGE = 1600
    const val MAX_BYTES = 24L * 1024 * 1024
    private val cache = LruCache<String, ImageBitmap>(8)
    private val failed = mutableSetOf<String>()
    private val gone = mutableSetOf<String>()

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** Whether the instance said there is no such file (any more). */
    fun isGone(key: String): Boolean = key in gone

    suspend fun load(context: Context, key: String, fetch: suspend (ApiClient) -> ByteArray): ImageBitmap? {
        cache.get(key)?.let { return it }
        if (key in failed) return null
        val api = context.lembas.instances.client() ?: return null
        val bytes = try {
            fetch(api).takeIf { it.size <= MAX_BYTES }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiException && e.status == 404) gone += key
            null
        }
        val image = bytes?.let { decode(it) }
        if (image == null) failed += key else cache.put(key, image)
        return image
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= EDGE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

/**
 * A picture `image_generate` made, in the reply: as wide as the reply allows (at most 480 dp),
 * its height from the size the server said, so the transcript does not jump when it arrives. A
 * tap shows it on its own, whole, on the page's ground; a tap or Back closes it.
 */
@Composable
fun GeneratedPicture(image: GeneratedImage, modifier: Modifier = Modifier) {
    if (image.id.isBlank()) return
    Picture("attachment:" + image.id, image.width, image.height, modifier) { api -> api.attachmentBytes(image.id) }
}

/**
 * A generated picture that is a file of the person's Files and no attachment: fetched by its
 * node id from `/files/raw`, drawn the same way. One the person has since put in the trash
 * says that it is no longer there.
 */
@Composable
fun GeneratedPicture(media: GeneratedMedia, modifier: Modifier = Modifier) {
    if (media.nodeId.isBlank()) return
    Picture("node:" + media.nodeId, media.width, media.height, modifier) { api ->
        // What the server says it weighs decides before a byte is asked for.
        if (media.size > Pictures.MAX_BYTES) throw ApiException(413, "")
        val held = ByteArrayOutputStream()
        api.download(api.raw(Raw.Node(media.nodeId), inline = true), held, most = Pictures.MAX_BYTES)
        held.toByteArray()
    }
}

/**
 * The shape a picture's place is given before its bytes are there: width over height, from the
 * size the server said, kept between a quarter and four. A size the server did not say is a
 * square. The place is that shape from its first draw to its last, so the picture arriving (or
 * not arriving) moves nothing in the transcript.
 */
internal fun pictureRatio(width: Int, height: Int): Float = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.25f, 4f) else 1f

@Composable
private fun Picture(key: String, width: Int, height: Int, modifier: Modifier, fetch: suspend (ApiClient) -> ByteArray) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val still = LocalInspectionMode.current
    var bitmap by remember(key) { mutableStateOf(Pictures.cached(key)) }
    var gone by remember(key) { mutableStateOf(Pictures.isGone(key)) }
    var whole by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (bitmap == null && !still) {
            bitmap = Pictures.load(context, key, fetch)
            gone = Pictures.isGone(key)
        }
    }
    val ratio = pictureRatio(width, height)
    val shape = RoundedCornerShape(16.dp)
    val described = stringResource(R.string.image_generated)
    Box(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(shape)
            .background(palette.surfaceRaised)
            .border(1.dp, palette.border, shape)
            .then(if (bitmap != null) Modifier.clickable { whole = true } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = described, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (gone) {
            Text(stringResource(R.string.media_gone), color = palette.inkMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        } else {
            Icon(Icons.Filled.Image, contentDescription = described, tint = palette.inkFaint, modifier = Modifier.size(32.dp))
        }
    }
    val shown = bitmap
    if (whole && shown != null) {
        Dialog(onDismissRequest = { whole = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(palette.bg).clickable { whole = false }, contentAlignment = Alignment.Center) {
                Image(shown, contentDescription = described, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
