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

import app.doorprints.drive.DeleteFailure
import app.doorprints.drive.DeleteReport
import app.doorprints.drive.DriveClient
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveQuery
import app.doorprints.drive.driveSha256
import app.doorprints.drive.listAll
import app.doorprints.shared.export.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deleting what Doorprints keeps in the person's Drive (S4b-BL-119; docs/15 §3, §10.1), a service over [DriveClient]. No
 * screen: a later ticket's dialog shows [DeletionPlan] and passes the result of device authentication.
 *
 * - [preflight] lists what will go (counts and bytes per kind, no names). It deletes nothing and works offline-refused.
 * - [delete] runs a plan: offline refused; L2/L3 refused without a genuine, fresh, operation-bound [AuthorizationToken];
 *   the list is written to [DeletionStore] before the first delete; permanent `files.delete`, a 404 counts as done.
 * - [resume] finishes a stopped run from the stored list (a fresh authorization again for L2/L3).
 *
 * The rules that keep Drive in a sane state:
 * - **Order**: backups, sync files, photos, shared files, then the read-me, `doorprints.json` and `keys.json` (the last
 *   file), then sub-folders, the root last. A failure in a phase stops the later phases, so `keys.json` never goes while
 *   an encrypted file failed to; `keys.json` goes at all only for [DeletionAction.Everything].
 * - **Only ours**: [DeletionRules.classifyFile]; a folder is deleted only when a listing shows it empty (Drive's folder
 *   delete takes everything inside), so a file someone else put there survives, and so does its folder.
 * - **Nothing is created**: this class has no create or upload call. The root id is the device's own, verified, never searched.
 * - **Nothing is opened**: `keys.json` and `doorprints.json` are deleted by id and kind, never read.
 */
