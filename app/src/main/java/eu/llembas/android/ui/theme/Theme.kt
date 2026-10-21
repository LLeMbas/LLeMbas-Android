package eu.llembas.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** The palette in use, for the parts Material's scheme has no slot for (code, bubbles, soft tints). */
val LocalPalette = staticCompositionLocalOf { Moria }

private fun scheme(p: Palette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.accentInk,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.ink,
        secondary = p.leaf,
        onSecondary = p.inkInverse,
        background = p.bg,
        onBackground = p.ink,
        surface = p.surface,
        onSurface = p.ink,
        surfaceVariant = p.surfaceRaised,
        onSurfaceVariant = p.inkMuted,
        surfaceContainerLowest = p.bgSunken,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surfaceRaised,
        surfaceContainerHigh = p.surfaceHover,
        surfaceContainerHighest = p.surfaceActive,
        outline = p.borderStrong,
        outlineVariant = p.border,
        error = p.danger,
        onError = p.inkInverse,
        errorContainer = p.dangerSoft,
        onErrorContainer = p.ink,
    )
}

/**
 * Moria (dark) or Shire (light), from the server's own tokens (`Tokens.kt`, generated). Dynamic
 * colour is off on purpose: the app looks like the web UI it sits beside, not like the wallpaper.
 * An empty choice follows the system's light or dark setting.
 */
@Composable
fun LembasTheme(choice: String = "", content: @Composable () -> Unit) {
    val dark = when (choice) {
        "moria" -> true
        "shire" -> false
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) Moria else Shire
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme(palette, dark), content = content)
    }
}
