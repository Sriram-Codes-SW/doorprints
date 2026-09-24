package app.doorprints.export

import kotlinx.serialization.Serializable

/**
 * What one "Add everything as new copies" import wrote (UX review, round 16), so that it can be undone.
 *
 * A copy keeps each house's label and times and only changes the ids, so afterwards the copies sit next to their
 * originals under the same names and sync to the server; the only cleanup used to be deleting them one by one, and
 * picking the wrong one of a pair lost the newer edits. A copy is all or nothing, so its undo can be too: the worker
 * records the new ids here, and *Undo this import* removes exactly those rows (`Repository.undoCopyImport`).
 *
 * [houses] and [visits] map each new id to the `updatedAt` it was written with, so the undo can leave alone a row
 * the user has edited since; [photos] are the new photo ids. [finishedAt] is wall-clock milliseconds.
 *
 * [undone] marks the record an undo leaves behind when it kept some houses because they had been edited since (UX
 * review, round 18): it holds only those houses ([keptOnly]), so that the house list can still show them behind the
 * "Just imported" chip until the record's day is over. Such a record offers no undo.
 *
 * [rowHidden] is set when the user closes the house list's undo row without undoing (UX review, round 19), for
 * example after importing a friend's backup they want to keep: the row stays closed for this run, while the "Just
 * imported" chip and the Import screen's own *Undo this import* stay. Old files without the field read it as false.
 *
 * Common code since CMP-5 (ADR-23), because the house list (in `:ui`) reads it; the record files themselves are
 * `:app`'s `ImportUndo` (Android storage).
 */
@Serializable
data class CopyRecord(
    val runId: String,
    val finishedAt: Long,
    val houses: Map<String, Long>,
    val visits: Map<String, Long> = emptyMap(),
    val photos: List<String> = emptyList(),
    val undone: Boolean = false,
    val rowHidden: Boolean = false,
) {
    /** What is left of this record after an undo that kept [keptHouses]: those houses only, and no undo. */
    fun keptOnly(keptHouses: Set<String>): CopyRecord = copy(
        houses = houses.filterKeys { it in keptHouses },
        visits = emptyMap(),
        photos = emptyList(),
        undone = true,
    )

    companion object {
        /** How long a copy import can be undone: a day, as the reviewer asked. */
        const val KEEP_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * What an undo of a copy import did, for the run it undid; [failed] means nothing was changed. [finishedAt] tells two
 * outcomes for the same run apart (a failure, then a retry), so a screen can save "this one was already shown" as
 * [key] across a rotation or process death instead of comparing objects (UX review, round 21). Written by `:app`'s
 * `CopyImportUndo`, read by the Import screen and the house list (was `CopyImportUndo.Outcome` before CMP-5).
 */
data class CopyUndoOutcome(
    val runId: String,
    val removed: Int,
    val kept: Int,
    val failed: Boolean = false,
    val finishedAt: Long = 0L,
) {
    /** runId plus finishedAt: stable across a saved-state round trip, unique per undo. */
    val key: String get() = "$runId@$finishedAt"
}