class DriveDeletionService(
    private val drive: DriveClient,
    private val gate: AuthorizationGate,
    private val store: DeletionStore,
    private val isOnline: () -> Boolean,
    private val now: () -> Long,
    private val sha256: () -> Sha256 = ::driveSha256,
    private val saveEvery: Int = 25,
) {
    private val running = Mutex()

    /** What a deletion of [action] would remove from the Drive folder [rootId] (the device's stored root id). */
    suspend fun preflight(rootId: String, action: DeletionAction): PlanResult {
        if (!isOnline()) return PlanResult.Refused(Refusal.OFFLINE)
        try {
            val root = try {
                drive.getFile(rootId)
            } catch (e: DriveException) {
                if (e.kind == DriveException.Kind.NOT_FOUND) return PlanResult.Refused(Refusal.ROOT_NOT_FOUND)
                throw e
            }
            if (!root.isFolder || root.trashed || root.appProperties[DriveLayout.ROLE] != "root") {
                return PlanResult.Refused(Refusal.ROOT_NOT_FOUND)
            }
            val children = drive.listAll(DriveQuery(parentId = rootId, trashed = null))
            val folders = mutableMapOf<String, MutableList<DriveFile>>()
            val rootFiles = mutableListOf<Pair<DriveFile, ItemKind>>()
            var foreign = 0
            for (c in children) {
                if (c.isFolder) {
                    val role = DeletionRules.classifyFolder(c.appProperties, "root")
                    if (role != null) folders.getOrPut(role) { mutableListOf() } += c else foreign++
                } else {
                    val kind = DeletionRules.classifyFile(c.appProperties, "root")
                    if (kind != null) rootFiles += c to kind else foreign++
                }
            }
            val wantsEverything = action == DeletionAction.Everything
            val inFolders = mutableMapOf<String, MutableList<Pair<DriveFile, ItemKind>>>()
            for (role in DeletionRules.SUBFOLDER_ROLES) {
                if (!wantsEverything && role != "backups") continue
                for (folder in folders[role].orEmpty()) {
                    for (f in drive.listAll(DriveQuery(parentId = folder.id, trashed = null))) {
                        val kind = if (f.isFolder) null else DeletionRules.classifyFile(f.appProperties, role)
                        if (kind != null) inFolders.getOrPut(role) { mutableListOf() } += f to kind else foreign++
                    }
                }
            }
            val items = mutableListOf<DeletionItem>()
            val level: DeletionLevel
            if (wantsEverything) {
                level = DeletionLevel.L3
                val ordered = inFolders.values.flatten() + rootFiles
                items += ordered.sortedWith(
                    compareBy({ DeletionRules.phaseOf(it.second) }, { createdAtOf(it.first) }, { it.first.id }),
                ).map { (f, k) -> item(f, k) }
                for (role in DeletionRules.SUBFOLDER_ROLES) {
                    items += folders[role].orEmpty().sortedBy { it.id }.map { DeletionItem(it.id, ItemKind.FOLDER, 0, DeletionRules.phaseOf(ItemKind.FOLDER, role)) }
                }
                items += DeletionItem(rootId, ItemKind.FOLDER, 0, DeletionRules.phaseOf(ItemKind.FOLDER, "root"))
            } else {
                val backups = inFolders["backups"].orEmpty().map { (f, _) ->
                    DeletionRules.BackupRef(f.id, f.appProperties[DriveLayout.STATE] == DriveLayout.STATE_COMPLETE, createdAtOf(f))
                }
                val selection = DeletionRules.selectBackups(action, backups)
                val ids = selection.ids ?: return PlanResult.Refused(Refusal.NOT_A_BACKUP)
                if (ids.isEmpty()) return PlanResult.Refused(Refusal.NOTHING_TO_DELETE)
                level = selection.level
                val byId = inFolders["backups"].orEmpty().associate { it.first.id to it.first }
                items += ids.map { item(byId.getValue(it), ItemKind.BACKUP) }
            }
            val totals = items.groupBy { it.kind }.mapValues { (_, v) -> KindTotal(v.size, v.sumOf { it.bytes }) }
            val plan = DeletionPlan(
                action, level, rootId, items, totals, foreign, now(),
                DeletionRules.operationId(level, rootId, items.map { it.id }, sha256),
            )
            return PlanResult.Ready(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            return PlanResult.Refused(if (e.kind == DriveException.Kind.OFFLINE) Refusal.OFFLINE else Refusal.DRIVE_ERROR, e)
        }
    }

    /** Runs [plan]. Nothing is deleted unless the result is [DeletionOutcome.Ran]. */
    suspend fun delete(plan: DeletionPlan, token: AuthorizationToken?): DeletionOutcome = running.withLock {
        if (!isOnline()) return DeletionOutcome.Refused(Refusal.OFFLINE)
        val pending = store.pending()
        if (pending != null && pending.operationId != plan.operationId) {
            return DeletionOutcome.Refused(Refusal.OTHER_DELETION_PENDING)
        }
        if (now() - plan.createdAtMs > DeletionRules.PLAN_MAX_AGE_MS || now() < plan.createdAtMs) {
            return DeletionOutcome.Refused(Refusal.STALE_PLAN)
        }
        authorizationRefusal(token, plan.level, plan.operationId)?.let { return DeletionOutcome.Refused(it) }
        val list = pending ?: PendingDeletion(
            plan.operationId, plan.level, plan.action, plan.rootId, plan.items, plan.items.size, plan.createdAtMs,
        ).also { store.savePending(it) }
        return run(list, token)
    }

    /** Finishes the deletion a stop left ([DeletionStore.pending]). L2 and L3 need a fresh authorization again. */
    suspend fun resume(token: AuthorizationToken?): DeletionOutcome = running.withLock {
        if (!isOnline()) return DeletionOutcome.Refused(Refusal.OFFLINE)
        val pending = store.pending() ?: return DeletionOutcome.Refused(Refusal.NOTHING_PENDING)
        authorizationRefusal(token, pending.level, pending.operationId)?.let { return DeletionOutcome.Refused(it) }
        return run(pending, token)
    }

    private suspend fun authorizationRefusal(token: AuthorizationToken?, level: DeletionLevel, operationId: String): Refusal? {
        DeletionRules.authorizationProblem(token, level, operationId, now())?.let { return it }
        if (level != DeletionLevel.L1 && !gate.isGenuine(token!!)) return Refusal.NOT_AUTHORIZED
        return null
    }

    private suspend fun run(pending: PendingDeletion, token: AuthorizationToken?): DeletionOutcome {
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<DeleteFailure>()
        val kept = mutableListOf<String>()
        val items = pending.items
        var firstError: DriveException? = null
        var stopped: StopReason? = null
        var blockedFrom = Int.MAX_VALUE
        var index = 0
        var sinceSave = 0
        while (index < items.size) {
            val it = items[index]
            if (it.phase >= blockedFrom) break
            if (pending.level != DeletionLevel.L1 && !gate.stillHolds(token!!)) {
                stopped = StopReason.AUTHORIZATION_LOST
                break
            }
            try {
                if (it.kind == ItemKind.FOLDER && drive.list(DriveQuery(parentId = it.id, trashed = null), null, 1).files.isNotEmpty()) {
                    kept += it.id
                } else {
                    drive.delete(it.id)
                    deleted += it.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                failed += DeleteFailure(it.id, e.kind, e.httpStatus)
                if (firstError == null) firstError = e
                if (DeletionRules.stopsRun(e.kind)) {
                    stopped = StopReason.DRIVE_ERROR
                    break
                }
                blockedFrom = minOf(blockedFrom, it.phase + 1)
            }
            index++
            if (++sinceSave >= saveEvery) {
                sinceSave = 0
                val gone = (deleted + kept).toSet()
                store.savePending(pending.copy(items = items.filter { r -> r.id !in gone }))
            }
        }
        val done = (deleted + kept).toSet()
        val left = items.filter { it.id !in done }
        if (stopped == null && left.isNotEmpty()) stopped = StopReason.FILES_FAILED
        val report = DeleteReport(deleted, left.map { it.id }, firstError, failed)
        if (left.isNotEmpty()) {
            store.savePending(pending.copy(items = left))
            return DeletionOutcome.Ran(report, false, pending.total, kept, stopped)
        }
        var marker: DeletedMarker? = null
        if (pending.level == DeletionLevel.L3) {
            marker = DeletedMarker(DeletionLevel.L3, now(), "everything")
            store.recordFinished(marker, forgetFolder = true)
        } else if (pending.action == DeletionAction.AllBackups) {
            marker = DeletedMarker(DeletionLevel.L2, now(), "allBackups")
            store.recordFinished(marker, forgetFolder = false)
        }
        store.clearPending()
        return DeletionOutcome.Ran(report, true, pending.total, kept, null, marker)
    }

    private fun item(file: DriveFile, kind: ItemKind) = DeletionItem(file.id, kind, file.size ?: 0, DeletionRules.phaseOf(kind))

    private fun createdAtOf(file: DriveFile): Long =
        file.appProperties[DriveLayout.CREATED_AT]?.toLongOrNull()?.takeIf { it >= 0 } ?: file.createdTime
}
