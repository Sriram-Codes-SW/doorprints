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

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.QR_PSK_LEN
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.HouseEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.InMemoryFakeDrive
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.Outcome
import app.doorprints.drive.connect.SyncState
import app.doorprints.drive.device.FakeKeyBackend
import app.doorprints.drive.photo.Metering
import app.doorprints.drive.photo.NetworkConditions
import app.doorprints.drive.photo.NetworkState
import app.doorprints.shared.sync.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Two phones syncing through Drive, end to end (S4b-BL-118): the real graph ([DriveAssembly]), the real Room rows
 * ([RoomSyncRows]), the real repository loop (`CommonRepository.sync`) reached through the route, on the in-memory
 * Drive. Proves that a house, its edit and its deletion travel, that the server path is not touched while Drive is in
 * use, and that rows pulled from the other phone land in this phone's database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DriveSyncEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val server = FakeDriveServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val at = 1_760_000_000_000
    private val nodes = mutableListOf<Node>()

    private object MemorySecrets : SecretStore {
        private val key = stringPreferencesKey("e2eKey")
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    /** One phone: its own database, settings, repository, Drive files and key store. */
    private inner class Node(val name: String, engagedFromStart: Boolean = true) {
        val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "e2e-$name.db").addMigrations(*AppDatabase.MIGRATIONS).build()
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "e2e-$name.preferences_pb") }, MemorySecrets)
        val dir: File = tmp.newFolder(name)
        val route = DriveSyncRoute()
        val backend = FakeKeyBackend()
        var engagedOverride: Boolean? = if (engagedFromStart) null else false
        var serverAsked = 0
        val passes = mutableListOf<Boolean>()
        lateinit var graph: DriveGraph
        val repository = AndroidRepository(
            context, db, settings,
            syncBackendFor = DriveSyncChoice.backendFor({ engagedOverride ?: graph.prefs.engaged }, route) { serverAsked++; null },
        )
        val controller get() = graph.controller

        init {
            val deviceAuth = object : DeviceAuth {
                override fun isDeviceLockEnabled() = true
                override suspend fun authenticate(reason: String, level: DeleteLevel) = AuthResult.SUCCESS
            }
            graph = DriveAssembly.assemble(
                DriveDeps(
                    dir = dir,
                    crypto = JvmCryptoProvider,
                    keyBackend = backend,
                    deviceName = name,
                    drive = InMemoryFakeDrive(server),
                    signIn = null,
                    deviceAuth = deviceAuth,
                    lock = { _, _ -> LockLostDetector { LockState.PRESENT } },
                    network = NetworkState { NetworkConditions(online = true, metering = Metering.UNMETERED) },
                    localRows = { deviceId -> RoomSyncRows(db, deviceId) { null } },
                    backupSource = null,
                    syncPass = { drive, photos ->
                        passes += photos
                        withContext(route.element(drive)) { repository.sync(photos) }
                    },
                    handBack = { repository.resetForServer() },
                    configured = true,
                    clock = { server.clock.now() },
                    utcOffsetMinutes = { 330 },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                ),
            )
            nodes += this
        }

        fun house(id: String, label: String, updatedAt: Long = at, deleted: Boolean = false) = runBlocking {
            db.houses().upsert(HouseEntity(id = id, label = label, lat = 12.9, lon = 77.6, createdAt = at, updatedAt = updatedAt, deleted = deleted))
        }

        fun label(id: String): String? = runBlocking { db.houses().get(id)?.takeIf { !it.deleted }?.label }

        fun dirty(id: String): Boolean? = runBlocking { db.houses().get(id)?.dirty }

        fun sync() = runBlocking { controller.syncNow() }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        nodes.forEach { it.db.close() }
        scope.cancel()
    }

    private fun connectedPair(): Pair<Node, Node> = runBlocking {
        val a = Node("a")
        a.controller.connect()
        a.controller.createFolder()
        a.controller.confirmRecoveryKeySaved()
        val b = Node("b")
        assertEquals(ConnectState.NEEDS_ENROLMENT, b.controller.connect().state)
        val psk = JvmCryptoProvider.randomBytes(QR_PSK_LEN)
        val wrap = a.controller.approveJoinedDevicePsk(b.controller.devicePublicKey(), "b", DevicePlatform.ANDROID, psk, "why") as Outcome.Ok
        assertEquals(ConnectState.READY, b.controller.joinFromPsk(wrap.value.wrapEnc, wrap.value.wrapCt, wrap.value.epoch, psk).state)
        a to b
    }

    @Test
    fun aHouseSavedOnOnePhoneAppearsOnTheOtherAndIsMarkedSent() {
        val (a, b) = connectedPair()
        a.house("h1", "Green View")
        assertEquals(true, a.dirty("h1"))
        assertEquals(SyncState.SYNCED, a.sync().state)
        assertEquals("the loop marks it sent only after the file was confirmed", false, a.dirty("h1"))
        assertEquals(SyncState.SYNCED, b.sync().state)
        assertEquals("Green View", b.label("h1"))
        assertEquals("what arrives is clean, not sent back", false, b.dirty("h1"))
    }

    @Test
    fun anEditTravelsAndTheNewerEditWins() {
        val (a, b) = connectedPair()
        a.house("h1", "Green View")
        a.sync(); b.sync()
        b.house("h1", "Green View, 2nd floor", updatedAt = at + 60_000)
        b.sync(); a.sync()
        assertEquals("Green View, 2nd floor", a.label("h1"))
    }

    @Test
    fun aDeletionTravelsAsATombstone() {
        val (a, b) = connectedPair()
        a.house("h1", "Green View")
        a.sync(); b.sync()
        assertEquals("Green View", b.label("h1"))
        a.house("h1", "Green View", updatedAt = at + 60_000, deleted = true)
        a.sync(); b.sync()
        assertNull("the other phone drops it too", b.label("h1"))
    }

    @Test
    fun housesFromBothPhonesEndUpOnBoth() {
        val (a, b) = connectedPair()
        a.house("h1", "From A")
        b.house("h2", "From B")
        a.sync(); b.sync(); a.sync()
        assertEquals("From B", a.label("h2"))
        assertEquals("From A", b.label("h1"))
    }

    @Test
    fun theSyncPassUsesThePhotoGate() {
        val (a, _) = connectedPair()
        a.sync()
        assertEquals(listOf(true), a.passes)
    }

    @Test
    fun withDriveInUseTheServerIsNeverAskedNotEvenOutsideADrivePass() = runBlocking {
        val (a, _) = connectedPair()
        assertTrue(a.graph.prefs.engaged)
        // The 30-minute server job, or Settings' *Sync now*, outside a Drive pass: nothing syncs, and the server is not used.
        val outcome = a.repository.sync(true)
        assertEquals(SyncOutcome.Kind.NOT_CONFIGURED, outcome.kind)
        assertEquals(0, a.serverAsked)
    }

    /** The hand-back writes to Room on its own thread, so the flag flips a moment after the controller's call returns. */
    private fun awaitDisengaged(node: Node) {
        val until = System.nanoTime() + 10_000_000_000L
        while (node.graph.prefs.engaged && System.nanoTime() < until) Thread.sleep(20)
        assertFalse("Drive stopped being in use", node.graph.prefs.engaged)
    }

    @Test
    fun rowsSentOnlyToDriveReachTheServerAfterDisconnect() = runBlocking {
        val (a, _) = connectedPair()
        a.house("h1", "Green View")
        a.sync()
        assertEquals("sent to Drive only: clean", false, a.dirty("h1"))
        a.settings.saveCursors(5, 5, 5)
        a.controller.disconnect()
        awaitDisengaged(a)
        assertEquals("the server must get it: marked for upload again", true, a.dirty("h1"))
        assertEquals("the pull cursors start over", 0L, a.settings.cursors().house)
    }

    @Test
    fun aDriveOnlyHouseIsNotLostWhenTheFolderIsGoneAndThePersonDisconnects() = runBlocking {
        val (a, _) = connectedPair()
        a.house("h1", "Green View")
        a.sync()
        assertEquals(false, a.dirty("h1"))
        // The folder was deleted elsewhere: the controller drops to disconnected with FOLDER_GONE, but Drive stays in use (the
        // server stays off until the person answers) and nothing is handed back yet.
        val root = app.doorprints.drive.store.DriveFileStores(a.dir).driveState.load().rootId!!
        server.deleteByHand(root)
        a.controller.connect()
        Thread.sleep(300)
        assertTrue("a folder gone is not a Disconnect", a.graph.prefs.engaged)
        assertEquals("still only in Drive", false, a.dirty("h1"))
        // Their Disconnect ends the use and the rows go back to the server.
        a.controller.disconnect()
        awaitDisengaged(a)
        assertEquals(true, a.dirty("h1"))
    }

    @Test
    fun withoutDriveTheServerIsChosenAsBefore() = runBlocking {
        val lone = Node("lone", engagedFromStart = false)
        val outcome = lone.repository.sync(true)
        assertEquals(SyncOutcome.Kind.NOT_CONFIGURED, outcome.kind)
        assertEquals("the server's chooser ran", 1, lone.serverAsked)
        assertNotNull(lone.route)
        assertFalse(lone.graph.prefs.engaged)
    }
}
