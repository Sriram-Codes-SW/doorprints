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

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.RecoveryKey
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.backup.DriveProblem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Approve, join and revoke: the controller's routing to [DeviceEnrolment] (the web's `approveJoinedDevice*`, `joinFrom*`, `revokeListedDevice`). */
class DriveConnectEnrolmentTest {
    private val server = FakeDriveServer()
    private val a = Phone(server, "Pixel 8")
    private val b = Phone(server, "Galaxy")
    private val newKey = ByteArray(65) { if (it == 0) 4 else it.toByte() }

    private suspend fun ready(): DriveConnection.Ready {
        a.c.createFolder()
        a.c.confirmRecoveryKeySaved()
        return a.rig.ready().let { DriveConnection.Ready(it) }
    }

    @Test
    fun approvingNeedsTheDeviceCheckThenRoutesTheExactKeyAndHandsBackTheWrapAsText() = runTest {
        val r = ready()
        a.enrolment.approval = EnrolmentApproval.Approved(r, byteArrayOf(1, 2, 3), byteArrayOf(9, 8), 4)
        val out = a.c.approveJoinedDevice(newKey, "Tablet", DevicePlatform.ANDROID, "Confirm it's you").ok()
        assertEquals(EnrolmentWrap(Bytes.b64(byteArrayOf(1, 2, 3)), Bytes.b64(byteArrayOf(9, 8)), 4), out)
        assertEquals(listOf("approve"), a.enrolment.calls)
        assertContentEquals(newKey, a.enrolment.lastKey)
        assertEquals("Tablet", a.enrolment.lastName)
        assertEquals(DevicePlatform.ANDROID, a.enrolment.lastPlatform)
        assertEquals(1, a.deviceAuth.asks)
        assertEquals(DeleteLevel.L2, a.deviceAuth.lastLevel)
        assertEquals(ConnectState.READY, a.c.state.value)
    }

    @Test
    fun theQrEnrolmentPassesThePskAndNeverTheBaseWrapPath() = runTest {
        val r = ready()
        a.enrolment.approval = EnrolmentApproval.Approved(r, byteArrayOf(5), byteArrayOf(6), 1)
        val psk = ByteArray(32) { 7 }
        a.c.approveJoinedDevicePsk(newKey, "Tablet", DevicePlatform.IOS, psk, "x").ok()
        assertEquals(listOf("approvePsk"), a.enrolment.calls)
        assertContentEquals(psk, a.enrolment.lastPsk)
        assertEquals(DevicePlatform.IOS, a.enrolment.lastPlatform)
    }

    @Test
    fun aRefusedOrCancelledCheckWritesNothingAndNeverReachesTheEnrolment() = runTest {
        ready()
        a.deviceAuth.next = AuthResult.CANCELLED
        assertEquals(DriveReason.AUTH_CANCELLED, a.c.approveJoinedDevice(newKey, "T", DevicePlatform.ANDROID, "x").reason())
        assertEquals(DriveReason.AUTH_CANCELLED, a.c.approveJoinedDevicePsk(newKey, "T", DevicePlatform.ANDROID, ByteArray(32), "x").reason())
        assertEquals(DriveReason.AUTH_CANCELLED, a.c.revokeListedDevice("aa", "x").reason())
        assertTrue(a.enrolment.calls.isEmpty())
    }

    @Test
    fun anApprovalThatFailsIsTypedAndLeavesTheFolderOpen() = runTest {
        ready()
        a.enrolment.approval = EnrolmentApproval.Failed(DriveProblem(DriveProblem.Kind.KEYS_ROLLED_BACK))
        assertEquals(DriveReason.KEYS_ROLLED_BACK, a.c.approveJoinedDevice(newKey, "T", DevicePlatform.ANDROID, "x").reason())
        assertTrue(a.c.isReady)
        assertEquals(ConnectState.READY, a.c.state.value)
    }

    @Test
    fun approvingFromTheKeyScreenDoesNotSkipTheKeyScreen() = runTest {
        a.c.createFolder()
        val r = DriveConnection.Ready(a.rig.ready())
        a.enrolment.approval = EnrolmentApproval.Approved(r, byteArrayOf(1), byteArrayOf(2), 1)
        a.c.approveJoinedDevice(newKey, "T", DevicePlatform.ANDROID, "x").ok()
        assertEquals(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, a.c.state.value)
    }

    @Test
    fun joiningFromTheWrapPinsAndGoesReadyOrKeepsTheJoinStep() = runTest {
        a.c.createFolder()
        b.c.connect()
        assertEquals(ConnectState.NEEDS_ENROLMENT, b.c.state.value)
        b.enrolment.join = DriveConnection.Error(DriveProblem(DriveProblem.Kind.WRONG_RECOVERY_KEY))
        val wrong = b.c.joinFromWrap(Bytes.b64(byteArrayOf(1)), Bytes.b64(byteArrayOf(2)), 1)
        assertEquals(ConnectResult(ConnectState.NEEDS_ENROLMENT, error = DriveReason.JOIN_WRONG_KEY), wrong)
        b.enrolment.join = DriveConnection.Ready(a.rig.ready())
        val ok = b.c.joinFromWrap(Bytes.b64(byteArrayOf(1)), Bytes.b64(byteArrayOf(2)), 7)
        assertEquals(ConnectResult(ConnectState.READY), ok)
        assertEquals(7, b.enrolment.lastEpoch)
        assertTrue(b.c.isReady)
    }

