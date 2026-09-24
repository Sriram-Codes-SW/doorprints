package app.doorprints.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The name of the Room database file, and the one-time move from the name it had before the rename to Doorprints.
 *
 * Until 2026-09-24 the file was `househunt.db` ([LEGACY_NAME]). [resolve] runs before Room opens the database: when
 * the old file exists and the new one does not, it renames the old file and its companions (`-wal`, `-shm`,
 * `-journal`) to [NAME]. The companions move first and the main file last, so a process killed halfway leaves the
 * old main file in place and the next start finishes the move; if a rename fails, the moved companions (including
 * any an interrupted earlier run moved) go back and
 * the database is opened under its old name, so a house is never hidden behind a new, empty file. When both files
 * exist (which only a hand-made copy produces) nothing is touched and the new one is used; the old one stays on disk.
 */
object DatabaseFile {
    const val NAME = "doorprints.db"

    /** Stored file name from before the rename (2026-09-24); kept only so [resolve] can find and move it. */
    const val LEGACY_NAME = "househunt.db"

    /** SQLite's companion files, which must move together with the main file. */
    private val COMPANION_SUFFIXES = listOf("-wal", "-shm", "-journal")

    private const val TAG = "DoorprintsDb"

    /** The file name Room should open: [NAME], after moving a [LEGACY_NAME] database there if one exists. */
    fun resolve(context: Context): String =
        resolve(legacy = context.getDatabasePath(LEGACY_NAME), current = context.getDatabasePath(NAME)).name

    /** [resolve] on explicit paths (unit tests). Returns the file to open: [current], or [legacy] if moving failed. */
    internal fun resolve(legacy: File, current: File): File {
        if (current.exists()) {
            if (legacy.exists()) Log.w(TAG, "Both $LEGACY_NAME and $NAME exist; using $NAME and leaving the old file")
            return current
        }
        if (!legacy.exists()) return current
        val moved = mutableListOf<Pair<File, File>>()
        // Companions an interrupted earlier run already moved: if this run has to give up, they go back as well,
        // so the old main file is never opened without its WAL.
        for (suffix in COMPANION_SUFFIXES) {
            val from = File(legacy.path + suffix)
            val to = File(current.path + suffix)
            if (!from.exists() && to.exists()) moved += from to to
        }
        for (suffix in COMPANION_SUFFIXES) {
            val from = File(legacy.path + suffix)
            val to = File(current.path + suffix)
            if (!from.exists()) continue
            if (!from.renameTo(to)) return undo(moved, legacy)
            moved += from to to
        }
        if (!legacy.renameTo(current)) return undo(moved, legacy)
        return current
    }

    private fun undo(moved: List<Pair<File, File>>, legacy: File): File {
        for ((from, to) in moved.asReversed()) to.renameTo(from)
        Log.w(TAG, "Could not rename $LEGACY_NAME to $NAME; opening it under its old name")
        return legacy
    }
}
