package app.simpletemplate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.simpletemplate.ui.resources.Res
import app.simpletemplate.ui.resources.atkinson_hyperlegible_bold
import app.simpletemplate.ui.resources.atkinson_hyperlegible_italic
import app.simpletemplate.ui.resources.atkinson_hyperlegible_regular
import org.jetbrains.compose.resources.Font

/*
 * The platform family's look (shared with the Mobile Auditor): calm, professional and easy to read
 * for everyone, including people with low vision. Atkinson Hyperlegible (Braille Institute) keeps
 * letters distinct; text colours meet WCAG AA contrast (4.5:1) on their backgrounds; colour is never
 * the only cue (badges also have a label). An app may change the accent colours; keep the contrast.
 */

private val Light = lightColorScheme(
    primary = Color(0xFF1F3A5F), // navy
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3F4),
    onPrimaryContainer = Color(0xFF0B1D33),
    secondary = Color(0xFF00696B), // teal
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC6ECEA),
    onSecondaryContainer = Color(0xFF002021),
    tertiary = Color(0xFF2E6B30), // green: done, approved
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEBC8),
    onTertiaryContainer = Color(0xFF0A2010),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF4F6F9),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E5EC),
    onSurfaceVariant = Color(0xFF41474F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFEDF0F4),
    surfaceContainerHigh = Color(0xFFE6EAF0),
    surfaceContainerHighest = Color(0xFFDFE3EA),
    outline = Color(0xFF6F7680),
    outlineVariant = Color(0xFFC9CED6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7F0),
    onPrimary = Color(0xFF0B2A4A),
    primaryContainer = Color(0xFF263F61),
    onPrimaryContainer = Color(0xFFD7E3F4),
    secondary = Color(0xFF7FD6D3),
    onSecondary = Color(0xFF003738),
    secondaryContainer = Color(0xFF0E4F51),
    onSecondaryContainer = Color(0xFFC6ECEA),
    tertiary = Color(0xFFA6D3A2),
    onTertiary = Color(0xFF0E3814),
    tertiaryContainer = Color(0xFF1F4F22),
    onTertiaryContainer = Color(0xFFCDEBC8),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE2E4E8),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE2E4E8),
    surfaceVariant = Color(0xFF3A4048),
    onSurfaceVariant = Color(0xFFC3C8D0),
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF181C21),
    surfaceContainer = Color(0xFF1D2126),
    surfaceContainerHigh = Color(0xFF272B31),
    surfaceContainerHighest = Color(0xFF32363C),
    outline = Color(0xFF8D939C),
    outlineVariant = Color(0xFF3F454D),
)

/** Colours for a badge: a bar (or dot) and a label background with readable text. */
@Immutable
data class BadgeColors(val bar: Color, val container: Color, val onContainer: Color)

/** Status colours beyond Material's scheme. Add the app's own kinds here (e.g. risk levels). */
@Immutable
data class StatusColors(
    val neutral: BadgeColors,
    val info: BadgeColors,
    val success: BadgeColors,
    /** A warning that is not an error. */
    val warning: BadgeColors,
)

private val LightStatus = StatusColors(
    neutral = BadgeColors(Color(0xFF8A9099), Color(0xFFE6E8EB), Color(0xFF2B2F35)),
    info = BadgeColors(Color(0xFF1F3A5F), Color(0xFFD7E3F4), Color(0xFF0B1D33)),
    success = BadgeColors(Color(0xFF2E6B30), Color(0xFFCDEBC8), Color(0xFF0A2010)),
    warning = BadgeColors(Color(0xFFB45309), Color(0xFFFFF1DC), Color(0xFF3D2200)),
)

private val DarkStatus = StatusColors(
    neutral = BadgeColors(Color(0xFF9AA0A8), Color(0xFF32363C), Color(0xFFE2E4E8)),
    info = BadgeColors(Color(0xFFA9C7F0), Color(0xFF263F61), Color(0xFFD7E3F4)),
    success = BadgeColors(Color(0xFFA6D3A2), Color(0xFF1F4F22), Color(0xFFCDEBC8)),
    warning = BadgeColors(Color(0xFFF6B26B), Color(0xFF5A3300), Color(0xFFFFF1DC)),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
private fun appFontFamily() = FontFamily(
    Font(Res.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(Res.font.atkinson_hyperlegible_bold, FontWeight.Bold),
    Font(Res.font.atkinson_hyperlegible_bold, FontWeight.SemiBold),
    Font(Res.font.atkinson_hyperlegible_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Slightly larger than Material's defaults, with clear weights; scales with the phone's font size. */
@Composable
private fun appTypography(): Typography {
    val f = appFontFamily()
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
        TextStyle(fontFamily = f, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)
    return Typography(
        displaySmall = style(34, 42),
        headlineLarge = style(30, 38, FontWeight.Bold),
        headlineMedium = style(27, 34, FontWeight.Bold),
        headlineSmall = style(24, 31, FontWeight.Bold),
        titleLarge = style(21, 28, FontWeight.Bold),
        titleMedium = style(18, 25, FontWeight.Bold),
        titleSmall = style(16, 22, FontWeight.Bold),
        bodyLarge = style(17, 25),
        bodyMedium = style(16, 23),
        bodySmall = style(14, 20),
        labelLarge = style(15, 20, FontWeight.Bold, 0.2),
        labelMedium = style(14, 18, FontWeight.Bold, 0.2),
        labelSmall = style(13, 17, FontWeight.Bold, 0.2),
    )
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = appTypography(), content = content)
    }
}

/** Access to [StatusColors] alongside MaterialTheme. */
object AppTheme {
    val status: StatusColors @Composable get() = LocalStatusColors.current
}
