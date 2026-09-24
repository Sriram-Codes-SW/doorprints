package app.doorprints.ui

import java.text.NumberFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The common [Formats] write what the JVM's formatters wrote for the four languages before CMP-3 (docs/06 TC-U-62):
 * scores as `String.format(locale, "%.1f")`, and amounts below a lakh as `NumberFormat.getCurrencyInstance` with no
 * decimals (the JDK does not group lakhs; Android's ICU does, as [Formats.rupees] and FormatsTest do).
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
}
