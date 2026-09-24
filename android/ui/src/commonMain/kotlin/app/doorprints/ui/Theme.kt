package app.doorprints.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.doorprints.shared.model.HouseStatus

/**
 * Colours are the web design tokens (web/src/styles.css, docs/05 section 4), not Material dynamic colour, so the
 * contrast ratios documented there hold on Android too (A11Y-A07).
 *
 * **Every role a component reads is set (Design review, 2026-09-22).** A role left out falls back to M3's baseline
 * lavender or pink, and components read more roles than the screens name: the Switch's unchecked track is
 * `surfaceContainerHighest`, the NavigationBar is `surfaceContainer`, menus and sheets use the other
 * `surfaceContainer*` steps. Those are a neutral green-grey ramp between `--bg` and `--surface` here.
 *
 * **`secondaryContainer` is `--primary-soft` (Design review, round 19).** It used to be the star family (the web's
 * amber), but M3 paints every *selected* state with it: the selected FilterChip and InputChip, the bottom
 * NavigationBar's active pill, the progress track, the Slider's inactive track and the tonal button. None of those is
 * a star, and fixing them one call site at a time left the chips and the bottom nav amber on every screen, where the
 * web shows `--primary-soft` (`.chip[aria-pressed='true']`, the phone nav's `.nav-icon` pill). Nothing in `:app`
 * reads `secondaryContainer` for a star — stars and the star colour come from [LocalDoorprintsColors] `.star` — so the
 * role now holds the brand's soft teal in both schemes: light #E3F0EC with #0B3B30 on it (10.66:1), dark #1D3B33
 * with #E4EBE8 (10.05:1), the same pair as `primaryContainer`. `secondary` stays `--star`. [brandSliderColors] and
 * [tonalPrimaryColors] still name `primaryContainer`; they are now only a safety net. Selection never rests on this
 * fill alone (about 1.1–1.2:1 against the background, WCAG 1.4.1): the chips add a ✓ ([ChipCheck]) and a 2 dp
 * primary border ([brandFilterChipBorder], [brandInputChipBorder]), and the NavigationBar's active label is `primary`.
 *
 * Token mapping with the web (docs/05 section 4): `surfaceVariant` = `--surface-2` (the chips' fill; muted text
 * on it 4.91:1), `primaryContainer` = `--primary-soft` in both themes (the chosen format card; dark was
 * `--header-bg` #173F35 before and now matches the web's #1D3B33: onPrimaryContainer 10.05:1, primary 6.63:1).
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6F5C),             // --primary, 6.02:1 with white
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F0EC),    // --primary-soft
    onPrimaryContainer = Color(0xFF0B3B30),
    secondary = Color(0xFFA86A00),           // --star
    onSecondary = Color.White,
    // --primary-soft, not the amber star family: M3's selected chips, active nav pill and tonal fills read it.
    secondaryContainer = Color(0xFFE3F0EC),
    onSecondaryContainer = Color(0xFF0B3B30), // 10.66:1
    tertiary = Color(0xFF3C5A99),            // --status-new
    onTertiary = Color.White,
    error = Color(0xFFB3261E),               // --status-rejected / --error-text
    onError = Color.White,
    // Result cards (docs/05 section 4.1). Without these M3 falls back to its baseline pink.
    errorContainer = Color(0xFFFBECEB),
    onErrorContainer = Color(0xFFB3261E),    // 5.70:1 on errorContainer
    background = Color(0xFFF4F6F5),          // --bg
    onBackground = Color(0xFF1C2421),        // --text
    surface = Color.White,                   // --surface
    onSurface = Color(0xFF1C2421),
    surfaceVariant = Color(0xFFEEF2F0),      // --surface-2 (chips); --muted on it 4.91:1
    onSurfaceVariant = Color(0xFF5F6B67),    // --muted, 5.55:1 on --surface
    outline = Color(0xFF7D8985),             // --border-strong, 3.63:1 (1.4.11)
    outlineVariant = Color(0xFFD9E0DD),      // --border (decorative only)
    // Without these M3 uses its baseline lavender (#F3EDF7 navigation bar, #E6E0E9 switch track).
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9F8),
    surfaceContainer = Color(0xFFF1F4F3),
    surfaceContainerHigh = Color(0xFFEBEFED),
    surfaceContainerHighest = Color(0xFFE3E8E6),
    surfaceDim = Color(0xFFDCE2DF),
    surfaceBright = Color(0xFFFFFFFF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD1B3),
    onPrimary = Color(0xFF0B1F19),
    primaryContainer = Color(0xFF1D3B33),    // --primary-soft (dark), as the web's chosen format card
    onPrimaryContainer = Color(0xFFE4EBE8),
    secondary = Color(0xFFF2B84B),           // --star (dark)
    onSecondary = Color(0xFF0E1412),
    secondaryContainer = Color(0xFF1D3B33),  // --primary-soft (dark), as primaryContainer
    onSecondaryContainer = Color(0xFFE4EBE8), // 10.05:1
    tertiary = Color(0xFF9DB4EA),
    onTertiary = Color(0xFF0E1412),
    error = Color(0xFFFF8E86),
    onError = Color(0xFF0E1412),
    errorContainer = Color(0xFF3A1B19),
    onErrorContainer = Color(0xFFFF8E86),    // 7.01:1 on errorContainer
    background = Color(0xFF101614),
    onBackground = Color(0xFFE4EBE8),
    surface = Color(0xFF19211F),
    onSurface = Color(0xFFE4EBE8),
    surfaceVariant = Color(0xFF212B28),
    onSurfaceVariant = Color(0xFFA7B3AE),
    outline = Color(0xFF7F8C87),
    outlineVariant = Color(0xFF2E3A36),
    surfaceContainerLowest = Color(0xFF0B100F),
    surfaceContainerLow = Color(0xFF151C1A),
    surfaceContainer = Color(0xFF1B2422),
    surfaceContainerHigh = Color(0xFF212B28),
    surfaceContainerHighest = Color(0xFF2A3531),
    surfaceDim = Color(0xFF101614),
    surfaceBright = Color(0xFF343F3B),
)

/**
 * Doorprints colours Material's scheme has no slot for, from the web tokens.
 *
 *  - [success] / [onSuccess] / [successBorder]: a "done" result card (web `.success`), the counterpart of
 *    `errorContainer` / `onErrorContainer` / [errorBorder] (web `.error`). The 1 dp border is what keeps a card
 *    visible on any surface; the fill alone is within 1.1:1 of white.
 *  - [warn] / [onWarn] / [warnBorder]: a calm privacy note (web `.warn-box`), such as the contact-details warning
 *    on the Export screen. Amber, not the error red: it is a caution about sharing, not a failure.
 */
