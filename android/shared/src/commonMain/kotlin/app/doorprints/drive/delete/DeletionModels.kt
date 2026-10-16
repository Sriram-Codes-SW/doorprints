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

import app.doorprints.drive.DeleteReport
import app.doorprints.drive.DriveException

/*
 * Deleting what Doorprints keeps in Drive (S4b-BL-119; docs/15 §3, §10.1): the values the service and a later screen
 * share. No names of houses anywhere: a plan carries ids, kinds and sizes only. Web twin: `drive-deletion.ts`.
 */

/** How much proof the person owes (docs/15 §10.1); a stronger grant covers a weaker level. */
enum class DeletionLevel { L1, L2, L3 }

/** What the person asked for. */
sealed interface DeletionAction {
    /** *Delete this backup*: L1, or L2 when it is the last complete backup left. */
    data class OneBackup(val fileId: String) : DeletionAction

    /** *Delete older backups*: every backup but the newest complete one (and any newer, still partial, upload): L2. */
    data object OlderBackups : DeletionAction

    /** *Delete all backups*: every file in `Backups`: L2. */
    data object AllBackups : DeletionAction

    /** *Delete everything Doorprints keeps in my Google Drive*: L3. */
    data object Everything : DeletionAction
}

/** The kinds of thing in the folder, in the order the pre-flight lists them. [code] is the vector spelling. */
enum class ItemKind(val code: String) {
    BACKUP("backup"), SYNC("sync"), PHOTO("photo"), SHARED("shared"), README("readme"), CONTROL("control"), KEYS("keys"), FOLDER("folder"),
}

/** One file or folder to delete. [phase] orders the run ([DeletionRules.phaseOf]); sort keys make it deterministic. */
data class DeletionItem(val id: String, val kind: ItemKind, val bytes: Long, val phase: Int)

/** Count and bytes of one kind, for the dialog ("14 backups, 212 photos (1.4 GB)"). */
data class KindTotal(val count: Int, val bytes: Long)

/**
 * What the pre-flight found and what will go, **exactly**: [items] in deletion order, [totals] per kind, never a name.
 * [foreignKept] counts files in the folder that Doorprints did not make (they stay, and keep their folder in place).
 */
data class DeletionPlan(
    val action: DeletionAction,
    val level: DeletionLevel,
    val rootId: String,
    val items: List<DeletionItem>,
    val totals: Map<ItemKind, KindTotal>,
    val foreignKept: Int,
    val createdAtMs: Long,
    /** The id the device authentication is bound to (docs/15 §10.2), from the level and the ordered ids. */
    val operationId: String,
) {
    val fileCount: Int get() = items.count { it.kind != ItemKind.FOLDER }
    val totalBytes: Long get() = items.sumOf { it.bytes }
}

/** The result of device authentication, passed in by the caller (S4b-BL-127 builds the real gate). */
data class AuthorizationToken(
    val level: DeletionLevel,
    val issuedAtMs: Long,
    /** The [DeletionPlan.operationId] it was granted for. */
    val operationId: String,
    /** Whatever the gate needs to know the token is genuine (an HMAC); never shown or logged. */
    val proof: String,
) {
    override fun toString(): String = "AuthorizationToken($level, operation=$operationId)"
}

/**
 * Seam for S4b-BL-127. The service checks level, operation and age itself ([DeletionRules.authorizationProblem]); the gate
 * says whether the token is **genuine** and whether it **still holds** (the screen lock is still on, nothing withdrew it).
 */
interface AuthorizationGate {
    /** Whether [token] was issued by a device check that passed. */
    suspend fun isGenuine(token: AuthorizationToken): Boolean

    /** Asked before every file: false stops the run before the next delete ("the lock removed half way", docs/15 §10.2). */
    suspend fun stillHolds(token: AuthorizationToken): Boolean
}

/** Why nothing was deleted (the run did not start). */
enum class Refusal {
    OFFLINE, NOT_AUTHORIZED, AUTHORIZATION_TOO_WEAK, AUTHORIZATION_STALE, AUTHORIZATION_OTHER_OPERATION,
    STALE_PLAN, ROOT_NOT_FOUND, NOT_A_BACKUP, NOTHING_TO_DELETE, OTHER_DELETION_PENDING, NOTHING_PENDING, DRIVE_ERROR,
}

/** Why a started run stopped before the end. */
enum class StopReason { DRIVE_ERROR, AUTHORIZATION_LOST, FILES_FAILED }

/**
 * The pre-flight's answer: the exact [DeletionPlan] to show, or the reason none could be made. Nothing is deleted yet.
 */
sealed interface PlanResult {
    data class Ready(val plan: DeletionPlan) : PlanResult
    data class Refused(val reason: Refusal, val error: DriveException? = null) : PlanResult
}

/** How a deletion request ended: refused before it started, or a run that deleted some or all of the plan. */
sealed interface DeletionOutcome {
    /** Nothing was deleted. */
    data class Refused(val reason: Refusal, val error: DriveException? = null) : DeletionOutcome

    /**
     * The run happened. [finished] means nothing of ours is left ([report].left is empty); [keptIds] are folders kept
     * because something Doorprints did not make is in them. [marker] is set when the run finished a level that must
     * be remembered ([DeletedMarker]).
     */
    data class Ran(
        val report: DeleteReport,
        val finished: Boolean,
        val total: Int,
        val keptIds: List<String> = emptyList(),
        val stopped: StopReason? = null,
        val marker: DeletedMarker? = null,
    ) : DeletionOutcome
}

/** What a device does about Drive after a deletion finished (docs/15 §3.4). */
enum class ReconnectPath {
    /** Backups were deleted: sync goes on, automatic backup stays off until the person says "Start backing up again". */
    ASK_BEFORE_BACKUP,

    /** Everything was deleted: the folder id is forgotten; connecting again is the full first connect (new keys, new recovery key). */
    FULL_FIRST_CONNECT,
}

/**
 * The rule "nothing comes back by itself": kept on the device after a finished deletion until the person turns Drive
 * back on. Automatic backup is off whenever a marker exists; after [DeletionLevel.L3] there is no folder id.
 */
data class DeletedMarker(val level: DeletionLevel, val atMs: Long, val action: String) {
    val reconnectPath: ReconnectPath
        get() = if (level == DeletionLevel.L3) ReconnectPath.FULL_FIRST_CONNECT else ReconnectPath.ASK_BEFORE_BACKUP
}

/** The list written on the device **before** the first delete (docs/15 §3.3), so a stop can be finished. */
data class PendingDeletion(
    val operationId: String,
    val level: DeletionLevel,
    val action: DeletionAction,
    val rootId: String,
    /** What is still to do, in order. */
    val items: List<DeletionItem>,
    val total: Int,
    val createdAtMs: Long,
)

/**
 * The device's memory of a deletion: the pending list and the marker. Whoever syncs or backs up must check
 * [pending] first and not start while a deletion is unfinished.
 */
interface DeletionStore {
    /** The unfinished deletion, or null. */
    suspend fun pending(): PendingDeletion?
    /** Writes [pending] before the first file is deleted, so an interrupted run can be finished. */
    suspend fun savePending(pending: PendingDeletion)
    /** Forgets the pending list once nothing is left to do. */
    suspend fun clearPending()
    /** The marker of the last finished deletion, or null. */
    suspend fun marker(): DeletedMarker?

    /** Records [marker]; when [forgetFolder] the stored Drive folder ids (root, backups, keys, ...) are dropped. */
    suspend fun recordFinished(marker: DeletedMarker, forgetFolder: Boolean)
}
