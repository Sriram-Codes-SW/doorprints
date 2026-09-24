package app.doorprints.ui

import androidx.compose.runtime.Composable
import app.doorprints.ui.res.*
import kotlin.math.abs
import kotlin.math.floor
import org.jetbrains.compose.resources.stringResource

/**
 * Formatting with Indian conventions in the app language (en/hi/ta/te), like the web's TranslationService: INR with
 * lakh grouping and no decimals (₹12,50,000), Latin digits, a one-decimal score, medium date + short time.
 *
 * The amounts and scores are common code (ADR-23 CMP-3): the four languages write them alike in their `IN` locales
 * (the `₹` sign before the digits with no space, `#,##,##0` grouping, a `.` decimal point, Latin digits by default), so
 * no locale is needed. The dates are the platform's own medium date and short time for `<language>-IN` ([formatDate]:
 * java.time on Android, `NSDateFormatter` on iOS). The language is [appLanguage], the one the app's strings resolved
 * to (S4b-BL-18), not the phone's first language: a phone set to [Marathi, Hindi] shows Hindi dates with Hindi text.
 */
object Formats {
    /** "₹12,50,000", "₹25,000", "₹999"; "-₹5,000" below zero (no price is negative, but nothing breaks). */
    fun rupees(amount: Long): String {
        val digits = indianGrouping(amount.toString().removePrefix("-"))
        return if (amount < 0) "-₹$digits" else "₹$digits"
    }

    /**
     * [digits] (0-9 only) grouped the Indian way: the last three together, then pairs ("1250000" → "12,50,000";
     * "100000000000" → "1,00,00,00,00,000").
     */
    fun indianGrouping(digits: String): String {
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        return "$pairs,${digits.takeLast(3)}"
    }

    /**
     * The price as the app shows it: [rupees], and for a rent [perMonth] around it ("₹25,000/month"), or null without a
     * price. [perMonth] is `price_per_month` from Compose resources in the UI ([priceText]) and from Android resources
     * in `:app`'s services (the notifications' strings stay Android resources).
     */
    fun price(price: Long?, priceType: String?, perMonth: (String) -> String): String? {
        price ?: return null
        val base = rupees(price)
        return if (priceType == "RENT") perMonth(base) else base
    }

    /**
     * One decimal, rounded half up ("3.3" for 3.25), or "–" without a score: what `String.format(locale, "%.1f")` wrote
     * in the four languages before CMP-3 (`FormatsParityTest` checks it against the JVM).
     */
    fun score(score: Double?): String {
        score ?: return "–"
        if (score.isNaN() || score.isInfinite()) return score.toString()
        val tenths = floor(abs(score) * 10 + 0.5).toLong()
        val sign = if (score < 0) "-" else ""
        return "$sign${tenths / 10}.${tenths % 10}"
    }

    /** A medium date and a short time in [language] (default: [appLanguage]). */
    fun dateTime(epochMillis: Long, language: String = appLanguage()): String =
        formatDate(epochMillis, language, withTime = true)

    /** A medium date in [language] (default: [appLanguage]). */
    fun date(epochMillis: Long, language: String = appLanguage()): String =
        formatDate(epochMillis, language, withTime = false)
}

/**
 * [format] (a UI string read with `stringResource`) with its positional placeholders `%1$s` and `%1$d` filled from
 * [args], each written with `toString()`: what Compose resources' `stringResource(res, args)` does, for a format filled
 * outside composition (Compare's rows, CMP-4 P4c). For these placeholders it writes what `String.format` wrote in the
 * four languages (Latin digits, no grouping; `FormatsParityTest`). Other `%` sequences are left as they are.
 */
fun formatPositional(format: String, vararg args: Any): String =
    POSITIONAL_PLACEHOLDER.replace(format) { args[it.groupValues[1].toInt() - 1].toString() }

/** The placeholders Compose resources fill (its `SimpleStringFormatRegex`). */
private val POSITIONAL_PLACEHOLDER = Regex("""%(\d+)\$[ds]""")

/**
 * The platform's medium date (and, [withTime], short time) for [language] with region IN, in the device's time zone.
 * Android: `java.time`'s localized formats; iOS: `NSDateFormatter`.
 */
internal expect fun formatDate(epochMillis: Long, language: String, withTime: Boolean): String

/**
 * The language the app's strings are shown in, outside composition (a service's notification text): Android's default
 * locale, which `AppLocale.applyDefault` in `:app` keeps on the language Android resolved for the strings (docs/05
 * §8.2); iOS: Compose's current locale. In composition use [uiLanguage], which also recomposes on a change.
 */
expect fun appLanguage(): String

/** The house's price as the list and the form show it; see [Formats.price]. */
@Composable
fun priceText(price: Long?, priceType: String?): String? {
    price ?: return null
    val base = Formats.rupees(price)
    return if (priceType == "RENT") stringResource(Res.string.price_per_month, base) else base
}

/** The score with one decimal, or "–"; see [Formats.score]. */
@Composable
fun Double?.scoreText(): String = Formats.score(this)

/** The date and time in the app language; read again after a language change ([uiLanguage]). */
@Composable
fun Long.dateText(): String = Formats.dateTime(this, uiLanguage())
