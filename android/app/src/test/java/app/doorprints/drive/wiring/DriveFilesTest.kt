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

    private fun probe(stores: DriveFileStores, dir: File) = FolderPinProbe({ stores.driveState.loadNow().rootId }, File(dir, "trust"))

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
        assertTrue(FolderPinProbe({ throw java.io.IOException("disk") }, File(dir, "trust")).isPinned())
    }

    @Test
    fun theStoredRootIsReadWithoutSuspending() = runBlocking {
        val dir = tmp.newFolder("a")
        val stores = DriveFileStores(dir)
        stores.driveState.save(DriveDeviceState(rootId = "root1", deviceId = "d1"))
        assertEquals(stores.driveState.load(), stores.driveState.loadNow())
        // A damaged file reads as "nothing stored", as load() does.
        java.io.File(dir, "drive-state.json").writeText("{not json")
        assertNull(stores.driveState.loadNow().rootId)
    }

    // ---- the prefs file is parsed once while it is unchanged, and a phone that never used Drive has none ----

    @Test
    fun anUnchangedPrefsFileIsParsedOnceNoMatterHowOftenItIsAsked() {
        val file = File(tmp.newFolder("p1"), "prefs.json")
        val prefs = FileDrivePrefs(file)
        prefs.put("a", "1")
        repeat(20) { prefs.get("a"); prefs.get("b"); prefs.engaged }
        assertEquals(1, prefs.parses)
        assertEquals("1", prefs.get("a"))
        assertNull(prefs.get("b"))
    }

    @Test
    fun aWriteIsSeenByTheNextRead() {
        val prefs = FileDrivePrefs(File(tmp.newFolder("p2"), "prefs.json"))
        prefs.put("a", "1")
        assertEquals("1", prefs.get("a"))
        prefs.put("a", "2")
        assertEquals("2", prefs.get("a"))
        prefs.engaged = true
        assertTrue(prefs.engaged)
        prefs.engaged = false
        assertFalse(prefs.engaged)
    }

    @Test
    fun anotherInstanceOverTheSameFileSeesAChangeBecauseTheFileChanged() {
        val file = File(tmp.newFolder("p3"), "prefs.json")
        val reader = FileDrivePrefs(file)
        val writer = FileDrivePrefs(file)
        writer.put("a", "1")
        assertEquals("1", reader.get("a"))
        writer.put("b", "x")
        assertEquals("x", reader.get("b"))
        assertEquals("the file changed: parsed again", 2, reader.parses)
    }

    @Test
    fun aMissingOrDamagedFileReadsAsNothingSetAndNoFileMeansNeverUsed() {
        val dir = tmp.newFolder("p4")
        val file = File(dir, "prefs.json")
        val prefs = FileDrivePrefs(file)
        assertFalse(prefs.exists())
        assertNull(prefs.get("a"))
        assertEquals(0, prefs.parses)
        file.writeText("{broken")
        assertTrue(prefs.exists())
        assertNull(prefs.get("a"))
        prefs.put("a", "1")
        assertEquals("1", prefs.get("a"))
    }

    @Test
    fun aPhoneThatNeverUsedDriveSchedulesNothingAtStart() {
        val prefs = FileDrivePrefs(File(tmp.newFolder("w1"), "prefs.json"))
        val calls = mutableListOf<Boolean>()
        DriveWork.reschedule(prefs) { calls += it }
        assertEquals("no cancel and no enqueue at every start", emptyList<Boolean>(), calls)
    }

    @Test
    fun theWorkFollowsInUseAndAutomaticBackupOnce() {
        val prefs = FileDrivePrefs(File(tmp.newFolder("w2"), "prefs.json"))
        val calls = mutableListOf<Boolean>()
        prefs.put("doorprints.drive.autoBackup", "1")
        DriveWork.reschedule(prefs) { calls += it }
        assertEquals("on but not in use: off", listOf(false), calls)
        prefs.engaged = true
        DriveWork.reschedule(prefs) { calls += it }
        assertEquals(listOf(false, true), calls)
        prefs.put("doorprints.drive.autoBackup", "0")
        DriveWork.reschedule(prefs) { calls += it }
        assertEquals(listOf(false, true, false), calls)
        assertEquals("the file was read through the memo, not once per question", true, prefs.parses <= 4)
    }

    @Test
    fun theLockStoreKeepsTheKeyStoreFaultAcrossInstancesAndClearsIt() {
        val file = File(tmp.newFolder("lock"), "lock.json")
        val first = FileDriveLockStore(file)
        first.paused = true
        first.keyStoreFault = true
        val second = FileDriveLockStore(file)
        assertTrue(second.keyStoreFault)
        assertTrue("setting one flag keeps the others", second.paused)
        second.keyDropped = true
        assertTrue(second.keyStoreFault)
        second.clear()
        assertFalse(FileDriveLockStore(file).keyStoreFault)
        assertFalse(FileDriveLockStore(file).paused)
    }

    @Test
    fun aLockFileFromBeforeTheKeyStoreFaultReadsAsNoFault() {
        val file = File(tmp.newFolder("old"), "lock.json")
        file.writeText("{\"paused\":true,\"needsReenrolment\":true,\"keyDropped\":false}")
        val store = FileDriveLockStore(file)
        assertTrue(store.paused)
        assertFalse(store.keyStoreFault)
    }

    @Test
    fun theProbeIsNeverBuiltOnRunBlockingBecauseItRunsOnTheMainThread() {
        val source = File("../shared/src/commonMain/kotlin/app/doorprints/drive/wiring/DriveFiles.kt").readText()
        val probe = source.substring(source.indexOf("class FolderPinProbe"))
        assertFalse("FolderPinProbe must not block a thread on a coroutine", probe.contains("runBlocking"))
        assertFalse("nor import it", source.contains("import kotlinx.coroutines.runBlocking"))
    }
}
