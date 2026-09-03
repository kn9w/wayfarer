package app.wayfarer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A fixed palette rather than dynamic colour: one fewer thing that varies by
// device, and the relay permission screens rely on approved/pending/denied being
// visually distinct in both themes.
//
// The four brand colours below are the identity; every other token is a tone of
// the same hue, generated in CIELCh at the tone Material 3 assigns to that role,
// so container/outline/surface tokens agree with each other instead of falling
// back to defaults derived from a different seed.
//
// The neutrals are warm in *both* themes, and that is the one rule to keep when
// touching them. They used to be parchment in light (CIELCh hue ~94°) and a cool
// blue-grey in dark (hue ~280°) — opposite sides of the wheel — so switching to
// dark mode did not dim the app, it changed what the app looked like it was.
// The dark neutrals are now the same hue family at hue 85°, at the *same* L*
// values they had before, so contrast and the surface ladder are unchanged and
// only the temperature moved. Chroma runs a little higher in dark than the light
// theme's equivalents because a dark neutral needs more of it to read as tinted
// at all.
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
        // Trail, which the app spends on everything that stays on this phone.
        secondary = Trail,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF9DEC6),
        onSecondaryContainer = Color(0xFF291800),
        // Moss, which the app spends on everything that becomes public — see
        // publicAccent below. Distinct from Compass so it never reads as a
        // primary action.
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
        background = Color(0xFF17140F),
        onBackground = Color(0xFFE6E2DC),
        surface = Color(0xFF17140F),
        onSurface = Color(0xFFE6E2DC),
        surfaceVariant = Color(0xFF4D463B),
        onSurfaceVariant = Color(0xFFCDC5B9),
        surfaceTint = CompassLight,
        inverseSurface = Color(0xFFE6E2DC),
        inverseOnSurface = Color(0xFF33302A),
        outline = Color(0xFF978F83),
        outlineVariant = Color(0xFF4D463B),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF3A352E),
        surfaceDim = Color(0xFF17140F),
        surfaceContainerLowest = Color(0xFF110E07),
        surfaceContainerLow = Color(0xFF1F1C17),
        surfaceContainer = Color(0xFF25211B),
        surfaceContainerHigh = Color(0xFF2E2A24),
        surfaceContainerHighest = Color(0xFF3A352E),
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
    MaterialTheme(colorScheme = colors, typography = WayfarerTypography) {
        Surface(color = colors.background, contentColor = colors.onBackground, content = content)
    }
}

/**
 * The two colours the whole app is sorted by.
 *
 * Every action in Wayfarer is one of two things, and which one it is matters
 * more than anything else on the screen it sits on: it either signs something
 * and hands it to other people's servers, or it changes a list that never leaves
 * this phone. Following, publishing a relay list, replying, editing a profile —
 * public. Allowing a relay, allowing a picture server, following on this phone —
 * local, told to nobody.
 *
 * The app said that in prose everywhere and drew it in one colour, so a user had
 * to read a paragraph to learn which kind of button they were about to press.
 * Now it is Moss for public and Trail for local, in both themes, everywhere: two
 * accents that carry a meaning rather than a mood.
 *
 * Colour is never the only signal — the wording still says what happens, and the
 * relay and picture screens keep their own glyphs — because this pair is a
 * green and a brown, which is exactly the pair a red-green colour-blind reader
 * separates worst.
 */
val ColorScheme.publicAccent: Color get() = tertiary

val ColorScheme.onPublicAccent: Color get() = onTertiary

val ColorScheme.publicContainer: Color get() = tertiaryContainer

val ColorScheme.onPublicContainer: Color get() = onTertiaryContainer

val ColorScheme.localAccent: Color get() = secondary

val ColorScheme.onLocalAccent: Color get() = onSecondary

val ColorScheme.localContainer: Color get() = secondaryContainer

val ColorScheme.onLocalContainer: Color get() = onSecondaryContainer

/** A filled button for something that will be signed and published. */
@Composable
fun publicButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.publicAccent,
        contentColor = MaterialTheme.colorScheme.onPublicAccent,
    )

/** A filled button for something that only changes this phone. */
@Composable
fun localButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.localAccent,
        contentColor = MaterialTheme.colorScheme.onLocalAccent,
    )

/** The outlined counterparts, for the same two kinds of action at lower weight. */
@Composable
fun publicOutlinedButtonColors(): ButtonColors =
    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.publicAccent)

@Composable
fun localOutlinedButtonColors(): ButtonColors =
    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.localAccent)

/**
 * The type scale, adjusted rather than replaced.
 *
 * No custom typeface: the app hand-draws its icons to avoid pulling in one large
 * artifact, and shipping a webfont for this would be the same trade made the
 * other way. What is changed is the *range*. Nearly everything on screen was
 * rendering at `bodySmall` or `labelSmall`, which is what made the app read as
 * uniformly small and grey, so the styles that carry actual reading — note and
 * article bodies — get more line height, and the style that names a person gets
 * enough weight to read as a byline rather than as another line of body text.
 *
 * Only the four roles below are overridden. Everything else stays at the Material
 * 3 default, so a style not named here is the library's, not a copy of it that
 * has to be maintained.
 */
private val WayfarerTypography =
    Typography().let { base ->
        base.copy(
            // The app's primary reading surfaces. Material's default 20sp/24sp on
            // bodyLarge is tight for prose that can run to several paragraphs.
            bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
            // Author names, in every byline and follow row.
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            // Section headings inside cards and sheets.
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }

/**
 * The tones a drawn identity mark can take.
 *
 * Six, because with initials doing most of the work six is enough for two
 * profiles side by side to look distinct, and because a wider spread would turn
 * a deliberately neutral app into a bag of sweets. Every hue here is one of the
 * app's own or sits between two of them — Compass at 254°, Moss at 140°, Trail
 * at 70° — held at a chroma low enough to read as tinted stone rather than as
 * colour. All six take the same foreground at the same contrast (9.4:1 light,
 * 5.6:1 dark), so the mark is legible whichever one a key happens to select.
 */
private val MarkTonesLight =
    listOf(
        Color(0xFFC6DDF1),
        Color(0xFFBBE1E8),
        Color(0xFFC8E0CD),
        Color(0xFFDBDCC3),
        Color(0xFFEDD6C4),
        Color(0xFFF4D2CE),
    )

private val MarkTonesDark =
    listOf(
        Color(0xFF445A6B),
        Color(0xFF3A5D63),
        Color(0xFF485C4C),
        Color(0xFF585943),
        Color(0xFF665444),
        Color(0xFF6C504D),
    )

private val MarkInkLight = Color(0xFF33302A)
private val MarkInkDark = Color(0xFFE6E2DC)

/**
 * The background and ink for one person's drawn mark.
 *
 * Deterministic in [seed] — a pubkey's hex — so the same person is always the
 * same colour, in this app on this phone and on the next launch. That is what
 * makes the mark worth looking at: it is weak identity, not decoration.
 *
 * Which theme is in force is read from the scheme rather than from
 * `isSystemInDarkTheme`, so a preview or a forced theme gets the right tones.
 */
@Composable
fun markColorsFor(seed: String): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tones = if (dark) MarkTonesDark else MarkTonesLight
    // A cheap, stable hash. Not security: it only has to spread evenly and give
    // the same answer every time, and String.hashCode is not specified to be
    // stable across platforms the way this needs to be.
    var acc = 0
    for (ch in seed) acc = (acc * 31 + ch.code) and 0x7FFFFFF
    return tones[acc % tones.size] to (if (dark) MarkInkDark else MarkInkLight)
}
