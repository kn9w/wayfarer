package app.wayfarer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A fixed palette rather than dynamic colour: one fewer thing that varies by
// device, and the relay permission screens rely on approved/pending/denied being
// visually distinct in both themes.
//
// The four brand colours below are the identity; every other token is a tone of
// the same hue, generated in CIELCh at the tone Material 3 assigns to that role,
// so container/outline/surface tokens agree with each other instead of falling
// back to defaults derived from a different seed.
private val Compass = Color(0xFF3E6B8A)
private val CompassLight = Color(0xFF9CC4DE)
private val Trail = Color(0xFF7A5C3E)
private val TrailLight = Color(0xFFD6BB9B)
private val Parchment = Color(0xFFFBF9F4)

private val LightColors =
    lightColorScheme(
        primary = Compass,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC9E6FF),
        onPrimaryContainer = Color(0xFF001E2E),
        inversePrimary = Color(0xFFA0CCEE),
        secondary = Trail,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF9DEC6),
        onSecondaryContainer = Color(0xFF291800),
        // Moss, for the "Local" half of the app. Distinct from Compass so a
        // tertiary accent never reads as a primary action.
        tertiary = Color(0xFF4E644B),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD0E9CC),
        onTertiaryContainer = Color(0xFF0D2007),
        error = Color(0xFFB4271F),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD3),
        onErrorContainer = Color(0xFF390C00),
        background = Parchment,
        onBackground = Color(0xFF1D1B17),
        surface = Parchment,
        onSurface = Color(0xFF1D1B17),
        surfaceVariant = Color(0xFFE7E2D5),
        onSurfaceVariant = Color(0xFF4A473C),
        surfaceTint = Compass,
        inverseSurface = Color(0xFF32302C),
        inverseOnSurface = Color(0xFFF3F1EB),
        outline = Color(0xFF7B776B),
        outlineVariant = Color(0xFFCBC6B9),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFBF9F3),
        surfaceDim = Color(0xFFE4E2DD),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F3EE),
        surfaceContainer = Color(0xFFF0EEE8),
        surfaceContainerHigh = Color(0xFFEAE8E2),
        surfaceContainerHighest = Color(0xFFE4E2DD),
    )

private val DarkColors =
    darkColorScheme(
        primary = CompassLight,
        onPrimary = Color(0xFF00344D),
        primaryContainer = Color(0xFF184B68),
        onPrimaryContainer = Color(0xFFC9E6FF),
        inversePrimary = Color(0xFF356381),
        secondary = TrailLight,
        onSecondary = Color(0xFF3F2D1B),
        secondaryContainer = Color(0xFF574330),
        onSecondaryContainer = Color(0xFFF9DEC6),
        tertiary = Color(0xFFB5CDB0),
        onTertiary = Color(0xFF21351F),
        tertiaryContainer = Color(0xFF374C34),
        onTertiaryContainer = Color(0xFFD0E9CC),
        error = Color(0xFFFFB4A5),
        onError = Color(0xFF690000),
        errorContainer = Color(0xFF93000B),
        onErrorContainer = Color(0xFFFFDAD3),
        background = Color(0xFF121316),
        onBackground = Color(0xFFE1E2E6),
        surface = Color(0xFF121316),
        onSurface = Color(0xFFE1E2E6),
        surfaceVariant = Color(0xFF434750),
        onSurfaceVariant = Color(0xFFC3C6D1),
        surfaceTint = CompassLight,
        inverseSurface = Color(0xFFE1E2E6),
        inverseOnSurface = Color(0xFF2F3033),
        outline = Color(0xFF8D909B),
        outlineVariant = Color(0xFF434750),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF343538),
        surfaceDim = Color(0xFF121316),
        surfaceContainerLowest = Color(0xFF0D0E12),
        surfaceContainerLow = Color(0xFF1B1B1E),
        surfaceContainer = Color(0xFF1F1F22),
        surfaceContainerHigh = Color(0xFF292A2D),
        surfaceContainerHighest = Color(0xFF343538),
    )

/**
 * The app's theme, and the one place a background is painted.
 *
 * The [Surface] is load-bearing rather than decorative. `MaterialTheme` supplies
 * a colour scheme but *not* `LocalContentColor`; `Surface` is what provides it,
 * and outside one it sits at the Material 3 library default of black. Every
 * screen drawn inside `Scaffold` got that for free, because `Scaffold` wraps its
 * content in a surface of its own — but onboarding deliberately renders before
 * the scaffold exists, so its text was black on the window background in both
 * light and dark. Wrapping here fixes it once, for every present and future
 * screen, instead of per composable.
 */
@Composable
fun WayfarerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        Surface(color = colors.background, contentColor = colors.onBackground, content = content)
    }
}
