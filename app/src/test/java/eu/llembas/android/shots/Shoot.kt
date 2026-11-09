@file:OptIn(ExperimentalRoborazziApi::class)

package eu.llembas.android.shots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size
import eu.llembas.android.ui.theme.LembasTheme

/**
 * The phones the screens are looked at on: a large one, and a small one where things crowd.
 * [Scroll] is the large one's width, tall enough to see a sheet's whole scroll at once;
 * [SmallScroll] the same for the small one.
 */
enum class Phone(val tag: String, val widthDp: Int, val heightDp: Int) {
    Large("411x891", 411, 891),
    Small("360x640", 360, 640),
    Scroll("411x2000", 411, 2000),
    SmallScroll("360x2000", 360, 2000),
}

/**
 * Render [content] in the app's theme, light (Shire) and dark (Moria), at [phone]'s size and
 * write each to `app/build/screenshots/<phone>-<name>-<theme>.png`, which CI uploads. These are
 * for looking at -- by a person, or by an agent reading the PNGs -- not compared against
 * anything: a design change is meant to change them.
 */
fun shoot(name: String, phone: Phone = Phone.Large, fontScale: Float = 1f, content: @Composable () -> Unit) {
    for ((theme, choice) in listOf("light" to "shire", "dark" to "moria")) {
        val font = if (fontScale != 1f) "-font$fontScale" else ""
        captureRoboImage(
            filePath = "build/screenshots/${phone.tag}-$name-$theme$font.png",
            roborazziComposeOptions = RoborazziComposeOptions {
                size(phone.widthDp, phone.heightDp)
                // As a preview: no endless animation, no pictures fetched from an instance.
                inspectionMode(true)
                if (fontScale != 1f) fontScale(fontScale)
            },
        ) {
            LembasTheme(choice) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}
