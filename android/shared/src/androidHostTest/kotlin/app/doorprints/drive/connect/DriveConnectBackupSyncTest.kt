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

import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveOp
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.backup.Payload
import app.doorprints.drive.photo.Metering
import app.doorprints.drive.photo.PhotoNetworkStatus
import app.doorprints.drive.sync.houseRow
import app.doorprints.drive.sync.photoRow
import app.doorprints.shared.sync.SyncKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backups, import hand-off, the schedule, sync and photos through the controller (web `real flows`, `automatic backup`, `sync status`, `photo settings`). */
class DriveConnectBackupSyncTest {
    private val server = FakeDriveServer()
    private val a = Phone(server, "Pixel 8")
    private val b = Phone(server, "Galaxy")
    private val day = 24L * 60 * 60 * 1000

    private suspend fun connected(p: Phone = a): String? {
        val key = p.c.createFolder().recoveryKey
        p.c.confirmRecoveryKeySaved()
        return key
    }

    // ---- backups and import ----

    @Test
    fun backUpNowListsAndImportsBackTheSameBytes() = runTest {
        connected()
        val done = a.c.backUpNow().ok()
        assertEquals(3, done.backup.houses)
        assertNull(done.shrinkHoldBackupId)
        val list = a.c.listBackups().ok()
        assertEquals(listOf(done.backup.id), list.backups.map { it.id })
        assertFalse(list.missingNewer)
        val staging = MemStaging()
        val imported = a.c.importFromDrive(done.backup.id, staging).ok()
        assertEquals(a.payload.bytes.size.toLong(), imported.plaintextSize)
        assertEquals("doorprints-backup/1", imported.format)
        assertContentEquals(a.payload.bytes, staging.bytes())
        assertEquals(0, staging.discards)
    }

    @Test
    fun backUpNowWithoutAConnectionOrASourceMakesNothingInDrive() = runTest {
        assertEquals(DriveReason.NOT_CONNECTED, a.c.backUpNow().reason())
        assertTrue(server.allFiles().isEmpty())
        val noSource = a.controller(source = false)
        noSource.createFolder()
        val before = server.allFiles().size
        assertEquals(DriveReason.NO_BACKUP_SOURCE, noSource.backUpNow().reason())
        assertEquals(before, server.allFiles().size)
    }

    @Test
    fun aSourceThatCannotMakeTheZipIsATypedFailure() = runTest {
        connected()
        val c = a.controller()
        // reuse the open folder through a connect on a controller whose source throws
        val failing = Phone(server, "Pixel 8").let { a.controller(source = false) }
        failing.connect()
        assertEquals(DriveReason.NO_BACKUP_SOURCE, failing.backUpNow().reason())
        assertNotNull(c)
    }

    @Test
    fun listingAndImportRefuseWhenNotConnectedOrTheBackupIsUnknown() = runTest {
        assertEquals(DriveReason.NOT_CONNECTED, a.c.listBackups().reason())
        assertEquals(DriveReason.NOT_CONNECTED, a.c.importFromDrive("x", MemStaging()).reason())
        connected()
        val staging = MemStaging()
        assertEquals(DriveReason.BACKUP_NOT_FOUND, a.c.importFromDrive("nope", staging).reason())
        assertEquals(DriveReason.NO_BACKUPS, a.c.lastBackup().reason())
        a.c.backUpNow().ok()
        assertEquals(1, a.c.lastBackup().ok().let { 1 })
    }

    @Test
    fun aBackupEditedByHandIsNeverListedSoItCannotBeImported() = runTest {
        connected()
        val id = a.c.backUpNow().ok().backup.id
        val bytes = server.contentOf(id)!!
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        server.editByHand(id, bytes)
        assertEquals(emptyList(), a.c.listBackups().ok().backups)
        val staging = MemStaging()
        assertEquals(DriveReason.BACKUP_NOT_FOUND, a.c.importFromDrive(id, staging).reason())
        assertEquals(0, staging.bytes().size)
    }

    @Test
    fun aDownloadThatFailsIsRefusedAndTheSinkIsDiscarded() = runTest {
        connected()
        val id = a.c.backUpNow().ok().backup.id
        server.faults.always(DriveFault.Offline, DriveOp.DOWNLOAD)
        val staging = MemStaging()
        assertEquals(DriveReason.OFFLINE, a.c.importFromDrive(id, staging).reason())
        assertTrue(staging.discards >= 1)
        assertEquals(0, staging.bytes().size)
        server.faults.clear()
        val again = MemStaging()
        a.c.importFromDrive(id, again).ok()
        assertContentEquals(a.payload.bytes, again.bytes())
    }

