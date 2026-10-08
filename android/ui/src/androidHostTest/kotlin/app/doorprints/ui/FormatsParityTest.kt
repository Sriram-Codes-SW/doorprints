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

import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    /** S4b-BL-40: iOS's coordinates ([sixDecimalsHalfUp]) write what Android's `%.6f` writes, halves included. */
    @Test
    fun iosCoordinatesMatchStringFormatInTheRootLocale() {
        val halves = (0..2_000).map { it / 1_000_000.0 + 0.0000005 } + (0..360).map { it - 180 + 0.1234565 }
        val values = halves + halves.map { -it } + (0..20_000).map { it * 0.009 - 90 } +
            listOf(0.0, -0.0, 1e-7, 2.5e-7, 1e-3, 1.23e-4, 179.9999995, 12345678.9, 1e21, 5e-324, Double.MAX_VALUE) +
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        values.forEach { value ->
            assertEquals(String.format(Locale.ROOT, "%.6f", value), sixDecimalsHalfUp(value), "$value")
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

    /**
     * S4b-BL-174: the shared regional amounts (docs/ai/evals/parity-vectors.json, `rupees`: 12,34,567 and the crores
     * after it, written out by hand), the list the website's TranslationService spec reads too.
     */
    @Test
    fun rupeesFollowTheSharedRegionalVectorsInIndianGrouping() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/ai/evals/parity-vectors.json") }.first { it.isFile }
        val amounts = Json.parseToJsonElement(file.readText()).jsonObject.getValue("rupees").jsonArray.map { it.jsonObject }
        assertEquals(17, amounts.size)
        for (a in amounts) {
            val amount = a.getValue("amount").jsonPrimitive.long
            assertEquals(a.getValue("expected").jsonPrimitive.content, Formats.rupees(amount), "$amount")
            assertEquals(a.getValue("expected").jsonPrimitive.content + "/month", Formats.price(amount, "RENT") { "$it/month" }, "rent $amount")
            assertEquals(a.getValue("expected").jsonPrimitive.content, Formats.price(amount, "SALE") { "$it/month" }, "sale $amount")
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
