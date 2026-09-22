package com.househunt.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.househunt.app.data.HouseStatus

/**
 * Colours are the web design tokens (web/src/styles.css, docs/05 section 4), not Material dynamic colour, so the
 * contrast ratios documented there hold on Android too (A11Y-A07).
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6F5C),             // --primary, 6.02:1 with white
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F0EC),    // --primary-soft
    onPrimaryContainer = Color(0xFF0B3B30),
    secondary = Color(0xFFA86A00),           // --star
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE7C2),
    onSecondaryContainer = Color(0xFF3D2700),
    tertiary = Color(0xFF3C5A99),            // --status-new
    onTertiary = Color.White,
    error = Color(0xFFB3261E),               // --status-rejected / --error-text
    onError = Color.White,
    background = Color(0xFFF4F6F5),          // --bg
    onBackground = Color(0xFF1C2421),        // --text
    surface = Color.White,                   // --surface
    onSurface = Color(0xFF1C2421),
    surfaceVariant = Color(0xFFF4F6F5),
    onSurfaceVariant = Color(0xFF5F6B67),    // --muted, 5.55:1
    outline = Color(0xFF7D8985),             // --border-strong, 3.63:1 (1.4.11)
    outlineVariant = Color(0xFFD9E0DD),      // --border (decorative only)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD1B3),
    onPrimary = Color(0xFF0B1F19),
    primaryContainer = Color(0xFF173F35),    // --header-bg
    onPrimaryContainer = Color(0xFFE4EBE8),
    secondary = Color(0xFFF2B84B),           // --star (dark)
    onSecondary = Color(0xFF0E1412),
    secondaryContainer = Color(0xFF3A2C10),
    onSecondaryContainer = Color(0xFFF7DDAA),
    tertiary = Color(0xFF9DB4EA),
    onTertiary = Color(0xFF0E1412),
    error = Color(0xFFFF8E86),
    onError = Color(0xFF0E1412),
    background = Color(0xFF101614),
    onBackground = Color(0xFFE4EBE8),
    surface = Color(0xFF19211F),
    onSurface = Color(0xFFE4EBE8),
    surfaceVariant = Color(0xFF212B28),
    onSurfaceVariant = Color(0xFFA7B3AE),
    outline = Color(0xFF7F8C87),
    outlineVariant = Color(0xFF2E3A36),
)

@Immutable
data class HouseHuntColors(val new: Color, val shortlisted: Color, val rejected: Color, val star: Color)

private val LightExtra = HouseHuntColors(
    new = Color(0xFF3C5A99), shortlisted = Color(0xFF1A7A43), rejected = Color(0xFFB3261E), star = Color(0xFFA86A00),
)
private val DarkExtra = HouseHuntColors(
    new = Color(0xFF9DB4EA), shortlisted = Color(0xFF6FD69A), rejected = Color(0xFFFF8E86), star = Color(0xFFF2B84B),
)

val LocalHouseHuntColors = staticCompositionLocalOf { LightExtra }

@Composable
fun HouseHuntTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHouseHuntColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/** Status colour for text and chips on app surfaces (light or dark). Always paired with the status text. */
@Composable
fun HouseStatus.color(): Color {
    val c = LocalHouseHuntColors.current
    return when (this) {
        HouseStatus.NEW -> c.new
        HouseStatus.SHORTLISTED -> c.shortlisted
        HouseStatus.REJECTED -> c.rejected
    }
}

/** Map tiles stay light in both themes, so markers always use the light-theme status colours. */
object MarkerColors {
    const val NEW = 0xFF3C5A99.toInt()
    const val SHORTLISTED = 0xFF1A7A43.toInt()
    const val REJECTED = 0xFFB3261E.toInt()
}
