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

package app.doorprints.drive.wiring

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.doorprints.data.Repository
import app.doorprints.export.ImportStaging
import app.doorprints.export.Imports
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/**
 * What a Drive import and a Drive backup leave on the phone (review of PR 142, items 16 and 17): the decrypted ZIP the
 * Import screen has copied goes at once, and the backup temp files of an earlier process go when the graph is built.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DriveStagingCleanupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val imports = File(context.cacheDir, "imports").apply { mkdirs() }

    private fun staged(name: String = "drive-${System.nanoTime()}.zip", dir: File = imports): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun aDriveStagingFileIsDiscardedByAddress() {
        val file = staged()
        assertTrue(DriveImportStaging.discardIfStaged(imports, Uri.fromFile(file)))
        assertFalse(file.exists())
    }

    @Test
    fun anythingElseIsLeftAlone() {
        val picked = staged("backup.zip")
        assertFalse("not a drive-*.zip", DriveImportStaging.discardIfStaged(imports, Uri.fromFile(picked)))
        assertTrue(picked.exists())

        val elsewhere = staged("drive-1.zip", File(context.filesDir, "other"))
        assertFalse("another folder", DriveImportStaging.discardIfStaged(imports, Uri.fromFile(elsewhere)))
        assertTrue(elsewhere.exists())

        val nested = staged("drive-2.zip", File(imports, "sub"))
        assertFalse("not directly in the staging folder", DriveImportStaging.discardIfStaged(imports, Uri.fromFile(nested)))
        assertTrue(nested.exists())

        val sneaky = staged("drive-3.zip", File(context.filesDir, "other"))
        assertFalse("a path that climbs out of the folder", DriveImportStaging.discardIfStaged(imports, Uri.fromFile(File(imports, "../../files/other/drive-3.zip"))))
        assertTrue(sneaky.exists())

        assertFalse("a content address is never ours", DriveImportStaging.discardIfStaged(imports, Uri.parse("content://media/external/file/1")))
    }

    @Test
    fun theImportScreensCopyIsMadeAndThenTheDecryptedFileIsGone() = runBlocking {
        val file = staged()
        val result = Imports.stage(context, Uri.fromFile(file))
        assertTrue("copied: $result", result is ImportStaging.Staged)
        val copy = File((result as ImportStaging.Staged).path)
        assertEquals(listOf<Byte>(1, 2, 3), copy.readBytes().toList())
        assertFalse("the decrypted Drive file is discarded once copied, not after six hours", file.exists())
    }

    @Test
    fun aPickedFileIsStillNotDeletedByTheImport() = runBlocking {
        val file = staged("my-backup.zip")
        val result = Imports.stage(context, Uri.fromFile(file))
        assertTrue(result is ImportStaging.Staged)
        assertTrue("the person's own file stays", file.exists())
    }

    @Test
    fun theBackupSourceSweepsEarlierLeftoversWhenItIsMade() {
        val dir = File(context.cacheDir, "drive-backup-test").apply { mkdirs() }
        File(dir, "backup-old.zip").writeBytes(ByteArray(10))
        File(dir, "backup-older.zip").writeBytes(ByteArray(10))
        val repository = Proxy.newProxyInstance(Repository::class.java.classLoader, arrayOf(Repository::class.java)) { _, m, _ ->
            throw UnsupportedOperationException(m.name)
        } as Repository
        AndroidDriveBackupSource.swept(context, repository, { File(it) }, dir)
        assertEquals("every leftover of an earlier process is gone", 0, dir.listFiles()?.size ?: 0)
    }
}
