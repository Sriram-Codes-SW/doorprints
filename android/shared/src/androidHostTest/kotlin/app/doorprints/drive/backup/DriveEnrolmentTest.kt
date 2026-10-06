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

import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.kidOf
import app.doorprints.drive.DriveOp
import app.doorprints.drive.FakeDriveServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Device enrolment, pairing and revocation on the fake Drive (S4b-BL-126, TC-U-134; web twin: the enrolment and
 * recovery-key tests of `drive-backup.service.spec.ts` and `tools/mutations/verify-recovery-key.json`). Real crypto.
 */
class DriveEnrolmentTest {
    private val p = JvmCryptoProvider
    private val server = FakeDriveServer()
    private val a = Rig(server, "Pixel 8")
    private val writes = setOf(DriveOp.CREATE, DriveOp.UPLOAD, DriveOp.UPLOAD_START, DriveOp.UPLOAD_CHUNK, DriveOp.UPDATE, DriveOp.DELETE, DriveOp.TRASH)

    private fun writeCount() = server.requests.count { it.first in writes }

    private fun Rig.pin(rootId: String) = trust.keys[rootId]?.value

    private fun approved(o: ApproveDeviceOutcome): ApproveDeviceOutcome.Approved =
        o as? ApproveDeviceOutcome.Approved ?: fail("expected Approved, got $o").let { error("unreachable") }

    private fun error(o: ApproveDeviceOutcome): DriveProblem =
        (o as? ApproveDeviceOutcome.Error ?: fail("expected Error, got $o").let { error("unreachable") }).problem

    private fun ready(c: DriveConnection): ReadyFolder =
        (c as? DriveConnection.Ready ?: fail("expected Ready, got $c").let { error("unreachable") }).folder

    private fun flipped(b: ByteArray) = b.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

    // ---- identity ----

    @Test
    fun theDeviceReportsItsOwnPublicKeyAndKid() {
        assertArrayEquals(a.identity.key.publicKey, a.service.devicePublicKey())
        assertArrayEquals(kidOf(p, a.identity.key.publicKey), a.service.deviceKid())
        // The kid handed out is a copy: changing it changes nothing inside.
        a.service.deviceKid()[0] = 0
        assertArrayEquals(kidOf(p, a.identity.key.publicKey), a.service.deviceKid())
    }

    // ---- the 8-digit wrap ----

    @Test
    fun anApprovedWrapEnrolsASecondDeviceThatThenOpensTheFolder() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        // Before anything: the newcomer is not pinned and the folder asks it to join; nothing is adopted.
        assertTrue(b.service.connect() is DriveConnection.NeedsEnrolment)
        val ap = approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        assertEquals(1, ap.epoch)
        assertEquals(2, ap.connection.folder.keys.body.devices.size)
        // The approver's own pin moved with the list it wrote (revision 1 at creation, 2 once the newcomer is listed).
        val rootId = a.state.value.rootId!!
        assertEquals(2L, a.pin(rootId)!!.revision)
        // Approving pins nothing on the newcomer.
        assertNull(b.pin(rootId))
        assertTrue(b.service.connect() is DriveConnection.NeedsEnrolment)

