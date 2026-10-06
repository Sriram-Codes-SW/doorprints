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

package app.doorprints.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import app.doorprints.data.Repository
import app.doorprints.ui.res.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/** The key under which Root hands a just-deleted house's id to the screen the form returns to. */
const val DELETED_HOUSE_KEY = "deletedHouse"

/**
 * "Deleted Green Villa" with *Undo* (docs/05 §7, 3.3.4; UX review, whole-app audit), shown by the Map and the house
 * list when the house form they opened was deleted: the user used to land there with no message and no way back.
 * *Undo* (within the snackbar's 10 s) writes the house back as a live row, a normal edit that syncs; its photos and
 * visits were never removed by the delete. Nothing happens when the house is gone or already live again.
 *
 * [deletedLabel] is the house's label while it is still deleted (null when it is gone or live again), and [restore]
 * writes it back as live if it is still deleted. The write-back is not cancelled by the screen closing. The screens
 * call the [Repository] overload below; this one takes the two lambdas it is built on (CMP-3, before the repository
 * interface was common).
 */
suspend fun offerDeletedHouseUndo(
    snackbar: SnackbarHostState,
    deletedLabel: suspend () -> String?,
    restore: suspend () -> Unit,
    /**
     * The delete is final: the snackbar closed without *Undo*, or the screen was left (this coroutine cancelled). Runs
     * under [NonCancellable], like [restore]; the Map and the house list sweep the house's saved walks here (docs/11
     * 5.27.6). A missed run is caught by the next trigger (app start, Hunt start, sync end, import end).
     */
    onFinal: suspend () -> Unit = {},
) {
    val label = deletedLabel() ?: return
    val name = label.ifBlank { getString(Res.string.house_unnamed) }
    val result = try {
        snackbar.showSnackbar(
            message = getString(Res.string.house_deleted, name),
            actionLabel = getString(Res.string.common_undo),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
    } catch (e: CancellationException) {
        withContext(NonCancellable) { onFinal() }
        throw e
    }
    if (result == SnackbarResult.ActionPerformed) {
        withContext(NonCancellable) { restore() }
    } else {
        withContext(NonCancellable) { onFinal() }
    }
}

/**
 * [offerDeletedHouseUndo] for the house [houseId] in [repo], for the Map and the house list: the house's label while it
 * is a tombstone, and *Undo* saves it back as live (a normal edit that syncs). Was `:app`'s `DeletedHouses.kt` until
 * the repository interface became common (CMP-4 P4c).
 */
suspend fun offerDeletedHouseUndo(repo: Repository, snackbar: SnackbarHostState, houseId: String) =
    offerDeletedHouseUndo(
        snackbar,
        deletedLabel = { repo.getHouse(houseId)?.takeIf { it.deleted }?.label },
        restore = { repo.getHouse(houseId)?.takeIf { it.deleted }?.let { repo.saveHouse(it.copy(deleted = false)) } },
        onFinal = { repo.sweepWalksOfDeletedHouses() },
    )