@Immutable
data class DoorprintsColors(
    val new: Color,
    val shortlisted: Color,
    val rejected: Color,
    val star: Color,
    val success: Color,
    val onSuccess: Color,
    val successBorder: Color,
    val errorBorder: Color,
    val warn: Color,
    val onWarn: Color,
    val warnBorder: Color,
)

private val LightExtra = DoorprintsColors(
    new = Color(0xFF3C5A99), shortlisted = Color(0xFF1A7A43), rejected = Color(0xFFB3261E), star = Color(0xFFA86A00),
    success = Color(0xFFE7F5ED), onSuccess = Color(0xFF1A7A43), // 4.78:1
    successBorder = Color(0xFFB5DCC4), errorBorder = Color(0xFFE8B4B0),
    warn = Color(0xFFFFF4E0), onWarn = Color(0xFF8A5A00), warnBorder = Color(0xFFF0C987), // 5.44:1
)
private val DarkExtra = DoorprintsColors(
    new = Color(0xFF9DB4EA), shortlisted = Color(0xFF6FD69A), rejected = Color(0xFFFF8E86), star = Color(0xFFF2B84B),
    success = Color(0xFF15301F), onSuccess = Color(0xFF6FD69A), // 7.98:1
    successBorder = Color(0xFF2B5A3B), errorBorder = Color(0xFF6E2C27),
    warn = Color(0xFF33260F), onWarn = Color(0xFFF2B84B), warnBorder = Color(0xFF6B4F1A), // 8.23:1
)

/**
 * Line height for Devanagari, Tamil and Telugu (Design review, 2026-09-22), the Android side of the web's
 * `--leading: 1.7` for hi/ta/te (docs/05 section 4.3).
 *
 * M3's type scale sets a fixed `lineHeight` (bodySmall 12/16 sp, bodyMedium 14/20) with a centred, untrimmed
 * line-height style, which overrides the taller spacing of the Indic fallback fonts. Tamil and Telugu stack
 * consonants below the baseline and vowel signs above it, so at 1.33–1.43 the subscript of one line meets the
 * vowel sign of the next. These styles keep the sizes and weights and set about 1.6–1.7× leading. Letter spacing
 * goes to 0: the Latin tracking of the labels pulls conjuncts apart.
 */
