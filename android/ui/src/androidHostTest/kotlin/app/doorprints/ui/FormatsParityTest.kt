package app.doorprints.ui

import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The common [Formats] write what the JVM's formatters wrote for the four languages before CMP-3 (docs/06 TC-U-62):
 * scores as `String.format(locale, "%.1f")`, and amounts below a lakh as `NumberFormat.getCurrencyInstance` with no
 * decimals (the JDK does not group lakhs; Android's ICU does, as [Formats.rupees] and FormatsTest do). Since CMP-4
 * P4c also Compare's four formats filled by [formatPositional] as `String.format` filled them, and since CMP-5 the
 * Assistant's walking distance ([Formats.oneDecimal], was `String.format(Locale.ROOT, "%.1f")`) and the AI errors'
 * counts ([aiErrorText]).
 */
class FormatsParityTest {
    private val locales = listOf("en", "hi", "ta", "te").map { Locale.Builder().setLanguage(it).setRegion("IN").build() }

    @Test
    fun scoresMatchStringFormat() {
        val scores = (0..500).map { it / 100.0 } + (0..60).map { it / 3.0 } + (0..40).map { it * 0.05 + 0.025 }
        locales.forEach { locale ->
            scores.forEach { score ->
                assertEquals(String.format(locale, "%.1f", score), Formats.score(score), "$score in $locale")
            }
        }
    }

    @Test
    fun walkingDistancesMatchStringFormatInTheRootLocale() {
        val kms = (0..3_000).map { it / 1000.0 } + (0..200).map { it * 0.05 + 0.025 } + listOf(12.35, 99.95, 1234.45)
        kms.forEach { km -> assertEquals(String.format(Locale.ROOT, "%.1f", km), Formats.oneDecimal(km), "$km") }
    }

    @Test
    fun amountsBelowALakhMatchTheCurrencyFormat() {
        locales.forEach { locale ->
            val format = NumberFormat.getCurrencyInstance(locale).apply {
                maximumFractionDigits = 0
                minimumFractionDigits = 0
            }
            listOf(0L, 7L, 999L, 1_000L, 22_500L, 28_000L, 99_999L).forEach { amount ->
                assertEquals(format.format(amount), Formats.rupees(amount), "$amount in $locale")
            }
        }
    }

    @Test
    fun datesAreJavaTimesMediumFormatsInTheIndianLocale() {
        val millis = 1_760_000_000_000
        // Latin digits and a Hindi month in Hindi; the digits are not Devanagari (java.time's standard decimal style).
        val hindi = Formats.date(millis, "hi")
        assertEquals(hindi, hindi.filter { it !in '०'..'९' })
        assertEquals(Formats.date(millis, "en"), Formats.date(millis, ""))
    }

    @Test
    fun compareFormatsMatchStringFormatInEveryLanguage() {
        val keys = listOf("common_bhk", "common_stars", "house_check_value", "compare_best_name", "ai_rate_limited", "ai_error")
        listOf("" to "en", "-hi" to "hi", "-ta" to "ta", "-te" to "te").forEach { (folder, language) ->
            val locale = Locale.Builder().setLanguage(language).setRegion("IN").build()
            val xml = File("src/commonMain/composeResources/values$folder/strings.xml").readText()
            keys.forEach { key ->
                val format = Regex("""<string name="$key">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
                    ?: error("$key missing in values$folder")
                val args: List<Any> = when (key) {
                    "compare_best_name" -> listOf("Green Villa", "50% off", "A \$1 flat")
                    // The Retry-After seconds (a Long) and the HTTP code (an Int) of aiErrorText.
                    "ai_rate_limited" -> listOf(0L, 60L, 3_600L)
                    "ai_error" -> listOf(0, 500, 503)
                    else -> (0..12).toList()
                }
                args.forEach { arg ->
                    assertEquals(String.format(locale, format, arg), formatPositional(format, arg), "$key($arg) in $language")
                }
            }
        }
    }
}
