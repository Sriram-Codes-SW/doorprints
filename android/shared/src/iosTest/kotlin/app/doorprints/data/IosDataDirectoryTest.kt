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

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS data folder (CMP-8; threat model F-03, SEC-011): made on first use, excluded from backup, and the flag is
 * read back from the file system. Runs on the iOS simulator only (macOS CI).
 */
@OptIn(ExperimentalForeignApi::class)
class IosDataDirectoryTest {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/doorprints-dir-" + NSUUID().UUIDString

    @AfterTest
    fun removeTheFolder() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun theFolderIsMadeAndExcludedFromBackup() {
        val folder = "$root/Application Support/Doorprints"

        assertEquals(folder, prepareDataDirectory(folder))

        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(folder))
        assertTrue(isExcludedFromBackup(folder))
    }

    @Test
    fun aSecondCallOnAnExcludedFolderSucceedsAndKeepsTheFlag() {
        val folder = "$root/Doorprints"
        prepareDataDirectory(folder)

        prepareDataDirectory(folder)

        assertTrue(isExcludedFromBackup(folder))
    }

    @Test
    fun aPlainFolderIsNotReportedAsExcluded() {
        NSFileManager.defaultManager.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null,
            error = null)

        assertFalse(isExcludedFromBackup(root))
    }

    // The real Application Support/Doorprints folder is left in place on purpose: the simulator is thrown away.
    @Test
    fun theAppsFolderIsDoorprintsInApplicationSupportAndExcluded() {
        val folder = iosDataDirectory()

        assertTrue(folder.endsWith("/Application Support/Doorprints"), folder)
        assertTrue(isExcludedFromBackup(folder))
    }
}
