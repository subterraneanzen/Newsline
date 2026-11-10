package ca.newsarchive.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ca.newsarchive.app.data.ThemeMode

// Restrained "newsprint" palette: ink on paper, one muted blue accent.
private val Light = lightColorScheme(
    primary = Color(0xFF1F3A5F), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2EE), onPrimaryContainer = Color(0xFF0E1D30),
    secondary = Color(0xFF5B5F66), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E4DF), onSecondaryContainer = Color(0xFF1C1C1A),
    tertiary = Color(0xFF7A4B12),
    background = Color(0xFFFAF9F6), onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFAF9F6), onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEBE6), onSurfaceVariant = Color(0xFF4A4A48),
    surfaceContainer = Color(0xFFF2F0EB), surfaceContainerHigh = Color(0xFFECEAE4),
    outline = Color(0xFFB9B6AE), outlineVariant = Color(0xFFDAD7D0),
    error = Color(0xFFA4262C),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C2E2), onPrimary = Color(0xFF0E1D30),
    primaryContainer = Color(0xFF2A3F5B), onPrimaryContainer = Color(0xFFD9E2EE),
    secondary = Color(0xFFBFC1C6), secondaryContainer = Color(0xFF34342F), onSecondaryContainer = Color(0xFFE6E4DF),
    tertiary = Color(0xFFE2B47A),
    background = Color(0xFF121212), onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF121212), onSurface = Color(0xFFE8E6E1),
    surfaceVariant = Color(0xFF2A2A28), onSurfaceVariant = Color(0xFFC4C2BC),
    surfaceContainer = Color(0xFF1C1C1B), surfaceContainerHigh = Color(0xFF242423),
    outline = Color(0xFF6E6C66), outlineVariant = Color(0xFF3A3936),
    error = Color(0xFFF2B8B5),
)

/** Colours for version comparison. Not used for anything else so they stay meaningful. */
object DiffColors {
    @Composable fun added() = if (isDark()) Color(0xFF1E3B26) else Color(0xFFDDF2E1)
    @Composable fun addedText() = if (isDark()) Color(0xFF9EE2AE) else Color(0xFF14562A)
    @Composable fun removed() = if (isDark()) Color(0xFF4A2224) else Color(0xFFF8DADA)
    @Composable fun removedText() = if (isDark()) Color(0xFFF2B0B0) else Color(0xFF8A1C22)
    @Composable fun modified() = if (isDark()) Color(0xFF433817) else Color(0xFFFBF0CF)
    @Composable private fun isDark() = MaterialTheme.colorScheme.background.red < 0.5f
}

val Serif = FontFamily.Serif

private val base = Typography()
private val AppTypography = base.copy(
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.2.sp),
)

val SectionLabel = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp)

/**
 * App theme. [background] and [text] are ARGB values chosen in Settings (0 = theme default);
 * [textScale] multiplies the system font scale for every screen.
 */
@Composable
fun NewsTheme(mode: ThemeMode, background: Long = 0L, text: Long = 0L, textScale: Float = 1f, content: @Composable () -> Unit) {
    val bg = if (background != 0L) Color(background.toInt()) else null
    val fg = if (text != 0L) Color(text.toInt()) else null
    // A custom background decides light vs dark so default text stays readable on it.
    val dark = when {
        bg != null -> bg.luminance() < 0.4f
        mode == ThemeMode.SYSTEM -> isSystemInDarkTheme()
        else -> mode == ThemeMode.DARK
    }
    var scheme = if (dark) Dark else Light
    if (bg != null || fg != null) {
        val b = bg ?: scheme.background
        val t = fg ?: scheme.onBackground
        scheme = scheme.copy(
            background = b, surface = b, onBackground = t, onSurface = t,
            onSurfaceVariant = if (fg != null) t.copy(alpha = 0.72f) else scheme.onSurfaceVariant,
            surfaceVariant = lerp(b, t, 0.10f), surfaceContainer = lerp(b, t, 0.05f),
            surfaceContainerHigh = lerp(b, t, 0.08f), surfaceContainerLow = lerp(b, t, 0.03f),
            surfaceContainerHighest = lerp(b, t, 0.11f), surfaceContainerLowest = b,
            outline = lerp(b, t, 0.40f), outlineVariant = lerp(b, t, 0.16f),
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
