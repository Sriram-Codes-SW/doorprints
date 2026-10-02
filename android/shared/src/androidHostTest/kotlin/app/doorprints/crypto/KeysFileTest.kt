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

package app.doorprints.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `keys.json`: create, open, add, revoke, chained epochs, MAC, rollback, recovery (TC-U-130). */
class KeysFileTest {
    private val p = JvmCryptoProvider
    private val files = KeysFile(p)
    private val phone = p.p256Generate()
    private val tablet = p.p256Generate()
    private val browser = p.p256Generate()
    private val recovery = RecoveryKey.generate(p)
    private val t0 = 1_790_000_000_000L

    private fun nd(k: P256PrivateKey, name: String, platform: DevicePlatform = DevicePlatform.ANDROID) = KeysFile.NewDevice(k.publicKey, name, platform)
    private fun kid(k: P256PrivateKey) = kidOf(p, k.publicKey)
    private fun guard() = KeysGuard(MemoryWatermarkStore())

    private fun expect(kind: KeysException.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: KeysException) {
            assertEquals(e.message, kind, e.kind)
        }
    }

    private fun created() = files.createFirstDevice(nd(phone, "Pixel 8"), recovery.keyPair(p).publicKey, t0)

    @Test
    fun createThenOpenWithTheDeviceAndTheRecoveryKey() {
        val w = created()
        assertEquals(1, w.opened.epoch)
        assertEquals(1L, w.opened.revision)
        val byPhone = files.open(w.bytes, phone, guard())
        val byRecovery = files.openWithRecovery(w.bytes, RecoveryKey.parse(recovery.display), guard())
        assertArrayEquals(w.opened.currentFolderKey(), byPhone.currentFolderKey())
        assertArrayEquals(w.opened.currentFolderKey(), byRecovery.currentFolderKey())
        expect(KeysException.Kind.NOT_ENROLLED) { files.open(w.bytes, tablet, guard()) }
        expect(KeysException.Kind.RECOVERY_MISMATCH) { files.openWithRecovery(w.bytes, RecoveryKey.generate(p), guard()) }
        // Two first connects never share a folder key.
        assertTrue(!created().opened.currentFolderKey().contentEquals(w.opened.currentFolderKey()))
    }

    @Test
    fun withoutARecoveryKeyOnlyDevicesOpenIt() {
        val w = files.createFirstDevice(nd(phone, "Pixel 8"), null, t0)
        assertNull(w.opened.body.recovery)
        expect(KeysException.Kind.NO_RECOVERY) { files.openWithRecovery(w.bytes, recovery, guard()) }
        files.open(w.bytes, phone, guard())
    }

    @Test
    fun addDeviceWrapsTheCurrentKeyForExactlyThatKey() {
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Galaxy Tab", DevicePlatform.ANDROID), t0 + 1)
        assertEquals(2L, w2.opened.revision)
        assertEquals(1, w2.opened.epoch)
        val byTablet = files.open(w2.bytes, tablet, guard())
        assertArrayEquals(w1.opened.currentFolderKey(), byTablet.currentFolderKey())
        val entry = byTablet.body.device(kid(tablet))!!
        assertArrayEquals(kid(phone), entry.enrolledBy)
        assertEquals("Galaxy Tab", entry.name)
        expect(KeysException.Kind.ALREADY_ENROLLED) { files.addDevice(w2.opened, kid(phone), nd(tablet, "again"), t0 + 2) }
        expect(KeysException.Kind.NOT_LISTED) { files.addDevice(w2.opened, kid(browser), nd(browser, "web"), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), nd(browser, ""), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), nd(browser, "a\u0000b"), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), nd(browser, "x".repeat(65)), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), KeysFile.NewDevice(ByteArray(65), "bad", DevicePlatform.WEB), t0 + 2) }
        // The recovery key can approve (a new device joined with it).
        val w3 = files.addDevice(w2.opened, w2.opened.body.recovery!!.kid, nd(browser, "Firefox", DevicePlatform.WEB), t0 + 3)
        files.open(w3.bytes, browser, guard())
    }

    @Test
    fun revokeStartsAChainedEpochTheRevokedDeviceCannotOpen() {
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val w3 = files.newEpoch(w2.opened, t0 + 2, revokeKid = kid(tablet))
        assertEquals(2, w3.opened.epoch)
        assertEquals(3L, w3.opened.revision)
        expect(KeysException.Kind.REVOKED) { files.open(w3.bytes, tablet, guard()) }
        val byPhone = files.open(w3.bytes, phone, guard())
        val byRecovery = files.openWithRecovery(w3.bytes, recovery, guard())
        assertTrue(!byPhone.currentFolderKey().contentEquals(w1.opened.currentFolderKey()))
        assertArrayEquals(byPhone.currentFolderKey(), byRecovery.currentFolderKey())
        // The chain gives epoch 1 back, and epoch 1 files still open.
        assertArrayEquals(w1.opened.currentFolderKey(), byPhone.folderKey(1))
        assertArrayEquals(w1.opened.currentFolderKey(), byRecovery.folderKey(1))
        assertNull(byPhone.folderKey(3))
        assertNull(byPhone.folderKey(0))
        val revoked = byPhone.body.revokedEntry(kid(tablet))!!
        assertEquals(t0 + 2, revoked.revokedAt)
        assertEquals(2, revoked.revokedAtEpoch)
        expect(KeysException.Kind.NOT_LISTED) { files.newEpoch(w3.opened, t0 + 3, revokeKid = kid(tablet)) }
        expect(KeysException.Kind.REVOKED) { files.addDevice(w3.opened, kid(phone), nd(tablet, "back"), t0 + 3) }
    }

    @Test
    fun aDeviceEnrolledAtEpochFiveOpensAnEpochTwoBackup() {
        var w = created()
        val dpx = Dpx(p)
        var backup: ByteArray? = null
        while (w.opened.epoch < 5) {
            w = files.newEpoch(w.opened, t0 + w.opened.epoch)
            if (w.opened.epoch == 2) backup = dpx.encryptBytes(w.opened.currentFolderKey(), 2, kid(phone), "doorprints-backup/2", "old houses".encodeToByteArray()).first
        }
        w = files.addDevice(w.opened, kid(phone), nd(browser, "New laptop", DevicePlatform.WEB), t0 + 10)
        val byBrowser = files.open(w.bytes, browser, guard())
        assertEquals(5, byBrowser.epoch)
        assertEquals("old houses", dpx.decryptBytes(byBrowser, "doorprints-backup/2", backup!!).first.decodeToString())
        assertEquals("old houses", dpx.decryptBytes(files.openWithRecovery(w.bytes, recovery, guard()), "doorprints-backup/2", backup).first.decodeToString())
    }

    @Test
    fun aNewRecoveryKeyStartsAnEpochTheOldOneCannotOpen() {
        val w1 = created()
        val newRecovery = RecoveryKey.generate(p)
        val w2 = files.newEpoch(w1.opened, t0 + 1, newRecoveryPublicKey = newRecovery.keyPair(p).publicKey)
        expect(KeysException.Kind.RECOVERY_MISMATCH) { files.openWithRecovery(w2.bytes, recovery, guard()) }
        assertArrayEquals(w2.opened.currentFolderKey(), files.openWithRecovery(w2.bytes, newRecovery, guard()).currentFolderKey())
    }

    @Test
    fun theLastRecipientCannotBeRevoked() {
        val w = files.createFirstDevice(nd(phone, "Only phone"), null, t0)
        expect(KeysException.Kind.LAST_RECIPIENT) { files.newEpoch(w.opened, t0 + 1, revokeKid = kid(phone)) }
        // With a recovery key the only device can go; the recovery key still opens the folder.
        val withRecovery = files.newEpoch(created().opened, t0 + 1, revokeKid = kid(phone))
        files.openWithRecovery(withRecovery.bytes, recovery, guard())
    }

    @Test
    fun anAddedOrChangedEntryFailsTheMac() {
        val w = created()
        val text = w.bytes.decodeToString()
        // Someone in the Google account renames the device (a valid, canonical change without the folder key).
        val renamed = text.replace("\"Pixel 8\"", "\"Pixel 9\"").encodeToByteArray()
        expect(KeysException.Kind.MAC_INVALID) { files.open(renamed, phone, guard()) }
        // ... or adds their own key with a wrap of a key of their own (docs/02 T-S13).
        val intruder = p.p256Generate()
        val other = files.createFirstDevice(nd(intruder, "Intruder"), null, t0)
        val otherEntry = other.bytes.decodeToString().substringAfter("\"devices\":[").substringBefore("],\"recovery\"")
        val added = text.replace("\"devices\":[", "\"devices\":[$otherEntry,").encodeToByteArray()
        expect(KeysException.Kind.MAC_INVALID) { files.open(added, phone, guard()) }
        // ... or replaces the whole list with their own, MACed under their key: this device is not in it.
        expect(KeysException.Kind.NOT_ENROLLED) { files.open(other.bytes, phone, guard()) }
        // A flipped bit in the MAC.
        val macAt = text.lastIndexOf("\"mac\":\"") + 8
        val flipped = w.bytes.copyOf().also { it[macAt] = if (it[macAt] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte() }
        expect(KeysException.Kind.MAC_INVALID) { files.open(flipped, phone, guard()) }
    }

    @Test
    fun anOlderRevisionIsRefusedOnceANewerOneWasSeen() {
        val store = MemoryWatermarkStore()
        val g = KeysGuard(store)
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val w3 = files.newEpoch(w2.opened, t0 + 2, revokeKid = kid(tablet))
        files.open(w1.bytes, phone, g)
        files.open(w3.bytes, phone, g)
        assertEquals(KeysWatermark(2, 3), store.value)
        // Drive's "Manage versions" brings back revision 2, which undoes the revoke: refused.
        expect(KeysException.Kind.ROLLED_BACK) { files.open(w2.bytes, phone, g) }
        expect(KeysException.Kind.ROLLED_BACK) { files.open(w1.bytes, phone, g) }
        expect(KeysException.Kind.ROLLED_BACK) { files.openWithRecovery(w2.bytes, recovery, g) }
        assertEquals(KeysWatermark(2, 3), store.value)
        // The same revision again is fine.
        files.open(w3.bytes, phone, g)
    }

    @Test
    fun onlyTheCanonicalFormIsRead() {
        val w = created()
        val text = w.bytes.decodeToString()
        expect(KeysException.Kind.NOT_CANONICAL) { files.open(" $text".encodeToByteArray(), phone, guard()) }
        expect(KeysException.Kind.NOT_CANONICAL) { files.open(text.replace("{\"format\"", "{ \"format\"").encodeToByteArray(), phone, guard()) }
        expect(KeysException.Kind.NOT_CANONICAL) { files.open(text.replace("\"revision\":1", "\"revision\":1.0").encodeToByteArray(), phone, guard()) }
        expect(KeysException.Kind.MALFORMED) { files.open(text.replace("\"revoked\":[]", "\"revoked\":[],\"totp\":null").encodeToByteArray(), phone, guard()) }
        expect(KeysException.Kind.MALFORMED) { files.open("[]".encodeToByteArray(), phone, guard()) }
        expect(KeysException.Kind.MALFORMED) { files.open(ByteArray(3) { -1 }, phone, guard()) }
        expect(KeysException.Kind.UNSUPPORTED_FORMAT) { files.open(text.replace("doorprints-keys/1", "doorprints-keys/2").encodeToByteArray(), phone, guard()) }
        // A kid that is not its key's (structurally fine, so checked as a rule).
        val wrongKid = text.replaceFirst(Regex("\"kid\":\"[^\"]+\""), "\"kid\":\"${Bytes.b64(ByteArray(16))}\"")
        expect(KeysException.Kind.INVALID_ENTRY) { files.open(wrongKid.encodeToByteArray(), phone, guard()) }
    }

    @Test
    fun aRevokedFileRuleOverRealLists() {
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val w3 = files.newEpoch(w2.opened, t0 + 100, revokeKid = kid(tablet))
        val body = w3.opened.body
        assertEquals(RevokedEpochRule.Verdict.ACCEPT, RevokedEpochRule.check(body, 1, kid(tablet), t0 + 50))
        assertEquals(RevokedEpochRule.Verdict.SKIP_REVOKED_WRITER, RevokedEpochRule.check(body, 1, kid(tablet), t0 + 150))
        assertEquals(RevokedEpochRule.Verdict.SKIP_OLD_EPOCH_AFTER_REVOKE, RevokedEpochRule.check(body, 1, kid(phone), t0 + 150))
        assertEquals(RevokedEpochRule.Verdict.ACCEPT, RevokedEpochRule.check(body, 2, kid(phone), t0 + 150))
        assertEquals(RevokedEpochRule.Verdict.SKIP_UNKNOWN_WRITER, RevokedEpochRule.check(body, 2, kid(browser), t0 + 150))
        assertEquals(RevokedEpochRule.Verdict.NEWER_EPOCH, RevokedEpochRule.check(body, 3, kid(phone), t0 + 150))
    }
}
