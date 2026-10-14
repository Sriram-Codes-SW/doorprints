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

package app.doorprints.shared.export

import app.doorprints.shared.model.SpaceView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A readable copy holds one space (kinds PR C, S4b-BL-289; docs/03 §18.13), and the backup stays whole: the space's
 * candidates only, with their visits, photos and viewings; the brokers of the copy, not the store's; the kind's plural in
 * the file name before the date (`space-scope-vectors.json` `fileName`); and with no space every name, row and byte is
 * what it always was (`HouseCopyPinsTest`, `ExportFormatTest`). A school in Guwahati beside a house in Indiranagar.
 */
class SpaceCopiesTest {
    private val both = listOf("house", "school")
    private val school = ExportHouse(
        id = "s1", label = "Pine Hill School", lat = 26.1433, lon = 91.7898, status = "SHORTLISTED", kind = "school",
        createdAt = 1_790_000_200_000, updatedAt = 1_790_000_200_000,
    )
    private val houses = ExportFixture.houses + school
    // v1 is the house's visit, v2 the school's, u1 a dwell Hunt mode recorded at a place that is no candidate yet.
    private val visits = ExportFixture.visits + ExportFixture.visit1.copy(id = "v2", houseId = "s1") + ExportFixture.visit1.copy(id = "u1", houseId = null)
    private val broker = ExportBroker(id = "b1", name = "R. Rao", phone = "+91 98450 00000", updatedAt = 1_790_000_300_000)

    private fun spaceOptions(kind: String, default: String = "house") =
        ExportFixture.options().copy(space = SpaceView(kind, multi = true, default = default, available = both))

    @Test
    fun aSchoolSpaceCopyHoldsTheSchoolsOnlyWithTheirVisitsAndNoOneElsesBroker() {
        val bundle = ExportBundle.build(spaceOptions("school"), houses, visits, ExportFixture.photos, brokers = listOf(broker))
        assertEquals(listOf("s1"), bundle.houses.map { it.id })
        assertEquals(listOf("v2"), bundle.visits.map { it.id })
        assertTrue(bundle.photos.isEmpty())
        assertTrue(bundle.brokers.isEmpty()) // the broker belongs to no school of the copy
        assertTrue(bundle.unlinkedVisits.isEmpty())
    }

    @Test
    fun theHouseSpaceCopyHoldsTheHousesAndTheirBroker() {
        val withBroker = houses.map { if (it.id == "h1") it.copy(brokerId = "b1") else it }
        val bundle = ExportBundle.build(spaceOptions("house"), withBroker, visits, ExportFixture.photos, brokers = listOf(broker))
        assertEquals(listOf("h1", "h2"), bundle.houses.map { it.id })
        assertEquals(listOf("b1"), bundle.brokers.map { it.id })
        assertEquals(listOf("p1"), bundle.photos.map { it.id })
        assertEquals(listOf("v1"), bundle.visits.map { it.id })
        assertTrue(bundle.unlinkedVisits.isEmpty()) // a dwell that belongs to no candidate is no space's
    }

    @Test
    fun aKindWithNoSpaceHereIsInTheDefaultSpace() {
        val clinic = school.copy(id = "c1", kind = "clinic")
        val bundle = ExportBundle.build(spaceOptions("house"), houses + clinic, visits, ExportFixture.photos)
        assertTrue("c1" in bundle.houses.map { it.id })
        assertFalse("s1" in bundle.houses.map { it.id })
    }

    @Test
    fun withNoSpaceTheCopyHoldsEverythingAsItAlwaysDid() {
        val bundle = ExportBundle.build(ExportFixture.options(), houses, visits, ExportFixture.photos)
        assertEquals(listOf("h1", "h2", "s1"), bundle.houses.map { it.id })
        assertEquals(listOf("u1"), bundle.unlinkedVisits.map { it.id })
        assertNull(bundle.options.space)
    }

    @Test
    fun theFileNameCarriesTheEnglishPluralBeforeTheDate() {
        val options = spaceOptions("school")
        assertEquals("Doorprints-schools-2026-09-22", ExportFormat.stem(options))
        assertEquals("Doorprints-schools-2026-09-22.html", ExportFormat.HTML.fileName(options))
        assertEquals("Doorprints-schools-2026-09-22.pdf", ExportFormat.PDF.fileName(options))
        assertEquals("Doorprints-schools-2026-09-22-csv.zip", ExportFormat.CSV.fileName(options))
        assertEquals("Doorprints-schools-2026-09-22.xlsx", ExportFormat.XLSX.fileName(options))
        assertEquals("Doorprints-schools-2026-09-22.md", ExportFormat.MARKDOWN.fileName(options))
    }

    @Test
    fun theHousesNamesAndEveryBackupNameAreUnchanged() {
        val house = spaceOptions("house")
        assertEquals("Doorprints-2026-09-22-csv.zip", ExportFormat.CSV.fileName(house))
        assertEquals("Doorprints-2026-09-22.md", ExportFormat.MARKDOWN.fileName(house))
        // A backup is made with no space (the Export screen drops it), so it is named as it always was.
        assertEquals("Doorprints-backup-2026-09-22.zip", ExportFormat.BACKUP.fileName(ExportFixture.options()))
        assertEquals("Doorprints-updates-2026-09-22.zip", ExportFormat.BACKUP.fileName(ExportFixture.options().copy(sharedTo = "Asha")))
    }

    @Test
    fun theBackupsSearchObjectNamesTheKindsOnAndTheDefault() {
        val rows = BackupData.of(ExportBundle.build(ExportFixture.options(), houses, visits, ExportFixture.photos))
        val house = BackupWire.encodeData(rows)
        assertTrue("\"exportedAt\":1790072130000,\"search\":{\"kind\":\"house\"},\"candidates\"" in house)
        val two = BackupWire.encodeData(rows, listOf("house", "school"), "school")
        assertTrue("\"exportedAt\":1790072130000,\"search\":{\"kinds\":[\"house\",\"school\"],\"defaultKind\":\"school\"},\"candidates\"" in two)
        // Only the search object differs: the rest of the file is the same bytes.
        assertEquals(house.replace("{\"kind\":\"house\"}", "{\"kinds\":[\"house\",\"school\"],\"defaultKind\":\"school\"}"), two)
    }
}
