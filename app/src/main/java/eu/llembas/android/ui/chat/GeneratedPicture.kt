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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
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
import eu.llembas.android.api.GeneratedImage
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures the instance made, fetched the one way the app fetches a chat's files: by
 * attachment id, from the instance it is signed in to, with its token. There is no URL to
 * follow -- a model's words can name an address, but never a picture this draws -- and nothing
 * bigger than [MAX_BYTES] is decoded.
 */
private object Pictures {
    /** Longest edge decoded: enough for a phone held either way, small enough to keep a few. */
    private const val EDGE = 1600
    private const val MAX_BYTES = 24 * 1024 * 1024
    private val cache = LruCache<String, ImageBitmap>(8)
    private val failed = mutableSetOf<String>()

    fun cached(id: String): ImageBitmap? = cache.get(id)

    suspend fun load(context: Context, id: String): ImageBitmap? {
        cache.get(id)?.let { return it }
        if (id.isBlank() || id in failed) return null
        val api = context.lembas.instances.client() ?: return null
        val bytes = runCatching { api.attachmentBytes(id) }.getOrNull()?.takeIf { it.size <= MAX_BYTES }
        val image = bytes?.let { decode(it) }
        if (image == null) failed += id else cache.put(id, image)
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
    val palette = LocalPalette.current
    val context = LocalContext.current
    val still = LocalInspectionMode.current
    var bitmap by remember(image.id) { mutableStateOf(Pictures.cached(image.id)) }
    var whole by remember { mutableStateOf(false) }
    LaunchedEffect(image.id) { if (bitmap == null && !still) bitmap = Pictures.load(context, image.id) }
    val ratio = if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else 1f
    val shape = RoundedCornerShape(16.dp)
    val described = stringResource(R.string.image_generated)
    Box(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.25f, 4f))
            .clip(shape)
            .background(palette.surfaceRaised)
            .border(1.dp, palette.border, shape)
            .then(if (bitmap != null) Modifier.clickable { whole = true } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = described, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
