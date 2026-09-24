package app.doorprints.ui

import androidx.compose.ui.text.intl.Locale
// A star import: dateWithTimeIntervalSince1970 comes from an NSDate category, an extension in Kotlin/Native.
import platform.Foundation.*

internal actual fun formatDate(epochMillis: Long, language: String, withTime: Boolean): String {
    val formatter = NSDateFormatter()
    // The language with region IN, as on Android; the formatter uses the device's time zone.
    formatter.locale = NSLocale(localeIdentifier = "${language.ifEmpty { "en" }}_IN")
    formatter.dateStyle = NSDateFormatterMediumStyle
    formatter.timeStyle = if (withTime) NSDateFormatterShortStyle else NSDateFormatterNoStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

// NSString's format uses the POSIX locale, so the decimal separator is a dot, as on Android.
internal actual fun formatSixDecimals(value: Double): String = NSString.stringWithFormat("%.6f", value)

// Compose's current locale reads NSLocale's preferred language. The iOS app's own language setting is ADR-23 phase 8.
actual fun appLanguage(): String = Locale.current.language
