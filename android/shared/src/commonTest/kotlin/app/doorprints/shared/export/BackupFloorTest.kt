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

import app.doorprints.data.toEntity
import app.doorprints.shared.model.HouseValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The house's floor in a backup (S4b-BL-87; docs/schemas README §3.1): after `moveIn` and before `brokerId`, a `/2`
 * file when a house has one (0, the ground floor, included), kept in a copy without contact details, and a value
 * outside -5..200 read as unknown.
 */
class BackupFloorTest {
    private fun data(houses: List<ExportHouse>, options: ExportOptions = ExportFixture.options()) =
        BackupData.of(ExportBundle.build(options, houses, emptyList(), emptyList()))

    private fun text(d: BackupData) = BackupFormat.json.encodeToString(BackupData.serializer(), d)

    @Test
    fun aHouseWithAFloorMakesAFormat2FileWithTheFloorBeforeTheBroker() {
        assertEquals("doorprints-backup/1", data(listOf(ExportFixture.house2)).format)
        val ground = text(data(listOf(ExportFixture.house2.copy(floor = 0, brokerId = "b1"))))
        assertTrue(ground.startsWith("{\"format\":\"doorprints-backup/2\","), ground)
        assertTrue(ground.contains("\"floor\":0,\"brokerId\":\"b1\",\"checklist\""), ground)
        assertEquals("doorprints-backup/2", BackupFormat.idFor(0, floors = 1))
        assertEquals("doorprints-backup/1", BackupFormat.idFor(0, floors = 0))
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), ground)
        assertEquals(0, back.houses.single().floor)
        assertNull(BackupValidation.checkData(back))
    }

    @Test
    fun theFloorSurvivesACopyWithoutContactDetails() {
        val without = data(listOf(ExportFixture.house1.copy(floor = 4)), ExportFixture.options(includeContacts = false))
        assertEquals("doorprints-backup/2", without.format)
        assertEquals(4, without.houses.single().floor)
        assertFalse(text(without).contains("\"brokerId\""))
    }

    @Test
    fun aFloorOutOfRangeReadsAsUnknown() {
        assertEquals(listOf(-5, 0, 200, null, null, null), listOf(-5, 0, 200, -6, 201, null).map(HouseValues::floor))
        val house = ExportFixture.house2
        assertEquals(-2, house.copy(floor = -2).toEntity().floor)
        assertNull(house.copy(floor = 999).toEntity().floor)
        assertNull(house.copy(floor = -6).toEntity().floor)
    }
}
