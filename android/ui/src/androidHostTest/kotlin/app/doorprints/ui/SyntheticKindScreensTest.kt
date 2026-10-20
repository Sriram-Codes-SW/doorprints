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

import app.doorprints.data.HouseEntity
import app.doorprints.shared.model.DevKinds
import app.doorprints.shared.model.KindFileKinds
import app.doorprints.shared.model.KindFilter
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.TestOnlyKinds
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.allStringResources
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A space of the synthetic kind of the conformance suite on the phone and the iPhone (docs/03 section 18.26, 18.28; the twin of the website's
 * `forKind('zz_all')`): the kind of `docs/schemas/kinds/test-all.json`, with every capability on, is registered for each case only (the test
 * seam of `:shared`, [KindFileKinds]; no build lists it), and the screens' own rules run against it. Every Android key of the file is a Compose
 * string; the space shows every place a kind with every capability shows; the form lays its extras out and stores each value as one of the
 * candidate's other extras; and the list filters by the file's choices and its two ranges. The words of the four languages are `:shared`'s
 * dictionaries (`KindWordVectorsTest`), which read the same strings.
 */
@OptIn(TestOnlyKinds::class)
class SyntheticKindScreensTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private var undo: (() -> Unit)? = null

    @BeforeTest
    fun register() {
        undo = KindFileKinds.register(repoFile("docs/schemas/kinds/test-all.json").readText())
    }

    @AfterTest
    fun unregister() {
        undo?.invoke()
    }

    private val zz get() = DevKinds.of("zz_all", on = true)

    @Test
    fun theKindIsRegisteredForTheRunOnlyAndOnlyWhileTheFlagAllowsIt() {
        assertEquals("zz_all", zz.id)
        assertEquals(listOf("house", "school", "zz_all"), DevKinds.creatable(true).map { it.id })
        assertEquals(listOf("house"), DevKinds.creatable(false).map { it.id }, "the flag off: the house alone, whatever a test registered")
        assertEquals("house", DevKinds.of("zz_all", on = false).id)
        undo?.invoke()
        undo = null
        assertEquals("house", DevKinds.of("zz_all", on = true).id, "taken away again")
    }

    @Test
    fun everyAndroidKeyOfTheKindIsAComposeString() {
        for (ref in listOf("kind.new", "kind.many", "kind.delete")) assertNotNull(Res.allStringResources[zz.labels.resolve(ref)?.key], ref)
        for (f in zz.fields.extras) {
            assertNotNull(Res.allStringResources[zz.fields.label(f)], f.id)
            for (o in f.options) assertNotNull(Res.allStringResources[o.label], "${f.id} ${o.id}")
        }
        assertTrue(zz.extracts)
    }

    @Test
    fun theSpaceShowsEveryPlaceAKindWithEveryCapabilityShows() {
        val setting = KindSetting(listOf("house", "zz_all"), experimental = "1", enabledText = "[\"house\",\"zz_all\"]")
        val space = SpaceUi.resolve(setting, "zz_all")
        assertEquals("zz_all", space.kind)
        assertTrue(space.multi)
        for (ref in listOf("section.rooms", "section.moveIn", "section.viewings", "section.questions", "field.brokerId", "section.checklist")) assertTrue(space.shows(ref), ref)
        assertTrue(space.huntHere)
    }

    @Test
    fun theExtrasAreLaidOutAndEachValueLandsAsOneOfTheCandidatesOtherExtras() {
        val kind = zz
        for (section in listOf("details", "cost")) {
            val rows = phoneRows(kind.fields.shown(section, null)).flatten().map { it.id }
            assertEquals(kind.fields.shown(section, null).map { it.id }.sorted(), rows.sorted(), section)
        }
        val seats = kind.fields.extras.single { it.id == "seats" }
        val fees = kind.fields.extras.single { it.id == "fees" }
        assertEquals(3, maxDigits(seats), "a number of at most 500 has three digits")
        val h = HouseEntity(id = "z", label = "Lake Place", lat = 13.0, lon = 80.25, createdAt = 1, updatedAt = 1).forKind(kind)
            .withExtra(seats, JsonPrimitive(40))
            .withExtra(fees, JsonObject(mapOf("amount" to JsonPrimitive(96000), "cadence" to JsonPrimitive("year"))))
        assertEquals("zz_all", h.kind)
        assertEquals(listOf("fees", "seats"), h.extrasOther!!.keys.toList())
        assertEquals(listOf(null, null, null), listOf(h.bedrooms, h.cost, h.floor))
        assertEquals(JsonPrimitive(40), h.extraValue(seats))
    }

    @Test
    fun theListFiltersByTheFilesChoicesAndItsTwoRanges() {
        val fields = zz.fields
        assertEquals(listOf("board", "includesMeals", "seats", "fees"), KindFilter.rows(fields).map { it.id })
        fun extras(seats: Int, fee: Int) = JsonObject(mapOf("seats" to JsonPrimitive(seats), "fees" to JsonObject(mapOf("amount" to JsonPrimitive(fee), "cadence" to JsonPrimitive("year")))))
        val small = extras(20, 12_000)
        val big = extras(300, 60_000)
        assertTrue(KindFilter.matches(mapOf("seats" to ":50"), fields, small))
        assertFalse(KindFilter.matches(mapOf("seats" to ":50"), fields, big))
        assertTrue(KindFilter.matches(mapOf("fees" to "50000:", "seats" to "100:"), fields, big))
        assertFalse(KindFilter.matches(mapOf("fees" to "50000:", "seats" to "100:"), fields, small))
        assertEquals(2, KindFilter.active(mapOf("seats" to "10:50", "fees" to ":20000"), fields))
    }
}
