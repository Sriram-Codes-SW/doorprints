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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An update file (docs/11 5.28, S4b-FR-3): [ExportBundle.build] with `since` keeps only what changed, the manifest
 * says who it is for and since when, and the file is named as an update.
 */
class ShareUpdatesTest {
    private val f = ExportFixture

    /** house1 and visit1 are older than this; house2 is the one that changed. */
    private val since = f.house1.updatedAt.coerceAtLeast(f.visit1.updatedAt) + 1

    private fun house2Changed() = f.house2.copy(updatedAt = since + 1_000)

    @Test
    fun onlyTheRowsChangedSinceAreKept() {
        val options = f.options().copy(since = since, sharedTo = "Priya")
        val bundle = ExportBundle.build(options, listOf(f.house1, house2Changed()), f.visits, f.photos)
        assertEquals(listOf("h2"), bundle.houses.map { it.id })
        assertTrue(bundle.visits.isEmpty(), "visit1 belongs to the unchanged house1 and is older")
        assertTrue(bundle.photos.isEmpty(), "photo1 belongs to house1 and is older")
    }

    @Test
    fun aChangedVisitBringsItsUnchangedHouseAlongAndAChangedHouseBringsItsPhotos() {
        val options = f.options().copy(since = since)
        val newerVisit = f.visit1.copy(updatedAt = since + 5)
        val bundle = ExportBundle.build(options, listOf(f.house1, f.house2), listOf(newerVisit), f.photos)
        assertEquals(listOf("h1"), bundle.houses.map { it.id }, "the visit needs its house in the file")
        assertEquals(listOf(newerVisit.id), bundle.visits.map { it.id })
        assertTrue(bundle.photos.isEmpty(), "an unchanged house's old photos do not travel again")

        val changedHouse1 = f.house1.copy(updatedAt = since + 1)
        val all = ExportBundle.build(options, listOf(changedHouse1, f.house2), f.visits, f.photos)
        assertEquals(listOf("v1"), all.visits.map { it.id }, "a changed house carries its visits")
        assertEquals(listOf("p1"), all.photos.map { it.id }, "and its photos")
    }

    @Test
    fun withoutSinceEverythingIsACopyAsBefore() {
        val bundle = ExportBundle.build(f.options(), f.houses, f.visits, f.photos)
        assertEquals(2, bundle.houses.size)
        assertNull(bundle.options.since)
        assertEquals("Doorprints-2026-09-22", bundle.fileStem)
    }

    @Test
    fun anUpdateIsNamedAsOneAndItsManifestSaysForWhomAndSinceWhen() {
        val options = f.options().copy(since = since, sharedTo = "Priya")
        assertEquals("Doorprints-updates-2026-09-22.zip", ExportFormat.BACKUP.fileName(options))
        assertTrue(f.options().copy(sharedTo = "Priya").isUpdate, "the first share to a name is an update too")
        val manifest = BackupManifest(
            createdAt = "2026-09-22T10:15:30Z", counts = BackupCounts(1, 0, 0),
            sharedSince = "2026-09-20T00:00:00Z", sharedTo = "Priya",
        )
        val text = BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest)
        assertTrue(text.contains("\"sharedTo\":\"Priya\"") && text.contains("\"sharedSince\":\"2026-09-20T00:00:00Z\""))
        val plain = BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest.copy(sharedSince = null, sharedTo = null))
        assertTrue(!plain.contains("shared"), "a copy's manifest carries neither field")
        val back = BackupFormat.json.decodeFromString(BackupManifest.serializer(), text)
        assertEquals("Priya", back.sharedTo)
    }
}
