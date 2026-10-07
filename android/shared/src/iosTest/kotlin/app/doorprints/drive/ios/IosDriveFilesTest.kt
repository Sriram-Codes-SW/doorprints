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

package app.doorprints.drive.ios

import app.doorprints.shared.export.PosixFileSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.Foundation.NSFileManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IosWebAuthLauncherTest {
    @Test
    fun aCallbackIsHandedToTheRedirectAndEverythingElseEndsTheRequest() {
        val callback = "com.googleusercontent.apps.1-a:/oauth2redirect?state=s&code=c"
        assertEquals(callback, assertIs<IosWebAuthLauncher.WebAuthEnd.Callback>(IosWebAuthLauncher.resultOf(callback, null)).url)
        assertEquals(IosWebAuthLauncher.WebAuthEnd.Cancelled, IosWebAuthLauncher.resultOf(null, ASWebAuthenticationSessionErrorCodeCanceledLogin))
        assertEquals(IosWebAuthLauncher.WebAuthEnd.Cancelled, IosWebAuthLauncher.resultOf(callback, ASWebAuthenticationSessionErrorCodeCanceledLogin))
        assertEquals(IosWebAuthLauncher.WebAuthEnd.Cancelled, IosWebAuthLauncher.resultOf(null, 2L))
        assertEquals(IosWebAuthLauncher.WebAuthEnd.Cancelled, IosWebAuthLauncher.resultOf(callback, 2L))
        assertEquals(IosWebAuthLauncher.WebAuthEnd.Cancelled, IosWebAuthLauncher.resultOf(null, null))
    }

    @Test
    fun aCallbackNeverShowsInItsToString() {
        assertEquals("Callback", IosWebAuthLauncher.WebAuthEnd.Callback("x:/y?code=SECRET").toString())
    }
}

/** The Drive scratch folders and the staged import file on the simulator's file system. */
@OptIn(ExperimentalForeignApi::class)
class IosDriveFilesTest {
    private val made = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        made.forEach { IosDriveFolders.remove(it) }
    }

    private fun staged(): IosDriveImportFile = IosDriveImportFile().also { made += it.path }

    @Test
    fun theImportFileIsMadeByTheFirstWriteHoldsWhatWasWrittenAndIsOneOfTheImportFoldersOwn() {
        val file = staged()
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path), "nothing is made before the first byte")
        file.write("PK".encodeToByteArray(), 0, 2)
        file.write("xyz123".encodeToByteArray(), 2, 3)
        assertTrue(file.finish())
        val source = PosixFileSource(file.path)
        try {
            assertEquals(5L, source.size)
            val buffer = ByteArray(5)
            assertEquals(5, source.readAt(0, buffer, 0, 5))
            assertEquals("PKz12", buffer.decodeToString())
        } finally {
            source.close()
        }
        assertTrue(IosDriveFolders.isStagedImport(file.path))
        assertTrue(file.path.substringAfterLast('/').startsWith("drive-") && file.path.endsWith(".zip"))
    }

    @Test
    fun discardRemovesWhatWasWrittenAndANeverWrittenFileIsFineToo() {
        val file = staged()
        file.write(ByteArray(3) { 1 }, 0, 3)
        file.discard()
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path))
        staged().discard()
    }

    @Test
    fun twoImportFilesNeverShareAName() {
        assertNotEquals(staged().path, staged().path)
    }

    @Test
    fun onlyAPlainFileDirectlyInTheImportFolderIsAStagedImport() {
        val folder = IosDriveFolders.importing
        assertTrue(IosDriveFolders.isStagedImport("$folder/drive-1.zip"))
        assertFalse(IosDriveFolders.isStagedImport("$folder/sub/drive-1.zip"))
        assertFalse(IosDriveFolders.isStagedImport("$folder/.."))
        assertFalse(IosDriveFolders.isStagedImport("$folder/"))
        assertFalse(IosDriveFolders.isStagedImport("$folder/../x"))
        assertFalse(IosDriveFolders.isStagedImport("/private/var/mobile/other/drive-1.zip"))
        assertFalse(IosDriveFolders.isStagedImport(null))
    }

    @Test
    fun sweepEmptiesAFolderWithoutRemovingIt() {
        val folder = IosDriveFolders.backup
        val leftover = "$folder/backup-old.zip"
        NSFileManager.defaultManager.createFileAtPath(leftover, contents = null, attributes = null)
        IosDriveFolders.sweep(folder)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(leftover))
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(folder))
    }
}