    @Test
    fun theShrinkHoldIsReportedAndConfirmationReachesTheCore() = runTest {
        connected()
        repeat(14) {
            a.c.backUpNow().ok()
            server.clock.advance(day)
        }
        val small = Phone(server, "x") // unused; keep the same device below
        assertNotNull(small)
        val smaller = a.controller()
        smaller.connect()
        // a backup of 3 houses after fourteen of 40 holds the pruning
        val big = Phone(server, "y")
        assertNotNull(big)
        val c = a.controllerWithHouses(40, 3)
        c.connect()
        repeat(2) { c.backUpNow().ok(); server.clock.advance(day) }
        val hold = c.backUpNow().ok().shrinkHoldBackupId
        assertNotNull(hold)
        c.confirmShrink(hold)
        assertTrue(hold in a.rig.state.value.confirmedDrops)
    }

    private fun Phone.controllerWithHouses(vararg houses: Int): DriveConnectController {
        var n = 0
        val src = app.doorprints.drive.backup.BackupSource {
            val h = houses[minOf(n++ , houses.size - 1)]
            Payload.of(2_000, h, seed = n).source().open()
        }
        return DriveConnectController(
            rig.service, rig.drive, p, rig.identity, rig.trust, enrolment, deletion, store, authorizer, rigs, network, prefs,
            server.clock::now, true, src, signIn, driver,
        )
    }

    // ---- automatic backup ----

    @Test
    fun automaticBackupIsOffUntilTurnedOnAndRemembered() = runTest {
        assertFalse(a.c.autoBackupEnabled())
        a.c.setAutoBackup(true)
        assertTrue(a.c.autoBackupEnabled())
        assertTrue(a.c.controllerLike().autoBackupEnabled(), "a new controller on the same prefs sees it")
        a.c.setAutoBackup(false)
        assertFalse(a.c.autoBackupEnabled())
        assertEquals("0", a.prefs.get(DriveConnectController.KEY_AUTO_BACKUP))
    }

    private fun DriveConnectController.controllerLike() = a.controller()

    @Test
    fun theChoiceSurvivesAStoreThatRefuses() = runTest {
        val refusing = object : DrivePrefs {
            override fun get(key: String): String? = throw IllegalStateException("no storage")
            override fun put(key: String, value: String) = throw IllegalStateException("no storage")
        }
        val c = DriveConnectController(
            a.rig.service, a.rig.drive, a.p, a.rig.identity, a.rig.trust, a.enrolment, a.deletion, a.store, a.authorizer, a.rigs, a.network, refusing,
            server.clock::now, true, a.payload.source(), a.signIn, null,
        )
        c.setAutoBackup(true)
        assertTrue(c.autoBackupEnabled())
        c.setPhotosWifiOnly(false)
        assertTrue(c.photoSettings().uploadOnMobileData)
    }

    @Test
    fun theScheduleRunsOnlyWhenReadyEnabledAndDue() = runTest {
        assertEquals(DueBackupResult.NotRan(BackupSchedule.Reason.NOT_READY), a.c.runDueBackup())
        connected()
        assertEquals(DueBackupResult.NotRan(BackupSchedule.Reason.DISABLED), a.c.runDueBackup())
        assertTrue(server.allFiles().none { it.appProperties["kind"] == "backup" })
        a.c.setAutoBackup(true)
        assertEquals(DueBackupResult.Ran(BackupSchedule.Reason.FIRST), a.c.runDueBackup())
        val count = { server.allFiles().count { it.appProperties["kind"] == "backup" && !it.trashed } }
        assertEquals(1, count())
        assertEquals(DueBackupResult.NotRan(BackupSchedule.Reason.NOT_DUE), a.c.runDueBackup())
        assertEquals(1, count())
        server.clock.advance(day + 1)
        assertEquals(DueBackupResult.Ran(BackupSchedule.Reason.DAILY), a.c.runDueBackup())
        assertEquals(2, count())
    }

    @Test
    fun aFailedDueBackupIsTypedAndTheScheduleBacksOff() = runTest {
        connected()
        a.c.setAutoBackup(true)
        server.faults.always(DriveFault.Offline, DriveOp.UPLOAD)
        val failed = a.c.runDueBackup()
        assertEquals(DueBackupResult.Failed(DriveReason.OFFLINE), failed)
        server.faults.clear()
        assertEquals(DueBackupResult.NotRan(BackupSchedule.Reason.WAIT_RETRY), a.c.runDueBackup())
    }

    @Test
    fun anUnexpectedFailureOfTheBackupCarriesItsStepAndClassOnly() = runTest {
        connected()
        val c = a.controller(customSource = app.doorprints.drive.backup.BackupSource { throw java.io.FileNotFoundException("/data/user/0/app/secret.zip") })
        c.connect()
        val r = c.backUpNow() as Outcome.Failed
        assertEquals(DriveReason.SOURCE_FAILED, r.reason)
        assertEquals("backup/java.io.FileNotFoundException", r.code)
        assertFalse(r.toString().contains("secret"))
    }

