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
 * Viewings in `doorprints-backup/2` (docs/11 5.8, slice 3b-1): `/1` with none, `/2` with a viewing only, the `viewings`
 * list after `questions` ordered by `updatedAt` then id with its optional keys only when set, `withWhom` blanked in a
 * copy without contact details, `counts.viewings`, every check that refuses the file, an absent or null value read as
 * its default, and the import's merge by id with the last write winning (a newer row brings back a tombstone).
 */
class BackupViewingTest {
    private val done = ExportViewing(
        "v_3c4d5e6f", "h1", 1_788_604_800_000, 30, "FIRST", "DONE", 60, withWhom = "Ravi Kumar", visitId = "v1",
        updatedAt = 1_788_606_600_000,
    )
    private val planned = ExportViewing(
        "v_a1b2c3d4", "h1", 1_790_501_400_000, 45, "SECOND", "PLANNED", 30, huntReminder = true,
        notes = "Ask for the water bill.", updatedAt = 1_790_072_130_000,
    )

    private fun bundle(options: ExportOptions = ExportFixture.options(), viewings: List<ExportViewing> = listOf(planned, done)) =
        ExportBundle.build(options, ExportFixture.houses, ExportFixture.visits, ExportFixture.photos, viewings = viewings)

    private fun text(b: ExportBundle) = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(b))

    @Test
    fun aCopyWithNoViewingIsFormat1WithoutTheKey() {
        val t = text(bundle(viewings = emptyList()))
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/1\","), t)
        assertFalse(t.contains("\"viewings\""), t)
        assertNull(BackupCounts.of(BackupData.of(bundle(viewings = emptyList()))).viewings)
    }

    @Test
    fun aCopyWithAViewingOnlyIsFormat2WithTheListLastInItsKeyOrder() {
        val t = text(bundle())
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/2\","), t)
        assertTrue(
            t.endsWith(
                "\"viewings\":[{\"id\":\"v_3c4d5e6f\",\"houseId\":\"h1\",\"startsAt\":1788604800000,\"durationMin\":30,\"kind\":\"FIRST\"," +
                    "\"status\":\"DONE\",\"remindMin\":60,\"withWhom\":\"Ravi Kumar\",\"visitId\":\"v1\",\"updatedAt\":1788606600000}," +
                    "{\"id\":\"v_a1b2c3d4\",\"houseId\":\"h1\",\"startsAt\":1790501400000,\"durationMin\":45,\"kind\":\"SECOND\"," +
                    "\"status\":\"PLANNED\",\"remindMin\":30,\"huntReminder\":true,\"notes\":\"Ask for the water bill.\"," +
                    "\"updatedAt\":1790072130000}]}",
            ),
            t,
        )
        assertEquals("doorprints-backup/2", BackupFormat.idFor(0, viewings = 1))
        val counts = BackupCounts.of(BackupData.of(bundle()))
        assertEquals(BackupCounts(2, 1, 1, viewings = 2), counts)
        assertTrue(BackupFormat.json.encodeToString(BackupCounts.serializer(), counts).endsWith("\"photos\":1,\"viewings\":2}"))
        // huntReminder is written only when true.
        assertFalse(BackupFormat.json.encodeToString(ExportViewing.serializer(), planned.copy(huntReminder = null)).contains("huntReminder"))
        assertEquals(null, ExportViewing.of(planned.toViewing().copy(huntReminder = false), 1).huntReminder)
    }

    @Test
    fun aCopyWithoutContactDetailsBlanksWithWhomAndKeepsTheRest() {
        val without = BackupData.of(bundle(ExportFixture.options(includeContacts = false)))
        assertEquals("doorprints-backup/2", without.format)
        assertEquals(listOf(done.copy(withWhom = null), planned), without.viewingRows)
        // A shortlist keeps the viewings of its houses only; a copy of all houses keeps one of a house that is gone.
        val gone = done.copy(id = "v_00000009", houseId = "gone")
        assertEquals(3, bundle(viewings = listOf(planned, done, gone)).viewings.size)
        assertEquals(
            listOf("v_3c4d5e6f", "v_a1b2c3d4"),
            bundle(ExportFixture.options(scope = ExportScope.SHORTLISTED), listOf(planned, done, gone)).viewings.map { it.id },
        )
        // An update carries the viewings changed since.
        assertEquals(listOf("v_a1b2c3d4"), bundle(ExportFixture.options().copy(since = 1_790_000_000_000)).viewings.map { it.id })
    }

    @Test
    fun theListReadsBackAndAnAbsentOrNullValueIsTheDefault() {
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text(bundle()))
        assertEquals(listOf(done, planned), back.viewingRows)
        assertNull(BackupValidation.checkData(back))
        val sparse = BackupFormat.json.decodeFromString(
            BackupData.serializer(),
            "{\"format\":\"doorprints-backup/2\",\"exportedAt\":1,\"viewings\":[{\"id\":\"v_00000001\",\"houseId\":\"h1\"," +
                "\"startsAt\":5,\"durationMin\":null,\"kind\":null,\"updatedAt\":2}]}",
        )
        assertNull(BackupValidation.checkData(sparse))
        val v = sparse.viewingRows.single().toViewing()
        assertEquals(listOf("30", "FIRST", "PLANNED", "60"), listOf(v.durationMin.toString(), v.kind, v.status, v.remindMin.toString()))
    }

    @Test
    fun aBadViewingRefusesTheWholeFile() {
        val good = BackupData.of(bundle())
        fun check(vararg rows: ExportViewing) = BackupValidation.checkData(good.copy(viewings = rows.toList()))
        assertNull(check(done, planned))
        for (bad in listOf(
            done.copy(id = "a/b"), done.copy(id = ".."), done.copy(houseId = ""), done.copy(houseId = " "),
            done.copy(houseId = "h".repeat(65)), done.copy(startsAt = 0), done.copy(startsAt = -5), done.copy(durationMin = 4),
            done.copy(durationMin = 481), done.copy(kind = "THIRD"), done.copy(status = "MISSED"), done.copy(remindMin = 45),
            done.copy(remindMin = -1), done.copy(withWhom = "w".repeat(201)), done.copy(notes = "n".repeat(2001)),
            done.copy(visitId = "x".repeat(65)), done.copy(updatedAt = -1),
        )) assertEquals(BackupProblem.BROKEN_DATA, check(bad), bad.toString())
        assertEquals(BackupProblem.BROKEN_DATA, check(done, done.copy(startsAt = 9)), "a duplicate id")
        val many = (0..5_000).map { done.copy(id = "v_" + it.toString(16).padStart(8, '0')) }
        assertEquals(BackupProblem.BROKEN_DATA, check(*many.toTypedArray()), "5,001 viewings")
        assertNull(check(*many.take(5_000).toTypedArray()))
        // A missing houseId or startsAt reads as blank / 0 and is refused too.
        val missing = BackupFormat.json.decodeFromString(
            BackupData.serializer(), "{\"format\":\"doorprints-backup/2\",\"exportedAt\":1,\"viewings\":[{\"id\":\"v_1\",\"updatedAt\":2}]}",
        )
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(missing))
        assertEquals(
            BackupProblem.BROKEN_DATA,
            BackupValidation.checkManifest(
                BackupManifest(format = "doorprints-backup/2", createdAt = "x", counts = BackupCounts(0, 0, 0, viewings = -1)),
            ),
        )
    }

    // ---- the import ----

    private fun file(viewings: List<ExportViewing>, houses: List<ExportHouse> = emptyList(), visits: List<ExportVisit> = emptyList()) =
        BackupData(format = "doorprints-backup/2", exportedAt = 1, houses = houses, visits = visits, viewings = viewings)

    private var ids = 0
    private fun plan(data: BackupData, local: Map<String, Long>, mode: ImportMode = ImportMode.MERGE, skip: Boolean = false) =
        ImportPlan.plan(
            data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, newId = { "id${ids++}" }, skipUpdates = skip,
            localViewings = local,
        )

    private fun preview(data: BackupData, local: Map<String, Long>, mode: ImportMode = ImportMode.MERGE, skip: Boolean = false) =
        ImportPlan.preview(data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, skipUpdates = skip, localViewings = local)

    @Test
    fun viewingsMergeByIdTheNewerWinsAndATombstoneComesBackOnlyForANewerRow() {
        val data = file(listOf(done, planned))
        // done is newer in the file than the tombstone here, planned is new here.
        val local = mapOf(done.id to done.updatedAt - 1)
        assertEquals(listOf(done, planned), plan(data, local).viewings)
        val p = preview(data, local)
        assertEquals(listOf(1, 1, 1), listOf(p.newViewings, p.updatedViewings, p.overwrites))
        assertFalse(p.isEmpty)
        // Older or equal here: left alone (a tombstone with a later time stays deleted). Nothing is ever deleted.
        assertEquals(emptyList(), plan(data, mapOf(done.id to done.updatedAt, planned.id to planned.updatedAt + 5)).viewings)
        assertTrue(preview(file(emptyList()), local).isEmpty)
        // "Keep mine": only the new ones.
        assertEquals(listOf(planned), plan(data, local, skip = true).viewings)
    }

    @Test
    fun aCopyImportGivesTheViewingsNewIdsOnTheCopiedHousesAndVisits() {
        val data = file(
            listOf(done, planned.copy(houseId = "not-in-file")), houses = listOf(ExportFixture.house1), visits = ExportFixture.visits,
        )
        val actions = plan(data, mapOf(done.id to done.updatedAt + 1), ImportMode.COPY)
        val house = actions.houses.single().id
        val visit = actions.visits.single().id
        assertEquals(2, actions.viewings.size)
        assertTrue(actions.viewings.none { it.id == done.id || it.id == planned.id })
        assertEquals(listOf(house, "not-in-file"), actions.viewings.map { it.houseId })
        assertEquals(visit, actions.viewings.first().visitId)
        assertEquals(2, preview(data, emptyMap(), ImportMode.COPY).newViewings)
    }
}
