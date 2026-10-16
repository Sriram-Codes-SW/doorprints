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

package app.doorprints.drive.store

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [IosStateFile] on the simulator's file system (macOS CI; on Linux the test is compiled, not run). */
@OptIn(ExperimentalForeignApi::class)
class IosStateFileTest {
    private val dir = NSTemporaryDirectory().trimEnd('/') + "/doorprints-state-" + NSUUID().UUIDString

    @AfterTest
    fun removeTheFolder() {
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }

    @Test
    fun aMissingFileIsAbsentEverywhere() {
        val file = IosStateFile("$dir/x.json")
        assertNull(file.readText())
        assertFalse(file.exists())
        assertNull(file.stamp())
        file.delete() // already gone: no error
    }

    @Test
    fun aWriteMakesTheFolderAndReadsBackAndAReplacementKeepsOnlyTheNewText() {
        val file = IosStateFile("$dir/a/b/x.json")
        file.writeText("{\"v\":1}")
        assertEquals("{\"v\":1}", file.readText())
        assertTrue(file.exists())
        file.writeText("{\"v\":2,\"name\":\"घर\"}")
        assertEquals("{\"v\":2,\"name\":\"घर\"}", IosStateFile("$dir/a/b/x.json").readText())
        val entries = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$dir/a/b", error = null).orEmpty()
        assertEquals(listOf<Any?>("x.json"), entries, "no temporary file is left beside it")
    }

    @Test
    fun theStampMovesWhenTheContentDoes() {
        val file = IosStateFile("$dir/x.json")
        file.writeText("one")
        val first = file.stamp()!!
        file.writeText("three")
        assertNotEquals(first.second, file.stamp()!!.second)
    }

    @Test
    fun aBlankFileReadsAsAbsentButExists() {
        val file = IosStateFile("$dir/x.json")
        file.writeText("   \n")
        assertNull(file.readText())
        assertTrue(file.exists())
    }

    @Test
    fun deleteRemovesTheFileAndATargetThatIsAFolderCannotBeWritten() {
        val file = IosStateFile("$dir/x.json")
        file.writeText("x")
        file.delete()
        assertFalse(file.exists())
        NSFileManager.defaultManager.createDirectoryAtPath("$dir/folder.json", withIntermediateDirectories = true, attributes = null, error = null)
        assertFailsWith<IOException> { IosStateFile("$dir/folder.json").writeText("x") }
    }

    @Test
    fun oneLockPerPathAndTheFactoryMakesTheIosFile() {
        assertSame(IosStateFile("$dir/x.json").lock, IosStateFile("$dir/x.json").lock)
        assertTrue(stateFileAt("$dir/y.json") is IosStateFile)
    }
}
