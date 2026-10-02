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

/**
 * `keys.json`: create, open, add, revoke, chained epochs, MAC, the pin, rollback, forks, the recovery anchor
 * (TC-U-130), and the review's proofs of concept turned into tests (forge.ts, poison.ts; TC-U-132).
 */
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

    /** A device with no pin yet (a fresh install). */
    private fun fresh() = KeysGuard(p, MemoryWatermarkStore())

    /** The creating device's guard, pinned to the list it made. */
    private fun pinnedTo(created: KeysFile.Written) = fresh().also { it.pinCreated(created) }

    private fun expect(kind: KeysException.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: KeysException) {
            assertEquals(e.message, kind, e.kind)
        }
    }

    private fun created() = files.createFirstDevice(nd(phone, "Pixel 8"), recovery, t0)

    /**
     * What anyone holding the Google account can make from the public bytes of [genuine]: a folder key of their own,
     * re-wrapped to every listed public key (the recovery key's too, keeping its genuine anchor), an extra device,
     * MACed under their key; at the same epoch, or at the next with a link they cannot make correctly.
     */
    private fun forge(genuine: ByteArray, nextEpoch: Boolean): ByteArray {
        val (body, _) = files.parse(genuine)
        val evil = p.p256Generate()
        val f = p.randomBytes(32)
        val epoch = if (nextEpoch) body.epoch + 1 else body.epoch
        val hpke = Hpke(p)
        fun wrap(pub: ByteArray, kid: ByteArray) = hpke.seal(pub, WrapAad.HPKE_INFO, WrapAad.folderKey(epoch, kid), f).let { HpkeWrap(it.enc, it.ciphertext) }
        val ek = kidOf(p, evil.publicKey)
        val devices = body.devices.map { DeviceEntry(it.kid, it.name, it.platform, it.publicKey, it.enrolledAt, it.enrolledBy, wrap(it.publicKey, it.kid)) } +
            DeviceEntry(ek, "Pixel 8", DevicePlatform.ANDROID, evil.publicKey, t0 + 9, body.devices[0].kid, wrap(evil.publicKey, ek))
        val rec = body.recovery?.let { RecoveryEntry(it.kid, it.publicKey, it.anchorEpoch, it.anchor, wrap(it.publicKey, it.kid)) }
        val chain = if (nextEpoch) body.chain + ChainLink(epoch, AeadWrap(p.randomBytes(12), p.randomBytes(48))) else body.chain
        val forged = KeysBody(body.revision + 1, epoch, chain, devices, rec, body.revoked)
        val mac = p.hmacSha256(FolderKey.macKey(p, f), Bytes.concat(Bytes.utf8(KeysFile.FORMAT), byteArrayOf(0), forged.json()))
        return Bytes.concat("{\"format\":\"doorprints-keys/1\",\"body\":".encodeToByteArray(), forged.json(), ",\"mac\":\"${Bytes.b64(mac)}\"}".encodeToByteArray())
    }

    @Test
    fun createThenOpenWithTheDeviceAndTheRecoveryKey() {
        val w = created()
        assertEquals(1, w.opened.epoch)
        assertEquals(1L, w.opened.revision)
        val byPhone = files.open(w.bytes, phone, pinnedTo(w))
        val byRecovery = files.openWithRecovery(w.bytes, RecoveryKey.parse(recovery.display), fresh())
        assertArrayEquals(w.opened.currentFolderKey(), byPhone.currentFolderKey())
        assertArrayEquals(w.opened.currentFolderKey(), byRecovery.currentFolderKey())
        expect(KeysException.Kind.NOT_ENROLLED) { files.open(w.bytes, tablet, fresh()) }
        expect(KeysException.Kind.RECOVERY_MISMATCH) { files.openWithRecovery(w.bytes, RecoveryKey.generate(p), fresh()) }
        assertTrue(!created().opened.currentFolderKey().contentEquals(w.opened.currentFolderKey()))
    }

    @Test
    fun withoutAPinOnlyTheNamedPathsOpen() {
        val w = created()
        // A fresh device cannot adopt a list it read from Drive (docs/15 §9.3: no silent adoption).
        expect(KeysException.Kind.NOT_PINNED) { files.open(w.bytes, phone, fresh()) }
        // The enrolment path pins only the key it received over the authenticated channel.
        expect(KeysException.Kind.PIN_MISMATCH) { files.openFirstPin(w.bytes, phone, fresh(), p.randomBytes(32)) }
        val g = fresh()
        files.openFirstPin(w.bytes, phone, g, w.opened.currentFolderKey())
        assertEquals(1, g.watermark()!!.epoch)
        files.open(w.bytes, phone, g)
        // The recovery path pins through the anchor.
        val r = fresh()
        files.openWithRecovery(w.bytes, recovery, r)
        files.open(w.bytes, phone, r)
        // pinCreated is a first pin only: another folder's list does not replace a pin.
        expect(KeysException.Kind.FORK_DETECTED) { g.pinCreated(created()) }
    }

    @Test
    fun withoutARecoveryKeyOnlyDevicesOpenIt() {
        val w = files.createFirstDevice(nd(phone, "Pixel 8"), null, t0)
        assertNull(w.opened.body.recovery)
        expect(KeysException.Kind.NO_RECOVERY) { files.openWithRecovery(w.bytes, recovery, fresh()) }
        files.open(w.bytes, phone, pinnedTo(w))
    }

    @Test
    fun addDeviceWrapsTheCurrentKeyForExactlyThatKey() {
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Galaxy Tab", DevicePlatform.ANDROID), t0 + 1)
        assertEquals(2L, w2.opened.revision)
        assertEquals(1, w2.opened.epoch)
        val byTablet = files.openFirstPin(w2.bytes, tablet, fresh(), w1.opened.currentFolderKey())
        assertArrayEquals(w1.opened.currentFolderKey(), byTablet.currentFolderKey())
        files.open(w2.bytes, phone, pinnedTo(w1))
        val entry = byTablet.body.device(kid(tablet))!!
        assertArrayEquals(kid(phone), entry.enrolledBy)
        assertEquals("Galaxy Tab", entry.name)
        expect(KeysException.Kind.ALREADY_ENROLLED) { files.addDevice(w2.opened, kid(phone), nd(tablet, "again"), t0 + 2) }
        expect(KeysException.Kind.NOT_LISTED) { files.addDevice(w2.opened, kid(browser), nd(browser, "web"), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), nd(browser, ""), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), nd(browser, "a\u0000b"), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), nd(browser, "x".repeat(65)), t0 + 2) }
        expect(KeysException.Kind.INVALID_ENTRY) { files.addDevice(w2.opened, kid(phone), KeysFile.NewDevice(ByteArray(65), "bad", DevicePlatform.WEB), t0 + 2) }
        val w3 = files.addDevice(w2.opened, w2.opened.body.recovery!!.kid, nd(browser, "Firefox", DevicePlatform.WEB), t0 + 3)
        files.open(w3.bytes, browser, pinnedTo(w1))
    }

    @Test
    fun revokeStartsAChainedEpochTheRevokedDeviceCannotOpen() {
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val w3 = files.newEpoch(w2.opened, t0 + 2, revokeKid = kid(tablet))
        assertEquals(2, w3.opened.epoch)
        assertEquals(3L, w3.opened.revision)
        expect(KeysException.Kind.REVOKED) { files.open(w3.bytes, tablet, pinnedTo(w1)) }
        val byPhone = files.open(w3.bytes, phone, pinnedTo(w1))
        val byRecovery = files.openWithRecovery(w3.bytes, recovery, fresh())
        assertTrue(!byPhone.currentFolderKey().contentEquals(w1.opened.currentFolderKey()))
        assertArrayEquals(byPhone.currentFolderKey(), byRecovery.currentFolderKey())
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
        val first = w
        val dpx = Dpx(p)
        var backup: ByteArray? = null
        while (w.opened.epoch < 5) {
            w = files.newEpoch(w.opened, t0 + w.opened.epoch)
            if (w.opened.epoch == 2) backup = dpx.encryptBytes(w.opened.currentFolderKey(), 2, kid(phone), "doorprints-backup/2", "old houses".encodeToByteArray()).first
        }
        w = files.addDevice(w.opened, kid(phone), nd(browser, "New laptop", DevicePlatform.WEB), t0 + 10)
        val byBrowser = files.openFirstPin(w.bytes, browser, fresh(), w.opened.currentFolderKey())
        assertEquals(5, byBrowser.epoch)
        assertEquals("old houses", dpx.decryptBytes(byBrowser, "doorprints-backup/2", backup!!).first.decodeToString())
        assertEquals("old houses", dpx.decryptBytes(files.openWithRecovery(w.bytes, recovery, fresh()), "doorprints-backup/2", backup).first.decodeToString())
        // The phone pinned at epoch 1 walks the whole chain down to its pin.
        files.open(w.bytes, phone, pinnedTo(first))
    }

    @Test
    fun aNewRecoveryKeyStartsAnEpochTheOldOneCannotOpen() {
        val w1 = created()
        val newRecovery = RecoveryKey.generate(p)
        val w2 = files.newEpoch(w1.opened, t0 + 1, newRecovery = newRecovery)
        expect(KeysException.Kind.RECOVERY_MISMATCH) { files.openWithRecovery(w2.bytes, recovery, fresh()) }
        val r = files.openWithRecovery(w2.bytes, newRecovery, fresh())
        assertArrayEquals(w2.opened.currentFolderKey(), r.currentFolderKey())
        assertEquals(2, r.body.recovery!!.anchorEpoch)
        // The old recovery kid is revoked, so a device it enrolled stays valid and it cannot come back.
        val oldKid = w1.opened.body.recovery!!.kid
        assertEquals(2, r.body.revokedEntry(oldKid)!!.revokedAtEpoch)
    }

    @Test
    fun theLastRecipientCannotBeRevoked() {
        val w = files.createFirstDevice(nd(phone, "Only phone"), null, t0)
        expect(KeysException.Kind.LAST_RECIPIENT) { files.newEpoch(w.opened, t0 + 1, revokeKid = kid(phone)) }
        val withRecovery = files.newEpoch(created().opened, t0 + 1, revokeKid = kid(phone))
        files.openWithRecovery(withRecovery.bytes, recovery, fresh())
    }

    @Test
    fun anAddedOrChangedEntryFailsTheMac() {
        val w = created()
        val text = w.bytes.decodeToString()
        val renamed = text.replace("\"Pixel 8\"", "\"Pixel 9\"").encodeToByteArray()
        expect(KeysException.Kind.MAC_INVALID) { files.open(renamed, phone, pinnedTo(w)) }
        val intruder = p.p256Generate()
        val other = files.createFirstDevice(nd(intruder, "Intruder"), null, t0)
        val otherEntry = other.bytes.decodeToString().substringAfter("\"devices\":[").substringBefore("],\"recovery\"")
        val added = text.replace("\"devices\":[", "\"devices\":[$otherEntry,").encodeToByteArray()
        expect(KeysException.Kind.MAC_INVALID) { files.open(added, phone, pinnedTo(w)) }
        expect(KeysException.Kind.NOT_ENROLLED) { files.open(other.bytes, phone, pinnedTo(w)) }
        val macAt = text.lastIndexOf("\"mac\":\"") + 8
        val flipped = w.bytes.copyOf().also { it[macAt] = if (it[macAt] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte() }
        expect(KeysException.Kind.MAC_INVALID) { files.open(flipped, phone, pinnedTo(w)) }
    }

    /** forge.ts: a list re-wrapped under the attacker's key at the same epoch, on both paths. */
    @Test
    fun aReWrappedListAtTheSameEpochIsRefused() {
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val forged = forge(w2.bytes, nextEpoch = false)
        // It is well formed and its MAC is right: only the pin and the anchor tell.
        val tabletGuard = fresh().also { files.openFirstPin(w2.bytes, tablet, it, w1.opened.currentFolderKey()) }
        expect(KeysException.Kind.FORK_DETECTED) { files.open(forged, tablet, tabletGuard) }
        expect(KeysException.Kind.FORK_DETECTED) { files.open(forged, phone, pinnedTo(w1)) }
        expect(KeysException.Kind.RECOVERY_ANCHOR_INVALID) { files.openWithRecovery(forged, recovery, fresh()) }
        expect(KeysException.Kind.RECOVERY_ANCHOR_INVALID) { files.openWithRecovery(forged, recovery, tabletGuard) }
        // The genuine list still opens afterwards: nothing moved.
        files.open(w2.bytes, tablet, tabletGuard)
    }

    /** forge.ts: the same at the next epoch, with a chain link the attacker cannot make. */
    @Test
    fun aReWrappedListAtTheNextEpochIsRefused() {
        val w1 = created()
        val w2 = files.newEpoch(w1.opened, t0 + 1)
        for (base in listOf(w1, w2)) {
            val forged = forge(base.bytes, nextEpoch = true)
            expect(KeysException.Kind.PIN_MISMATCH) { files.open(forged, phone, pinnedTo(w1)) }
            expect(KeysException.Kind.RECOVERY_ANCHOR_INVALID) { files.openWithRecovery(forged, recovery, fresh()) }
            val g = pinnedTo(w1)
            files.open(w2.bytes, phone, g)
            // Pinned at epoch 2 now: a forged epoch 2 is a second key for it, a forged epoch 3 does not chain to it.
            expect(if (base === w1) KeysException.Kind.FORK_DETECTED else KeysException.Kind.PIN_MISMATCH) { files.open(forged, phone, g) }
        }
        // Without a recovery key the device path holds by itself.
        val alone = files.createFirstDevice(nd(phone, "Alone"), null, t0)
        expect(KeysException.Kind.PIN_MISMATCH) { files.open(forge(alone.bytes, nextEpoch = true), phone, pinnedTo(alone)) }
        expect(KeysException.Kind.FORK_DETECTED) { files.open(forge(alone.bytes, nextEpoch = false), phone, pinnedTo(alone)) }
    }

    @Test
    fun anOlderRevisionIsRefusedOnceANewerOneWasSeen() {
        val store = MemoryWatermarkStore()
        val g = KeysGuard(p, store)
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val w3 = files.newEpoch(w2.opened, t0 + 2, revokeKid = kid(tablet))
        g.pinCreated(w1)
        files.open(w3.bytes, phone, g)
        assertEquals(2, store.value!!.epoch)
        assertEquals(3L, store.value!!.revision)
        expect(KeysException.Kind.ROLLED_BACK) { files.open(w2.bytes, phone, g) }
        expect(KeysException.Kind.ROLLED_BACK) { files.open(w1.bytes, phone, g) }
        expect(KeysException.Kind.ROLLED_BACK) { files.openWithRecovery(w2.bytes, recovery, g) }
        assertEquals(3L, store.value!!.revision)
        files.open(w3.bytes, phone, g)
    }

    /** poison.ts: a revoked thief writes its old epoch at the top revision; the genuine revoke still wins. */
    @Test
    fun aHugeRevisionUnderAnOldEpochCannotBlockTheRevoke() {
        val thief = p.p256Generate()
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "B"), t0 + 1)
        val w3 = files.addDevice(w2.opened, kid(phone), nd(thief, "Thief"), t0 + 2)
        val gB = fresh().also { files.openFirstPin(w3.bytes, tablet, it, w1.opened.currentFolderKey()) }
        val thiefOpened = files.openFirstPin(w3.bytes, thief, fresh(), w1.opened.currentFolderKey())
        val revoke = files.newEpoch(w3.opened, t0 + 3, revokeKid = kid(thief))
        // The thief knows epoch 1's key, so its list passes B's pin at epoch 1 (an enrolled device is trusted).
        val b = thiefOpened.body
        val poisoned = KeysBody(CanonicalJson.MAX_SAFE, b.epoch, b.chain, b.devices, b.recovery, b.revoked)
        val k = thiefOpened.currentFolderKey()
        val mac = p.hmacSha256(FolderKey.macKey(p, k), Bytes.concat(Bytes.utf8(KeysFile.FORMAT), byteArrayOf(0), poisoned.json()))
        val file = Bytes.concat("{\"format\":\"doorprints-keys/1\",\"body\":".encodeToByteArray(), poisoned.json(), ",\"mac\":\"${Bytes.b64(mac)}\"}".encodeToByteArray())
        val o = files.open(file, tablet, gB)
        // Writing on from it is a clean refusal, not an overflow.
        expect(KeysException.Kind.REVISION_LIMIT) { files.addDevice(o, kid(tablet), nd(browser, "x"), t0 + 4) }
        // The genuine revoke (epoch 2, revision 4) is accepted: the higher epoch wins whatever the revision.
        files.open(revoke.bytes, tablet, gB)
        assertEquals(2, gB.watermark()!!.epoch)
        assertEquals(4L, gB.watermark()!!.revision)
        expect(KeysException.Kind.ROLLED_BACK) { files.open(file, tablet, gB) }
    }

    @Test
    fun twoWritersAtOnceAreAFork() {
        val w1 = created()
        val a = files.addDevice(w1.opened, kid(phone), nd(tablet, "A"), t0 + 1)
        val b = files.addDevice(w1.opened, kid(phone), nd(browser, "B"), t0 + 1)
        val g = pinnedTo(w1)
        files.open(a.bytes, phone, g)
        expect(KeysException.Kind.FORK_DETECTED) { files.open(b.bytes, phone, g) }
        val e1 = files.newEpoch(w1.opened, t0 + 2)
        val e2 = files.newEpoch(w1.opened, t0 + 2)
        val h = pinnedTo(w1)
        files.open(e1.bytes, phone, h)
        expect(KeysException.Kind.FORK_DETECTED) { files.open(e2.bytes, phone, h) }
        // A device's own write is checked like any other list.
        val g2 = pinnedTo(w1)
        g2.acceptWritten(a)
        expect(KeysException.Kind.FORK_DETECTED) { g2.acceptWritten(b) }
    }

    @Test
    fun theWatermarkUsesCompareAndSet() {
        val w1 = created()
        val w2 = files.addDevice(w1.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val inner = MemoryWatermarkStore()
        KeysGuard(p, inner).pinCreated(w1)
        // Another writer gets in between once: the guard reads again and still decides on the newest value.
        var raced = false
        val racing = object : KeysWatermarkStore {
            override fun load() = inner.load()
            override fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark): Boolean {
                if (!raced) {
                    raced = true
                    KeysGuard(p, inner).acceptWritten(w2)
                    return inner.compareAndSet(expected, next)
                }
                return inner.compareAndSet(expected, next)
            }
        }
        files.open(w2.bytes, phone, KeysGuard(p, racing))
        assertEquals(2L, inner.value!!.revision)
        val never = object : KeysWatermarkStore {
            override fun load() = inner.load()
            override fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark) = false
        }
        val w3 = files.addDevice(w2.opened, kid(phone), nd(browser, "Web"), t0 + 2)
        expect(KeysException.Kind.CONCURRENT_UPDATE) { files.open(w3.bytes, phone, KeysGuard(p, never)) }
    }

    @Test
    fun onlyTheCanonicalFormIsRead() {
        val w = created()
        val text = w.bytes.decodeToString()
        val g = pinnedTo(w)
        expect(KeysException.Kind.NOT_CANONICAL) { files.open(" $text".encodeToByteArray(), phone, g) }
        expect(KeysException.Kind.NOT_CANONICAL) { files.open(text.replace("{\"format\"", "{ \"format\"").encodeToByteArray(), phone, g) }
        expect(KeysException.Kind.NOT_CANONICAL) { files.open(text.replace("\"revision\":1", "\"revision\":1.0").encodeToByteArray(), phone, g) }
        expect(KeysException.Kind.MALFORMED) { files.open(text.replace("\"revoked\":[]", "\"revoked\":[],\"totp\":null").encodeToByteArray(), phone, g) }
        expect(KeysException.Kind.MALFORMED) { files.open("[]".encodeToByteArray(), phone, g) }
        expect(KeysException.Kind.MALFORMED) { files.open(ByteArray(3) { -1 }, phone, g) }
        expect(KeysException.Kind.UNSUPPORTED_FORMAT) { files.open(text.replace("doorprints-keys/1", "doorprints-keys/2").encodeToByteArray(), phone, g) }
        val wrongKid = text.replaceFirst(Regex("\"kid\":\"[^\"]+\""), "\"kid\":\"${Bytes.b64(ByteArray(16))}\"")
        expect(KeysException.Kind.INVALID_ENTRY) { files.open(wrongKid.encodeToByteArray(), phone, g) }
        // enrolledBy must name a kid the list knows.
        val w2 = files.addDevice(w.opened, kid(phone), nd(tablet, "Tab"), t0 + 1)
        val unknownBy = w2.bytes.decodeToString().replace("\"enrolledBy\":\"${Bytes.b64(kid(phone))}\"", "\"enrolledBy\":\"${Bytes.b64(ByteArray(16) { 9 })}\"")
        expect(KeysException.Kind.INVALID_ENTRY) { files.open(unknownBy.encodeToByteArray(), phone, g) }
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
        assertEquals(RevokedEpochRule.Verdict.SKIP_UNKNOWN_WRITER, RevokedEpochRule.check(body, 2, body.recovery!!.kid, t0 + 150))
        assertEquals(RevokedEpochRule.Verdict.NEWER_EPOCH, RevokedEpochRule.check(body, 3, kid(phone), t0 + 150))
    }
}