        val joined = ready(b.service.joinFromWrap(ap.wrapEnc, ap.wrapCt, ap.epoch))
        assertEquals(2, joined.keys.body.devices.size)
        assertNotNull(b.pin(rootId))
        assertTrue(b.service.connect() is DriveConnection.Ready)
    }

    @Test
    fun aBackupWrittenByTheFirstDeviceIsListedByTheEnrolledOne() = runTest {
        val first = a.created().folder
        a.service.backUp(first, Payload.of(5_000, 4).source())
        val b = Rig(server, "Tablet")
        val ap = approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        val joined = ready(b.service.joinFromWrap(ap.wrapEnc, ap.wrapCt, ap.epoch))
        assertEquals(1, b.service.listBackups(joined).backups.size)
    }

    @Test
    fun aWrapThatDoesNotOpenForThisDeviceWritesNoPin() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        val c = Rig(server, "Phone 2")
        val ap = approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        val rootId = a.state.value.rootId!!
        // Another device's wrap, the wrong epoch, a flipped byte of enc or of ct: every one is an Error and a pin never appears.
        assertTrue(c.service.joinFromWrap(ap.wrapEnc, ap.wrapCt, ap.epoch) is DriveConnection.Error)
        assertTrue(b.service.joinFromWrap(ap.wrapEnc, ap.wrapCt, ap.epoch + 1) is DriveConnection.Error)
        assertTrue(b.service.joinFromWrap(ap.wrapEnc, ap.wrapCt, 0) is DriveConnection.Error)
        assertTrue(b.service.joinFromWrap(flipped(ap.wrapEnc), ap.wrapCt, ap.epoch) is DriveConnection.Error)
        assertTrue(b.service.joinFromWrap(ap.wrapEnc, flipped(ap.wrapCt), ap.epoch) is DriveConnection.Error)
        assertTrue(b.service.joinFromWrap(ap.wrapEnc.copyOf(10), ap.wrapCt, ap.epoch) is DriveConnection.Error)
        assertNull(b.pin(rootId))
        assertNull(c.pin(rootId))
        assertEquals(DriveProblem.Kind.BACKUP_REFUSED, (b.service.joinFromWrap(ap.wrapEnc, flipped(ap.wrapCt), ap.epoch) as DriveConnection.Error).problem.kind)
    }

    @Test
    fun aFolderKeyFromAnotherFolderNeverMakesTheFirstPin() = runTest {
        a.created()
        val rootId = a.state.value.rootId!!
        val b = Rig(server, "Tablet")
        approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        // A stranger's folder (another Drive) hands b a perfectly valid wrap for b's key, but its key is not this list's.
        val other = Rig(FakeDriveServer(), "Stranger")
        other.created()
        val foreign = approved(other.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        val out = b.service.joinFromWrap(foreign.wrapEnc, foreign.wrapCt, foreign.epoch)
        assertTrue("got $out", out is DriveConnection.Error)
        assertNull(b.pin(rootId))
        assertTrue(b.service.connect() is DriveConnection.NeedsEnrolment)
    }

    @Test
    fun aDeviceWithNoPinCannotApproveAndWritesNothing() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        val c = Rig(server, "Phone 2")
        val before = writeCount()
        val problem = error(b.service.approveDevice(c.service.devicePublicKey(), c.identity.name, DevicePlatform.ANDROID))
        assertEquals(before, writeCount())
        assertTrue("got $problem", problem.kind == DriveProblem.Kind.KEYS_UNTRUSTED || problem.kind == DriveProblem.Kind.KEYS_UNREADABLE)
    }

    @Test
    fun approvingTheSameDeviceTwiceOrOneListedAlreadyIsRefusedWithoutWriting() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        val before = writeCount()
        val problem = error(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        assertEquals(KeysException.Kind.ALREADY_ENROLLED, problem.keysKind)
        assertEquals(before, writeCount())
    }

    @Test
    fun approvingAFolderThatIsGoneReportsItAndWritesNothing() = runTest {
        val fresh = Rig(FakeDriveServer(), "Pixel 8")
        val b = Rig(server, "Tablet")
        val before = fresh.server.requests.count { it.first in writes }
        val out = fresh.service.approveDevice(b.service.devicePublicKey(), "Tablet", DevicePlatform.ANDROID)
        assertTrue(out is ApproveDeviceOutcome.Error)
        assertEquals(before, fresh.server.requests.count { it.first in writes })
    }

    // ---- the QR (PSK) wrap ----

    @Test
    fun aQrPskWrapEnrolsASecondDeviceAndAWrongPskOrAnotherKeyDoesNot() = runTest {
        a.created()
        val rootId = a.state.value.rootId!!
        val b = Rig(server, "Tablet")
        val psk = p.randomBytes(32)
        val ap = approved(a.service.approveDevicePsk(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID, psk))
        assertEquals(2, ap.connection.folder.keys.body.devices.size)

        assertTrue(b.service.joinFromPsk(ap.wrapEnc, ap.wrapCt, ap.epoch, flipped(psk)) is DriveConnection.Error)
        val other = Rig(server, "Phone 2")
        assertTrue(other.service.joinFromPsk(ap.wrapEnc, ap.wrapCt, ap.epoch, psk) is DriveConnection.Error)
        assertTrue(b.service.joinFromPsk(ap.wrapEnc, ap.wrapCt, ap.epoch + 1, psk) is DriveConnection.Error)
        assertTrue(b.service.joinFromPsk(ap.wrapEnc, ap.wrapCt, ap.epoch, psk.copyOf(31)) is DriveConnection.Error)
        assertNull(b.pin(rootId))
        assertNull(other.pin(rootId))

        val joined = ready(b.service.joinFromPsk(ap.wrapEnc, ap.wrapCt, ap.epoch, psk))
        assertEquals(2, joined.keys.body.devices.size)
        assertNotNull(b.pin(rootId))
    }

    @Test
    fun theQrWrapIsAPskWrapNotTheBaseWrapThatStaysInKeysJson() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        val psk = p.randomBytes(32)
        val ap = approved(a.service.approveDevicePsk(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID, psk))
        // The handed-back wrap is not the one listed in keys.json: the base-mode open of it fails.
        val listed = ap.connection.folder.keys.body.device(kidOf(p, b.service.devicePublicKey()))!!.wrap
        assertFalse(listed.enc.contentEquals(ap.wrapEnc))
        assertTrue(b.service.joinFromWrap(ap.wrapEnc, ap.wrapCt, ap.epoch) is DriveConnection.Error)
        // And the listed base wrap does not open as a PSK wrap with any psk.
        assertTrue(b.service.joinFromPsk(listed.enc, listed.ct, ap.epoch, psk) is DriveConnection.Error)
    }

    @Test
    fun aPskOfTheWrongLengthWritesNothingAtAll() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        val before = writeCount()
        for (n in listOf(0, 16, 31, 33)) {
            val problem = error(a.service.approveDevicePsk(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID, ByteArray(n)))
            assertEquals(DriveProblem.Kind.KEYS_UNREADABLE, problem.kind)
        }
        assertEquals(before, writeCount())
        assertEquals(1, ready(a.service.connect()).keys.body.devices.size)
    }

    // ---- revoke ----

    @Test
    fun revokeDropsTheDeviceChainsANewEpochAndShowsANewRecoveryKeyOnce() = runTest {
        val created = a.service.createFolder(true)
        val oldRecovery = created.recoveryKey!!
        val b = Rig(server, "Tablet")
        val ap = approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        ready(b.service.joinFromWrap(ap.wrapEnc, ap.wrapCt, ap.epoch))
        val rootId = a.state.value.rootId!!
        val victim = b.service.deviceKid()

        val revoked = a.service.revokeDevice(victim)
        val folder = ready(revoked.connection)
        assertNotNull(revoked.recoveryKey)
        assertEquals(2, folder.keys.epoch)
        assertEquals(1, folder.keys.body.chain.size)
        assertFalse(folder.keys.body.devices.any { it.kid.contentEquals(victim) })
        assertTrue(folder.keys.body.revokedEntry(victim) != null)
        assertEquals(2, folder.keys.body.revokedEntry(victim)!!.revokedAtEpoch)
        // The pin moved forward with the new epoch.
        assertEquals(2, a.pin(rootId)!!.epoch)
        // The revoked device cannot open the new list.
        assertTrue(b.service.connect() !is DriveConnection.Ready)
        // The new recovery key verifies; the old one no longer does (it is on the revoked list).
        assertTrue(a.service.verifyRecoveryKey(revoked.recoveryKey!!))
        assertFalse(a.service.verifyRecoveryKey(oldRecovery))
        assertFalse(revoked.recoveryKey!!.symbols == oldRecovery.symbols)
    }

    @Test
    fun aSecondRevokeChainsAThirdEpochAndOldBackupsStillOpen() = runTest {
        val first = a.created().folder
        val backup = (a.service.backUp(first, Payload.of(6_000, 2).source()) as BackupOutcome.Done).backup
        val b = Rig(server, "Tablet")
        val c = Rig(server, "Phone 2")
        approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        approved(a.service.approveDevice(c.service.devicePublicKey(), c.identity.name, DevicePlatform.ANDROID))
        val r1 = a.service.revokeDevice(b.service.deviceKid())
        val r2 = a.service.revokeDevice(c.service.deviceKid())
        val folder = ready(r2.connection)
        assertEquals(3, folder.keys.epoch)
        assertEquals(listOf(2, 3), folder.keys.body.chain.map { it.epoch })
        assertNotNull(r1.recoveryKey)
        assertNotNull(r2.recoveryKey)
        // Each revoke made its own recovery key.
        assertFalse(r1.recoveryKey!!.symbols == r2.recoveryKey!!.symbols)
        assertFalse(a.service.verifyRecoveryKey(r1.recoveryKey!!))
        assertTrue(a.service.verifyRecoveryKey(r2.recoveryKey!!))
        // The epoch-1 backup opens through the chain.
        val staging = RecordingStaging()
        assertTrue(a.service.imports.download(folder, backup, staging) is ImportDownload.Verified)
        assertEquals(6_000, staging.bytes().size)
    }

    @Test
    fun aRevokeOfADeviceThatIsNotListedChangesNothingAndShowsNoKey() = runTest {
        a.created()
        val keysId = a.state.value.keysId!!
        val before = server.contentOf(keysId)!!
        val out = a.service.revokeDevice(ByteArray(16) { 9 })
        assertTrue(out.connection is DriveConnection.Error)
        assertNull(out.recoveryKey)
        assertEquals(KeysException.Kind.NOT_LISTED, ((out.connection as DriveConnection.Error).problem).keysKind)
        assertArrayEquals(before, server.contentOf(keysId))
    }

    @Test
    fun aRevokedDeviceCannotBeListedAgain() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        approved(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        a.service.revokeDevice(b.service.deviceKid())
        val problem = error(a.service.approveDevice(b.service.devicePublicKey(), b.identity.name, DevicePlatform.ANDROID))
        assertEquals(KeysException.Kind.REVOKED, problem.keysKind)
        assertEquals(DriveProblem.Kind.DEVICE_REVOKED, problem.kind)
    }

    @Test
    fun aRevokeByADeviceWithNoPinWritesNothing() = runTest {
        a.created()
        val b = Rig(server, "Tablet")
        val before = writeCount()
        val out = b.service.revokeDevice(a.service.deviceKid())
        assertNull(out.recoveryKey)
        assertEquals(before, writeCount())
    }

    // ---- verifying a recovery key (docs/15 §10.4a) ----

    @Test
    fun verifyingTheRecoveryKeyChangesNothing() = runTest {
        val out = a.service.createFolder(true)
        val folder = ready(out.connection)
        val keysBefore = server.contentOf(folder.keysId)!!
        val pinBefore = a.pin(folder.rootId)
        val before = writeCount()

        assertTrue(a.service.verifyRecoveryKey(out.recoveryKey!!))
        assertEquals(before, writeCount())
        assertArrayEquals(keysBefore, server.contentOf(folder.keysId))
        assertFalse(a.service.verifyRecoveryKey(RecoveryKey.generate(p)))
        assertEquals(before, writeCount())
        assertArrayEquals(keysBefore, server.contentOf(folder.keysId))
        assertEquals(pinBefore, a.pin(folder.rootId))
    }

    @Test
    fun verifyingIsFalseWhenThereIsNoPinYetAndMakesNone() = runTest {
        val out = a.service.createFolder(true)
        val b = Rig(server, "Tablet")
        assertFalse(b.service.verifyRecoveryKey(out.recoveryKey!!))
        assertNull(b.pin(a.state.value.rootId!!))
        assertTrue(b.trust.keys.values.all { it.value == null })
    }

    @Test
    fun verifyingIsFalseWhenTheFolderIsMissingOrHasNoKeyList() = runTest {
        val out = a.service.createFolder(true)
        val rootId = a.state.value.rootId!!
        val keysId = a.state.value.keysId!!
        server.trashByHand(keysId)
        assertFalse(a.service.verifyRecoveryKey(out.recoveryKey!!))
        server.trashByHand(rootId)
        assertFalse(a.service.verifyRecoveryKey(out.recoveryKey!!))
        // A Drive with no Doorprints at all.
        assertFalse(Rig(FakeDriveServer(), "Empty").service.verifyRecoveryKey(out.recoveryKey!!))
    }

    @Test
    fun verifyingAgainstANewerListAnotherDeviceWroteLeavesThisDevicesPinWhereItWas() = runTest {
        val out = a.service.createFolder(true)
        val folder = a.ready()
        val pinBefore = a.pin(folder.rootId)
        val b = Rig(server, "Tablet")
        assertTrue(b.service.openWithRecoveryKey(out.recoveryKey!!) is DriveConnection.Ready)
        // keys.json is now at a newer revision than a's pin has seen.

        assertTrue(a.service.verifyRecoveryKey(out.recoveryKey!!))
        val pinAfter = a.pin(folder.rootId)
        assertNotNull(pinBefore)
        assertEquals(pinBefore!!.revision, pinAfter!!.revision)
        assertEquals(pinBefore, pinAfter)
        // A wrong key against the same newer list is still refused.
        assertFalse(a.service.verifyRecoveryKey(RecoveryKey.generate(p)))
        assertEquals(pinBefore, a.pin(folder.rootId))
    }

    @Test
    fun aNormalOpenDoesMoveThePinSoTheReadOnlyTestIsMeaningful() = runTest {
        val out = a.service.createFolder(true)
        val folder = a.ready()
        val pinBefore = a.pin(folder.rootId)!!
        val b = Rig(server, "Tablet")
        b.service.openWithRecoveryKey(out.recoveryKey!!)
        a.ready()
        assertTrue(a.pin(folder.rootId)!!.revision > pinBefore.revision)
    }
}
