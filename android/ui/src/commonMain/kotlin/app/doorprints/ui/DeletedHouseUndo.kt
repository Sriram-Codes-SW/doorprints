package app.doorprints.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import app.doorprints.data.Repository
import app.doorprints.ui.res.*
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
) {
    val label = deletedLabel() ?: return
    val name = label.ifBlank { getString(Res.string.house_unnamed) }
    val result = snackbar.showSnackbar(
        message = getString(Res.string.house_deleted, name),
        actionLabel = getString(Res.string.common_undo),
        withDismissAction = true,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) {
        withContext(NonCancellable) { restore() }
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
    )
