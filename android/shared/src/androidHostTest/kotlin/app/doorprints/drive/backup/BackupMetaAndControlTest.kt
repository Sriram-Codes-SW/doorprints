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

package app.doorprints.drive.backup

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.KeysFile
import app.doorprints.crypto.KeysGuard
import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.kidOf
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The backup metadata MAC and `doorprints.json` (MAC, canonical form, rollback watermark): S4b-BL-116, TC-U-133. */
class BackupMetaAndControlTest {
    private val p = JvmCryptoProvider
    private val keysFile = KeysFile(p)
    private val phone = p.p256Generate()
    private val t0 = 1_790_000_000_000L
    private val created = keysFile.createFirstDevice(KeysFile.NewDevice(phone.publicKey, "Pixel 8", DevicePlatform.ANDROID), RecoveryKey.generate(p), t0)
    private val kid = kidOf(p, phone.publicKey)
    private val sha = p.randomBytes(32)

    private fun meta(createdAt: Long = t0, houses: Int = 12, epoch: Int = 1, k: ByteArray = kid, s: ByteArray = sha) =
        BackupMeta(createdAt, houses, epoch, k, s)

    private fun macOf(m: BackupMeta): ByteArray = created.opened.currentFolderKey().let { m.mac(p, it) }

    private fun fileOf(m: BackupMeta, mac: ByteArray, extra: Map<String, String> = emptyMap(), sum: String? = Bytes.hex(m.ciphertextSha256)) =
        DriveFile(
            "f1", "x", "application/octet-stream", listOf("b"),
            m.appProperties(mac, "dev0123456789abc") + extra, sha256Checksum = sum,
        )

    // ---- BackupMeta ----

    @Test
    fun theMetadataRoundTripsThroughAppProperties() {
        val m = meta()
        val mac = macOf(m)
        val (read, readMac) = BackupMeta.read(fileOf(m, mac))!!
        assertEquals(m.createdAt, read.createdAt)
        assertEquals(m.houses, read.houses)
        assertEquals(m.epoch, read.epoch)
        assertArrayEquals(kid, read.writerKid)
        assertArrayEquals(sha, read.ciphertextSha256)
        assertArrayEquals(mac, readMac)
        assertTrue(BackupMeta.verify(p, created.opened, read, readMac))
        // Every appProperty fits Drive's 124 bytes (key and value together).
        for ((k, v) in m.appProperties(mac, "dev0123456789abc")) assertTrue(k, k.length + v.length <= DriveLayout.MAX_PROPERTY_BYTES)
    }

