package app.doorprints.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import app.doorprints.data.Repository
import app.doorprints.ui.res.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The key under which Root hands a just-deleted house's id to the screen the form returns to. */
const val DELETED_HOUSE_KEY = "deletedHouse"

/**
 * "Deleted Green Villa" with *Undo* (docs/05 §7, 3.3.4; UX review, whole-app audit), shown by the Map and the house
 * list when the house form they opened was deleted: the user used to land there with no message and no way back.
 * *Undo* (within the snackbar's 10 s) writes the house back as a live row, a normal edit that syncs; its photos and
 * visits were never removed by the delete. Nothing happens when the house is gone or already live again.
 */
internal suspend fun offerDeletedHouseUndo(
    context: Context,
    repo: Repository,
    snackbar: SnackbarHostState,
    houseId: String,
) {
    val house = repo.getHouse(houseId)?.takeIf { it.deleted } ?: return
    val name = house.label.ifBlank { context.getString(Res.string.house_unnamed) }
    val result = snackbar.showSnackbar(
        message = context.getString(Res.string.house_deleted, name),
        actionLabel = context.getString(Res.string.common_undo),
        withDismissAction = true,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) {
        withContext(NonCancellable) {
            repo.getHouse(houseId)?.takeIf { it.deleted }?.let { repo.saveHouse(it.copy(deleted = false)) }
        }
    }
}
