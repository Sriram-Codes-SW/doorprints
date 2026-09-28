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
 * that is gone; `CommonRepository.photoFileOf` (built on [CommonRepository.photoFileIn]) still reaches the file.
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

        val file = CommonRepository.photoFileIn(current, row.id)

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

            val file = CommonRepository.photoFileIn(newDir.toString(), row.id)
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