    @Test
    fun aMessageThatIsNotBase64NeverReachesTheEnrolment() = runTest {
        a.c.createFolder()
        b.c.connect()
        val r = b.c.joinFromWrap("!!not base64!!", Bytes.b64(byteArrayOf(2)), 1)
        assertEquals(ConnectResult(ConnectState.NEEDS_ENROLMENT, error = DriveReason.ENROL_BAD_MESSAGE), r)
        assertEquals(DriveReason.ENROL_BAD_MESSAGE, b.c.joinFromPsk(Bytes.b64(byteArrayOf(1)), "***", 1, ByteArray(32)).error)
        assertTrue(b.enrolment.calls.isEmpty())
    }

    @Test
    fun joiningFromThePskPassesThePsk() = runTest {
        a.c.createFolder()
        b.c.connect()
        b.enrolment.join = DriveConnection.Ready(a.rig.ready())
        val psk = ByteArray(32) { 3 }
        assertEquals(ConnectState.READY, b.c.joinFromPsk(Bytes.b64(byteArrayOf(1)), Bytes.b64(byteArrayOf(2)), 2, psk).state)
        assertEquals(listOf("joinPsk"), b.enrolment.calls)
        assertContentEquals(psk, b.enrolment.lastPsk)
    }

    @Test
    fun aJoinThatThrowsIsTheGenericErrorNotItsMessage() = runTest {
        a.c.createFolder()
        b.c.connect()
        val throwing = object : DeviceEnrolment by b.enrolment {
            override suspend fun joinFromWrap(enc: ByteArray, ct: ByteArray, epoch: Int): DriveConnection = error("hpke secret detail")
        }
        val c = DriveConnectController(
            b.rig.service, b.rig.drive, b.p, b.rig.identity, b.rig.trust, throwing, b.deletion, b.store, b.authorizer, b.rigs, b.network, b.prefs,
            server.clock::now, true, null, b.signIn, null,
        )
        val r = c.joinFromWrap(Bytes.b64(byteArrayOf(1)), Bytes.b64(byteArrayOf(2)), 1)
        assertEquals(ConnectResult(ConnectState.ERROR, error = DriveReason.FAILED), r)
    }

    @Test
    fun revokingShowsTheNewRecoveryKeyOnceAndStaysReady() = runTest {
        val r = ready()
        val key = RecoveryKey.generate(a.p)
        a.enrolment.revoke = EnrolmentRevoke(r, key)
        val out = a.c.revokeListedDevice("0a0b", "Confirm").ok()
        assertEquals(key.display, out.recoveryKey)
        assertFalse(out.toString().contains(key.display))
        assertContentEquals(byteArrayOf(10, 11), a.enrolment.lastKey)
        assertEquals(DeleteLevel.L2, a.deviceAuth.lastLevel)
        assertEquals(ConnectState.READY, a.c.state.value)
    }

    private fun assertFalse(b: Boolean) = kotlin.test.assertFalse(b)

    @Test
    fun aRevokeTheCoreRefusesKeepsTheOldFolderOpenAndSaysWhy() = runTest {
        ready()
        a.enrolment.revoke = EnrolmentRevoke(DriveConnection.Error(DriveProblem(DriveProblem.Kind.KEYS_ROLLED_BACK)), null)
        assertEquals(DriveReason.KEYS_ROLLED_BACK, a.c.revokeListedDevice("0a", "x").reason())
        assertTrue(a.c.isReady)
        assertTrue(a.c.listBackups() is Outcome.Ok)
        a.enrolment.revoke = EnrolmentRevoke(DriveConnection.NoFolder, null)
        assertEquals(DriveReason.FAILED, a.c.revokeListedDevice("0a", "x").reason())
    }

    @Test
    fun aRevokeThatYieldsNoNewRecoveryKeyIsNotReportedAsDone() = runTest {
        val r = ready()
        a.enrolment.revoke = EnrolmentRevoke(r, null)
        assertEquals(DriveReason.FAILED, a.c.revokeListedDevice("0a", "x").reason())
    }

    @Test
    fun aKidThatIsNotHexNeverReachesTheEnrolment() = runTest {
        ready()
        assertEquals(DriveReason.FAILED, a.c.revokeListedDevice("zz-not-hex", "x").reason())
        assertTrue(a.enrolment.calls.isEmpty())
    }

    @Test
    fun theDevicePublicKeyIsACopyOfThisDevicesKey() {
        val k = a.c.devicePublicKey()
        assertContentEquals(a.rig.identity.key.publicKey, k)
        k[0] = 0
        assertEquals(4, a.c.devicePublicKey()[0].toInt())
    }
}
