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
 * The brokers in `doorprints-backup/2` (docs/11 5.25, slice 1b; docs/schemas README 1.1 and 3.4): `/2` only when the
 * copy has a broker, the list after `photos`, `brokerId` after `cost`, `counts.brokers`, the check that refuses a bad
 * broker, and the import's merge by id with last write wins.
 */
class BackupBrokerTest {
    private val ravi = ExportBroker(
        id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", name = "Ravi Kumar", phone = "+91 98400 11111", agency = "Adyar Homes",
        feeTerms = "15 days' rent, once", notes = "Replies fast", rating = 4, updatedAt = 1_789_029_000_000,
    )
    private val meena = ExportBroker(id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", name = "Meena Iyer", updatedAt = 1_788_415_200_000)
    private val house1 = ExportFixture.house1.copy(brokerId = ravi.id)

    private fun bundle(options: ExportOptions = ExportFixture.options(), brokers: List<ExportBroker> = listOf(ravi, meena)) =
        ExportBundle.build(options, listOf(house1, ExportFixture.house2), ExportFixture.visits, ExportFixture.photos, brokers)

    private fun data(b: ExportBundle) = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(b))

    @Test
    fun aCopyWithoutBrokersIsWrittenAsFormat1WithNoBrokersKey() {
        val text = data(bundle(brokers = emptyList()))
        assertTrue(text.startsWith("{\"format\":\"doorprints-backup/1\","), text)
        assertFalse(text.contains("brokers"), text)
        assertNull(BackupData.of(bundle(brokers = emptyList())).brokers)
        assertNull(BackupCounts.of(BackupData.of(bundle(brokers = emptyList()))).brokers)
        assertEquals("doorprints-backup/1", BackupFormat.idFor(0))
    }

    @Test
    fun aCopyWithABrokerIsFormat2WithTheListAfterThePhotosOrderedByUpdatedAt() {
        val text = data(bundle())
        assertTrue(text.startsWith("{\"format\":\"doorprints-backup/2\","), text)
        assertTrue(text.contains("\"brokerId\":\"${ravi.id}\",\"checklist\""), "brokerId sits between cost and checklist: $text")
        assertTrue(text.indexOf("\"photos\":[") < text.indexOf("\"brokers\":["))
        assertTrue(
            text.endsWith(
                "\"brokers\":[{\"id\":\"${meena.id}\",\"name\":\"Meena Iyer\",\"updatedAt\":1788415200000}," +
                    "{\"id\":\"${ravi.id}\",\"name\":\"Ravi Kumar\",\"phone\":\"+91 98400 11111\",\"agency\":\"Adyar Homes\"," +
                    "\"feeTerms\":\"15 days' rent, once\",\"notes\":\"Replies fast\",\"rating\":4,\"updatedAt\":1789029000000}]}",
            ),
            text,
        )
        val counts = BackupCounts.of(BackupData.of(bundle()))
        assertEquals(BackupCounts(2, 1, 1, brokers = 2), counts)
        assertTrue(BackupFormat.json.encodeToString(BackupCounts.serializer(), counts).endsWith("\"photos\":1,\"brokers\":2}"))
        // A /1 manifest keeps its bytes: no `brokers` key.
        assertEquals("{\"houses\":2,\"visits\":1,\"photos\":1}", BackupFormat.json.encodeToString(BackupCounts.serializer(), BackupCounts(2, 1, 1)))
    }

    @Test
    fun aCopyMadeWithoutContactDetailsLeavesOutTheBrokersAndTheLinksAndIsFormat1() {
        val without = BackupData.of(bundle(ExportFixture.options(includeContacts = false)))
        assertEquals("doorprints-backup/1", without.format)
        assertNull(without.brokers)
        assertTrue(without.houses.all { it.brokerId == null })
    }

