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

import app.doorprints.crypto.KeysWatermark
import app.doorprints.drive.backup.DriveDeviceState
import app.doorprints.drive.store.DriveFileStores
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The small per-device files of the Drive wiring: preferences, the lock's memory, and the pin test of the device key. */
class DriveFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---- preferences ----

    @Test
    fun aValueWrittenIsReadBackAfterARestart() {
        val file = File(tmp.root, "prefs.json")
        FileDrivePrefs(file).put("a", "1")
        FileDrivePrefs(file).put("b", "0")
        val again = FileDrivePrefs(file)
        assertEquals("1", again.get("a"))
        assertEquals("0", again.get("b"))
        assertNull(again.get("c"))
    }

    @Test
    fun anEmptyOrDamagedFileReadsAsNothingSet() {
        val file = File(tmp.root, "prefs.json")
        assertNull(FileDrivePrefs(file).get("a"))
        file.writeText("not json {")
        val prefs = FileDrivePrefs(file)
        assertNull(prefs.get("a"))
        assertFalse(prefs.engaged)
        // And it can be written over.
        prefs.put("a", "1")
        assertEquals("1", prefs.get("a"))
    }

    @Test
    fun driveIsNotInUseUntilSaid() {
        val prefs = FileDrivePrefs(File(tmp.root, "prefs.json"))
        assertFalse(prefs.engaged)
        prefs.engaged = true
        assertTrue(FileDrivePrefs(File(tmp.root, "prefs.json")).engaged)
        prefs.engaged = false
        assertFalse(FileDrivePrefs(File(tmp.root, "prefs.json")).engaged)
    }

    @Test
    fun theChangeCallbackSeesEachWriteAndNotAFailedOne() {
        val prefs = FileDrivePrefs(File(tmp.root, "prefs.json"))
        val seen = mutableListOf<String>()
        prefs.onChange = { k, v -> seen += "$k=$v" }
        prefs.put("x", "1")
        prefs.engaged = true
        assertEquals(listOf("x=1", "${FileDrivePrefs.KEY_ENGAGED}=1"), seen)
        // A write that cannot be made (the folder is a file) is dropped, silently, with no callback.
        val blocked = File(tmp.newFile("blocker"), "prefs.json")
        val failing = FileDrivePrefs(blocked)
        failing.onChange = { k, v -> seen += "$k=$v" }
        failing.put("y", "1")
        assertFalse(seen.any { it.startsWith("y=") })
    }

    // ---- the lock's memory ----

    @Test
    fun theLockStoreKeepsItsThreeFlagsAndClearsThemTogether() {
        val file = File(tmp.root, "lock.json")
        val store = FileDriveLockStore(file)
        assertFalse(store.paused)
        store.paused = true
        store.keyDropped = true
        val again = FileDriveLockStore(file)
        assertTrue(again.paused)
        assertTrue(again.keyDropped)
        assertFalse(again.needsReenrolment)
        again.needsReenrolment = true
        assertTrue(FileDriveLockStore(file).needsReenrolment)
        again.clear()
        val cleared = FileDriveLockStore(file)
        assertFalse(cleared.paused)
        assertFalse(cleared.needsReenrolment)
        assertFalse(cleared.keyDropped)
    }

    @Test
    fun aDamagedLockFileReadsAsNotPaused() {
        val file = File(tmp.root, "lock.json").apply { writeText("###") }
        assertFalse(FileDriveLockStore(file).paused)
    }

    // ---- the pin test of the device key ----

    private val watermark = KeysWatermark(1, 1L, ByteArray(16) { 1 }, ByteArray(32) { 2 })

    private fun probe(stores: DriveFileStores, dir: File) = FolderPinProbe(stores.driveState, File(dir, "trust"))

    @Test
    fun noFolderMeansNoPin() {
        val dir = tmp.newFolder("a")
        assertFalse(probe(DriveFileStores(dir), dir).isPinned())
    }

    @Test
    fun aFolderWithoutAPinIsNotPinned() = runBlocking {
        val dir = tmp.newFolder("a")
        val stores = DriveFileStores(dir)
        stores.driveState.save(DriveDeviceState(rootId = "root1"))
        assertFalse(probe(stores, dir).isPinned())
    }

    @Test
    fun theFoldersPinMakesTheKeyPinned() = runBlocking {
        val dir = tmp.newFolder("a")
        val stores = DriveFileStores(dir)
        stores.driveState.save(DriveDeviceState(rootId = "root1"))
        assertTrue(stores.trust.keys("root1").compareAndSet(null, watermark))
        assertTrue(probe(stores, dir).isPinned())
    }

    @Test
    fun aPinOfAnotherFolderDoesNotPinThisOne() = runBlocking {
        val dir = tmp.newFolder("a")
        val stores = DriveFileStores(dir)
        stores.trust.keys("old-root").compareAndSet(null, watermark)
        stores.driveState.save(DriveDeviceState(rootId = "root2"))
        assertFalse(probe(stores, dir).isPinned())
        // A forgotten folder (no root id) is no pin either.
        stores.driveState.save(DriveDeviceState(rootId = null))
        assertFalse(probe(stores, dir).isPinned())
    }

    @Test
    fun aRootIdThatIsNotAFileNameIsStillFound() = runBlocking {
        val dir = tmp.newFolder("a")
        val stores = DriveFileStores(dir)
        val odd = "root/with..slashes"
        stores.driveState.save(DriveDeviceState(rootId = odd))
        stores.trust.keys(odd).compareAndSet(null, watermark)
        assertTrue(probe(stores, dir).isPinned())
    }

    @Test
    fun aStateThatCannotBeReadIsTreatedAsPinnedNotAsFree() {
        val dir = tmp.newFolder("a")
        val failing = object : app.doorprints.drive.backup.DriveStateStore {
            override suspend fun load(): DriveDeviceState = throw java.io.IOException("disk")
            override suspend fun save(state: DriveDeviceState) = Unit
        }
        assertTrue(FolderPinProbe(failing, File(dir, "trust")).isPinned())
    }
}
