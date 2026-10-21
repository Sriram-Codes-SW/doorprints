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

import app.doorprints.shared.model.KindWordKeys
import app.doorprints.shared.model.UserKinds
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.allStringResources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The words of the kinds a person adds on the phones (docs/03 ADR-39, docs/06 TC-U-297): every neutral word of their space
 * (`ukind_<id>`, one for each word the house's space has) and every word of Settings' *Kinds you add* is a Compose string in
 * en, hi, ta and te, with the house word's placeholders, translated (not the English copied) and marked *under review*; no
 * neutral word names a house or a school; and every refusal code of `user-kind-vectors.json` has words of its own.
 */
class UserKindStringsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val languages = listOf("", "-hi", "-ta", "-te")

    private fun text(lang: String) = repoFile("android/ui/src/commonMain/composeResources/values$lang/strings.xml").readText()

    private fun strings(lang: String): Map<String, String> =
        Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text(lang)).associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(s: String) = Regex("""%\d\$[sd]""").findAll(s).map { it.value }.sorted().toList()

    private val own = listOf(
        "userkinds_heading", "userkinds_switch", "userkinds_switch_hint", "userkinds_import", "userkinds_import_hint", "userkinds_reading",
        "userkinds_preview_title", "userkinds_preview_name", "userkinds_preview_fields", "userkinds_preview_no_fields", "userkinds_preview_checklist",
        "userkinds_preview_replaces", "userkinds_add", "userkinds_replace", "userkinds_added", "userkinds_replaced", "userkinds_full",
        "userkinds_refused", "userkinds_where", "userkinds_yours", "userkinds_remove", "userkinds_remove_aria", "userkinds_remove_confirm",
        "userkinds_remove_do", "userkinds_removed", "userkinds_read_failed",
    )

    @Test
    fun everyNeutralWordIsAComposeStringWithTheHouseWordsPlaceholdersInFourLanguages() {
        val en = strings("")
        val neutral = UserKinds.NEUTRAL_KEYS
        assertTrue(neutral.size > 80, "${neutral.size} neutral words")
        for (lang in languages) {
            val s = strings(lang)
            for (key in neutral) {
                val houseKey = checkNotNull(KindWordKeys.HOUSE[key.removePrefix(UserKinds.NEUTRAL_PREFIX)])
                val value = assertNotNull(s[key], "$key in values$lang")
                assertEquals(placeholders(en.getValue(houseKey)), placeholders(value), "$key in values$lang")
                if (lang.isNotEmpty()) assertNotEquals(en.getValue(key), value, "$key in values$lang is translated")
            }
        }
        for (key in neutral) {
            assertNotNull(Res.allStringResources[key], key)
            assertFalse(Regex("\\b(house|houses|school|schools)\\b", RegexOption.IGNORE_CASE).containsMatchIn(en.getValue(key).replace("work, school or family", "")), "$key names no kind: ${en.getValue(key)}")
        }
    }

    @Test
    fun theSettingsWordsAreInFourLanguagesTranslatedAndMarkedUnderReview() {
        val en = strings("")
        for (lang in languages) {
            val s = strings(lang)
            for (key in own) {
                val value = assertNotNull(s[key], "$key in values$lang")
                assertEquals(placeholders(en.getValue(key)), placeholders(value), "$key in values$lang")
                assertFalse(value.contains("browser", ignoreCase = true), "$key in values$lang says browser")
                if (lang.isNotEmpty()) assertNotEquals(en.getValue(key), value, "$key in values$lang is translated")
            }
            if (lang.isNotEmpty()) {
                assertTrue(text(lang).contains("<!-- Kinds a person adds: the neutral words of their space (docs/03 ADR-39, 2026-10-21): under review. -->"), lang)
                assertTrue(text(lang).contains("<!-- Kinds a person adds: Settings > What you track (docs/03 ADR-39, 2026-10-21): under review. -->"), lang)
            }
        }
        for (key in own) assertNotNull(Res.allStringResources[key], key)
    }

    @Test
    fun everyRefusalCodeHasWordsOfItsOwn() {
        val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/user-kind-vectors.json").readText()).jsonObject
        val codes = vectors.getValue("codes").jsonArray.map { it.jsonPrimitive.content }
        val reasons = codes.map { refusalReason(it) }
        assertEquals(codes.size, reasons.toSet().size, "one reason per code")
        for (code in codes) assertEquals("userkinds_err_$code", refusalReason(code).key, code)
        assertEquals(Res.allStringResources["userkinds_err_notJson"], refusalReason("anything else"))
    }
}