private val BaseTypography = Typography()

private fun TextStyle.indic(lineHeight: TextUnit) = copy(lineHeight = lineHeight, letterSpacing = 0.sp)

val IndicTypography: Typography = BaseTypography.copy(
    headlineSmall = BaseTypography.headlineSmall.indic(36.sp),
    titleLarge = BaseTypography.titleLarge.indic(32.sp),
    titleMedium = BaseTypography.titleMedium.indic(28.sp),
    titleSmall = BaseTypography.titleSmall.indic(24.sp),
    bodyLarge = BaseTypography.bodyLarge.indic(28.sp),
    bodyMedium = BaseTypography.bodyMedium.indic(24.sp),
    bodySmall = BaseTypography.bodySmall.indic(20.sp),
    labelLarge = BaseTypography.labelLarge.indic(22.sp),
    labelMedium = BaseTypography.labelMedium.indic(20.sp),
    labelSmall = BaseTypography.labelSmall.indic(18.sp),
)

/** The interface languages whose scripts need [IndicTypography]. */
private val INDIC_LANGUAGES = setOf("hi", "ta", "te")

/**
 * The interface language's ISO 639 code ("en", "hi", "ta", "te"), read where it changes the UI's composition: the
 * language the app's strings resolved to ([appLanguage]; S4b-BL-18), read again when the configuration changes
 * (Android). Public since CMP-3: `:app`'s Compare table formats with it.
 */
@Composable
expect fun uiLanguage(): String

val LocalDoorprintsColors = staticCompositionLocalOf { LightExtra }

@Composable
fun DoorprintsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val language = uiLanguage()
    CompositionLocalProvider(LocalDoorprintsColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = if (language in INDIC_LANGUAGES) IndicTypography else BaseTypography,
            content = content,
        )
    }
}

/**
 * A Slider in the brand colours. M3 draws the inactive track in `secondaryContainer`, which was the amber star family
 * until round 19 and would have read as "caution" on a plain setting; it is `--primary-soft` now, so this is a safety
 * net that keeps the track teal if that role ever changes again.
 */
@Composable
fun brandSliderColors(): SliderColors =
    SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer)

/**
 * A FilledTonalButton on `primaryContainer` (onPrimaryContainer on it: 10.66:1 light, 10.05:1 dark). M3's default
 * is `secondaryContainer`, which is the same `--primary-soft` since round 19; naming `primaryContainer` keeps a safe
 * action out of a caution colour whatever that role holds.
 */
@Composable
fun tonalPrimaryColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * The border of a selectable chip, the web's `.chip` (Design review, round 19): 1 dp `outline` (`--border-strong`,
 * 3.63:1, WCAG 1.4.11) when not selected, 2 dp `primary` when selected. With the ✓ leading icon ([ChipCheck]) it is
 * what shows the selection; the `--primary-soft` fill alone is about 1.1:1 against the background.
 */
@Composable
fun brandFilterChipBorder(selected: Boolean, enabled: Boolean = true): BorderStroke =
    FilterChipDefaults.filterChipBorder(
        enabled = enabled,
        selected = selected,
        borderColor = MaterialTheme.colorScheme.outline,
        selectedBorderColor = MaterialTheme.colorScheme.primary,
        borderWidth = 1.dp,
        selectedBorderWidth = 2.dp,
    )

/** [brandFilterChipBorder] for an `InputChip` (an applied filter such as "Just imported"). */
@Composable
fun brandInputChipBorder(selected: Boolean, enabled: Boolean = true): BorderStroke =
    InputChipDefaults.inputChipBorder(
        enabled = enabled,
        selected = selected,
        borderColor = MaterialTheme.colorScheme.outline,
        selectedBorderColor = MaterialTheme.colorScheme.primary,
        borderWidth = 1.dp,
        selectedBorderWidth = 2.dp,
    )

/**
 * The ✓ a selected FilterChip leads with, as the web's `.chip[aria-pressed='true']::before` (Design review, round
 * 19): selection shown by a shape, not by the fill alone (WCAG 1.4.1). Decorative; the chip's selected state is what
 * TalkBack reads. Pass it as `leadingIcon = if (selected) ChipCheck else null`.
 */
val ChipCheck: @Composable () -> Unit = {
    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
}

/** Status colour for text and chips on app surfaces (light or dark). Always paired with the status text. */
@Composable
fun HouseStatus.color(): Color {
    val c = LocalDoorprintsColors.current
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
