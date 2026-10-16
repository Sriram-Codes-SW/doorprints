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

package app.doorprints.drive.delete

import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveOp
import app.doorprints.drive.DriveRetry
import app.doorprints.drive.FOLDER_MIME
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.InMemoryFakeDrive
import app.doorprints.drive.NewFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DriveDeletionService] over [InMemoryFakeDrive] and its [app.doorprints.drive.FaultScript] (S4b-BL-119, docs/06
 * TC-U-12x): every level, a stop at every step then a resume, 404s, rate and quota faults, refused without (fresh)
 * authorization, refused offline, the order, and files that are not Doorprints' own.
 */
class DriveDeletionServiceTest {

    // --- the fakes behind the seams ---

    private class FakeGate : AuthorizationGate {
        var genuine = true
        var lockOn = true
        var holdsFor = Int.MAX_VALUE
        var asked = 0

        override suspend fun isGenuine(token: AuthorizationToken) = genuine && token.proof == "ok"

        override suspend fun stillHolds(token: AuthorizationToken): Boolean = lockOn && ++asked <= holdsFor
    }

    private class FakeStore(val crashy: Boolean = false) : DeletionStore {
        var current: PendingDeletion? = null
        var marker: DeletedMarker? = null
        var folderForgotten = false
        var saves = 0

        override suspend fun pending() = current
        override suspend fun savePending(pending: PendingDeletion) {
            saves++
            if (!crashy || current == null) current = pending
        }

        override suspend fun clearPending() {
            current = null
        }

        override suspend fun marker() = marker
        override suspend fun recordFinished(marker: DeletedMarker, forgetFolder: Boolean) {
            this.marker = marker
            if (forgetFolder) folderForgotten = true
        }
    }

    private class Rig(crashy: Boolean = false) {
        val server = FakeDriveServer()
        val drive = InMemoryFakeDrive(server, retry = DriveRetry(random = { 0.5 }, sleep = { server.clock.sleep(it) }))
        val gate = FakeGate()
        val store = FakeStore(crashy)
        var online = true
        val service = DriveDeletionService(drive, gate, store, { online }, server.clock::now, saveEvery = 3)

        val root = folder("Doorprints", "root", null)
        val backups = folder("Backups", "backups", root.id)
        val sync = folder("Sync", "sync", root.id)
        val photos = folder("Photos", "photos", root.id)
        val shared = folder("Shared", "shared", root.id)
        val b1 = file("backup-1", "backup", backups.id, 100, complete = true)
        val b2 = file("backup-2", "backup", backups.id, 200, complete = true)
        val b3 = file("backup-3", "backup", backups.id, 300, complete = true)
        val bPartial = file("partial-backup", "backup", backups.id, 400, complete = false)
        val s1 = file("device-a", "sync", sync.id, 0)
        val s2 = file("device-b", "sync", sync.id, 0)
        val p1 = file("p-1", "photo", photos.id, 0)
        val p2 = file("p-2", "photo", photos.id, 0)
        val p3 = file("p-3", "photo", photos.id, 0)
        val sh = file("shared-1", "shared", shared.id, 0)
        val readme = file("Read me.txt", "readme", root.id, 0)
        val control = file("doorprints.json", "control", root.id, 0)
        val keys = file("keys.json", "keys", root.id, 0)

        fun folder(name: String, role: String, parent: String?) =
            server.putByHand(NewFile(name, FOLDER_MIME, listOfNotNull(parent), mapOf(DriveLayout.ROLE to role)))

        fun file(name: String, kind: String, parent: String, createdAt: Long, complete: Boolean = true): DriveFile {
            val props = buildMap {
                put(DriveLayout.KIND, kind)
                if (kind == "backup") {
                    put(DriveLayout.CREATED_AT, createdAt.toString())
                    put(DriveLayout.STATE, if (complete) DriveLayout.STATE_COMPLETE else DriveLayout.STATE_PARTIAL)
                }
            }
            return server.putByHand(NewFile(name, "application/octet-stream", listOf(parent), props), ByteArray(10 + name.length))
        }

        fun foreign(name: String, parent: String, props: Map<String, String> = emptyMap()) =
            server.putByHand(NewFile(name, "text/plain", listOf(parent), props), ByteArray(5))

