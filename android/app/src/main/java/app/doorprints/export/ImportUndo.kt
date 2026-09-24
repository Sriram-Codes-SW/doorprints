package app.doorprints.export

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The [CopyRecord] files, one per copy import, in `filesDir/imports/<runId>.json` — a file and not WorkManager
 * output `Data`, which is capped at 10 KB (a few hundred ids). Private storage, never shared or backed up in a copy.
 * A record is offered for [KEEP_MS] after the import, and swept at app start after that ([sweep]). An undo deletes it,
 * or, when it kept some houses, replaces it with [CopyRecord.keptOnly]. Closing the import's result, or picking
 * another file, does **not** delete it (UX review, round 18): the undo stays on offer from the house list until its
 * day is over.
 */
object ImportUndo {

    /** How long a copy import can be undone: a day ([CopyRecord.KEEP_MS], common since CMP-5). */
    const val KEEP_MS = CopyRecord.KEEP_MS

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
