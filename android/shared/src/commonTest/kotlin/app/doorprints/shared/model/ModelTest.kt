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

package app.doorprints.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Wire names are shared with the API, the web app and the Room database, so they are pinned here. */
class ModelTest {

    @Test
    fun statusNamesAreStable() {
        // TAKEN and NOT_CHOSEN since slice 5 (docs/11 5.24).
        assertEquals(listOf("NEW", "SHORTLISTED", "REJECTED", "TAKEN", "NOT_CHOSEN"), HouseStatus.entries.map { it.name })
        assertEquals(listOf("AUTO", "MANUAL"), VisitSource.entries.map { it.name })
    }

    @Test
    fun unknownOrMissingWireValuesFallBack() {
        assertEquals(HouseStatus.SHORTLISTED, HouseStatus.fromWire("SHORTLISTED"))
        assertEquals(HouseStatus.NEW, HouseStatus.fromWire(null))
        assertEquals(HouseStatus.NEW, HouseStatus.fromWire("ARCHIVED"))
        assertEquals(HouseStatus.NEW, HouseStatus.fromWire("shortlisted")) // case-sensitive, like Enum.valueOf
        assertEquals(VisitSource.AUTO, VisitSource.fromWire("AUTO"))
        assertEquals(VisitSource.MANUAL, VisitSource.fromWire(null))
        assertEquals(VisitSource.MANUAL, VisitSource.fromWire("IMPORTED"))
    }

    @Test
    fun checklistKeysMatchTheWebAndApi() {
        assertEquals(
            listOf("water", "power", "parking", "sunlight", "ventilation", "noise", "security", "maintenance",
                "neighbourhood", "commute"),
            Checklist.keys,
        )
        assertEquals(20, MAX_PHOTOS_PER_HOUSE)
    }
}
