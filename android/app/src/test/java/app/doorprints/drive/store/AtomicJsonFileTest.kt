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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The one way every Drive store writes and reads a file: whole-or-nothing, and unreadable means absent. */
class AtomicJsonFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun missingFileReadsAsAbsent() {
        assertNull(AtomicJsonFile(File(tmp.root, "x.json")).readText())
    }

    @Test
    fun writeThenReadRoundTripsAndOverwrites() {
        val f = AtomicJsonFile(File(tmp.root, "x.json"))
        f.writeText("one")
        assertEquals("one", f.readText())
        f.writeText("two")
        assertEquals("two", f.readText())
    }

    @Test
    fun aNewInstanceOnTheSameFileSeesTheSameText() {
        AtomicJsonFile(File(tmp.root, "x.json")).writeText("kept")
        assertEquals("kept", AtomicJsonFile(File(tmp.root, "x.json")).readText())
    }

    @Test
    fun createsMissingFolders() {
        val f = AtomicJsonFile(File(tmp.root, "a/b/x.json"))
        f.writeText("deep")
        assertEquals("deep", f.readText())
    }

    @Test
    fun aWriteLeavesNoTemporaryFileBehind() {
        AtomicJsonFile(File(tmp.root, "x.json")).writeText("one")
        assertEquals(listOf("x.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun aHalfWrittenTemporaryFileFromACrashNeverShowsAndIsReplaced() {
        val target = File(tmp.root, "x.json")
        val f = AtomicJsonFile(target)
        f.writeText("good")
        File(tmp.root, "x.json.tmp").writeText("par")
        assertEquals("good", f.readText())
        f.writeText("better")
        assertEquals("better", f.readText())
        assertEquals(listOf("x.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun aFailedWriteLeavesTheOldContent() {
        val target = File(tmp.root, "x.json")
        val f = AtomicJsonFile(target)
        f.writeText("good")
        // The temporary name is taken by a folder, so the new content cannot be written.
        File(tmp.root, "x.json.tmp").mkdir()
        try {
            f.writeText("new")
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertEquals("good", f.readText())
    }

    @Test
    fun aFolderWhereTheFileShouldBeReadsAsAbsent() {
        val dir = File(tmp.root, "x.json").also { it.mkdir() }
        assertNull(AtomicJsonFile(dir).readText())
    }

    @Test
    fun anEmptyFileReadsAsAbsent() {
        val target = File(tmp.root, "x.json").also { it.writeText("") }
        assertNull(AtomicJsonFile(target).readText())
    }

    @Test
    fun pathLockIsSharedByEveryInstanceOfTheSameFile() {
        val a = PathLocks.of(File(tmp.root, "x.json"))
        val b = PathLocks.of(File(tmp.root, "sub/../x.json"))
        val c = PathLocks.of(File(tmp.root, "y.json"))
        assertTrue(a === b)
        assertFalse(a === c)
    }
}
