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
package app.doorprints.drive.photo

import app.doorprints.drive.connect.SyncRig
import app.doorprints.drive.sync.SyncWorld
import app.doorprints.drive.sync.photoRow
import app.doorprints.shared.sync.SyncKind
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the 30-day clean-up gets its tombstones (S4b-BL-293): the deleted photos of the device's own sync file, each with the
 * Drive file of its bytes, read through the sync engine like the restore does (the web's `DriveSyncAdapter.photoTombstones`).
 */
class PhotoTombstonesTest {
    private fun bytes(n: Int, seed: Int): ByteArray = ByteArray(n) { i -> (i * 31 + seed * 17 + (i shr 3)).toByte() }

    @Test
    fun theDeletedPhotosOfTheOwnSyncFileComeWithTheirDriveFilesAndLivePhotosDoNot() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        a.edit("h1", "Blue gate")
        for (id in listOf("p1", "p2", "live")) a.local.put(photoRow(id, "h1", a.now(), a.id), true)
        val ba = a.backend()
        ba.commitPushes()
        for ((i, id) in listOf("p1", "p2", "live").withIndex()) {
            val data = bytes(3000 + i, i + 1)
            ba.uploadPhoto("h1", id, "$id.jpg", data.size.toLong()) { Buffer().apply { write(data) } }
        }
        ba.commitPushes()
        w.server.clock.advance(1000)
        val bd = a.backend()
        for (id in listOf("p1", "p2")) {
            bd.deletePhoto(id)
            a.local.rows.remove(SyncKind.PHOTOS to id)
        }
        bd.commitPushes()
        val rig = SyncRig(bd, a.photos(), a.local, a.engine())
        val gone = rig.ownPhotoTombstones().sortedBy { it.id }
        assertEquals(listOf("p1", "p2"), gone.map { it.id })
        for (g in gone) assertEquals(a.photoState.refs.getValue(g.id).driveFileId, g.ref?.driveFileId)
        assertTrue(gone.all { it.deletedAt > 0 })
    }

    @Test
    fun aRigWithNoEngineHasNoTombstones() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        assertEquals(emptyList<GonePhoto>(), SyncRig(a.backend(), a.photos(), a.local).ownPhotoTombstones())
    }
}
