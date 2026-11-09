package eu.llembas.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Models' pictures, fetched once with the token and kept small in memory. A picture that
 * failed is not asked for again until the app starts anew; the initial stands in for it.
 */
private object ModelImages {
    private const val SIDE = 128
    private val cache = LruCache<String, ImageBitmap>(48)
    private val failed = mutableSetOf<String>()

    fun cached(path: String): ImageBitmap? = cache.get(path)

    suspend fun load(context: Context, path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        if (path in failed) return null
        val api = context.lembas.instances.client() ?: return null
        val bytes = runCatching { api.modelImage(path) }.getOrNull()
        val image = bytes?.let { decode(it) }
        if (image == null) failed += path else cache.put(path, image)
        return image
    }

    /** Decoded at about [SIDE] pixels a side: an avatar, not the original. */
    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

/**
 * A model's picture in a circle, as the web UI shows it beside the model's name: the picture
 * the instance has for it, or its initial on the theme's soft accent when there is none (or
 * while it loads, or when it could not be read).
 */
@Composable
fun ModelAvatar(model: ModelInfo?, fallbackName: String, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val path = model?.image
    var image by remember(path) { mutableStateOf(path?.let { ModelImages.cached(it) }) }
    val still = LocalInspectionMode.current
    LaunchedEffect(path) {
        if (path != null && image == null && !still) image = ModelImages.load(context, path)
    }
    Box(modifier.size(size).clip(CircleShape).background(palette.accentSoft), contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val name = model?.name?.ifBlank { null } ?: fallbackName
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = palette.ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() },
            )
        }
    }
}
