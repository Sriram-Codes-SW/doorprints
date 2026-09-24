package app.doorprints.ui

import androidx.compose.material3.SnackbarHostState
import app.doorprints.data.Repository

/**
 * [offerDeletedHouseUndo] (common code in `:ui`) over the Room repository, for the Map and the house list. Moves to
 * `:ui` with the repository interface (ADR-23 CMP-4).
 */
internal suspend fun offerDeletedHouseUndo(repo: Repository, snackbar: SnackbarHostState, houseId: String) =
    offerDeletedHouseUndo(
        snackbar,
        deletedLabel = { repo.getHouse(houseId)?.takeIf { it.deleted }?.label },
        restore = { repo.getHouse(houseId)?.takeIf { it.deleted }?.let { repo.saveHouse(it.copy(deleted = false)) } },
    )
