package app.wayfarer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A fixed palette rather than dynamic colour: one fewer thing that varies by
// device, and the relay permission screens rely on approved/pending/denied being
// visually distinct in both themes.
private val Ink = Color(0xFF1B1B1F)
private val Parchment = Color(0xFFFBF9F4)
private val Compass = Color(0xFF3E6B8A)
private val CompassLight = Color(0xFF9CC4DE)
private val Trail = Color(0xFF7A5C3E)

private val LightColors =
    lightColorScheme(
        primary = Compass,
        secondary = Trail,
        background = Parchment,
        surface = Color(0xFFFFFFFF),
        onBackground = Ink,
        onSurface = Ink,
    )

private val DarkColors =
    darkColorScheme(
        primary = CompassLight,
        secondary = Color(0xFFD6BB9B),
        background = Color(0xFF121316),
        surface = Color(0xFF1B1C20),
    )

@Composable
fun WayfarerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