        val now get() = server.clock.now()

        fun token(plan: DeletionPlan, level: DeletionLevel = plan.level, at: Long = now, proof: String = "ok") =
            AuthorizationToken(level, at, plan.operationId, proof)

        suspend fun plan(action: DeletionAction, rootId: String = root.id): DeletionPlan =
            (service.preflight(rootId, action) as PlanResult.Ready).plan

        fun deletes(): List<String> = server.requests.filter { it.first == DriveOp.DELETE }.map { it.second!! }
        fun exists(id: String) = server.fileOrNull(id) != null
        fun idsOf(vararg f: DriveFile) = f.map { it.id }
    }

    private fun ran(o: DeletionOutcome) = assertIs<DeletionOutcome.Ran>(o)
    private fun refused(o: DeletionOutcome) = assertIs<DeletionOutcome.Refused>(o).reason

    // --- the pre-flight ---

    @Test
    fun theTreeIsListedWithCountsAndSizesAndNoNames() = runTest {
        val r = Rig()
        r.foreign("holiday-photos.txt", r.root.id)
        val plan = r.plan(DeletionAction.Everything)
        assertEquals(DeletionLevel.L3, plan.level)
        assertEquals(KindTotal(4, r.listOf(r.b1, r.b2, r.b3, r.bPartial).sumOf { it.size!! }), plan.totals[ItemKind.BACKUP])
        assertEquals(2, plan.totals[ItemKind.SYNC]!!.count)
        assertEquals(3, plan.totals[ItemKind.PHOTO]!!.count)
        assertEquals(1, plan.totals[ItemKind.SHARED]!!.count)
        assertEquals(1, plan.totals[ItemKind.KEYS]!!.count)
        assertEquals(5, plan.totals[ItemKind.FOLDER]!!.count)
        assertEquals(1, plan.foreignKept)
        assertEquals(r.server.allFiles().size - 1, plan.items.size)
        val text = plan.toString() + plan.items.joinToString() + plan.operationId
        for (f in r.server.allFiles()) assertFalse(f.name in text, "no name in ${f.name}")
        assertTrue(r.deletes().isEmpty(), "the pre-flight deletes nothing")
    }

    private fun Rig.listOf(vararg f: DriveFile) = f.map { server.fileOrNull(it.id)!! }

    @Test
    fun theRootIsTheDevicesOwnAndVerified() = runTest {
        val r = Rig()
        val other = r.server.putByHand(NewFile("Doorprints", FOLDER_MIME, emptyList(), mapOf(DriveLayout.ROLE to "root")))
        assertEquals(
            Refusal.ROOT_NOT_FOUND,
            assertIs<PlanResult.Refused>(r.service.preflight("nope-id", DeletionAction.Everything)).reason,
        )
        assertEquals(Refusal.ROOT_NOT_FOUND, assertIs<PlanResult.Refused>(r.service.preflight(r.sync.id, DeletionAction.Everything)).reason)
        r.server.trashByHand(other.id)
        assertEquals(Refusal.ROOT_NOT_FOUND, assertIs<PlanResult.Refused>(r.service.preflight(other.id, DeletionAction.Everything)).reason)
        // a second root planted elsewhere is never searched for: the plan covers the stored root only
        val plan = r.plan(DeletionAction.Everything)
        assertTrue(other.id !in plan.items.map { it.id })
    }

    @Test
    fun aBackupActionOnSomethingThatIsNotABackupIsRefused() = runTest {
        val r = Rig()
        r.foreign("planted.dpx", r.sync.id, mapOf(DriveLayout.KIND to "backup"))
        for (id in listOf(r.s1.id, r.keys.id, r.backups.id, "missing")) {
            assertEquals(Refusal.NOT_A_BACKUP, assertIs<PlanResult.Refused>(r.service.preflight(r.root.id, DeletionAction.OneBackup(id))).reason)
        }
        val nothing = r.service.preflight(r.root.id, DeletionAction.OneBackup(r.server.allFiles().first { it.name == "planted.dpx" }.id))
        assertEquals(Refusal.NOT_A_BACKUP, assertIs<PlanResult.Refused>(nothing).reason)
    }

