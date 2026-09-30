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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Areas, places and area notes in `doorprints-backup/2` (docs/11 "Design of slice 4a"): `/1` without them, `/2` with
 * any of them, the three lists after `viewings` ordered by `updatedAt` then id in the sample's key order, the counts
 * after `counts.viewings`, kept without contact details, every check that refuses the file, and the import's merge by
 * id with the last write winning in both modes (a newer row brings back a tombstone; nothing is deleted).
 */
class BackupAreaTest {
    private val adyar = ExportArea("a_1f2e3d4c", "Adyar", 13.0067, 80.2574, 500, updatedAt = 1_788_328_800_000)
    private val indiranagar = ExportArea("a_5b6c7d8e", "Indiranagar 2nd stage", 12.9784, 77.6408, 1200, false, 1_788_415_200_000)
    private val office = ExportPlace("p_0a1b2c3d", "Office", 13.0827, 80.2707, 1_788_242_400_000)
    private val amma = ExportPlace("p_4e5f6a7b", "Amma's home", 12.9716, 77.5946, 1_788_328_800_000)
    private val tanker = ExportAreaNote("n_11223344", areaId = "a_1f2e3d4c", text = "Water tanker every morning.", updatedAt = 1_788_501_600_000)
    private val noisy = ExportAreaNote("n_55667788", street = "MG Road", text = "Noisy after 9 pm.", updatedAt = 1_788_588_000_000)

    private fun bundle(
        options: ExportOptions = ExportFixture.options(),
        areas: List<ExportArea> = listOf(indiranagar, adyar),
        places: List<ExportPlace> = listOf(amma, office),
        notes: List<ExportAreaNote> = listOf(noisy, tanker),
    ) = ExportBundle.build(
        options, ExportFixture.houses, ExportFixture.visits, ExportFixture.photos, areas = areas, places = places, areaNotes = notes,
    )