    @Test
    fun theListReadsBackAndAnAbsentListIsNone() {
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), data(bundle()))
        assertEquals(listOf(meena, ravi), back.brokerRows)
        assertEquals(ravi.id, back.houses.first { it.id == "h1" }.brokerId)
        val v1 = BackupFormat.json.decodeFromString(BackupData.serializer(), "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1}")
        assertEquals(emptyList(), v1.brokerRows)
        assertNull(BackupValidation.checkData(back))
    }

    @Test
    fun aBadBrokerRefusesTheWholeFileButADanglingLinkDoesNot() {
        val good = BackupData.of(bundle())
        fun with(b: ExportBroker) = good.copy(brokers = listOf(b))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(ravi.copy(name = " "))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(ravi.copy(name = "n".repeat(201)))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(ravi.copy(rating = 6))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(ravi.copy(rating = 0))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(ravi.copy(id = "../x"))))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(good.copy(brokers = listOf(ravi, ravi))))
        assertEquals(
            BackupProblem.BROKEN_DATA,
            BackupValidation.checkData(good.copy(houses = listOf(house1.copy(brokerId = "a/b")))),
        )
        // A house whose broker is in neither the file nor the store is imported with the id unchanged.
        assertNull(BackupValidation.checkData(good.copy(brokers = null, houses = listOf(house1))))
        assertEquals(
            BackupProblem.BROKEN_DATA,
            BackupValidation.checkManifest(
                BackupManifest(format = "doorprints-backup/2", createdAt = "x", counts = BackupCounts(0, 0, 0, brokers = -1)),
            ),
        )
    }

    // ---- the import ----

    private fun file(vararg brokers: ExportBroker) = BackupData(
        format = "doorprints-backup/2", exportedAt = 1, houses = listOf(house1), brokers = brokers.toList(),
    )

    private fun plan(data: BackupData, local: Map<String, Long>, mode: ImportMode = ImportMode.MERGE, skip: Boolean = false) =
        ImportPlan.plan(
            data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, newId = counter(), skipUpdates = skip,
            localBrokers = local,
        )

    private fun counter(): () -> String {
        var n = 0
        return { "new-${++n}" }
    }

    @Test
    fun brokersMergeByIdAndTheNewestUpdatedAtWins() {
        val data = file(ravi, meena)
        val actions = plan(data, mapOf(ravi.id to ravi.updatedAt - 1, meena.id to meena.updatedAt + 1))
        // Ravi is newer in the file (replaces), Meena is newer here (kept).
        assertEquals(listOf(ravi), actions.brokers)
        assertEquals(setOf(ravi.id), actions.updatedBrokerIds)
        // Equal timestamps: already here. A broker the phone does not know: added.
        assertEquals(emptyList(), plan(data, mapOf(ravi.id to ravi.updatedAt, meena.id to meena.updatedAt)).brokers)
        val added = plan(data, emptyMap())
        assertEquals(listOf(ravi, meena), added.brokers)
        assertEquals(emptySet(), added.updatedBrokerIds)
        // "Keep mine" leaves a newer one alone, and still adds the new.
        assertEquals(listOf(meena), plan(data, mapOf(ravi.id to ravi.updatedAt + 1), skip = true).brokers)
        assertEquals(emptyList(), plan(data, mapOf(ravi.id to 1, meena.id to meena.updatedAt), skip = true).brokers)
        // A house naming a broker the file and the phone lack keeps the id (it dangles).
        assertEquals(ravi.id, plan(file(), emptyMap()).houses.single().brokerId)
    }

    @Test
    fun thePreviewPromisesWhatThePlanWrites() {
        val data = file(ravi, meena)
        val local = mapOf(ravi.id to ravi.updatedAt - 1)
        val preview = ImportPlan.preview(data, emptyMap(), emptyMap(), emptySet(), emptySet(), ImportMode.MERGE, localBrokers = local)
        val actions = plan(data, local)
        assertEquals(1, preview.newBrokers)
        assertEquals(1, preview.updatedBrokers)
        assertEquals(actions.brokers.size, preview.newBrokers + preview.updatedBrokers)
        assertFalse(preview.isEmpty)
        assertEquals(1, preview.overwrites)
        val same = ImportPlan.preview(
            file(ravi), mapOf(house1.id to house1.updatedAt), emptyMap(), emptySet(), emptySet(), ImportMode.MERGE,
            localBrokers = mapOf(ravi.id to ravi.updatedAt),
        )
        assertTrue(same.isEmpty)
    }

    @Test
    fun aCopyGivesTheBrokersNewIdsAndTheCopiedHousesNameThem() {
        val actions = plan(file(ravi, meena), mapOf(ravi.id to ravi.updatedAt), ImportMode.COPY)
        assertEquals(2, actions.brokers.size)
        val newRavi = actions.brokers.first { it.name == "Ravi Kumar" }
        assertFalse(newRavi.id == ravi.id)
        assertEquals(newRavi.id, actions.houses.single().brokerId)
        assertEquals(ravi.copy(id = newRavi.id), newRavi)
        val preview = ImportPlan.preview(file(ravi, meena), emptyMap(), emptyMap(), emptySet(), emptySet(), ImportMode.COPY)
        assertEquals(2, preview.newBrokers)
        // The ids of a file without brokers come out as before: the brokers draw theirs last.
        val none = plan(BackupData(exportedAt = 1, houses = listOf(ExportFixture.house1)), emptyMap(), ImportMode.COPY)
        assertEquals("new-1", none.houses.single().id)
    }
}
