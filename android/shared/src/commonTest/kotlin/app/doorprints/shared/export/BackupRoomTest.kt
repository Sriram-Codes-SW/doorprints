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

import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rooms in a backup (docs/11 5.6, slice 1c; docs/schemas README 1.1): nested in the house after `cost` and before
 * `brokerId`, a `/2` file when the copy has a room (and still `/1` with neither brokers nor rooms), kept in a copy
 * without contact details, and the check that refuses a file with a bad room.
 */
class BackupRoomTest {
    private val master = HouseRoom(
        id = "c1111111-1111-4111-8111-111111111111", type = "BEDROOM", name = "Master bedroom", lengthCm = 396,
        widthCm = 366, condition = 4, notes = "Damp patch near the window", sort = 0,
    )
    private val kitchen = HouseRoom(
        id = "c2222222-2222-4222-8222-222222222222", type = "KITCHEN", name = "Kitchen", lengthCm = 300, widthCm = 244, sort = 1,
    )
    private val house1 = ExportFixture.house1.copy(rooms = listOf(master, kitchen))

    private fun bundle(options: ExportOptions = ExportFixture.options(), houses: List<ExportHouse> = listOf(house1, ExportFixture.house2)) =
        ExportBundle.build(options, houses, ExportFixture.visits, ExportFixture.photos)

    private fun data(b: ExportBundle) = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(b))

    @Test
    fun aCopyWithNoBrokersAndNoRoomsIsStillFormat1() {
        val text = data(ExportFixture.bundle())
        assertTrue(text.startsWith("{\"format\":\"doorprints-backup/1\","), text)
        assertFalse(text.contains("\"rooms\""), text)
        assertEquals("doorprints-backup/1", BackupFormat.idFor(0, 0))
    }

    @Test
    fun aCopyWithARoomAndNoBrokerIsFormat2WithTheRoomsAfterTheCost() {
        val text = data(bundle(houses = listOf(house1.copy(cost = HouseCost(deposit = 1)))))
        assertTrue(text.startsWith("{\"format\":\"doorprints-backup/2\","), text)
        assertFalse(text.contains("\"brokers\""), "no brokers list without a broker: $text")
        assertTrue(
            text.contains(
                "\"cost\":{\"deposit\":1},\"rooms\":[{\"id\":\"${master.id}\",\"type\":\"BEDROOM\",\"name\":\"Master bedroom\"," +
                    "\"lengthCm\":396,\"widthCm\":366,\"condition\":4,\"notes\":\"Damp patch near the window\",\"sort\":0}," +
                    "{\"id\":\"${kitchen.id}\",\"type\":\"KITCHEN\",\"name\":\"Kitchen\",\"lengthCm\":300,\"widthCm\":244,\"sort\":1}]," +
                    "\"checklist\"",
            ),
            text,
        )
        // The counts do not change: rooms are part of their house.
        assertEquals(BackupCounts(1, 1, 1), BackupCounts.of(BackupData.of(bundle(houses = listOf(house1)))))
        assertEquals("doorprints-backup/2", BackupFormat.idFor(0, 1))
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text)
        assertEquals(listOf(master, kitchen), back.houses.single().rooms)
        assertNull(BackupValidation.checkData(back))
    }

    @Test
    fun theRoomsSurviveACopyWithoutContactDetails() {
        val without = BackupData.of(bundle(ExportFixture.options(includeContacts = false)))
        assertEquals("doorprints-backup/2", without.format)
        assertEquals(listOf(master, kitchen), without.houses.first { it.id == "h1" }.rooms)
        assertNull(without.houses.first { it.id == "h1" }.contactPhone)
    }

    @Test
    fun anEmptyOrNullListReadsAsNoRoomsAndAnUnknownTypeIsKept() {
        val house = "\"lat\":0,\"lon\":0,\"status\":\"NEW\",\"createdAt\":1,\"updatedAt\":1"
        val file = "{\"format\":\"doorprints-backup/2\",\"exportedAt\":1,\"houses\":[" +
            "{\"id\":\"h1\",\"label\":\"a\",$house,\"rooms\":[]}," +
            "{\"id\":\"h2\",\"label\":\"b\",$house,\"rooms\":null}," +
            "{\"id\":\"h3\",\"label\":\"c\",$house,\"rooms\":[{\"id\":\"r\",\"type\":\"GARAGE\",\"sort\":0}]}]}"
        val data = BackupFormat.json.decodeFromString(BackupData.serializer(), file)
        assertNull(BackupValidation.checkData(data), "an unknown type reads as OTHER; it does not refuse the file")
        assertEquals(listOf(emptyList(), null, listOf(HouseRoom("r", "GARAGE"))), data.houses.map { it.rooms })
        // The writer never writes an empty list.
        val none = ExportBundle.build(ExportFixture.options(), listOf(ExportFixture.house2), emptyList(), emptyList())
        assertFalse(data(none).contains("\"rooms\""))
    }

    @Test
    fun aBadRoomRefusesTheWholeFile() {
        val good = BackupData.of(bundle())
        fun with(vararg rooms: HouseRoom) = good.copy(houses = listOf(house1.copy(rooms = rooms.toList()), ExportFixture.house2))
        assertNull(BackupValidation.checkData(with(master, kitchen)))
        for (bad in listOf(
            master.copy(lengthCm = 5001), master.copy(widthCm = -1), master.copy(condition = 6), master.copy(condition = 0),
            master.copy(name = "n".repeat(61)), master.copy(notes = "n".repeat(2001)), master.copy(sort = -1),
            master.copy(id = ".."), master.copy(id = "a/b"),
        )) assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(bad)), bad.toString())
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(master, master.copy(sort = 1))), "a duplicate id")
        val thirtyOne = (0..30).map { HouseRoom("r$it", sort = it) }
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(with(*thirtyOne.toTypedArray())), "31 rooms")
        assertNull(BackupValidation.checkData(with(*thirtyOne.take(30).toTypedArray())))
    }

    @Test
    fun anImportedHouseKeepsItsRoomsAsTheReaderCoercesThem() {
        val plan = ImportPlan.plan(
            BackupData(format = BackupFormat.ID_2, exportedAt = 1, houses = listOf(house1)), emptyMap(), emptyMap(), emptySet(),
            emptySet(), ImportMode.COPY, newId = { "new" },
        )
        assertEquals(listOf(master, kitchen), plan.houses.single().rooms, "a copy keeps the room ids: they are the house's own")
    }
}
