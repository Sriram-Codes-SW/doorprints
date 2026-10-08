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
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.driveSha256
import app.doorprints.drive.sha256HexOf
import app.doorprints.shared.export.Sha256

/**
 * The pure rules of a deletion (S4b-BL-119), pinned on both stacks by `docs/schemas/delete-vectors.json`
 * (web: `drive-deletion-rules.ts`): what counts as ours, the order, which backups go, who may authorise, which failure
 * stops the run. No Drive calls here.
 */
object DeletionRules {
    /** A grant is good for one operation, at most 60 seconds, from the moment the person authenticated (docs/15 §10.2). */
    const val AUTHORIZATION_MAX_AGE_MS: Long = 60_000

    /** A pre-flight older than this is shown again before anything is deleted (the dialog must match the Drive). */
    const val PLAN_MAX_AGE_MS: Long = 10 * 60_000

    /** Folder roles under the root, in the order their folders are deleted. */
    val SUBFOLDER_ROLES: List<String> = listOf("backups", "sync", "photos", "shared")

    private val kindByCode = mapOf(
        "backup" to ItemKind.BACKUP, "sync" to ItemKind.SYNC, "photo" to ItemKind.PHOTO, "shared" to ItemKind.SHARED,
        "readme" to ItemKind.README, "control" to ItemKind.CONTROL, "keys" to ItemKind.KEYS,
    )

    /** The folder a kind of file must sit in to count as ours. */
    fun containerOf(kind: ItemKind): String? = when (kind) {
        ItemKind.BACKUP -> "backups"
        ItemKind.SYNC -> "sync"
        ItemKind.PHOTO -> "photos"
        ItemKind.SHARED -> "shared"
        ItemKind.README, ItemKind.CONTROL, ItemKind.KEYS -> "root"
        ItemKind.FOLDER -> null
    }

    /**
     * A **file** is ours only if it carries a known `kind` app property **and** sits in the folder that kind belongs in
     * ([containerRole]: `root`, `backups`, ...). Anything else is foreign and is never deleted.
     */
    fun classifyFile(appProperties: Map<String, String>, containerRole: String): ItemKind? {
        val kind = kindByCode[appProperties[DriveLayout.KIND]] ?: return null
        return kind.takeIf { containerOf(it) == containerRole }
    }

    /** A **folder** directly under the root is ours only with a known `doorprints` role; the role, else null. */
    fun classifyFolder(appProperties: Map<String, String>, containerRole: String): String? {
        if (containerRole != "root") return null
        return appProperties[DriveLayout.ROLE]?.takeIf { it in SUBFOLDER_ROLES }
    }

    /**
     * The order of a run: backups, sync files, photos, shared files (data), then the read-me, `doorprints.json`, and
     * `keys.json` **last of the files**, then the sub-folders, the root last. [folderRole] is `root` for the root folder.
     */
    fun phaseOf(kind: ItemKind, folderRole: String? = null): Int = when (kind) {
        ItemKind.BACKUP -> 0
        ItemKind.SYNC -> 1
        ItemKind.PHOTO -> 2
        ItemKind.SHARED -> 3
        ItemKind.README -> 4
        ItemKind.CONTROL -> 5
        ItemKind.KEYS -> 6
        ItemKind.FOLDER -> if (folderRole == "root") 8 else 7
    }

    /** A backup as the selection sees it. [createdAt] is the writer's `createdAt` (else Drive's time), epoch ms. */
    data class BackupRef(val id: String, val complete: Boolean, val createdAt: Long)

    /** Which backups go for an action, oldest first, and the level that needs. Null [ids]: the named backup is not there. */
    data class BackupSelection(val ids: List<String>?, val level: DeletionLevel)

    private val oldestFirst = compareBy<BackupRef>({ it.createdAt }, { it.id })

    /**
      * Which of [backups] [action] removes (oldest first) and the level that needs. *Delete this backup* is L2 when it
      * is the
     * last complete one; *older backups* keeps the newest complete backup and everything newer; *all backups* and
     * *everything* take every backup. A null id list means the named backup is not there.
     */
    fun selectBackups(action: DeletionAction, backups: List<BackupRef>): BackupSelection {
        val sorted = backups.sortedWith(oldestFirst)
        return when (action) {
            is DeletionAction.OneBackup -> {
                val target = sorted.firstOrNull { it.id == action.fileId }
                    ?: return BackupSelection(null, DeletionLevel.L1)
                val otherComplete = sorted.any { it.complete && it.id != target.id }
                BackupSelection(listOf(target.id), if (target.complete && !otherComplete) DeletionLevel.L2 else DeletionLevel.L1)
            }
            DeletionAction.AllBackups -> BackupSelection(sorted.map { it.id }, DeletionLevel.L2)
            DeletionAction.OlderBackups -> {
                val keep = sorted.lastOrNull { it.complete }
                    ?: return BackupSelection(emptyList(), DeletionLevel.L2)
                BackupSelection(
                    sorted.filter { it.id != keep.id && it.createdAt <= keep.createdAt }.map { it.id },
                    DeletionLevel.L2,
                )
            }
            DeletionAction.Everything -> BackupSelection(sorted.map { it.id }, DeletionLevel.L3)
        }
    }

    /** The id device authentication is bound to: the level, the root and the ordered ids (docs/15 §10.2). */
    fun operationId(level: DeletionLevel, rootId: String, orderedIds: List<String>, sha256: () -> Sha256 = ::driveSha256): String {
        val text = "doorprints-delete/1\n${level.name}\n$rootId\n" + orderedIds.joinToString("\n")
        return "del-" + sha256HexOf(text.encodeToByteArray(), sha256).take(24)
    }

    /**
     * Why [token] does not cover [required] for [operationId] at [nowMs], else null. L1 needs none. The gate's
     * `isGenuine` is asked separately by the service.
     */
    fun authorizationProblem(token: AuthorizationToken?, required: DeletionLevel, operationId: String, nowMs: Long): Refusal? {
        if (required == DeletionLevel.L1) return null
        if (token == null) return Refusal.NOT_AUTHORIZED
        if (token.level < required) return Refusal.AUTHORIZATION_TOO_WEAK
        if (token.operationId != operationId) return Refusal.AUTHORIZATION_OTHER_OPERATION
        val age = nowMs - token.issuedAtMs
        if (age < 0 || age > AUTHORIZATION_MAX_AGE_MS) return Refusal.AUTHORIZATION_STALE
        return null
    }

    /** A failed delete that ends the run on the spot (the rest of the Drive is not going to answer better). */
    fun stopsRun(kind: DriveException.Kind): Boolean = when (kind) {
        DriveException.Kind.FORBIDDEN, DriveException.Kind.BAD_REQUEST, DriveException.Kind.CONFLICT,
        DriveException.Kind.CORRUPT, DriveException.Kind.NOT_FOUND -> false
        else -> true
    }
}
