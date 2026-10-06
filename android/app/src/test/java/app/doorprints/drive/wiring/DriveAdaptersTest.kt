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

import android.net.ConnectivityManager
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.QR_PSK_LEN
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.auth.AndroidDriveTokenProvider
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.DRIVE_FILE_SCOPE
import app.doorprints.drive.auth.GoogleAuthorizer
import app.doorprints.drive.auth.PendingConsent
import app.doorprints.drive.auth.SignInException
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.backup.DriveProblem
import app.doorprints.drive.connect.EnrolmentApproval
import app.doorprints.drive.connect.SignInResult
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.device.OperationProver
import app.doorprints.drive.device.ProofOutcome
import app.doorprints.drive.photo.Metering
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The thin adapters between the controller's seams and Android (S4b-BL-117, -126, -127): each one only maps. */
class DriveAdaptersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---- sign-in ----

    private class Google : GoogleAuthorizer {
        val answers = ArrayDeque<AuthorizerResult>()
        var asked = 0
        val revoked = mutableListOf<String?>()
        override suspend fun authorize(scopes: List<String>): AuthorizerResult {
            asked++
            return answers.removeFirst()
        }
        override suspend fun clearToken(accessToken: String) = Unit
        override suspend fun revoke(accessToken: String?, scopes: List<String>) {
            revoked += accessToken
        }
    }

    private class FakeConsent : PendingConsent

    private fun granted(token: String = "tok") = AuthorizerResult.Granted(token, setOf(DRIVE_FILE_SCOPE))

    private fun signIn(google: Google, canConnect: () -> Boolean = { true }) =
        TokenDriveSignIn(AndroidDriveTokenProvider(google, { null }), canConnect)

    @Test
    fun aGrantSignsIn() = runBlocking {
        val google = Google().apply { answers += granted() }
        assertEquals(SignInResult.SIGNED_IN, signIn(google).signIn())
    }

    @Test
    fun aClosedPromptIsNotADenial() = runBlocking {
        val google = Google().apply { answers += AuthorizerResult.Cancelled }
        assertEquals(SignInResult.CANCELLED, signIn(google).signIn())
    }

    @Test
    fun aUntickedDrivePermissionIsADenial() = runBlocking {
        val google = Google().apply { answers += AuthorizerResult.Granted("tok", emptySet()) }
        assertEquals(SignInResult.DENIED, signIn(google).signIn())
    }

    @Test
    fun everyFailureKindHasItsOwnResult() {
        assertEquals(SignInResult.DENIED, TokenDriveSignIn.resultOf(SignInException.Kind.DENIED))
        assertEquals(SignInResult.CANCELLED, TokenDriveSignIn.resultOf(SignInException.Kind.CANCELLED))
        assertEquals(SignInResult.UNAVAILABLE, TokenDriveSignIn.resultOf(SignInException.Kind.CONSENT_REQUIRED))
        assertEquals(SignInResult.OFFLINE, TokenDriveSignIn.resultOf(SignInException.Kind.OFFLINE))
        assertEquals(SignInResult.UNAVAILABLE, TokenDriveSignIn.resultOf(SignInException.Kind.UNAVAILABLE))
    }

    @Test
    fun offlineAndMissingPlayServicesAreReported() = runBlocking {
        val offline = Google().apply { answers += AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE) }
        assertEquals(SignInResult.OFFLINE, signIn(offline).signIn())
        val noPlay = Google().apply { answers += AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE) }
        assertEquals(SignInResult.UNAVAILABLE, signIn(noPlay).signIn())
    }

    @Test
    fun googleWantingAScreenWithNobodyThereIsUnavailable() = runBlocking {
        // A background run has no consent resolver: the provider says CONSENT_REQUIRED, and nothing is asked of the person.
        val google = Google().apply { answers += AuthorizerResult.NeedsConsent(FakeConsent()) }
        assertEquals(SignInResult.UNAVAILABLE, signIn(google).signIn())
    }

    @Test
    fun withoutAScreenLockNothingIsAskedOfGoogle() = runBlocking {
        val google = Google().apply { answers += granted() }
        assertEquals(SignInResult.UNAVAILABLE, signIn(google, canConnect = { false }).signIn())
        assertEquals(0, google.asked)
    }

    @Test
    fun signingOutForgetsTheTokenSoTheNextSignInAsksGoogleAgain() = runBlocking {
        val google = Google().apply { answers += granted("one"); answers += granted("two") }
        val adapter = signIn(google)
        adapter.signIn()
        adapter.signIn()
        assertEquals("a held token is reused", 1, google.asked)
        adapter.signOut()
        adapter.signIn()
        assertEquals(2, google.asked)
    }

    @Test
    fun revokingAsksGoogleToWithdrawTheGrant() = runBlocking {
        val google = Google().apply { answers += granted("one") }
        val adapter = signIn(google)
        adapter.signIn()
        adapter.revokeAccess()
        assertEquals(listOf<String?>("one"), google.revoked)
    }

    // ---- the device check ----

    private class Prover(var next: ProofOutcome) : OperationProver {
        val asked = mutableListOf<Pair<String, DeletionLevel>>()
        override suspend fun prove(operationId: String, level: DeletionLevel, reason: String, now: () -> Long): ProofOutcome {
            asked += operationId to level
            return next
        }
    }

    private fun auth(p: Prover, lock: Boolean = true) = ProverDeviceAuth(p, { lock }, { 1_000L })

    @Test
    fun theDeviceCheckResultsMapOneToOne() = runBlocking {
        val cases = listOf(
            ProofOutcome.Proved(1L, "0".repeat(64)) to AuthResult.SUCCESS,
            ProofOutcome.Denied to AuthResult.LOCKED_OUT,
            ProofOutcome.Cancelled to AuthResult.CANCELLED,
            ProofOutcome.TimedOut to AuthResult.CANCELLED,
            ProofOutcome.NoLock to AuthResult.LOCK_NOT_SET,
            ProofOutcome.Unavailable to AuthResult.NOT_AVAILABLE,
            ProofOutcome.Failed to AuthResult.FAILED,
        )
        for ((outcome, result) in cases) assertEquals(outcome.toString(), result, auth(Prover(outcome)).authenticate("why", DeleteLevel.L2))
    }

    @Test
    fun levelsAreHandedToTheProverAndEveryAskIsItsOwnOperation() = runBlocking {
        val prover = Prover(ProofOutcome.Proved(1L, "0".repeat(64)))
        val a = auth(prover)
        a.authenticate("why", DeleteLevel.L2)
        a.authenticate("why", DeleteLevel.L3)
        assertEquals(listOf(DeletionLevel.L2, DeletionLevel.L3), prover.asked.map { it.second })
        assertEquals("a proof for one ask is never good for another", 2, prover.asked.map { it.first }.toSet().size)
        assertTrue(prover.asked.all { it.first.startsWith(ProverDeviceAuth.OPERATION_PREFIX) })
    }

    @Test
    fun theBoundOperationIsWhatTheProverSignsAndItsProofIsKeptOnce() = runBlocking {
        val prover = Prover(ProofOutcome.Proved(7L, "1".repeat(64)))
        val a = auth(prover)
        a.bindNext("del-plan")
        assertEquals(AuthResult.SUCCESS, a.authenticate("why", DeleteLevel.L2))
        assertEquals("del-plan", prover.asked.single().first)
        val p = a.takeProof()!!
        assertEquals(7L, p.issuedAtMs)
        assertEquals("1".repeat(64), p.proof)
        assertEquals(null, a.takeProof())
    }

    @Test
    fun bindingAgainOrARefusalDropsTheEarlierProof() = runBlocking {
        val prover = Prover(ProofOutcome.Proved(7L, "1".repeat(64)))
        val a = auth(prover)
        a.authenticate("why", DeleteLevel.L2)
        a.bindNext("other")
        assertEquals(null, a.takeProof())
        prover.next = ProofOutcome.Cancelled
        assertEquals(AuthResult.CANCELLED, a.authenticate("why", DeleteLevel.L2))
        assertEquals(null, a.takeProof())
    }

    @Test
    fun aPassWhoseProofIsNotSixtyFourHexDigitsIsNoPass() = runBlocking {
        for (bad in listOf("", "0".repeat(63), "G".repeat(64))) {
            val a = auth(Prover(ProofOutcome.Proved(1L, bad)))
            assertEquals(bad, AuthResult.FAILED, a.authenticate("why", DeleteLevel.L2))
            assertEquals(null, a.takeProof())
        }
    }

    @Test
    fun levelOneAsksNothingAndNeverPasses() = runBlocking {
        val prover = Prover(ProofOutcome.Proved(1L, "0".repeat(64)))
        assertEquals(AuthResult.FAILED, auth(prover).authenticate("why", DeleteLevel.L1))
        assertTrue(prover.asked.isEmpty())
    }

    @Test
    fun theLockQuestionIsTheKeyguardsNotThePromptSs() {
        assertTrue(auth(Prover(ProofOutcome.Failed), lock = true).isDeviceLockEnabled())
        assertFalse(auth(Prover(ProofOutcome.Failed), lock = false).isDeviceLockEnabled())
    }

    // ---- the network ----

    @Test
    fun unmeteredWifiAllowsPhotos() {
        val c = ConnectivityNetworkState.conditions(internet = true, validated = true, notMetered = true, notRoaming = true, restrictBackground = 1)
        assertTrue(c.online)
        assertEquals(Metering.UNMETERED, c.metering)
        assertFalse(c.roaming)
        assertFalse(c.dataSaver)
    }

    @Test
    fun mobileDataIsMeteredAndRoamingAndDataSaverAreSeen() {
        val c = ConnectivityNetworkState.conditions(true, true, notMetered = false, notRoaming = false, restrictBackground = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        assertEquals(Metering.METERED, c.metering)
        assertTrue(c.roaming)
        assertTrue(c.dataSaver)
    }

    @Test
    fun aNetworkWithoutValidatedInternetIsNotOnline() {
        assertFalse(ConnectivityNetworkState.conditions(internet = true, validated = false, notMetered = true, notRoaming = true, restrictBackground = 1).online)
        assertFalse(ConnectivityNetworkState.conditions(internet = false, validated = true, notMetered = true, notRoaming = true, restrictBackground = 1).online)
    }

    @Test
    fun dataSaverCountsOnlyWhenItIsOnForTheApp() {
        for (status in listOf(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED, ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED)) {
            assertFalse(ConnectivityNetworkState.conditions(true, true, true, true, status).dataSaver)
        }
    }

    // ---- enrolment, against the real backup service on the in-memory Drive ----

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
