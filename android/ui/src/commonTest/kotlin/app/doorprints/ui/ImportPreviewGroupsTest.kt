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

import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPreview
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.import_floors_left_blank
import app.doorprints.ui.res.import_new_brokers
import app.doorprints.ui.res.import_new_houses
import app.doorprints.ui.res.import_updated_brokers
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Import screen's preview lines (S4b-BL-86): the brokers' own group, "New brokers" and the updated ones, last. */
class ImportPreviewGroupsTest {
    private fun preview(newHouses: Int = 0, newBrokers: Int = 0, updatedBrokers: Int = 0) = ImportPreview(
        ImportMode.MERGE, newHouses, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, newBrokers = newBrokers, updatedBrokers = updatedBrokers,
    )

    @Test fun theBrokersAreTheirOwnLastGroupAndZeroLinesAreHidden() {
        val groups = previewGroups(preview(newHouses = 3, newBrokers = 2, updatedBrokers = 1), duplicates = 0)
        assertEquals(listOf(listOf(Res.string.import_new_houses to 3), listOf(Res.string.import_new_brokers to 2, Res.string.import_updated_brokers to 1)),
            groups.map { g -> g.map { it.label to it.count } })
        assertEquals(listOf(listOf(Res.string.import_updated_brokers to 4)),
            previewGroups(preview(updatedBrokers = 4), 0).map { g -> g.map { it.label to it.count } })
        assertEquals(emptyList(), previewGroups(preview(), 0))
    }

    /** S4b-BL-104 (d): houses whose floor in the file is out of range get a warning line after the houses. */
    @Test fun aFloorOutOfRangeIsAWarningLineWithTheHouses() {
        val line = previewGroups(preview(newHouses = 2).copy(floorsLeftBlank = 1), 0).single().last()
        assertEquals(Res.string.import_floors_left_blank to 1, line.label to line.count)
        assertEquals(true, line.loss)
    }
}