    private fun text(b: ExportBundle) = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(b))

    @Test
    fun aCopyWithoutThemIsFormat1WithoutTheKeys() {
        val t = text(bundle(areas = emptyList(), places = emptyList(), notes = emptyList()))
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/1\","), t)
        for (key in listOf("\"areas\"", "\"places\"", "\"areaNotes\"")) assertFalse(t.contains(key), key)
    }

    @Test
    fun anyOfThemMakesFormat2WithTheListsLastInTheSamplesKeyOrder() {
        val t = text(bundle())
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/2\","), t)
        assertTrue(
            t.endsWith(
                "\"areas\":[{\"id\":\"a_1f2e3d4c\",\"name\":\"Adyar\",\"lat\":13.0067,\"lon\":80.2574,\"radiusM\":500,\"updatedAt\":1788328800000}," +
                    "{\"id\":\"a_5b6c7d8e\",\"name\":\"Indiranagar 2nd stage\",\"lat\":12.9784,\"lon\":77.6408,\"radiusM\":1200,\"enabled\":false,\"updatedAt\":1788415200000}]," +
                    "\"places\":[{\"id\":\"p_0a1b2c3d\",\"name\":\"Office\",\"lat\":13.0827,\"lon\":80.2707,\"updatedAt\":1788242400000}," +
                    "{\"id\":\"p_4e5f6a7b\",\"name\":\"Amma's home\",\"lat\":12.9716,\"lon\":77.5946,\"updatedAt\":1788328800000}]," +
                    "\"areaNotes\":[{\"id\":\"n_11223344\",\"areaId\":\"a_1f2e3d4c\",\"text\":\"Water tanker every morning.\",\"updatedAt\":1788501600000}," +
                    "{\"id\":\"n_55667788\",\"street\":\"MG Road\",\"text\":\"Noisy after 9 pm.\",\"updatedAt\":1788588000000}]}",
            ),
            t,
        )
        for (one in listOf(bundle(places = emptyList(), notes = emptyList()), bundle(areas = emptyList(), notes = emptyList()), bundle(areas = emptyList(), places = emptyList()))) {
            assertEquals(BackupFormat.ID_2, BackupData.of(one).format)
        }
        val counts = BackupCounts.of(BackupData.of(bundle()))
        assertEquals(BackupCounts(2, 1, 1, areas = 2, places = 2, areaNotes = 2), counts)
        assertTrue(
            BackupFormat.json.encodeToString(BackupCounts.serializer(), counts).endsWith("\"photos\":1,\"areas\":2,\"places\":2,\"areaNotes\":2}"),
        )
    }

    @Test
    fun aCopyWithoutContactDetailsOrOfAShortlistKeepsThemAllAndAnUpdateTheChangedOnes() {
        val without = BackupData.of(bundle(ExportFixture.options(includeContacts = false, scope = ExportScope.SHORTLISTED)))
        assertEquals(listOf(adyar, indiranagar), without.areaRows)
        assertEquals(listOf(office, amma), without.placeRows)
        assertEquals(listOf(tanker, noisy), without.areaNoteRows)
        val update = bundle(ExportFixture.options().copy(since = 1_788_400_000_000))
        assertEquals(listOf(listOf("a_5b6c7d8e"), emptyList(), listOf("n_11223344", "n_55667788")),
            listOf(update.areas.map { it.id }, update.places.map { it.id }, update.areaNotes.map { it.id }))
    }

    @Test
    fun theListsReadBackAndAnAbsentRadiusIs500() {
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text(bundle()))
        assertEquals(listOf(adyar, indiranagar), back.areaRows)
        assertNull(BackupValidation.checkData(back))
        val sparse = BackupFormat.json.decodeFromString(
            BackupData.serializer(),
            "{\"format\":\"doorprints-backup/2\",\"exportedAt\":1,\"areas\":[{\"id\":\"a_00000001\",\"name\":\"X\",\"lat\":1,\"lon\":2,\"updatedAt\":2}]}",
        )
        assertNull(BackupValidation.checkData(sparse))
        assertEquals(500, sparse.areaRows.single().toArea()?.radiusM)
        assertEquals(true, sparse.areaRows.single().toArea()?.enabled)
    }

    @Test
    fun aBadAreaPlaceOrNoteRefusesTheWholeFile() {
        val good = BackupData.of(bundle())
        assertNull(BackupValidation.checkData(good))
        for (bad in listOf(
            adyar.copy(id = "a/b"), adyar.copy(id = ".."), adyar.copy(name = ""), adyar.copy(name = "  "), adyar.copy(name = "x".repeat(101)),
            adyar.copy(lat = 90.5), adyar.copy(lon = -181.0), adyar.copy(lat = null), adyar.copy(radiusM = 199), adyar.copy(radiusM = 2001),
            adyar.copy(updatedAt = -1),
        )) assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(areas = listOf(bad))), bad.toString())
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(areas = listOf(adyar, adyar.copy(name = "Y")))), "a repeated id")
        for (bad in listOf(office.copy(id = ""), office.copy(name = ""), office.copy(name = "x".repeat(61)), office.copy(lat = 91.0), office.copy(lon = null))) {
            assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(places = listOf(bad))), bad.toString())
        }
        for (bad in listOf(
            tanker.copy(areaId = null), tanker.copy(street = "MG Road"), tanker.copy(areaId = " "), tanker.copy(areaId = "a".repeat(65)),
            noisy.copy(street = "x".repeat(101)), noisy.copy(text = " "), noisy.copy(text = "x".repeat(1001)), noisy.copy(id = "n/1"),
        )) assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(areaNotes = listOf(bad))), bad.toString())
        // The caps: 21 areas, 11 places or 201 notes refuse the file; 20, 10 and 200 pass.
        fun areas(n: Int) = (0 until n).map { adyar.copy(id = "a_" + it.toString(16).padStart(8, '0')) }
        fun places(n: Int) = (0 until n).map { office.copy(id = "p_" + it.toString(16).padStart(8, '0')) }
        fun notes(n: Int) = (0 until n).map { noisy.copy(id = "n_" + it.toString(16).padStart(8, '0')) }
        assertNull(BackupValidation.checkData(good.copy(areas = areas(20), places = places(10), areaNotes = notes(200))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(areas = areas(21))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(places = places(11))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(areaNotes = notes(201))))
        assertEquals(
            BackupProblem.BROKEN_DATA,
            BackupValidation.checkManifest(
                BackupManifest(format = "doorprints-backup/2", createdAt = "x", counts = BackupCounts(0, 0, 0, areaNotes = -1)),
            ),
        )
    }

    // ---- the import ----

    private fun file() = BackupData(
        format = "doorprints-backup/2", exportedAt = 1, areas = listOf(adyar, indiranagar), places = listOf(office), areaNotes = listOf(tanker),
    )

    private fun plan(mode: ImportMode, areas: Map<String, Long>, skip: Boolean = false) = ImportPlan.plan(
        file(), emptyMap(), emptyMap(), emptySet(), emptySet(), mode, newId = { "new" }, skipUpdates = skip, localAreas = areas,
    )

    private fun preview(mode: ImportMode, areas: Map<String, Long>, skip: Boolean = false) = ImportPlan.preview(
        file(), emptyMap(), emptyMap(), emptySet(), emptySet(), mode, skipUpdates = skip, localAreas = areas,
    )

    @Test
    fun theyMergeByIdTheNewerWinsATombstoneComesBackForANewerRowAndACopyKeepsTheIds() {
        // Adyar is older here (a tombstone, say), Indiranagar newer here: Adyar is written, Indiranagar left alone.
        val local = mapOf(adyar.id to adyar.updatedAt - 1, indiranagar.id to indiranagar.updatedAt + 1)
        for (mode in ImportMode.entries) {
            val actions = plan(mode, local)
            assertEquals(listOf(adyar), actions.areas, mode.name)
            assertEquals(listOf(office), actions.places, mode.name)
            assertEquals(listOf(tanker), actions.areaNotes, "$mode: a copy keeps the ids, as a note names its area")
            val p = preview(mode, local)
            assertEquals(listOf(0, 1, 1, 0, 1, 0), listOf(p.newAreas, p.updatedAreas, p.newPlaces, p.updatedPlaces, p.newAreaNotes, p.updatedAreaNotes))
            assertFalse(p.isEmpty)
        }
        assertEquals(1, preview(ImportMode.MERGE, local).overwrites)
        // Keep mine: only what is new here.
        assertEquals(emptyList(), plan(ImportMode.MERGE, local, skip = true).areas)
        assertEquals(listOf(office), plan(ImportMode.MERGE, local, skip = true).places)
    }
}