    @Test
    fun theMacCoversEveryFieldAndTheFileHash() {
        val m = meta()
        val mac = macOf(m)
        val others = listOf(
            meta(createdAt = t0 + 1), meta(houses = 13), meta(epoch = 2), meta(k = kid.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }),
            meta(s = sha.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }),
        )
        for (o in others) {
            assertFalse(BackupMeta.verify(p, created.opened, o, mac) && o.epoch == 1)
            assertFalse(macOf(o).contentEquals(mac))
        }
        // The same fields under another folder key do not verify (another folder's file).
        val other = keysFile.createFirstDevice(KeysFile.NewDevice(phone.publicKey, "Pixel 8", DevicePlatform.ANDROID), null, t0)
        assertFalse(BackupMeta.verify(p, other.opened, m, mac))
        // An epoch this device holds no key for does not verify (false, not an exception).
        assertFalse(BackupMeta.verify(p, created.opened, meta(epoch = 9), mac))
    }

    @Test
    fun theMacKeyIsNotTheFolderKeyNorTheControlKey() {
        val f = created.opened.currentFolderKey()
        assertFalse(BackupKeys.backupMeta(p, f).contentEquals(f))
        assertFalse(BackupKeys.backupMeta(p, f).contentEquals(BackupKeys.control(p, f)))
    }

    @Test
    fun nonCanonicalOrMissingMetadataIsNotABackup() {
        val m = meta()
        val mac = macOf(m)
        fun bad(extra: Map<String, String>) = assertNull(extra.toString(), BackupMeta.read(fileOf(m, mac, extra)))
        bad(mapOf(DriveLayout.CREATED_AT to "0123"))
        bad(mapOf(DriveLayout.CREATED_AT to "-5"))
        bad(mapOf(DriveLayout.CREATED_AT to "1e3"))
        bad(mapOf(DriveLayout.CREATED_AT to "9007199254740992"))
        bad(mapOf(BackupMeta.HOUSES to "+3"))
        bad(mapOf(BackupMeta.HOUSES to "10000001"))
        bad(mapOf(BackupMeta.EPOCH to "0"))
        bad(mapOf(BackupMeta.KID to Bytes.b64(ByteArray(15))))
        bad(mapOf(BackupMeta.KID to Bytes.b64(kid).trimEnd('=')))
        bad(mapOf(BackupMeta.MAC to Bytes.b64(ByteArray(31))))
        bad(mapOf(DriveLayout.KIND to "sync"))
        assertNull(BackupMeta.read(fileOf(m, mac, sum = null)))
        assertNull(BackupMeta.read(fileOf(m, mac, sum = "zz")))
        assertNull(BackupMeta.read(fileOf(m, mac).copy(appProperties = emptyMap())))
        assertNotNull(BackupMeta.read(fileOf(m, mac, sum = Bytes.hex(sha).uppercase())))
    }

    // ---- ControlFile ----

    private val control = ControlFile(p)

    @Test
    fun aControlFileRoundTripsAndMovesTheWatermark() {
        val store = MemoryControlStore()
        val w1 = control.create(created.opened, t0)
        val b1 = control.open(w1.bytes, created.opened, store)
        assertEquals(1, b1.revision)
        assertEquals("dpx/1", b1.encryption)
        assertNull(b1.backupsDeletedAt)
        assertEquals(1, store.value!!.revision)
        val w2 = control.next(created.opened, b1, backupsDeletedAt = t0 + 5)
        val b2 = control.open(w2.bytes, created.opened, store)
        assertEquals(2, b2.revision)
        assertEquals(t0 + 5, b2.backupsDeletedAt)
        assertEquals(t0 + 5, store.value!!.backupsDeletedAt)
        // Opening the current one again is fine.
        control.open(w2.bytes, created.opened, store)
        assertEquals(t0, b2.createdAt)
    }

    private fun controlKind(kind: ControlException.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: ControlException) {
            assertEquals(e.message, kind, e.kind)
        }
    }

    @Test
    fun aRolledBackControlFileIsRefused() {
        val store = MemoryControlStore()
        val w1 = control.create(created.opened, t0)
        val w2 = control.next(created.opened, w1.body, backupsDeletedAt = t0 + 5)
        control.open(w2.bytes, created.opened, store)
        controlKind(ControlException.Kind.ROLLED_BACK) { control.open(w1.bytes, created.opened, store) }
        assertEquals(2, store.value!!.revision)
    }

    @Test
    fun theSameRevisionWithAnotherBodyIsAFork() {
        val store = MemoryControlStore()
        val w1 = control.create(created.opened, t0)
        val a = control.next(created.opened, w1.body, backupsDeletedAt = t0 + 5)
        val b = control.next(created.opened, w1.body, backupsDeletedAt = t0 + 6)
        control.open(a.bytes, created.opened, store)
        controlKind(ControlException.Kind.FORK_DETECTED) { control.open(b.bytes, created.opened, store) }
    }

    @Test
    fun aForgedOrEditedControlFileIsRefused() {
        val store = MemoryControlStore()
        val w1 = control.create(created.opened, t0)
        // Another folder's key set: its MAC does not verify under ours.
        val foreign = keysFile.createFirstDevice(KeysFile.NewDevice(phone.publicKey, "Pixel 8", DevicePlatform.ANDROID), null, t0)
        controlKind(ControlException.Kind.MAC_INVALID) { control.open(control.create(foreign.opened, t0).bytes, created.opened, store) }
        // Edited by hand (a hidden backupsDeletedAt, or a downgrade to "none"): the MAC fails.
        val text = String(w1.bytes)
        controlKind(ControlException.Kind.MAC_INVALID) { control.open(text.replace("\"backupsDeletedAt\":null", "\"backupsDeletedAt\":99").toByteArray(), created.opened, store) }
        controlKind(ControlException.Kind.MAC_INVALID) { control.open(text.replace("dpx/1", "none/").toByteArray(), created.opened, store) }
        // Another epoch than the keys hold, extra whitespace, not JSON, and a newer format.
        controlKind(ControlException.Kind.UNKNOWN_EPOCH) { control.open(text.replace("\"epoch\":1", "\"epoch\":4").toByteArray(), created.opened, store) }
        controlKind(ControlException.Kind.NOT_CANONICAL) { control.open(text.replace("{\"format\"", "{ \"format\"").toByteArray(), created.opened, store) }
        controlKind(ControlException.Kind.MALFORMED) { control.open("nope".toByteArray(), created.opened, store) }
        controlKind(ControlException.Kind.UNSUPPORTED_FORMAT) { control.open(text.replace("doorprints-control/1", "doorprints-control/2").toByteArray(), created.opened, store) }
        assertNull(store.value)
    }

    @Test
    fun anUnencryptedControlFileIsRefusedEvenWhenMacedByAKeyHolder() {
        val key = created.opened.currentFolderKey()
        val body = ControlBody(1, 1, t0, "none", null)
        // Build it the way a key holder with a buggy writer would: the MAC is right, the encryption is not dpx/1.
        val mac = p.hmacSha256(BackupKeys.control(p, key), Bytes.concat(Bytes.utf8(ControlFile.FORMAT), byteArrayOf(0), body.json()))
        val bytes = Bytes.concat(
            "{\"format\":\"${ControlFile.FORMAT}\",\"body\":".toByteArray(), body.json(), ",\"mac\":\"${Bytes.b64(mac)}\"}".toByteArray(),
        )
        controlKind(ControlException.Kind.UNSUPPORTED_ENCRYPTION) { control.open(bytes, created.opened, MemoryControlStore()) }
    }

    @Test
    fun theWatermarkSurvivesAConcurrentUpdateOrGivesUp() {
        val w1 = control.create(created.opened, t0)
        val racing = object : ControlWatermarkStore {
            var n = 0
            override fun load(): ControlWatermark? = ControlWatermark(0, ByteArray(32) { n.toByte() }, null).takeIf { false }
            override fun compareAndSet(expected: ControlWatermark?, next: ControlWatermark): Boolean {
                n++
                return false
            }
        }
        controlKind(ControlException.Kind.CONCURRENT_UPDATE) { control.open(w1.bytes, created.opened, racing) }
        assertEquals(4, racing.n)
    }

    @Test
    fun theGuardAndTheControlFileKeepSeparateWatermarks() {
        // The key list pin does not move the control watermark and vice versa.
        val keyStore = MemoryKeysStore()
        KeysGuard(p, keyStore).pinCreated(created)
        val c = MemoryControlStore()
        assertNull(c.value)
        control.open(control.create(created.opened, t0).bytes, created.opened, c)
        assertNotNull(keyStore.value)
        assertNotNull(c.value)
    }

    @Test
    fun fileNamesAreLocalTime() {
        assertEquals("Doorprints-backup-2026-10-02-0930.dpx", BackupNames.of(1_790_913_600_000, 330))
        assertEquals("Doorprints-backup-2026-10-02-0400.dpx", BackupNames.of(1_790_913_600_000, 0))
        assertEquals("Doorprints-backup-2026-10-01-2300.dpx", BackupNames.of(1_790_913_600_000, -300))
    }
}
