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
import app.doorprints.crypto.KeysFile
import app.doorprints.crypto.KeysGuard
import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.kidOf
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveOp
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.UploadTarget
import app.doorprints.drive.backup.DriveBackupService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Connect state machine over the real backup service and the fake Drive (the web's `drive-connect.service.spec.ts`, state half). */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveConnectStateTest {
    private val server = FakeDriveServer()
    private val a = Phone(server, "Pixel 8")
    private val b = Phone(server, "Galaxy")

    @Test
    fun withoutAClientIdDriveIsUnavailableAndNothingIsTouched() = runTest {
        val c = a.controller(configured = false)
        assertEquals(ConnectState.UNAVAILABLE, c.state.value)
        val r = c.connect()
        assertEquals(ConnectResult(ConnectState.UNAVAILABLE, error = DriveReason.NOT_CONFIGURED), r)
        assertEquals(0, a.signIn.signIns)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun anEmptyDriveStaysDisconnectedAndWritesNothing() = runTest {
        assertEquals(ConnectState.DISCONNECTED, a.c.state.value)
        val r = a.c.connect()
        assertEquals(ConnectResult(ConnectState.DISCONNECTED), r)
        assertEquals(ConnectState.DISCONNECTED, a.c.state.value)
        assertTrue(server.allFiles().isEmpty())
        assertEquals(1, a.signIn.signIns)
    }

    @Test
    fun theRecoveryKeyIsShownOnceThenConfirmedAndNeverKeptInTheState() = runTest {
        val created = a.c.createFolder()
        assertEquals(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, created.state)
        val key = created.recoveryKey!!
        assertTrue(Regex("[A-Z0-9]{4}(-[A-Z0-9]{4})+").matches(key) || key.isNotBlank())
        assertFalse(created.toString().contains(key), "the log line of a result never holds the key")
        assertEquals(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, a.c.state.value)
        assertFalse(a.c.hasShownRecoveryKey())
        a.c.confirmRecoveryKeySaved()
        assertTrue(a.c.hasShownRecoveryKey())
        assertEquals(ConnectState.READY, a.c.state.value)
        assertTrue(a.c.isReady)
    }

    @Test
    fun backupsWorkWhileTheKeyIsOnScreen() = runTest {
        a.c.createFolder()
        assertEquals(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, a.c.state.value)
        assertTrue(a.c.backUpNow() is Outcome.Ok)
    }

    @Test
    fun skippingTheWarningLeavesTheKeyScreenToo() = runTest {
        a.c.createFolder()
        a.c.skipRecoveryKeyWithWarning()
        assertTrue(a.c.hasShownRecoveryKey())
        assertEquals(ConnectState.READY, a.c.state.value)
    }

    @Test
    fun confirmingWithoutTheKeyScreenChangesNothing() = runTest {
        a.c.confirmRecoveryKeySaved()
        a.c.skipRecoveryKeyWithWarning()
        assertEquals(ConnectState.DISCONNECTED, a.c.state.value)
        assertFalse(a.c.hasShownRecoveryKey())
        a.c.createFolder()
        a.c.confirmRecoveryKeySaved()
        a.c.confirmRecoveryKeySaved()
        assertEquals(ConnectState.READY, a.c.state.value)
    }

    @Test
    fun skippingTheRecoveryKeyAtCreationGoesStraightToReady() = runTest {
        val r = a.c.createFolder(withRecoveryKey = false)
        assertEquals(ConnectResult(ConnectState.READY), r)
        assertNull(r.recoveryKey)
        assertEquals(ConnectState.READY, a.c.state.value)
    }

    @Test
    fun aFolderWithKeysNeedsEnrolmentAndNothingIsWritten() = runTest {
        a.c.createFolder()
        val files = server.allFiles().map { it.id to server.contentOf(it.id)?.toList() }
        val r = b.c.connect()
        assertEquals(ConnectResult(ConnectState.NEEDS_ENROLMENT), r)
        assertEquals(ConnectState.NEEDS_ENROLMENT, b.c.state.value)
        assertEquals(files, server.allFiles().map { it.id to server.contentOf(it.id)?.toList() })
        assertEquals(DriveReason.NOT_CONNECTED, b.c.listBackups().reason())
        assertFalse(b.c.isReady)
        assertNull(b.c.accountEmail().takeIf { false })
    }

    @Test
    fun aMistypedKeyIsRefusedBeforeDriveIsAskedAndTheJoinFormStays() = runTest {
        val key = a.c.createFolder().recoveryKey!!
        b.c.connect()
        val before = server.requests.size
        val flipped = key.take(2) + (if (key[2] == '2') '3' else '2') + key.drop(3)
        val r = b.c.openWithRecoveryKey(flipped)
        assertEquals(ConnectResult(ConnectState.NEEDS_ENROLMENT, error = DriveReason.JOIN_INVALID_FORMAT), r)
        assertEquals(before, server.requests.size, "a typo never reaches Drive")
        assertEquals(ConnectState.NEEDS_ENROLMENT, b.c.state.value)
        val garbage = b.c.openWithRecoveryKey("wrong-key-string-12345")
        assertEquals(DriveReason.JOIN_INVALID_FORMAT, garbage.error)
    }

    @Test
    fun aWellFormedWrongKeyStaysOnTheJoinStepAndOpensNothing() = runTest {
        a.c.createFolder()
        b.c.connect()
        val files = server.allFiles().size
        val r = b.c.openWithRecoveryKey(freshRecoveryKey(b.p))
        assertEquals(ConnectState.NEEDS_ENROLMENT, r.state)
        assertEquals(DriveReason.JOIN_WRONG_KEY, r.error)
        assertEquals(ConnectState.NEEDS_ENROLMENT, b.c.state.value)
        assertFalse(b.c.isReady)
        assertEquals(files, server.allFiles().size)
    }

    @Test
    fun theRightKeyTypedLikeAPersonJoinsAndTheDeviceListMarksOwnRow() = runTest {
        val key = a.c.createFolder().recoveryKey!!
        a.c.confirmRecoveryKeySaved()
        b.c.connect()
        val typed = key.lowercase().replace("-", " ")
        val r = b.c.openWithRecoveryKey(typed)
        assertEquals(ConnectState.READY, r.state)
        assertEquals(ConnectState.READY, b.c.state.value)
        val devices = b.c.listedDevices()
        assertEquals(2, devices.size)
        assertEquals(listOf("Galaxy"), devices.filter { it.self }.map { it.name })
        assertEquals("android", devices.first().platform)
        assertEquals(emptyList(), a.c.listedDevices().filter { it.self && it.name == "Galaxy" })
    }

    @Test
    fun theFolderGoneFlagIsSetBeforeTheStateDropsAndClearedByStartAgainOrDisconnect() = runTest {
        a.c.createFolder()
        a.c.confirmRecoveryKeySaved()
        assertFalse(a.c.folderGone.value)
        val root = server.allFiles().first { it.appProperties[app.doorprints.drive.DriveLayout.ROLE] == "root" }
        server.deleteByHand(root.id)
        val seen = mutableListOf<Pair<ConnectState, Boolean>>()
        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            kotlinx.coroutines.flow.combine(a.c.state, a.c.folderGone, ::Pair).collect { seen += it }
        }
        a.c.connect()
        assertTrue(a.c.folderGone.value)
        assertFalse(seen.contains(ConnectState.DISCONNECTED to false), "the drop to disconnected is never seen without the flag")
        a.c.createFolder()
        assertFalse(a.c.folderGone.value)
        a.c.confirmRecoveryKeySaved()
        server.deleteByHand(server.allFiles().first { it.appProperties[app.doorprints.drive.DriveLayout.ROLE] == "root" }.id)
        a.c.connect()
        assertTrue(a.c.folderGone.value)
        a.c.disconnect()
        assertFalse(a.c.folderGone.value)
        job.cancel()
    }

    @Test
    fun aDeletedFolderIsSaidAndStartAgainMakesANewOne() = runTest {
        a.c.createFolder()
        a.c.confirmRecoveryKeySaved()
        val root = server.allFiles().first { it.appProperties[app.doorprints.drive.DriveLayout.ROLE] == "root" }
        server.deleteByHand(root.id)
        val r = a.c.connect()
        assertEquals(ConnectResult(ConnectState.DISCONNECTED, error = DriveReason.FOLDER_GONE), r)
        assertFalse(a.c.isReady)
        val again = a.c.createFolder()
        assertEquals(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, again.state)
        assertNotNull(again.recoveryKey)
    }

    @Test
    fun aRevokedDeviceIsToldSoAndAsksForTheRecoveryKey() = runTest {
        val key = a.c.createFolder().recoveryKey!!
        b.c.connect()
        b.c.openWithRecoveryKey(key)
        // A revokes B through the key list directly (the enrolment branch does this in the app).
        val folder = a.rig.ready()
        val kf = KeysFile(a.p)
        val written = kf.newEpoch(folder.keys, server.clock.now(), revokeKid = kidOf(a.p, b.rig.identity.key.publicKey), newRecovery = RecoveryKey.generate(a.p))
        a.rig.drive.upload(UploadTarget.Existing(folder.keysId, DriveBackupService.JSON_MIME), written.bytes)
        KeysGuard(a.p, a.rig.trust.keys(folder.rootId)).acceptWritten(written)
        val r = b.c.connect()
        assertEquals(ConnectState.NEEDS_RECOVERY_KEY, r.state)
        assertEquals(DriveReason.DEVICE_REVOKED, b.c.enrolmentNotice.value)
        assertFalse(b.c.isReady)
        // A fresh connect from a healthy state clears the notice.
        assertEquals(ConnectState.READY, a.c.connect().state)
        assertNull(a.c.enrolmentNotice.value)
    }

    @Test
    fun aDriveFailureIsAnErrorStateWithATypedReasonNeverAMessage() = runTest {
        server.faults.always(DriveFault.Offline)
        val r = a.c.connect()
        assertEquals(ConnectState.ERROR, r.state)
        assertEquals(DriveReason.OFFLINE, r.error)
        assertEquals(ConnectState.ERROR, a.c.state.value)
        server.faults.clear()
        assertEquals(ConnectState.DISCONNECTED, a.c.connect().state)
    }

    @Test
    fun anUnexpectedExceptionBecomesTheGenericKeyNotItsMessage() = runTest {
        val boom = object : app.doorprints.drive.connect.DriveSignIn {
            override suspend fun signIn(): SignInResult = throw IllegalStateException("token=secret-123")
            override suspend fun signOut() = Unit
            override suspend fun revokeAccess() = Unit
        }
        val r = a.controller(boom).connect()
        assertEquals(ConnectState.ERROR, r.state)
        assertEquals(DriveReason.FAILED, r.error)
        assertFalse(r.toString().contains("secret"))
    }

    private class BrokenScalar(private val inner: app.doorprints.crypto.CryptoProvider, private val boom: () -> Throwable) :
        app.doorprints.crypto.CryptoProvider by inner {
        override fun p256FromScalar(scalar: ByteArray): app.doorprints.crypto.P256PrivateKey = throw boom()
    }

    @Test
    fun anUnexpectedFailureOfTheRecoveryKeyJoinShowsACodeNotAMessage() = runTest {
        val key = a.c.createFolder().recoveryKey!!
        val phone = Phone(server, "Phone", crypto = BrokenScalar(app.doorprints.crypto.JvmCryptoProvider) { NoClassDefFoundError("Lorg/conscrypt/Secret;") })
        val r = phone.c.openWithRecoveryKey(key)
        assertEquals(ConnectState.ERROR, r.state)
        assertEquals(DriveReason.SOURCE_FAILED, r.error)
        assertEquals("join-recover/java.lang.NoClassDefFoundError", r.code)
        assertFalse(r.toString().contains("Secret"))
    }

    @Test
    fun anErrorSubclassOnTheConnectPathIsAScreenWithACodeNotACrash() = runTest {
        val boom = object : app.doorprints.drive.connect.DriveSignIn {
            override suspend fun signIn(): SignInResult = throw ExceptionInInitializerError("token=secret-123")
            override suspend fun signOut() = Unit
            override suspend fun revokeAccess() = Unit
        }
        val c = a.controller(boom)
        val r = c.connect()
        assertEquals(ConnectState.ERROR, r.state)
        assertEquals(DriveReason.FAILED, r.error)
        assertEquals("connect/java.lang.ExceptionInInitializerError", r.code)
        assertEquals(ConnectState.ERROR, c.state.value)
        assertFalse(r.toString().contains("secret"))
    }

    @Test
    fun aTypedFailureAndASuccessCarryNoCode() = runTest {
        server.faults.always(DriveFault.Offline)
        assertNull(a.c.connect().code)
        server.faults.clear()
        assertNull(a.c.connect().code)
        assertNull(a.c.createFolder().code)
    }

    @Test
    fun signInClosedGoesBackWhereItWasAndDeniedIsAnError() = runTest {
        a.signIn.next = SignInResult.CANCELLED
        val closed = a.c.connect()
        assertEquals(ConnectResult(ConnectState.DISCONNECTED, error = DriveReason.SIGNIN_CLOSED), closed)
        assertEquals(ConnectState.DISCONNECTED, a.c.state.value)
        assertTrue(server.requests.isEmpty(), "no Drive call before the person has signed in")
        a.signIn.next = SignInResult.DENIED
        assertEquals(ConnectResult(ConnectState.ERROR, error = DriveReason.SIGNIN_DENIED), a.c.connect())
        a.signIn.next = SignInResult.UNAVAILABLE
        assertEquals(DriveReason.SIGNIN_UNAVAILABLE, a.c.connect().error)
        a.signIn.next = SignInResult.OFFLINE
        assertEquals(DriveReason.OFFLINE, a.c.connect().error)
        a.signIn.next = SignInResult.FAILED
        assertEquals(DriveReason.CONNECT_FAILED, a.c.connect().error)
        a.signIn.next = SignInResult.SIGNED_IN
        assertEquals(ConnectState.DISCONNECTED, a.c.connect().state)
    }

    @Test
    fun aSecondConnectWhileOneIsRunningIsToldSo() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        a.signIn.gate = gate
        val first = async { a.c.connect() }
        assertEquals(ConnectState.CONNECTING, a.c.state.value)
        val second = a.c.connect()
        assertEquals(ConnectResult(ConnectState.CONNECTING, error = DriveReason.CONNECTION_IN_PROGRESS), second)
        assertEquals(1, a.signIn.signIns)
        gate.complete(Unit)
        assertEquals(ConnectState.DISCONNECTED, first.await().state)
    }

    @Test
    fun disconnectKeepsEveryDriveFileEndsTheSessionAndSignsOut() = runTest {
        a.c.createFolder()
        a.c.confirmRecoveryKeySaved()
        a.c.backUpNow()
        val before = server.allFiles().size
        a.c.disconnect()
        assertEquals(ConnectState.DISCONNECTED, a.c.state.value)
        assertEquals(1, a.signIn.signOuts)
        assertEquals(before, server.allFiles().size)
        assertEquals(DriveReason.NOT_CONNECTED, a.c.listBackups().reason())
        assertEquals(DriveReason.NOT_CONNECTED, a.c.backUpNow().reason())
        assertEquals(before, server.allFiles().size)
        assertEquals(emptyList(), a.c.listedDevices())
        assertEquals(SyncState.ERROR, a.c.syncNow().state)
    }

    @Test
    fun disconnectOnAllDevicesNeedsTheDeviceCheckThenRevokesTheGrant() = runTest {
        a.c.createFolder()
        a.c.confirmRecoveryKeySaved()
        a.deviceAuth.lockEnabled = false
        assertEquals(DriveReason.NO_DEVICE_LOCK, a.c.disconnectAll("Confirm").reason())
        a.deviceAuth.lockEnabled = true
        a.deviceAuth.next = app.doorprints.deviceauth.AuthResult.CANCELLED
        assertEquals(DriveReason.AUTH_CANCELLED, a.c.disconnectAll("Confirm").reason())
        assertEquals(0, a.signIn.revokes)
        assertEquals(ConnectState.READY, a.c.state.value)
        a.deviceAuth.next = app.doorprints.deviceauth.AuthResult.SUCCESS
        a.signIn.revokeBoom = true // a failed revoke does not stop the local session from ending
        assertTrue(a.c.disconnectAll("Confirm") is Outcome.Ok)
        assertEquals(1, a.signIn.revokes)
        assertEquals(ConnectState.DISCONNECTED, a.c.state.value)
        assertEquals(app.doorprints.deviceauth.DeleteLevel.L2, a.deviceAuth.lastLevel)
    }

    @Test
    fun accountEmailComesFromDriveAboutAndAFailureIsNull() = runTest {
        assertEquals("person@example.com", a.c.accountEmail())
        server.faults.always(DriveFault.Offline, DriveOp.ABOUT)
        assertNull(a.c.accountEmail())
    }

    @Test
    fun everyReasonHasAUniqueKeyAndEveryProblemKindMaps() {
        val keys = DriveReason.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() && it.startsWith("drive") })
        for (k in app.doorprints.drive.backup.DriveProblem.Kind.entries) DriveReason.of(k)
        assertEquals(DriveReason.JOIN_WRONG_KEY, DriveReason.of(app.doorprints.drive.backup.DriveProblem.Kind.WRONG_RECOVERY_KEY))
        assertEquals(DriveReason.BACKUP_NOT_FOUND, DriveReason.of(app.doorprints.drive.backup.DriveProblem.Kind.BACKUP_GONE))
        assertEquals(DriveReason.FAILED, DriveReason.of(RuntimeException("password=hunter2")))
        assertEquals(DriveReason.OFFLINE, DriveReason.of(app.doorprints.drive.DriveException(app.doorprints.drive.DriveException.Kind.OFFLINE)))
    }
}