    // --- levels ---

    @Test
    fun deleteOneBackupIsL1AndNeedsNoToken() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.OneBackup(r.b2.id))
        assertEquals(DeletionLevel.L1, plan.level)
        val out = ran(r.service.delete(plan, null))
        assertTrue(out.finished)
        assertEquals(listOf(r.b2.id), r.deletes())
        assertFalse(r.exists(r.b2.id))
        for (f in r.idsOf(r.b1, r.b3, r.bPartial, r.s1, r.p1, r.keys, r.control, r.backups, r.root)) assertTrue(r.exists(f))
        assertNull(r.store.marker)
        assertNull(r.store.current)
    }

    @Test
    fun theLastCompleteBackupIsL2() = runTest {
        val r = Rig()
        r.server.deleteByHand(r.b1.id)
        r.server.deleteByHand(r.b2.id)
        val plan = r.plan(DeletionAction.OneBackup(r.b3.id))
        assertEquals(DeletionLevel.L2, plan.level)
        assertEquals(Refusal.NOT_AUTHORIZED, refused(r.service.delete(plan, null)))
        assertTrue(r.exists(r.b3.id))
        assertTrue(ran(r.service.delete(plan, r.token(plan))).finished)
        assertFalse(r.exists(r.b3.id))
        assertNull(r.store.marker, "deleting one backup does not switch automatic backup off")
    }

    @Test
    fun olderBackupsKeepsTheNewestCompleteAndANewerPartialUpload() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.OlderBackups)
        assertEquals(DeletionLevel.L2, plan.level)
        assertEquals(r.idsOf(r.b1, r.b2), plan.items.map { it.id })
        ran(r.service.delete(plan, r.token(plan)))
        assertEquals(r.idsOf(r.b1, r.b2), r.deletes())
        assertTrue(r.exists(r.b3.id) && r.exists(r.bPartial.id))
    }

    @Test
    fun olderBackupsWithOnlyOneCompleteBackupHasNothingToDelete() = runTest {
        val r = Rig()
        r.server.deleteByHand(r.b1.id)
        r.server.deleteByHand(r.b2.id)
        assertEquals(Refusal.NOTHING_TO_DELETE, assertIs<PlanResult.Refused>(r.service.preflight(r.root.id, DeletionAction.OlderBackups)).reason)
    }

    @Test
    fun allBackupsGoesOldestFirstAndLeavesEverythingElseAndSwitchesBackupOff() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertTrue(out.finished)
        assertEquals(r.idsOf(r.b1, r.b2, r.b3, r.bPartial), r.deletes())
        for (f in r.idsOf(r.backups, r.s1, r.s2, r.p1, r.sh, r.keys, r.control, r.root)) assertTrue(r.exists(f))
        val marker = assertNotNull(r.store.marker)
        assertEquals(ReconnectPath.ASK_BEFORE_BACKUP, marker.reconnectPath)
        assertFalse(r.store.folderForgotten, "sync goes on: the folder is still known")
    }

    @Test
    fun everythingDeletesDataFirstThenKeysThenFoldersAndForgetsTheFolder() = runTest {
        val r = Rig()
        val before = r.server.allFiles().size
        val plan = r.plan(DeletionAction.Everything)
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertTrue(out.finished)
        assertEquals(before, out.report.deleted.size)
        assertTrue(r.server.allFiles().isEmpty())
        val order = r.deletes()
        val pos = { f: DriveFile -> order.indexOf(f.id) }
        val data = listOf(r.b1, r.b2, r.b3, r.bPartial, r.s1, r.s2, r.p1, r.p2, r.p3, r.sh)
        for (d in data) {
            assertTrue(pos(d) < pos(r.readme) && pos(r.readme) < pos(r.control) && pos(r.control) < pos(r.keys), "data, read-me, control, keys")
        }
        assertEquals(r.keys.id, order[order.size - 1 - 5], "keys.json is the last file, then four folders and the root")
        assertEquals(r.root.id, order.last())
        for (folder in listOf(r.backups, r.sync, r.photos, r.shared)) assertTrue(pos(r.keys) < pos(folder) && pos(folder) < pos(r.root))
        // backups, then sync, then photos, then shared
        assertTrue(pos(r.b3) < pos(r.s1) && pos(r.s2) < pos(r.p1) && pos(r.p3) < pos(r.sh))
        val marker = assertNotNull(out.marker)
        assertEquals(ReconnectPath.FULL_FIRST_CONNECT, marker.reconnectPath)
        assertTrue(r.store.folderForgotten)
        assertNull(r.store.current)
    }

    @Test
    fun nothingIsCreatedOrUploadedAndNothingIsReadOrTrashed() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.Everything)
        ran(r.service.delete(plan, r.token(plan)))
        val ops = r.server.requests.map { it.first }.toSet()
        assertTrue(ops.all { it in setOf(DeletionOps.GET, DeletionOps.LIST, DeletionOps.DELETE) }, "ops were $ops")
    }

    private object DeletionOps {
        val GET = DriveOp.GET
        val LIST = DriveOp.LIST
        val DELETE = DriveOp.DELETE
    }

    @Test
    fun trashedFilesOfOursGoToo() = runTest {
        val r = Rig()
        r.server.trashByHand(r.b1.id)
        val plan = r.plan(DeletionAction.AllBackups)
        assertTrue(r.b1.id in plan.items.map { it.id })
        ran(r.service.delete(plan, r.token(plan)))
        assertFalse(r.exists(r.b1.id))
    }

    // --- not ours ---

    @Test
    fun aForeignFileIsNeverDeletedAndKeepsItsFolders() = runTest {
        val r = Rig()
        val mine = r.foreign("tax-return.pdf", r.photos.id)
        val wrongPlace = r.foreign("fake-keys", r.backups.id, mapOf(DriveLayout.KIND to "keys"))
        val rootStranger = r.foreign("notes.txt", r.root.id)
        val nested = r.server.putByHand(NewFile("Mine", FOLDER_MIME, listOf(r.root.id), emptyMap()))
        val inNested = r.foreign("deep.txt", nested.id, mapOf(DriveLayout.KIND to "backup"))
        val plan = r.plan(DeletionAction.Everything)
        assertEquals(4, plan.foreignKept)
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertTrue(out.finished)
        for (f in listOf(mine, wrongPlace, rootStranger, nested, inNested)) assertTrue(r.exists(f.id), f.name)
        assertEquals(setOf(r.photos.id, r.backups.id, r.root.id), out.keptIds.toSet())
        assertFalse(r.exists(r.sync.id) || r.exists(r.shared.id), "empty folders of ours go")
        for (id in r.idsOf(r.b1, r.s1, r.p1, r.sh, r.keys, r.control, r.readme)) assertFalse(r.exists(id))
        assertTrue(r.store.folderForgotten, "our files are gone: the folder is forgotten")
    }

    @Test
    fun aFileAddedWhileDeletingKeepsTheFolder() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.Everything)
        val callsBefore = r.server.requests.size
        var added: DriveFile? = null
        r.server.faults.onCall(callsBefore + 1, DriveFault.Interleave { s ->
            added = s.putByHand(NewFile("late.txt", "text/plain", listOf(r.shared.id), emptyMap()))
        })
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertTrue(out.finished)
        assertTrue(r.exists(added!!.id))
        assertTrue(r.shared.id in out.keptIds && r.root.id in out.keptIds)
    }

    @Test
    fun aBackupKindFileInTheSyncFolderIsNotABackupToDelete() = runTest {
        val r = Rig()
        val planted = r.foreign("planted", r.sync.id, mapOf(DriveLayout.KIND to "backup"))
        val plan = r.plan(DeletionAction.AllBackups)
        assertFalse(planted.id in plan.items.map { it.id })
        ran(r.service.delete(plan, r.token(plan)))
        assertTrue(r.exists(planted.id))
    }

    // --- authorization ---

    @Test
    fun l2AndL3AreRefusedWithoutAGrantedFreshOperationBoundToken() = runTest {
        val r = Rig()
        for (action in listOf(DeletionAction.AllBackups, DeletionAction.OlderBackups, DeletionAction.Everything)) {
            val plan = r.plan(action)
            val other = r.plan(if (action == DeletionAction.Everything) DeletionAction.AllBackups else DeletionAction.Everything)
            assertEquals(Refusal.NOT_AUTHORIZED, refused(r.service.delete(plan, null)))
            assertEquals(Refusal.NOT_AUTHORIZED, refused(r.service.delete(plan, r.token(plan, proof = "forged"))))
            assertEquals(Refusal.AUTHORIZATION_OTHER_OPERATION, refused(r.service.delete(plan, r.token(other, level = DeletionLevel.L3))))
            assertEquals(Refusal.AUTHORIZATION_STALE, refused(r.service.delete(plan, r.token(plan, at = r.now - 61_000))))
            assertEquals(Refusal.AUTHORIZATION_STALE, refused(r.service.delete(plan, r.token(plan, at = r.now + 5_000))))
            r.gate.genuine = false
            assertEquals(Refusal.NOT_AUTHORIZED, refused(r.service.delete(plan, r.token(plan))))
            r.gate.genuine = true
        }
        val everything = r.plan(DeletionAction.Everything)
        assertEquals(Refusal.AUTHORIZATION_TOO_WEAK, refused(r.service.delete(everything, r.token(everything, level = DeletionLevel.L2))))
        assertTrue(r.deletes().isEmpty(), "nothing was deleted by any refusal")
        assertNull(r.store.current, "a refusal leaves no list behind")
    }

    @Test
    fun anAuthorizationThatAgedWhilePlanningIsRefused() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        val t = r.token(plan)
        r.server.clock.advance(61_000)
        assertEquals(Refusal.AUTHORIZATION_STALE, refused(r.service.delete(plan, t)))
        r.server.clock.advance(10 * 60_000)
        assertEquals(Refusal.STALE_PLAN, refused(r.service.delete(plan, r.token(plan))))
        assertTrue(r.deletes().isEmpty())
    }

    @Test
    fun theLockRemovedHalfWayStopsBeforeTheNextFileAndAFreshGrantFinishes() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.Everything)
        r.gate.holdsFor = 4
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertFalse(out.finished)
        assertEquals(StopReason.AUTHORIZATION_LOST, out.stopped)
        assertEquals(4, r.deletes().size)
        assertEquals(plan.items.size - 4, out.report.left.size)
        assertTrue(r.exists(r.keys.id))
        // the old token cannot finish it; a new one can
        r.gate.holdsFor = Int.MAX_VALUE
        r.server.clock.advance(61_000)
        assertEquals(Refusal.AUTHORIZATION_STALE, refused(r.service.resume(r.token(plan, at = r.now - 61_000))))
        val done = ran(r.service.resume(r.token(plan)))
        assertTrue(done.finished)
        assertTrue(r.server.allFiles().isEmpty())
    }

    // --- offline ---

    @Test
    fun offlineIsRefusedAndNothingIsDeletedOrQueued() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.Everything)
        r.online = false
        assertEquals(Refusal.OFFLINE, refused(r.service.delete(plan, r.token(plan))))
        assertEquals(Refusal.OFFLINE, assertIs<PlanResult.Refused>(r.service.preflight(r.root.id, DeletionAction.Everything)).reason)
        assertEquals(Refusal.OFFLINE, refused(r.service.resume(r.token(plan))))
        assertTrue(r.deletes().isEmpty())
        assertNull(r.store.current)
        r.online = true
        r.server.faults.always(DriveFault.Offline)
        assertEquals(Refusal.OFFLINE, assertIs<PlanResult.Refused>(r.service.preflight(r.root.id, DeletionAction.Everything)).reason)
    }

    @Test
    fun theConnectionDroppingMidRunReportsWhatIsLeftAndTryAgainFinishes() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        r.server.faults.stopAfter(r.server.requests.size + 2)
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertFalse(out.finished)
        assertEquals(StopReason.DRIVE_ERROR, out.stopped)
        assertEquals(DriveException.Kind.OFFLINE, out.report.error!!.kind)
        assertEquals(r.idsOf(r.b1, r.b2), out.report.deleted)
        assertEquals(r.idsOf(r.b3, r.bPartial), out.report.left)
        assertNull(r.store.marker, "not finished: no marker yet")
        assertNotNull(r.store.current)
        r.server.faults.clear()
        val done = ran(r.service.resume(r.token(plan)))
        assertTrue(done.finished)
        assertNull(r.store.current)
        assertNotNull(r.store.marker)
        assertFalse(r.exists(r.b3.id) || r.exists(r.bPartial.id))
    }

    // --- faults ---

    @Test
    fun aFileThatIsAlreadyGoneIsSuccess() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        r.server.deleteByHand(r.b2.id)
        r.server.faults.on(DriveOp.DELETE, 4, DriveFault.NotFound)
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertTrue(out.finished)
        assertTrue(out.report.failed.isEmpty() && out.report.error == null)
        assertEquals(4, out.report.deleted.size)
        assertTrue(r.exists(r.bPartial.id), "the fault answered 404 but did nothing: that file is still there")
    }

    @Test
    fun aRateLimitIsRetriedAndALongOneStopsTheRun() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        r.server.faults.next(DriveFault.RateLimited(retryAfterMs = 2_000), DriveOp.DELETE, times = 2)
        val first = ran(r.service.delete(plan, r.token(plan)))
        assertTrue(first.finished)
        assertEquals(listOf(2_000L, 2_000L), r.server.clock.slept)

        val r2 = Rig()
        val plan2 = r2.plan(DeletionAction.AllBackups)
        r2.server.faults.on(DriveOp.DELETE, 2, DriveFault.RateLimited(retryAfterMs = 120_000))
        val out = ran(r2.service.delete(plan2, r2.token(plan2)))
        assertFalse(out.finished)
        assertEquals(DriveException.Kind.RATE_LIMITED, out.report.error!!.kind)
        assertEquals(r2.idsOf(r2.b1), out.report.deleted)
        assertEquals(3, out.report.left.size)
        assertEquals(listOf(DriveException.Kind.RATE_LIMITED), out.report.failed.map { it.kind })
        assertTrue(ran(r2.service.resume(r2.token(plan2))).finished)
    }

    @Test
    fun aQuotaFaultStopsAndIsReportedPerFile() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        r.server.faults.on(DriveOp.DELETE, 1, DriveFault.QuotaExceeded)
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertEquals(DriveException.Kind.QUOTA_EXCEEDED, out.report.failed.single().kind)
        assertEquals(r.b1.id, out.report.failed.single().fileId)
        assertEquals(4, out.report.left.size)
        assertTrue(r.deletes().size == 1)
    }

    @Test
    fun aFileThatFailsBlocksTheLaterPhasesSoKeysJsonNeverGoesAlone() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.Everything)
        // the first sync file is refused (403); the rest of the sync phase still goes, but not photos, keys or folders
        r.server.faults.on(DriveOp.DELETE, 5, DriveFault.Forbidden)
        val out = ran(r.service.delete(plan, r.token(plan)))
        assertFalse(out.finished)
        assertEquals(StopReason.FILES_FAILED, out.stopped)
        assertEquals(listOf(r.s1.id), out.report.failed.map { it.fileId })
        assertEquals(DriveException.Kind.FORBIDDEN, out.report.failed.single().kind)
        assertTrue(r.exists(r.keys.id) && r.exists(r.control.id) && r.exists(r.p1.id))
        assertFalse(r.exists(r.s2.id), "the same phase went on")
        assertTrue(r.exists(r.s1.id))
        assertNull(out.marker)
        assertFalse(r.store.folderForgotten)
        // Try again finishes
        val done = ran(r.service.resume(r.token(plan)))
        assertTrue(done.finished)
        assertTrue(r.server.allFiles().isEmpty())
    }

    @Test
    fun aStopAtEveryStepThenAResumeEndsWithNothingLeftAndKeysAlwaysLast() = runTest {
        val total = run {
            val r = Rig()
            val plan = r.plan(DeletionAction.Everything)
            val before = r.server.requests.size
            ran(r.service.delete(plan, r.token(plan)))
            r.server.requests.size - before
        }
        for (crashy in listOf(false, true)) {
            for (stopAt in 0..total + 1) {
                val r = Rig(crashy)
                val plan = r.plan(DeletionAction.Everything)
                val base = r.server.requests.size
                r.server.faults.stopAfter(base + stopAt)
                val first = ran(r.service.delete(plan, r.token(plan)))
                // the invariant: keys.json gone means no encrypted data file is left
                if (!r.exists(r.keys.id)) {
                    val dataLeft = r.server.allFiles().filter { it.appProperties[DriveLayout.KIND] in setOf("backup", "sync", "photo", "shared") }
                    assertTrue(dataLeft.isEmpty(), "stop at $stopAt crashy=$crashy left data without keys")
                }
                r.server.faults.clear()
                if (!first.finished) {
                    assertNotNull(r.store.current)
                    val second = ran(r.service.resume(r.token(plan)))
                    assertTrue(second.finished, "stop at $stopAt crashy=$crashy")
                }
                assertTrue(r.server.allFiles().isEmpty(), "stop at $stopAt crashy=$crashy")
                assertNull(r.store.current)
                assertTrue(r.store.folderForgotten)
                assertTrue(r.server.requests.none { it.first == DriveOp.CREATE || it.first == DriveOp.UPLOAD })
                // keys.json was the last file: every file deleted before it is data or the read-me / control file
                val order = r.deletes().distinct()
                val keysAt = order.indexOf(r.keys.id)
                val after = order.drop(keysAt + 1).toSet()
                assertEquals(setOf(r.backups.id, r.sync.id, r.photos.id, r.shared.id, r.root.id), after, "stop at $stopAt crashy=$crashy")
            }
        }
    }

    @Test
    fun aStopAtEveryStepOfAllBackupsKeepsKeysAndEverythingElse() = runTest {
        for (stopAt in 0..12) {
            val r = Rig()
            val plan = r.plan(DeletionAction.AllBackups)
            r.server.faults.stopAfter(r.server.requests.size + stopAt)
            val first = ran(r.service.delete(plan, r.token(plan)))
            r.server.faults.clear()
            if (!first.finished) assertTrue(ran(r.service.resume(r.token(plan))).finished)
            for (id in r.idsOf(r.b1, r.b2, r.b3, r.bPartial)) assertFalse(r.exists(id))
            for (id in r.idsOf(r.keys, r.control, r.s1, r.p1, r.sh, r.backups, r.root)) assertTrue(r.exists(id), "stop at $stopAt")
        }
    }

    // --- the list on the device ---

    @Test
    fun theListIsWrittenBeforeTheFirstDeleteAndAnotherDeletionWaits() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        var seen: PendingDeletion? = null
        r.server.faults.on(DriveOp.DELETE, 1, DriveFault.Interleave { seen = r.store.current })
        r.server.faults.on(DriveOp.DELETE, 2, DriveFault.Offline)
        r.server.faults.always(DriveFault.Offline, DriveOp.DELETE)
        ran(r.service.delete(plan, r.token(plan)))
        assertEquals(plan.items, seen?.items, "the whole list was on the device before the first delete")
        assertEquals(plan.operationId, seen?.operationId)
        // an unfinished deletion blocks a different one until it is finished
        r.server.faults.clear()
        val other = r.plan(DeletionAction.Everything)
        assertEquals(Refusal.OTHER_DELETION_PENDING, refused(r.service.delete(other, r.token(other))))
        assertTrue(ran(r.service.resume(r.token(plan))).finished)
        assertEquals(Refusal.NOTHING_PENDING, refused(r.service.resume(r.token(plan))))
    }

    @Test
    fun theSameDeletionRunAgainFromItsPlanFinishes() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.AllBackups)
        r.server.faults.on(DriveOp.DELETE, 2, DriveFault.Offline)
        r.server.faults.always(DriveFault.Offline, DriveOp.DELETE)
        assertFalse(ran(r.service.delete(plan, r.token(plan))).finished)
        r.server.faults.clear()
        assertTrue(ran(r.service.delete(plan, r.token(plan))).finished)
    }

    @Test
    fun theProgressIsSavedAlongTheWay() = runTest {
        val r = Rig()
        val plan = r.plan(DeletionAction.Everything)
        ran(r.service.delete(plan, r.token(plan)))
        assertTrue(r.store.saves > 2, "saveEvery = 3 on ${plan.items.size} items")
    }
}
