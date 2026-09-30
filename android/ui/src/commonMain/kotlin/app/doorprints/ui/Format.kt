/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import androidx.compose.runtime.Composable
import app.doorprints.ui.res.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Clock
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
        return oneDecimal(score)
    }

    /**
     * [value] with one decimal, rounded half up ("1.3" for 1.25): the score's rule ([score]) and the Assistant's walking
     * distance in km, which was `String.format(Locale.ROOT, "%.1f")` before CMP-5 (`FormatsParityTest` checks both
     * against the JVM).
     */
    fun oneDecimal(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val tenths = floor(abs(value) * 10 + 0.5).toLong()
        val sign = if (value < 0) "-" else ""
        return "$sign${tenths / 10}.${tenths % 10}"
    }

    /**
     * A latitude or longitude as the house form shows it: six decimals (about 10 cm), a dot whatever the language.
     * Java's `%.6f` ([formatSixDecimals]), so Android writes exactly what the form wrote before CMP-6 and iOS agrees.
     */
    fun coordinate(value: Double): String = formatSixDecimals(value)

    /** A medium date and a short time in [language] (default: [appLanguage]). */
    fun dateTime(epochMillis: Long, language: String = appLanguage()): String =
        formatDate(epochMillis, language, withTime = true)

    /** A medium date in [language] (default: [appLanguage]). */
    fun date(epochMillis: Long, language: String = appLanguage()): String =
        formatDate(epochMillis, language, withTime = false)
}

/** Now, wall clock, in epoch ms (a house's `createdAt`, a run's `finishedAt`; was `System.currentTimeMillis`). */
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

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
 * [value] with six decimals and a dot, rounded as Java's `%.6f` rounds (Android: `String.format(Locale.ROOT)`; iOS:
 * [sixDecimalsHalfUp]).
 */
internal expect fun formatSixDecimals(value: Double): String

/**
 * [value] with six decimals and a dot, as Java's `String.format(Locale.ROOT, "%.6f")` writes it (S4b-BL-40): half up
 * on the double's shortest decimal ("12.345679" for 12.3456785, "0.000001" for 5.0E-7), where C's `%.6f` (iOS's
 * `NSString` format) rounds the exact binary value and writes "12.345678" and "0.000000". The sign stays on a value
 * that rounds to zero ("-0.000000"), as in both. `FormatsParityTest` checks it against the JVM.
 */
internal fun sixDecimalsHalfUp(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    // The shortest decimal as digits and the place of the point: "1.2345E-7" → "12345", point at -6.
    val text = abs(value).toString().uppercase()
    val mantissa = text.substringBefore('E')
    val exponent = text.substringAfter('E', "0").toInt()
    val whole = mantissa.substringBefore('.')
    var digits = whole + mantissa.substringAfter('.', "")
    var point = whole.length + exponent
    if (point < 1) {
        digits = "0".repeat(1 - point) + digits
        point = 1
    }
    digits = digits.padEnd(point + 7, '0')
    // The whole part and six decimals as one number, plus one when the seventh decimal is 5 or more.
    var kept = digits.take(point + 6)
    if (digits[point + 6] >= '5') kept = incrementDigits(kept)
    val intPart = kept.dropLast(6).trimStart('0').ifEmpty { "0" }
    val sign = if (value.toRawBits() < 0) "-" else ""
    return "$sign$intPart.${kept.takeLast(6)}"
}

/** [digits] (0-9 only) plus one, carried as in writing ("0999" → "1000", "999" → "1000"). */
private fun incrementDigits(digits: String): String {
    val chars = digits.toCharArray()
    var i = chars.lastIndex
    while (i >= 0) {
        if (chars[i] != '9') {
            chars[i] = chars[i] + 1
            return chars.concatToString()
        }
        chars[i] = '0'
        i--
    }
    return "1" + chars.concatToString()
}

/**
 * The platform's medium date (and, [withTime], short time) for [language] with region IN, in the device's time zone.
 * Android: `java.time`'s localized formats; iOS: `NSDateFormatter`.
 */
internal expect fun formatDate(epochMillis: Long, language: String, withTime: Boolean): String

/**
 * The device time zone's offset from UTC at [epochMillis], in ms (Android: `TimeZone.getDefault`; iOS:
 * `NSTimeZone.localTimeZone`): the viewing form turns the date picker's UTC day and the time picker's hour into the
 * instant the person means on their own clock ([LocalClock]).
 */
internal expect fun utcOffsetMillis(epochMillis: Long): Int

/** Wall-clock arithmetic in the device's time zone, for the viewing form's date and time pickers (no kotlinx-datetime). */
internal object LocalClock {
    private const val DAY_MS = 86_400_000L

    /** The picker's day (UTC midnight of the local date, as Material's DatePicker gives it) of [epochMillis]. */
    fun dayOf(epochMillis: Long): Long = (epochMillis + utcOffsetMillis(epochMillis)).floorDiv(DAY_MS) * DAY_MS

    /** The local hour and minute of [epochMillis]. */
    fun hourMinuteOf(epochMillis: Long): Pair<Int, Int> {
        val minutes = (epochMillis + utcOffsetMillis(epochMillis)).mod(DAY_MS) / 60_000L
        return (minutes / 60).toInt() to (minutes % 60).toInt()
    }

    /** The instant of local [hour]:[minute] on the picker's [day]; the offset is read at that instant (DST safe). */
    fun at(day: Long, hour: Int, minute: Int): Long {
        val wall = day + hour * 3_600_000L + minute * 60_000L
        val guess = wall - utcOffsetMillis(wall)
        return wall - utcOffsetMillis(guess)
    }
}

/**
 * The language the app's strings are shown in, outside composition (a service's notification text): Android's default
 * locale, which `AppLocale.applyDefault` in `:app` keeps on the language Android resolved for the strings (docs/05
 * §8.2); iOS: the first preferred language when the app ships it, else "en", as Compose resources pick the strings.
 * Always one of en, hi, ta, te on iOS. In composition use [uiLanguage], which also recomposes on a change.
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