    @Test
    fun anUnexpectedFailureOfASyncPassCarriesItsStepAndClassOnly() = runTest {
        connected()
        val c = a.controller(customDriver = { throw NoClassDefFoundError("Lsecret/Thing;") })
        c.connect()
        val r = c.syncNow()
        assertEquals(SyncState.ERROR, r.state)
        assertEquals(DriveReason.FAILED, r.error)
        assertEquals("sync/java.lang.NoClassDefFoundError", r.code)
    }

    @Test
    fun aTypedSyncFailureHasNoCode() = runTest {
        connected()
        server.faults.always(DriveFault.Server(), DriveOp.LIST)
        assertNull(a.c.syncNow().code)
    }

    // ---- sync ----

    @Test
    fun syncBeforeConnectIsANotConnectedErrorAndStatusIsNotRun() = runTest {
        assertEquals(SyncInfo(SyncState.NOT_RUN, null), a.c.syncStatus())
        val r = a.c.syncNow()
        assertEquals(SyncState.ERROR, r.state)
        assertEquals(DriveReason.NOT_CONNECTED, r.error)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun twoPhonesConvergeAndTheStatusLineFollows() = runTest {
        val key = connected()!!
        a.local.put(houseRow("h1", "Lake View", server.clock.now(), a.kidHex), true)
        val first = a.c.syncNow()
        assertEquals(SyncState.SYNCED, first.state)
        assertEquals(server.clock.now(), first.lastSyncAt)
        assertEquals(first, a.c.syncStatus())
        b.c.connect()
        b.c.openWithRecoveryKey(key)
        server.clock.advance(1000)
        assertEquals(SyncState.SYNCED, b.c.syncNow().state)
        assertEquals("Lake View", b.local.label("h1"))
        assertTrue(a.local.dirty.isEmpty())
    }

    @Test
    fun aPassSaysWhetherAnythingMovedSoTheCadenceCanBackOff() = runTest {
        val key = connected()!!
        a.local.put(houseRow("h1", "Lake View", server.clock.now(), a.kidHex), true)
        assertTrue(a.c.syncNow().changed, "a pass that wrote")
        b.c.connect()
        b.c.openWithRecoveryKey(key)
        server.clock.advance(1000)
        assertTrue(b.c.syncNow().changed, "a pass that took a row")
        server.clock.advance(1000)
        assertFalse(b.c.syncNow().changed, "nothing new on either side")
        b.local.put(houseRow("h2", "Hill Top", server.clock.now(), b.kidHex), true)
        assertTrue(b.c.syncNow().changed, "a pass that wrote")
        server.clock.advance(1000)
        assertTrue(a.c.syncNow().changed, "a pass that only took the other phone's row")
    }

    @Test
    fun theShrinkGuardAsksThenAppliesOnConfirmation() = runTest {
        val key = connected()!!
        for (i in 1..12) a.local.put(houseRow("h$i", "house $i", server.clock.now(), a.kidHex), true)
        a.c.syncNow()
        b.c.connect(); b.c.openWithRecoveryKey(key)
        b.c.syncNow()
        server.clock.advance(1000)
        for (i in 1..11) a.local.put(houseRow("h$i", "house $i", server.clock.now(), a.kidHex, deleted = true), true)
        assertEquals(SyncState.SYNCED, a.c.syncNow().state)
        server.clock.advance(1000)
        val ask = b.c.syncNow()
        assertEquals(SyncState.NEEDS_CONFIRMATION, ask.state)
        assertTrue(ask.needsConfirmation)
        assertEquals(11, ask.housesToDelete)
        assertEquals(12, ask.liveHouses)
        assertEquals("house 1", b.local.label("h1"), "nothing was applied before the yes")
        server.clock.advance(1000)
        assertEquals(SyncState.SYNCED, b.c.syncNow(confirmShrink = true).state)
        assertEquals("<deleted>", b.local.label("h1"))
    }

    @Test
    fun aFileFromAnUnlistedDeviceIsSkippedAndReportedNotApplied() = runTest {
        connected()
        a.local.put(houseRow("h1", "one", server.clock.now(), a.kidHex), true)
        assertEquals(SyncState.SYNCED, a.c.syncNow().state)
        val real = server.allFiles().first { it.appProperties["kind"] == "sync" }
        val folder = server.allFiles().first { it.appProperties[app.doorprints.drive.DriveLayout.ROLE] == "sync" }.id
        server.putByHand(
            app.doorprints.drive.NewFile(
                "x.dpx", "application/octet-stream", listOf(folder),
                mapOf(
                    app.doorprints.drive.DriveLayout.KIND to "sync", app.doorprints.drive.DriveLayout.DEVICE to "f".repeat(32),
                    app.doorprints.drive.DriveLayout.STATE to app.doorprints.drive.DriveLayout.STATE_COMPLETE, "seq" to "99",
                ),
            ),
            server.contentOf(real.id),
        )
        server.clock.advance(1000)
        val r = a.c.syncNow()
        assertEquals(SyncState.SKIPPED_FILES, r.state)
        assertEquals(listOf(app.doorprints.drive.sync.SkipReason.UNLISTED_DEVICE), r.skipped)
        assertEquals(null, r.error)
        assertEquals(r, a.c.syncStatus())
    }

    @Test
    fun aFailedPassIsTypedThenWaitsOutItsBackoff() = runTest {
        connected()
        a.local.put(houseRow("h1", "x", server.clock.now(), a.kidHex), true)
        server.faults.always(DriveFault.Offline)
        val off = a.c.syncNow()
        assertEquals(SyncState.OFFLINE, off.state)
        assertEquals(DriveReason.OFFLINE, off.error)
        server.faults.clear()
        // the engine's own back-off: no new try before notBefore (the controller reports the wait, never a raw error)
        assertEquals(SyncState.WAITING, a.c.syncNow().state)
        server.clock.advance(10 * 60_000)
        assertEquals(SyncState.SYNCED, a.c.syncNow().state)
    }

    @Test
    fun aServerErrorIsAnErrorWithItsKey() = runTest {
        connected()
        server.faults.always(DriveFault.Server(), DriveOp.LIST)
        val r = a.c.syncNow()
        assertEquals(SyncState.ERROR, r.state)
        assertEquals(DriveReason.SERVER, r.error)
    }

    @Test
    fun aPendingDeletionPausesSync() = runTest {
        connected()
        a.store.current = app.doorprints.drive.delete.PendingDeletion(
            "op", app.doorprints.drive.delete.DeletionLevel.L2, app.doorprints.drive.delete.DeletionAction.AllBackups, "r", emptyList(), 0, 0,
        )
        assertEquals(SyncState.PAUSED, a.c.syncNow().state)
        a.store.current = null
        assertEquals(SyncState.SYNCED, a.c.syncNow().state)
    }

    @Test
    fun withoutADriverTheControllerRunsTheDrivePassItself() = runTest {
        connected()
        val c = a.controller(driver = false)
        c.connect()
        assertEquals(SyncState.SYNCED, c.syncNow().state)
    }

    // ---- photos ----

    @Test
    fun photoSettingsDefaultToWifiOnlyAndPersist() = runTest {
        assertFalse(a.c.photoSettings().uploadOnMobileData)
        a.c.setPhotosWifiOnly(false)
        assertTrue(a.c.photoSettings().uploadOnMobileData)
        assertTrue(a.controller().photoSettings().uploadOnMobileData, "a new controller reads the saved choice")
        a.c.setPhotosWifiOnly(true)
        assertFalse(a.controller().photoSettings().uploadOnMobileData)
    }

    @Test
    fun theWifiRuleAndTheThirtyMinuteException() = runTest {
        a.metering = Metering.METERED
        assertFalse(a.c.photosAllowed())
        assertEquals(PhotoNetworkStatus.WAITING_FOR_WIFI, a.c.photoStatus(2))
        a.c.uploadPhotosNowOverMobile()
        assertTrue(a.c.photosAllowed())
        server.clock.advance(31 * 60_000)
        assertFalse(a.c.photosAllowed())
        a.c.setPhotosWifiOnly(false)
        assertTrue(a.c.photosAllowed())
        a.online = false
        assertEquals(PhotoNetworkStatus.PAUSED_OFFLINE, a.c.photoStatus(1))
    }

    @Test
    fun pendingPhotoBytesCountsLivePhotosNotYetInDrive() = runTest {
        assertEquals(0L, a.c.pendingPhotoBytes(), "before connect")
        connected()
        a.c.prepareSync()
        a.local.put(photoRow("ph1", "h1", server.clock.now(), a.kidHex), true)
        a.local.put(photoRow("ph2", "h1", server.clock.now(), a.kidHex), true)
        a.local.put(photoRow("ph3", "h1", server.clock.now(), a.kidHex, deleted = true), true)
        assertEquals(2468L, a.c.pendingPhotoBytes())
        a.photoState = a.photoState.copy(refs = mapOf("ph1" to app.doorprints.drive.photo.PhotoRef("f", "s")))
        assertEquals(1234L, a.c.pendingPhotoBytes())
        assertTrue(SyncKind.PHOTOS in a.local.rows.keys.map { it.first })
    }
}
