package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import app.doorprints.export.CopyRecord
import app.doorprints.export.CopyUndoOutcome
import app.doorprints.export.ImportCheck
import app.doorprints.export.ImportRequest
import app.doorprints.export.ImportStaging
import app.doorprints.export.ImportStart
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * UX review, 2026-09-22: a double tap on Import, or Replace followed by Import, must start **one** import. The
 * second call used to enqueue a request that `ExistingWorkPolicy.KEEP` dropped and overwrite `startedRunId` with
 * its id, so the real run's end was never recognised: no success card, and a stale Import button that handed the
 * worker a staged copy it had already deleted.
 *
 * Android review, 2026-09-22: and a start WorkManager drops (KEEP, with an earlier import still running) must not
 * leave the screen on "Importing…" for good.
 *
 * The "was it kept" check runs on [Dispatchers.Unconfined], so with a check that does not suspend it has finished
 * by the time `startImport` returns; a test has no main dispatcher for `viewModelScope`. Common since CMP-6 P6b (was
 * `:app`'s, on the Android ViewModel): the staged copies are [FakeImports]' paths instead of temporary files.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportStartOnceTest {

    /** Only which staged copies exist; nothing here stages, previews or starts through it. */
    private class FakeImports(val staged: MutableSet<String> = mutableSetOf()) : ImportServices {
        override fun runs(): Flow<List<ImportRun>> = emptyFlow()
        override fun stop() = Unit
        override fun clearDoneNotification() = Unit
        override fun screenVisible(visible: Boolean) = Unit
        @Composable
        override fun rememberBackupPicker(onPicked: (file: String?) -> Unit): (folder: String?) -> Boolean = { false }
        override suspend fun displayName(file: String): String? = null
        override suspend fun stage(file: String): ImportStaging = error("not staged in this test")
        override suspend fun preview(stagedPath: String, displayName: String?): ImportCheck = error("no preview")
        override fun isStaged(path: String) = path in staged
        override fun discard(path: String?) {
            staged.remove(path)
        }
        override fun start(request: ImportRequest): ImportStart = error("started through the test's startWork")
    }

    /** No undo in these tests. */
    private object NoUndo : CopyImportUndoes {
        override val undoingRun: String? = null
        override val outcome: CopyUndoOutcome? = null
        override suspend fun load(runId: String): CopyRecord? = null
        override suspend fun latestUndoable(): CopyRecord? = null
        override fun hideRow(runId: String) = Unit
        override fun start(record: CopyRecord) = false
    }

    private val now = CoroutineScope(Dispatchers.Unconfined)

    private fun viewModel(imports: ImportServices = FakeImports(), startWork: (ImportRequest) -> ImportStart) =
        ImportViewModel(imports, NoUndo, SavedStateHandle(), now, startWork)

    private fun preview(mode: ImportMode) = ImportPreview(
        mode = mode,
        newHouses = 3, updatedHouses = 1, newerHereHouses = 0, unchangedHouses = 0,
        newVisits = 0, updatedVisits = 0, newerHereVisits = 0, unchangedVisits = 0,
        newPhotos = 0, skippedPhotos = 0, photosMissingFromFile = 0,
    )

    /** A finished check of the staged copy at [path]. (Built whole: `Ready` has a property named `copy`.) */
    private fun readyAt(path: String) = ImportCheck.Ready(
        stagedPath = path,
        manifest = null,
        merge = preview(ImportMode.MERGE),
        copy = preview(ImportMode.COPY),
        duplicateHouses = 1,
    )

    private val ready = readyAt("/cache/import/staged-1.zip")

    /** A start WorkManager kept, as the seam reports it. */
    private fun kept(id: String = Uuid.random().toString()) = ImportStart(id) { true }

    @Test
    fun aSecondTapBeforeWorkManagerReportsTheRunStartsNothing() {
        val started = mutableListOf<ImportRequest>()
        val vm = viewModel { request ->
            started += request
            kept()
        }
        vm.checkedForTest(ready)

        vm.startImport()
        val first = vm.startedRunId
        assertTrue(vm.starting)

        // The second tap arrives while `check` is still Ready and the button is still on screen.
        vm.startImport()
        vm.startImport(ImportMode.COPY)

        assertEquals(1, started.size)
        assertEquals(ImportRequest(ready.stagedPath, ImportMode.MERGE), started.single())
        assertEquals(first, vm.startedRunId)
        assertEquals(ImportMode.MERGE, vm.mode)
    }

    @Test
    fun theStartedRunEndingClearsThePreviewAndTheStartingState() {
        val ids = ArrayDeque(listOf(Uuid.random().toString(), Uuid.random().toString()))
        val vm = viewModel { kept(ids.removeFirst()) }
        vm.checkedForTest(ready)

        vm.startImport()
        vm.startImport()
        val runId = vm.startedRunId!!

        vm.onRunObserved(runId)
        assertFalse(vm.starting)

        vm.onRunEnded(runId, stopped = false)
        assertEquals(null, vm.check)
    }

    @Test
    fun aStartWorkManagerDroppedGivesTheCopyBackInsteadOfWaitingForever() {
        // A staged copy that exists: the copy is only taken back while it still does.
        val staged = "/cache/import/staged-2.zip"
        val readyHere = readyAt(staged)
        val started = mutableListOf<ImportRequest>()
        var keep = false
        val vm = viewModel(FakeImports(mutableSetOf(staged))) { request ->
            started += request
            val k = keep
            ImportStart(Uuid.random().toString()) { k }
        }
        vm.checkedForTest(readyHere)

        // KEEP dropped it: an earlier import was still running. No run with this id will ever be reported.
        vm.startImport()
        assertFalse(vm.starting, "the bar must not wait for a run that does not exist")
        assertNull(vm.startedRunId)
        assertTrue(vm.blocked)
        assertEquals(readyHere, vm.check)

        // The copy is this screen's again, so once the earlier run has ended Import starts from the same file.
        keep = true
        vm.startImport()
        assertEquals(2, started.size)
        assertEquals(ImportRequest(staged, ImportMode.MERGE), started.last())
        assertTrue(vm.starting)
        assertFalse(vm.blocked)
    }

    @Test
    fun aFailedEnqueueIsTreatedAsDropped() {
        val staged = "/cache/import/staged-3.zip"
        val vm = viewModel(FakeImports(mutableSetOf(staged))) {
            ImportStart(Uuid.random().toString()) { throw IllegalStateException("enqueue failed") }
        }
        vm.checkedForTest(readyAt(staged))

        vm.startImport()
        assertFalse(vm.starting)
        assertNull(vm.startedRunId)
        assertTrue(vm.blocked)
    }
    /**
     * UX review, round 11: the worker must re-plan the import the user saw. The undelete switch and the Replace
     * dialog's "Keep mine, add only what's new" travel in the request, and neither applies to a copy.
     */
    @Test
    fun theMergeFlagsOnScreenTravelToTheWorker() {
        val started = mutableListOf<ImportRequest>()
        val vm = viewModel { request ->
            started += request
            kept()
        }
        vm.checkedForTest(ready)
        vm.onRestoreDeletedChange(true)
        vm.startImport(skipUpdates = true)
        assertEquals(
            ImportRequest(ready.stagedPath, ImportMode.MERGE, restoreDeleted = true, skipUpdates = true),
            started.single(),
        )

        val copyStarts = mutableListOf<ImportRequest>()
        val copyVm = viewModel { request ->
            copyStarts += request
            kept()
        }
        copyVm.checkedForTest(ready)
        copyVm.onRestoreDeletedChange(true)
        copyVm.startImport(ImportMode.COPY, skipUpdates = true)
        assertEquals(ImportRequest(ready.stagedPath, ImportMode.COPY), copyStarts.single())
    }
}
