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

import app.doorprints.crypto.ByteSource
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.QR_PSK_LEN
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState
import app.doorprints.drive.DriveOp
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.InMemoryFakeDrive
import app.doorprints.drive.backup.BackupPayload
import app.doorprints.drive.backup.BackupSource
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.DriveSignIn
import app.doorprints.drive.connect.Outcome
import app.doorprints.drive.connect.SignInResult
import app.doorprints.drive.connect.SyncState
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionItem
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.ItemKind
import app.doorprints.drive.delete.PendingDeletion
import app.doorprints.drive.device.DeviceKeyException
import app.doorprints.drive.device.DeviceKeyStatus
import app.doorprints.drive.device.FakeKeyBackend
import app.doorprints.drive.photo.Metering
import app.doorprints.drive.photo.NetworkConditions
import app.doorprints.drive.photo.NetworkState
import app.doorprints.drive.sync.DriveSyncBackend
import app.doorprints.drive.sync.LocalRows
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.sync.SyncRow
import app.doorprints.ui.drive.NewcomerOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The real Drive object graph over fakes (S4b-BL-117/-118/-127): what [DriveAssembly] fixes is the sharing and the order,
 * so each test goes through the controller and the real services on the in-memory Drive, with a Keystore-like device key
 * (a software P-256 key behind the `DeviceKeyBackend` seam, so only [app.doorprints.drive.device.DeviceKeyCryptoProvider]
 * can agree with it).
 */
class DriveAssemblyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeDriveServer()

    private class Phone(
        val dir: File,
        val backend: FakeKeyBackend = FakeKeyBackend(),
    ) {
        var lock = true
        var authResult = AuthResult.SUCCESS
        var signIn = SignInResult.SIGNED_IN
        var signIns = 0
        var network = NetworkConditions(online = true, metering = Metering.UNMETERED)
        val passes = mutableListOf<Pair<DriveSyncBackend, Boolean>>()
        var probes = mutableListOf<() -> Boolean>()
        var authAsked = 0
        var houses = 3
        val handBacks = mutableListOf<Boolean>()
        var handBackFailures = 0
        lateinit var graph: DriveGraph
    }

    private fun phone(name: String, dir: File = tmp.newFolder(name), configured: Boolean = true, plainSyncPass: Boolean = false, backend: FakeKeyBackend = FakeKeyBackend(), handBackFails: Int = 0): Phone {
        val phone = Phone(dir, backend)
        val deviceAuth = object : DeviceAuth {
            override fun isDeviceLockEnabled() = phone.lock
            override suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult {
                phone.authAsked++
                return phone.authResult
            }
        }
        val deps = DriveDeps(
            dir = dir,
            crypto = JvmCryptoProvider,
            keyBackend = backend,
            deviceName = name,
            drive = InMemoryFakeDrive(server),
            signIn = object : DriveSignIn {
                override suspend fun signIn(): SignInResult {
                    phone.signIns++
                    return phone.signIn
                }
                override suspend fun signOut() = Unit
                override suspend fun revokeAccess() = Unit
            },
            deviceAuth = deviceAuth,
            lock = { keyUsable ->
                phone.probes += keyUsable
                LockLostDetector { if (!phone.lock || !keyUsable()) LockState.REMOVED else LockState.PRESENT }
            },
            network = NetworkState { phone.network },
            localRows = { _ ->
                object : LocalRows {
                    override suspend fun all(): List<SyncRow> = emptyList()
                    override suspend fun photo(photoId: String): PhotoChangeDto? = null
                }
            },
            backupSource = BackupSource {
                val bytes = ByteArray(2_000) { (it % 251).toByte() }
                var at = 0
                BackupPayload(
                    ByteSource { b, o, l ->
                        if (at >= bytes.size) {
                            -1
                        } else {
                            val n = minOf(l, bytes.size - at)
                            bytes.copyInto(b, o, at, at + n)
                            at += n
                            n
                        }
                    },
                    "doorprints-backup/1", phone.houses,
                )
            },
            syncPass = if (plainSyncPass) null else { backend, photos -> phone.passes += backend to photos; backend.commitPushes() },
            handBack = {
                if (phone.handBackFailures < handBackFails) {
                    phone.handBackFailures++
                    throw java.io.IOException("database busy")
                }
                phone.handBacks += phone.graphEngaged()
            },
            configured = configured,
            clock = { server.clock.now() },
            utcOffsetMinutes = { 330 },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        phone.graph = DriveAssembly.assemble(deps)
        return phone
    }

    private val Phone.controller get() = graph.controller

    /** The next use of the key after the key store changed under us: it fails, which is what makes the app look at the key again. */
    private fun Phone.failUse() {
        val peer = JvmCryptoProvider.p256Generate().publicKey
        assertThrows(DeviceKeyException::class.java) { graph.crypto.p256Agree(graph.identity.key, peer) }
    }

    private fun Phone.graphEngaged() = graph.prefs.engaged

    private fun Phone.connected(): Phone = also {
        runBlocking {
            assertEquals(ConnectState.DISCONNECTED, controller.connect().state)
            val created = controller.createFolder()
            assertEquals(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, created.state)
            controller.confirmRecoveryKeySaved()
        }
    }

    // ---- one provider, the device key one ----

    @Test
    fun aSecondPhoneEnrolsThroughTheKeystoreLikeKeyOnBothSides() = runBlocking {
        val a = phone("Pixel 8").connected()
        val b = phone("Tablet")
        assertEquals(ConnectState.NEEDS_ENROLMENT, b.controller.connect().state)

        // The newcomer's offer, the approver's PSK wrap (the approver's own key agrees in the key store), the join.
        val psk = JvmCryptoProvider.randomBytes(QR_PSK_LEN)
        val wrap = a.controller.approveJoinedDevicePsk(b.controller.devicePublicKey(), "Tablet", DevicePlatform.ANDROID, psk, "why")
        wrap as Outcome.Ok
        val bytes = java.util.Base64.getDecoder()
        val joined = b.controller.joinFromPsk(wrap.value.wrapEnc, wrap.value.wrapCt, wrap.value.epoch, psk)
        assertEquals("a plain provider cannot agree with a key store key: $joined", ConnectState.READY, joined.state)
        assertTrue(b.backend.agrees > 0)
        assertEquals(2, a.controller.listedDevices().size)
        assertTrue(bytes != null)
    }

    @Test
    fun theTrustAnchorIsThePersistedPinAcrossARestart() = runBlocking {
        val a = phone("Pixel 8").connected()
        // A new process over the same files: the folder opens through the pin the first one wrote.
        val again = phone("Pixel 8", dir = a.dir, backend = a.backend)
        assertEquals(ConnectState.READY, again.controller.connect().state)
    }

    // ---- the key knows about the pin ----

    @Test
    fun aLostKeyWithAPinnedFolderIsNeverReplaced() {
        val a = phone("Pixel 8").connected()
        val creates = a.backend.creates
        a.backend.state = DeviceKeyStatus.ABSENT
        a.failUse()
        val e = assertThrows(DeviceKeyException::class.java) { a.controller.devicePublicKey() }
        assertEquals(DeviceKeyException.Kind.LOST, e.kind)
        assertEquals("no new key under an existing folder", creates, a.backend.creates)
    }

    @Test
    fun withNoFolderYetAMissingKeyIsMadeOnFirstUse() {
        val a = phone("Pixel 8")
        assertEquals(65, a.controller.devicePublicKey().size)
        assertEquals(1, a.backend.creates)
    }

    @Test
    fun afterTheFolderIsForgottenTheOldPinNoLongerPinsTheKey() = runBlocking {
        val a = phone("Pixel 8").connected()
        // *Delete everything* forgets the folder's ids on this phone; the key may then be made again.
        a.graph.deletion
        val stores = app.doorprints.drive.store.DriveFileStores(a.dir)
        stores.driveState.save(stores.driveState.load().copy(rootId = null))
        a.backend.state = DeviceKeyStatus.ABSENT
        a.failUse()
        assertEquals(65, a.controller.devicePublicKey().size)
    }

    // ---- one gate, one authorizer ----

    @Test
    fun aDeleteGrantMadeByTheControllerIsTheOneTheServiceRedeems() = runBlocking {
        val a = phone("Pixel 8").connected()
        assertTrue(a.controller.backUpNow() is Outcome.Ok)
        val plan = (a.controller.deletePlan(DeletionAction.AllBackups) as Outcome.Ok).value
        assertEquals(DeletionLevel.L2, plan.level)
        val grant = a.controller.authorizeDelete(plan, "delete?") as Outcome.Ok
        assertEquals("the person was asked once", 1, a.authAsked)
        val run = a.controller.executeDelete(plan, grant.value)
        assertTrue("got $run", run is Outcome.Ok && (run as Outcome.Ok).value.finished)
    }

    @Test
    fun aRefusedDeviceCheckDeletesNothing() = runBlocking {
        val a = phone("Pixel 8").connected()
        a.controller.backUpNow()
        val plan = (a.controller.deletePlan(DeletionAction.AllBackups) as Outcome.Ok).value
        a.authResult = AuthResult.CANCELLED
        assertEquals(Outcome.Failed(DriveReason.AUTH_CANCELLED), a.controller.authorizeDelete(plan, "delete?"))
        assertEquals(0, server.requests.count { it.first == DriveOp.DELETE || it.first == DriveOp.TRASH })
    }

    // ---- the lock ----

    @Test
    fun theLockProbeSeesAnInvalidatedKey() {
        val a = phone("Pixel 8").connected()
        val probe = a.probes.single()
        assertTrue(probe())
        a.backend.state = DeviceKeyStatus.INVALIDATED
        a.failUse()
        assertFalse("the probe is the identity's own: an invalidated key means the lock is gone", probe())
    }

    @Test
    fun aRemovedLockPausesTheSyncDropsTheLocalKeyAndTouchesNothingInDrive() = runBlocking {
        val a = phone("Pixel 8").connected()
        val before = server.requests.size
        a.lock = false
        val info = a.controller.syncNow()
        assertEquals(SyncState.PAUSED, info.state)
        assertTrue("the dead key is dropped locally", a.backend.discards >= 1)
        assertTrue(a.graph.lockStore.paused)
        assertTrue(a.graph.lockStore.needsReenrolment)
        val writes = server.requests.drop(before).count { it.first in setOf(DriveOp.CREATE, DriveOp.UPLOAD, DriveOp.UPDATE, DriveOp.DELETE, DriveOp.TRASH) }
        assertEquals("no upload, download or delete", 0, writes)
    }

    @Test
    fun withTheLockThereTheSyncPassRuns() = runBlocking {
        val a = phone("Pixel 8").connected()
        val info = a.controller.syncNow()
        assertEquals(SyncState.SYNCED, info.state)
        assertEquals(1, a.passes.size)
    }

    @Test
    fun aHalfFinishedDeletionPausesTheSync() = runBlocking {
        val a = phone("Pixel 8").connected()
        val root = app.doorprints.drive.store.DriveFileStores(a.dir).driveState.load().rootId!!
        app.doorprints.drive.store.DriveFileStores(a.dir).deletion.savePending(
            PendingDeletion("op", DeletionLevel.L2, DeletionAction.AllBackups, root, listOf(DeletionItem("f1", ItemKind.BACKUP, 1, 1)), 1, 0),
        )
        assertEquals(SyncState.PAUSED, a.controller.syncNow().state)
    }

    @Test
    fun aPausedPhoneIsClearedOnceTheFolderIsOpenAgainWithALock() = runBlocking {
        val a = phone("Pixel 8").connected()
        a.graph.lockStore.paused = true
        a.graph.lockStore.needsReenrolment = true
        assertEquals(ConnectState.READY, a.controller.connect().state)
        assertFalse(a.graph.lockStore.paused)
        assertFalse(a.graph.lockStore.needsReenrolment)
    }

    @Test
    fun aPauseStaysWhileThereIsStillNoLock() = runBlocking {
        val a = phone("Pixel 8").connected()
        a.graph.lockStore.paused = true
        a.lock = false
        a.controller.connect()
        assertTrue(a.graph.lockStore.paused)
    }

    // ---- sign-in, the network, the sync pass ----

    @Test
    fun theControllerSignsInThroughTheSignInSeam() = runBlocking {
        val a = phone("Pixel 8")
        a.controller.connect()
        assertEquals(1, a.signIns)
    }

    @Test
    fun aClosedPromptIsNotAnErrorAndADenialIs() = runBlocking {
        val a = phone("Pixel 8")
        a.signIn = SignInResult.CANCELLED
        val closed = a.controller.connect()
        assertEquals(ConnectState.DISCONNECTED, closed.state)
        assertEquals(DriveReason.SIGNIN_CLOSED, closed.error)
        a.signIn = SignInResult.DENIED
        val denied = a.controller.connect()
        assertEquals(ConnectState.ERROR, denied.state)
        assertEquals(DriveReason.SIGNIN_DENIED, denied.error)
    }

    @Test
    fun withoutAClientTheCardIsUnavailable() {
        val a = phone("Pixel 8", configured = false)
        assertEquals(ConnectState.UNAVAILABLE, a.controller.state.value)
    }

    @Test
    fun photosFollowTheNetworkTheAppSees() {
        val a = phone("Pixel 8")
        assertTrue(a.controller.photosAllowed())
        a.network = NetworkConditions(online = true, metering = Metering.METERED)
        assertFalse("Wi-Fi only by default", a.controller.photosAllowed())
    }

    @Test
    fun theSyncPassGetsTheBackendAndThePhotoGate() = runBlocking {
        val a = phone("Pixel 8").connected()
        a.network = NetworkConditions(online = true, metering = Metering.METERED)
        a.controller.syncNow()
        assertEquals(false, a.passes.single().second)
        a.network = NetworkConditions(online = true, metering = Metering.UNMETERED)
        a.controller.syncNow()
        assertEquals(true, a.passes.last().second)
    }

    @Test
    fun withoutASyncPassTheControllerCommitsItself() = runBlocking {
        val a = phone("Pixel 8", plainSyncPass = true).connected()
        assertEquals(SyncState.SYNCED, a.controller.syncNow().state)
        assertTrue(a.passes.isEmpty())
    }

    // ---- Drive in use, and the automatic backup default ----

    @Test
    fun anOpenFolderEngagesDriveAndItIsRememberedAcrossARestart() = runBlocking {
        val a = phone("Pixel 8")
        assertFalse(a.graph.prefs.engaged)
        a.connected()
        assertTrue(a.graph.prefs.engaged)
        // The new process starts DISCONNECTED before it reconnects: Drive is still in use.
        val again = phone("Pixel 8", dir = a.dir, backend = a.backend)
        assertEquals(ConnectState.DISCONNECTED, again.controller.state.value)
        assertTrue(again.graph.prefs.engaged)
    }

    @Test
    fun disconnectingThisDeviceEndsTheUse() = runBlocking {
        val a = phone("Pixel 8").connected()
        a.controller.disconnect()
        assertFalse(a.graph.prefs.engaged)
    }

    @Test
    fun disconnectingHandsTheRowsBackBeforeTheServerResumes() = runBlocking {
        val a = phone("Pixel 8").connected()
        assertTrue("nothing handed back while Drive is in use", a.handBacks.isEmpty())
        a.controller.disconnect()
        assertEquals("handed back once, while still engaged (the server resumes only after)", listOf(true), a.handBacks)
        assertFalse(a.graph.prefs.engaged)
    }

    @Test
    fun aFreshProcessAtDisconnectedHandsNothingBack() = runBlocking {
        val a = phone("Pixel 8").connected()
        val again = phone("Pixel 8", dir = a.dir, backend = a.backend)
        assertEquals(ConnectState.DISCONNECTED, again.controller.state.value)
        assertTrue(again.handBacks.isEmpty())
        assertTrue(again.graph.prefs.engaged)
    }

    @Test
    fun aFailedHandBackKeepsDriveInUseAndTriesAgainAtTheNextChange() = runBlocking {
        val a = phone("Pixel 8", handBackFails = 1).connected()
        a.controller.disconnect()
        assertTrue("the server must not take over before the rows were handed back", a.graph.prefs.engaged)
        a.controller.connect()
        a.controller.disconnect()
        assertEquals(1, a.handBacks.size)
        assertFalse(a.graph.prefs.engaged)
    }

    @Test
    fun automaticBackupStartsOnAtTheFirstConnect() {
        val a = phone("Pixel 8").connected()
        assertTrue(a.controller.autoBackupEnabled())
        assertEquals("1", a.graph.prefs.get("doorprints.drive.autoBackup"))
    }

    @Test
    fun aChoiceTheyAlreadyMadeIsKept() {
        val a = phone("Pixel 8")
        a.controller.setAutoBackup(false)
        a.connected()
        assertFalse(a.controller.autoBackupEnabled())
    }

    @Test
    fun theBackgroundWorkFollowsTheSwitches() {
        val a = phone("Pixel 8")
        val changes = mutableListOf<String>()
        a.graph.prefs.onChange = { k, v -> changes += "$k=$v" }
        a.connected()
        assertTrue(changes.any { it == "doorprints.drive.engaged=1" })
        assertTrue(changes.any { it == "doorprints.drive.autoBackup=1" })
    }

    // ---- a backup goes through the real service ----

    @Test
    fun aBackupIsUploadedAndListed() = runBlocking {
        val a = phone("Pixel 8").connected()
        val done = a.controller.backUpNow() as Outcome.Ok
        assertEquals(3, done.value.backup.houses)
        val list = a.controller.listBackups() as Outcome.Ok
        assertEquals(1, list.value.backups.size)
        assertNotNull(a.controller.lastBackup())
        assertNull((a.controller.syncStatus()).error)
    }

    @Suppress("unused")
    private fun unusedOffer(o: NewcomerOffer) = o
}
