package app.doorprints.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.doorprints.DoorprintsApp
import app.doorprints.data.ResultScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Undo this import* for a copy import, written once for every screen that offers it (UX review, round 18): the Import
 * screen's result and the house list's "Imported from your backup" row. *See your houses* sends the user to the list
 * of copies, which is exactly where they decide to undo, so the undo has to work from there too, and both screens must
 * see the same run being undone and the same outcome.
 *
 * The undo runs in the application's scope, so leaving a screen does not cut it short, and its state is Compose
 * snapshot state held for the life of the process: [undoingRun] while it writes, then [outcome]. Whichever screen is
 * showing when it ends reads the outcome; nothing is delivered only to a ViewModel that has been cleared meanwhile.
 *
 * What it does, in order: `Repository.undoCopyImport` (one transaction; every keep-or-remove rule is [CopyUndo]'s),
 * then the record goes — deleted when every house went, or replaced by [CopyRecord.keptOnly] when some houses were kept
 * because they had been edited since, so those can still be found behind the "Just imported" chip — and the import's
 * result is marked closed, so a later visit to the Import screen does not show "Added 40 houses" for houses that are
 * gone. A failure has rolled everything back; the record stays and the undo can be tried again.
 *
 * [start] is called on the main thread (from a tap), which is what keeps two undos from running at once.
 */
object CopyImportUndo {

    /** The run whose copies are being removed right now, or null. */
    var undoingRun by mutableStateOf<String?>(null)
        private set

    /** The outcome of the last undo in this process, or null before the first one. */
    var outcome by mutableStateOf<CopyUndoOutcome?>(null)
        private set

    /**
     * Starts undoing [record]. Returns false, and does nothing, while another undo is running or when the record is
     * what an earlier undo left behind ([CopyRecord.undone]).
     */
    fun start(app: DoorprintsApp, record: CopyRecord): Boolean {
        if (undoingRun != null || record.undone) return false
        undoingRun = record.runId
        app.appScope.launch {
            var result = CopyUndoOutcome(record.runId, 0, 0, failed = true)
            try {
                val repository = app.container.repository
                val done = repository.undoCopyImport(record.houses, record.visits, record.photos)
                if (done.keptHouses.isEmpty()) {
                    ImportUndo.delete(app, record.runId)
                } else if (!ImportUndo.save(app, record.keptOnly(done.keptHouses))) {
                    // The kept houses cannot be pointed at any more; the record must not offer the undo again.
                    ImportUndo.delete(app, record.runId)
                }
                runCatching { repository.settings.markResultDismissed(ResultScreen.IMPORT, record.runId) }
                result = CopyUndoOutcome(record.runId, done.removed, done.kept)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One transaction: a failure has rolled everything back, and the undo can be tried again.
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    outcome = result.copy(finishedAt = System.currentTimeMillis())
                    undoingRun = null
                }
            }
        }
        return true
    }
}
