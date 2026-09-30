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

import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

/**
 * Vector V6 (docs/11 5.8, design of 2026-09-30): the calendar file of the sample backup's viewing `v_a1b2c3d4` (house
 * "Green View 2BHK", address "12, MG Road", word "Viewing", `DTSTAMP` its `updatedAt`) is `docs/schemas/viewing-sample.ics`
 * byte for byte, the file the web's `viewingIcs` is checked against too. Not in commonTest: it reads repository files.
 */
class ViewingIcsFileTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    @Test
    fun theSamplesSecondViewingIsTheSampleCalendarFileByteForByte() {
        val data = BackupFormat.json.decodeFromString(BackupData.serializer(), repoFile("docs/schemas/backup-sample.json").readText())
        val row = data.viewingRows.single { it.id == "v_a1b2c3d4" }
        val house = data.houses.single { it.id == row.houseId }
        val ics = ViewingIcs.build(row.toViewing().coerced()!!, house.label, house.address, "Viewing", row.updatedAt)
        assertArrayEquals(repoFile("docs/schemas/viewing-sample.ics").readBytes(), ics.encodeToByteArray())
    }
}
