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

import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.QR_PSK_LEN
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.backup.DriveProblem
import app.doorprints.drive.connect.EnrolmentApproval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The enrolment adapter against the real backup service on the in-memory Drive (S4b-BL-117, -126, -127); the other adapters' tests, which need Android, stay in :app (`DriveAdaptersTest`). */
class DriveEnrolmentAdapterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun anApproveThenAJoinAcrossTwoServicesWorksEndToEnd() = runBlocking {
        val server = FakeDriveServer()
        val a = WiringRig(server, tmp.newFolder("a"), "Pixel 8")
        val b = WiringRig(server, tmp.newFolder("b"), "Tablet")
        a.created()
        assertTrue(b.service.connect() is DriveConnection.NeedsEnrolment)

        val approved = a.enrolment.approveDevice(b.service.devicePublicKey(), "Tablet", DevicePlatform.ANDROID)
        approved as EnrolmentApproval.Approved
        assertEquals(2, approved.connection.folder.keys.body.devices.size)
        assertEquals(1, approved.epoch)

        val joined = b.enrolment.joinFromWrap(approved.wrapEnc, approved.wrapCt, approved.epoch)
        assertTrue("got $joined", joined is DriveConnection.Ready)
        assertTrue(b.service.connect() is DriveConnection.Ready)
    }

    @Test
    fun theQrPskPathWorksAcrossTwoServices() = runBlocking {
        val server = FakeDriveServer()
        val a = WiringRig(server, tmp.newFolder("a"), "Pixel 8")
        val b = WiringRig(server, tmp.newFolder("b"), "Tablet")
        a.created()
        val psk = b.p.randomBytes(QR_PSK_LEN)
        val approved = a.enrolment.approveDevicePsk(b.service.devicePublicKey(), "Tablet", DevicePlatform.ANDROID, psk)
        approved as EnrolmentApproval.Approved
        // The wrong secret opens nothing; the right one pins.
        val wrong = b.enrolment.joinFromPsk(approved.wrapEnc, approved.wrapCt, approved.epoch, b.p.randomBytes(QR_PSK_LEN))
        assertTrue(wrong is DriveConnection.Error)
        assertTrue(b.enrolment.joinFromPsk(approved.wrapEnc, approved.wrapCt, approved.epoch, psk) is DriveConnection.Ready)
    }

    @Test
    fun aRefusedApprovalKeepsItsProblem() = runBlocking {
        val server = FakeDriveServer()
        // A device that never connected has no pin and cannot approve anything.
        val lone = WiringRig(server, tmp.newFolder("lone"), "Lone")
        val out = lone.enrolment.approveDevice(lone.service.devicePublicKey(), "X", DevicePlatform.ANDROID)
        assertTrue("got $out", out is EnrolmentApproval.Failed)
        assertNotNull((out as EnrolmentApproval.Failed).problem)
    }

    @Test
    fun aRevokeGivesANewRecoveryKeyAndTheRevokedDeviceLosesTheFolder() = runBlocking {
        val server = FakeDriveServer()
        val a = WiringRig(server, tmp.newFolder("a"), "Pixel 8")
        val b = WiringRig(server, tmp.newFolder("b"), "Tablet")
        val created = a.service.createFolder(withRecoveryKey = true)
        val oldKey = created.recoveryKey!!
        val approved = a.enrolment.approveDevice(b.service.devicePublicKey(), "Tablet", DevicePlatform.ANDROID) as EnrolmentApproval.Approved
        b.enrolment.joinFromWrap(approved.wrapEnc, approved.wrapCt, approved.epoch)

        val bKid = app.doorprints.crypto.kidOf(a.p, b.identity.key.publicKey)
        val revoke = a.enrolment.revokeDevice(bKid)
        assertTrue(revoke.connection is DriveConnection.Ready)
        val fresh = revoke.recoveryKey
        assertNotNull("a revoke makes a new recovery key", fresh)
        assertEquals(2, (revoke.connection as DriveConnection.Ready).folder.keys.body.epoch)
        assertFalse(oldKey.display == fresh!!.display)
        assertTrue("the new key opens the folder", a.service.verifyRecoveryKey(fresh))
        assertFalse("the old key no longer does", a.service.verifyRecoveryKey(oldKey))

        // The revoked device no longer opens the folder.
        val after = b.service.connect()
        assertFalse("got $after", after is DriveConnection.Ready)
    }

    @Test
    fun aProblemFromTheServiceReachesTheControllerUnchanged() = runBlocking {
        val server = FakeDriveServer()
        val a = WiringRig(server, tmp.newFolder("a"), "Pixel 8")
        a.created()
        // An off-curve public key is refused by the service; the adapter passes its problem on.
        val out = a.enrolment.approveDevice(ByteArray(65), "Bad", DevicePlatform.ANDROID)
        assertTrue(out is EnrolmentApproval.Failed)
        assertTrue((out as EnrolmentApproval.Failed).problem is DriveProblem)
    }
}
