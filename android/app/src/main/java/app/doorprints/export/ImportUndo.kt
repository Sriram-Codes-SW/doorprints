package app.doorprints.export

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

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
}

/**
 * The [CopyRecord] files, one per copy import, in `filesDir/imports/<runId>.json` — a file and not WorkManager
 * output `Data`, which is capped at 10 KB (a few hundred ids). Private storage, never shared or backed up in a copy.
 * A record is offered for [KEEP_MS] after the import, and swept at app start after that ([sweep]). An undo deletes it,
 * or, when it kept some houses, replaces it with [CopyRecord.keptOnly]. Closing the import's result, or picking
 * another file, does **not** delete it (UX review, round 18): the undo stays on offer from the house list until its
 * day is over.
 */
object ImportUndo {

    /** How long a copy import can be undone: a day, as the reviewer asked. */
    const val KEEP_MS = 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun dir(context: Context): File = File(context.filesDir, "imports")

    /** Writes [record]; false when it could not be written (the import stands, it just cannot be undone). */
    fun save(context: Context, record: CopyRecord): Boolean = save(dir(context), record)

    /** The record of [runId] while it can still be undone, else null. Reads a file: call it off the main thread. */
    fun load(context: Context, runId: String, now: Long = System.currentTimeMillis()): CopyRecord? =
        load(dir(context), runId, now)

    /**
     * Marks the record of [runId] so the house list no longer shows its undo row ([CopyRecord.rowHidden]); nothing
     * when the record is gone or already undone. Reads and writes a file: call it off the main thread.
     */
    fun hideRow(context: Context, runId: String, now: Long = System.currentTimeMillis()): Boolean =
        hideRow(dir(context), runId, now)

    fun delete(context: Context, runId: String) {
        fileOf(dir(context), runId)?.delete()
    }

    /**
     * The newest record that can still be undone ([CopyRecord.undone] false, within [KEEP_MS]), or null: what the house
     * list offers to undo when it was not opened from an import. Reads files: call it off the main thread.
     */
    fun latestUndoable(context: Context, now: Long = System.currentTimeMillis()): CopyRecord? =
        latestUndoable(dir(context), now)

    /** Removes records older than [KEEP_MS] (and any half-written temp file); run at app start. */
    fun sweep(context: Context, now: Long = System.currentTimeMillis()) = sweep(dir(context), now)

    internal fun save(dir: File, record: CopyRecord): Boolean = runCatching {
        val target = fileOf(dir, record.runId) ?: return false
        dir.mkdirs()
        // Written aside and renamed, so a reader never sees half a file.
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(json.encodeToString(CopyRecord.serializer(), record))
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    internal fun load(dir: File, runId: String, now: Long): CopyRecord? {
        val file = fileOf(dir, runId)?.takeIf { it.isFile } ?: return null
        val record = runCatching { json.decodeFromString(CopyRecord.serializer(), file.readText()) }.getOrNull()
            ?: return null
        // The file's own name decides which run it is; a record that says otherwise is not trusted.
        if (record.runId != runId || now - record.finishedAt > KEEP_MS || record.finishedAt > now + KEEP_MS) {
            return null
        }
        return record
    }

    internal fun hideRow(dir: File, runId: String, now: Long): Boolean {
        val record = load(dir, runId, now)?.takeIf { !it.undone && !it.rowHidden } ?: return false
        return save(dir, record.copy(rowHidden = true))
    }

    internal fun latestUndoable(dir: File, now: Long): CopyRecord? =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { load(dir, it.name.removeSuffix(".json"), now) }
            .filter { !it.undone }
            .maxByOrNull { it.finishedAt }

    internal fun sweep(dir: File, now: Long) {
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp") || now - file.lastModified() > KEEP_MS) file.delete()
        }
    }

    /**
     * `<uuid>.json` for a WorkManager run id, or null for anything that is not a UUID: the id also arrives from saved
     * navigation state, and it must never name a file outside [dir].
     */
    internal fun fileOf(dir: File, runId: String): File? {
        val id = runCatching { UUID.fromString(runId) }.getOrNull() ?: return null
        if (id.toString() != runId.lowercase()) return null
        return File(dir, "$id.json")
    }
}
