package app.doorprints.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal actual fun formatDate(epochMillis: Long, language: String, withTime: Boolean): String {
    val formatter = if (withTime) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
    // The language with region IN (hi-IN, ta-IN), so the date's pattern is the Indian one in every language.
    val locale = Locale.Builder().setLanguage(language.ifEmpty { "en" }).setRegion("IN").build()
    return formatter.withLocale(locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
}

internal actual fun formatSixDecimals(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

// The default locale: AppLocale.applyDefault (in :app) keeps it on the language Android resolved for the strings.
actual fun appLanguage(): String = Locale.getDefault().language
