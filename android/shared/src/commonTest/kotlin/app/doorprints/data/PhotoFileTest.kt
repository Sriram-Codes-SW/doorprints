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

package app.doorprints.data

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S4b-BL-52: a photo's file is found from its id in the current photo folder, never from the row's stored full path.
 * An iOS app's container folder changes when the app is updated, so a row written before the update names a folder
 * that is gone; `CommonRepository.photoFileOf` (built on [PhotoStore.fileIn]) still reaches the file.
 * That `photoFileOf` creates nothing is checked on Android in `PhotoFileByIdTest`.
 */
class PhotoFileTest {

    private val fs = SystemFileSystem

    private fun photoRow(id: String, path: String) = PhotoEntity(id, "h1", path, uploaded = true, createdAt = 1L)

    @Test
    fun aRowFromAnOldContainerFolderResolvesToTheCurrentFolderById() {
        val old = "/var/mobile/Containers/Data/Application/0A1B-OLD/Library/Application Support/photos"
        val current = "/var/mobile/Containers/Data/Application/9Z8Y-NEW/Library/Application Support/photos"
        val row = photoRow("p1", "$old/p1.jpg")

        val file = PhotoStore.fileIn(current, row.id)

        assertEquals(Path(current, "p1.jpg"), file)
        assertNotEquals(Path(row.path), file)
    }

    @Test
    fun theFileIsFoundOnDiskAfterTheFolderMoved() {
        val root = Path(SystemTemporaryDirectory, "doorprints-photo-file-${Random.nextLong().toULong()}")
        val oldDir = Path(root, "old", "photos")
        val newDir = Path(root, "new", "photos")
        fs.createDirectories(newDir)
        val bytes = byteArrayOf(1, 2, 3)
        fs.sink(Path(newDir, "p1.jpg")).buffered().use { it.write(bytes) }
        try {
            val row = photoRow("p1", Path(oldDir, "p1.jpg").toString())
            assertFalse(fs.exists(Path(row.path)), "the stored path is stale")

            val file = PhotoStore.fileIn(newDir.toString(), row.id)
            assertTrue(fs.exists(file))
            assertContentEquals(bytes, fs.source(file).buffered().use { it.readByteArray() })
        } finally {
            fs.delete(Path(newDir, "p1.jpg"), mustExist = false)
            fs.delete(newDir, mustExist = false)
            fs.delete(Path(root, "new"), mustExist = false)
            fs.delete(root, mustExist = false)
        }
    }
}
