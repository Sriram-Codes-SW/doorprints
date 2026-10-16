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
package app.doorprints

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import app.doorprints.data.HouseEntity
import app.doorprints.i18n.AppLocale
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.model.BuiltInKind
import app.doorprints.shared.model.DevKinds
import app.doorprints.ui.mergeListing
import app.doorprints.ui.mergeListingExtras
import app.doorprints.ui.pasteResultText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The summary of *Fill in from listing text* for a school (kinds PR 7, S4b-BL-211; S4b-BL-273, docs/06): the extras the fill
 * wrote and the one it kept are named after the fields, by the kind's own labels in the app's language, as the website names
 * them. A house's fill has none, so its sentence is word for word what it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SchoolPasteSummaryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val savedDefault = LocaleList.getDefault()

    @After
    fun restore() {
        LocaleList.setDefault(savedDefault)
    }

    private fun appIn(tag: String) = AppLocale.applyDefault(
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) },
        ),
    )

    private val school = DevKinds.of("school", on = true)
    private val form = HouseEntity(
        id = "s1", label = "New school", lat = 26.14, lon = 91.79, priceType = null, createdAt = 1L, updatedAt = 1L,
        kind = "school", extrasOther = buildJsonObject { put("board", "STATE") },
    )
    private val draft = HouseDraftDto(label = "Pine Hill School", extras = buildJsonObject { put("board", "CBSE"); put("medium", "REGIONAL"); put("busService", true) })
    private val merged = mergeListingExtras(mergeListing(form, draft, labelIsPlaceholder = true), draft, school.fields)

    @Test
    fun theFilledAndTheKeptExtrasAreNamedByTheKindsLabels() = runBlocking {
        appIn("en")
        assertEquals(
            "Filled in: name, Medium of instruction and School bus. Check them, then save. Kept what you had typed for: Board.",
            pasteResultText(merged, emptyList(), school.fields),
        )
    }

    @Test
    fun anExtrasOnlyFillIsASentenceNotNothingNew() = runBlocking {
        appIn("en")
        val onlyExtras = merged.copy(filled = emptyList(), kept = emptyList())
        assertEquals(
            "Filled in: Medium of instruction and School bus. Check them, then save. Kept what you had typed for: Board.",
            pasteResultText(onlyExtras, emptyList(), school.fields),
        )
        assertEquals("The listing had nothing new to fill in.", pasteResultText(merged.copy(filled = emptyList(), kept = emptyList(), extrasFilled = emptyList(), extrasKept = emptyList()), emptyList(), school.fields))
    }

    @Test
    fun theSameSentenceInHindiNamesThemInHindi() = runBlocking {
        appIn("hi")
        val text = pasteResultText(merged, emptyList(), school.fields)
        assertTrue(text, "पढ़ाई का माध्यम" in text && "स्कूल बस" in text && "बोर्ड" in text)
    }

    @Test
    fun aHousesFillSaysWhatItAlwaysSaid() = runBlocking {
        appIn("en")
        val house = HouseEntity(id = "h1", label = "New house", lat = 12.97, lon = 77.64, createdAt = 1L, updatedAt = 1L)
        val m = mergeListing(house, HouseDraftDto(label = "Sea view 2BHK", price = 32_000), labelIsPlaceholder = true)
        val withFields = pasteResultText(m, emptyList(), BuiltInKind.HOUSE.fields)
        assertEquals("the default is the house's", withFields, pasteResultText(m, emptyList()))
        assertTrue(withFields, withFields.startsWith("Filled in: "))
    }
}
