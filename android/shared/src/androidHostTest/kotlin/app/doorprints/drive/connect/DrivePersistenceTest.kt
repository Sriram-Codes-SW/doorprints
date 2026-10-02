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

package app.doorprints.drive.connect

import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.KeysWatermark
import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.backup.ControlWatermark
import app.doorprints.drive.backup.DriveDeviceState
import app.doorprints.drive.delete.DeletedMarker
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionItem
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.ItemKind
import app.doorprints.drive.delete.PendingDeletion
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrivePersistenceTest {
    @Test
    fun theDeviceStateRoundTripsAndForgetsTheFolderButNotTheDeviceId() = runTest {
        val kv = MemoryKeyValueStore()
        val store = KvDriveStateStore(kv)
        assertEquals(DriveDeviceState(), store.load())
        val s = DriveDeviceState("dev", "root", "bk", "keys", "ctl", "creating", "last", 5, 6, BackupSchedule.Failure.QUOTA, 7, 8, setOf("a", "b"))
        store.save(s)
        assertEquals(s, KvDriveStateStore(kv).load())
        store.forgetFolder()
        val after = store.load()
        assertEquals("dev", after.deviceId); assertNull(after.rootId); assertNull(after.lastBackupId); assertTrue(after.confirmedDrops.isEmpty())
        kv.put("drive.state", "{broken")
        assertEquals(DriveDeviceState(), store.load(), "a damaged entry reads as a fresh device, never crashes")
    }

    @Test
    fun watermarksCompareAndSetByTheirOwnValue() {
        val trust = KvFolderTrustStores(MemoryKeyValueStore())
        val k = trust.keys("root-1")
        assertNull(k.load())
        val w1 = KeysWatermark(1, 1, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val w2 = KeysWatermark(1, 2, ByteArray(32) { 1 }, ByteArray(32) { 3 })
        assertTrue(k.compareAndSet(null, w1))
        assertFalse(k.compareAndSet(null, w2), "another writer got there first")
        assertEquals(w1, k.load())
        assertTrue(k.compareAndSet(w1, w2))
        assertNull(trust.keys("root-2").load(), "per folder")
        val c = trust.control("root-1")
        val c1 = ControlWatermark(1, ByteArray(32) { 4 }, null)
        val c2 = ControlWatermark(2, ByteArray(32) { 5 }, 123L)
        assertTrue(c.compareAndSet(null, c1)); assertTrue(c.compareAndSet(c1, c2))
        assertEquals(c2, c.load())
    }

    @Test
    fun aPendingDeletionAndItsMarkerRoundTrip() = runTest {
        val kv = MemoryKeyValueStore()
        val state = KvDriveStateStore(kv)
        val store = KvDeletionStore(kv, state)
        assertNull(store.pending()); assertNull(store.marker())
        for (action in listOf(DeletionAction.OneBackup("f1"), DeletionAction.OlderBackups, DeletionAction.AllBackups, DeletionAction.Everything)) {
            val p = PendingDeletion("del-1", DeletionLevel.L2, action, "root", listOf(DeletionItem("a", ItemKind.BACKUP, 10, 1), DeletionItem("r", ItemKind.FOLDER, 0, 9)), 2, 99)
            store.savePending(p)
            assertEquals(p, KvDeletionStore(kv, state).pending())
        }
        store.clearPending(); assertNull(store.pending())
        state.save(DriveDeviceState(deviceId = "d", rootId = "root"))
        store.recordFinished(DeletedMarker(DeletionLevel.L3, 5, "everything"), forgetFolder = true)
        assertEquals(DeletedMarker(DeletionLevel.L3, 5, "everything"), store.marker())
        assertNull(state.load().rootId); assertEquals("d", state.load().deviceId)
    }

    @Test
    fun theDeviceKeyIsMadeOnceKeptOnlyInTheSealedStoreAndReloaded() {
        val sealed = MemoryKeyValueStore()
        val k1 = DeviceKeyStore(sealed, JvmCryptoProvider).loadOrCreate()
        val k2 = DeviceKeyStore(sealed, JvmCryptoProvider).loadOrCreate()
        assertContentEquals(k1.publicKey, k2.publicKey)
        assertFalse(k1.toString().contains(sealed.get("drive.device-key")!!))
        sealed.put("drive.device-key", "garbage")
        val k3 = DeviceKeyStore(sealed, JvmCryptoProvider).loadOrCreate()
        assertFalse(k3.publicKey.contentEquals(k1.publicKey), "a damaged entry is replaced, never trusted")
        DeviceKeyStore(sealed, JvmCryptoProvider).forget()
        assertNull(sealed.get("drive.device-key"))
    }

    @Test
    fun theReadMeIsTheSchemaFileAndNamesNoAccountOrKey() {
        var dir = File("").absoluteFile
        while (!File(dir, "docs/schemas/drive-readme.txt").exists()) dir = dir.parentFile
        assertEquals(File(dir, "docs/schemas/drive-readme.txt").readText(Charsets.UTF_8), DriveReadme.TEXT)
        assertEquals("Read me.txt", DriveReadme.NAME)
        for (lang in listOf("हिन्दी", "தமிழ்", "తెలుగు")) assertTrue(lang in DriveReadme.TEXT)
        assertFalse(Regex("@|apps\\.googleusercontent|DP[0-9A-Z]{2}-").containsMatchIn(DriveReadme.TEXT))
        assertFalse("Restore" in DriveReadme.TEXT)
    }
}
