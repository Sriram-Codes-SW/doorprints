package app.doorprints

import androidx.work.WorkInfo
import androidx.work.workDataOf
import app.doorprints.export.ExportProblem
import app.doorprints.export.ExportRequest
import app.doorprints.export.ImportWorker
import app.doorprints.export.exportRunOf
import app.doorprints.export.importRunOf
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ImportMode
import app.doorprints.ui.ExportRun
import app.doorprints.ui.ImportRun
import app.doorprints.ui.RunState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * The Export and Import screens see WorkManager's runs as [ExportRun] and [ImportRun] since ADR-23 CMP-6 P6b: the state
 * one to one, the progress, the tags (the export's target, the import's mode) and the output data read with the same
 * keys and defaults as the screens read them from `WorkInfo` before (a run from an older build: not finished-at, told).
 */
class BackupRunsTest {
    private val id = UUID.fromString("5b0c3a8e-2f41-4d7c-9a6e-1c2d3e4f5a6b")

    @Test
    fun everyWorkManagerStateHasItsRunState() {
        for (state in WorkInfo.State.entries) {
            assertEquals(state.name, exportRunOf(WorkInfo(id, state, emptySet())).state.name)
        }
        assertEquals(WorkInfo.State.entries.map { it.isFinished }, RunState.entries.map { it.isFinished })
    }

    @Test
    fun anExportRunCarriesItsProgressTargetAndResult() {
        val target = "content://com.android.providers.downloads.documents/document/42"
        val running = WorkInfo(
            id, WorkInfo.State.RUNNING, setOf("export-target:$target"),
            progress = workDataOf(ExportRequest.KEY_DONE to 3, ExportRequest.KEY_TOTAL to 12),
        )
        assertEquals(
            ExportRun(id.toString(), RunState.RUNNING, done = 3, total = 12, target = target),
            exportRunOf(running),
        )

        val done = WorkInfo(
            id, WorkInfo.State.SUCCEEDED, setOf("export-target:$target"),
            outputData = workDataOf(
                ExportRequest.KEY_WRITTEN to target,
                ExportRequest.KEY_FORMAT to ExportFormat.BACKUP.name,
                ExportRequest.KEY_NAME to "Doorprints-2026-09-24.zip",
                ExportRequest.KEY_LOCATION to "Download",
                ExportRequest.KEY_PARTIAL to true,
                ExportRequest.KEY_FINISHED_AT to 1_760_000_000_000L,
                ExportRequest.KEY_NOTIFIED to false,
            ),
        )
        assertEquals(
            ExportRun(
                id.toString(), RunState.SUCCEEDED, target = target, written = target, format = ExportFormat.BACKUP,
                name = "Doorprints-2026-09-24.zip", location = "Download", partial = true,
                finishedAt = 1_760_000_000_000L, notified = false,
            ),
            exportRunOf(done),
        )

        val failed = WorkInfo(id, WorkInfo.State.FAILED, emptySet(), workDataOf(ExportRequest.KEY_ERROR to "no-space"))
        assertEquals(ExportProblem.NO_SPACE, exportRunOf(failed).problem)
        // A blank name is no name; no output at all is an old run: never finished-at, and told.
        val blankName = WorkInfo(id, WorkInfo.State.SUCCEEDED, emptySet(), workDataOf(ExportRequest.KEY_NAME to " "))
        assertEquals(null, exportRunOf(blankName).name)
        val stopped = WorkInfo(id, WorkInfo.State.CANCELLED, emptySet())
        assertEquals(ExportRun(id.toString(), RunState.CANCELLED), exportRunOf(stopped))
    }

    @Test
    fun anImportRunCarriesItsModeCountsAndProblem() {
        val done = WorkInfo(
            id, WorkInfo.State.SUCCEEDED, setOf(ImportWorker.modeTag(ImportMode.COPY)),
            outputData = workDataOf(
                ImportWorker.KEY_HOUSES to 5, ImportWorker.KEY_VISITS to 7, ImportWorker.KEY_PHOTOS to 9,
                ImportWorker.KEY_UPDATED_HOUSES to 1, ImportWorker.KEY_UPDATED_VISITS to 2,
                ImportWorker.KEY_RESTORED_HOUSES to 3, ImportWorker.KEY_PHOTOS_SKIPPED to 4,
                ImportWorker.KEY_UNDOABLE to true,
                ExportRequest.KEY_FINISHED_AT to 1_760_000_000_000L, ExportRequest.KEY_NOTIFIED to false,
            ),
            progress = workDataOf(ExportRequest.KEY_DONE to 8, ExportRequest.KEY_TOTAL to 8),
        )
        assertEquals(
            ImportRun(
                id.toString(), RunState.SUCCEEDED, done = 8, total = 8, mode = ImportMode.COPY,
                houses = 5, visits = 7, photos = 9, updatedHouses = 1, updatedVisits = 2, restoredHouses = 3,
                photosSkipped = 4, undoable = true, finishedAt = 1_760_000_000_000L, notified = false,
            ),
            importRunOf(done),
        )
        val failed =
            WorkInfo(id, WorkInfo.State.FAILED, emptySet(), workDataOf(ExportRequest.KEY_ERROR to "WRITE_FAILED"))
        assertEquals(BackupProblem.WRITE_FAILED, importRunOf(failed).problem)
        assertEquals(null, importRunOf(failed).mode)
        // An unrecognised code reads as "not a backup", as before.
        val noOutput = WorkInfo(id, WorkInfo.State.FAILED, emptySet())
        assertEquals(ImportRun(id.toString(), RunState.FAILED), importRunOf(noOutput))
    }
}
