package com.househunt.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.househunt.app.export.ImportCheck
import com.househunt.app.export.ImportRequest
import com.househunt.app.export.ImportStart
import com.househunt.app.ui.ImportViewModel
import com.househunt.shared.export.ImportMode
import com.househunt.shared.export.ImportPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

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
 * by the time `startImport` returns; a JVM test has no main dispatcher for `viewModelScope`.
 */
class ImportStartOnceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = CoroutineScope(Dispatchers.Unconfined)

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
    private fun kept(id: UUID = UUID.randomUUID()) = ImportStart(id) { true }

    @Test
    fun aSecondTapBeforeWorkManagerReportsTheRunStartsNothing() {
        val app = Application()
        val started = mutableListOf<ImportRequest>()
        val vm = ImportViewModel(app, SavedStateHandle(), now) { _, request ->
            started += request
            kept()
        }
        vm.checkedForTest(ready)

        vm.startImport(app)
        val first = vm.startedRunId
        assertTrue(vm.starting)

        // The second tap arrives while `check` is still Ready and the button is still on screen.
        vm.startImport(app)
        vm.startImport(app, ImportMode.COPY)

        assertEquals(1, started.size)
        assertEquals(ImportRequest(ready.stagedPath, ImportMode.MERGE), started.single())
        assertEquals(first, vm.startedRunId)
        assertEquals(ImportMode.MERGE, vm.mode)
    }

    @Test
    fun theStartedRunEndingClearsThePreviewAndTheStartingState() {
        val app = Application()
        val ids = ArrayDeque(listOf(UUID.randomUUID(), UUID.randomUUID()))
        val vm = ImportViewModel(app, SavedStateHandle(), now) { _, _ -> kept(ids.removeFirst()) }
        vm.checkedForTest(ready)

        vm.startImport(app)
        vm.startImport(app)
        val runId = vm.startedRunId!!

        vm.onRunObserved(runId)
        assertFalse(vm.starting)

        vm.onRunEnded(runId, stopped = false)
        assertEquals(null, vm.check)
    }

    @Test
    fun aStartWorkManagerDroppedGivesTheCopyBackInsteadOfWaitingForever() {
        val app = Application()
        // A real file: the copy is only taken back while it still exists.
        val staged = tmp.newFile("staged-2.zip").absolutePath
        val readyHere = readyAt(staged)
        val started = mutableListOf<ImportRequest>()
        var keep = false
        val vm = ImportViewModel(app, SavedStateHandle(), now) { _, request ->
            started += request
            val k = keep
            ImportStart(UUID.randomUUID()) { k }
        }
        vm.checkedForTest(readyHere)

        // KEEP dropped it: an earlier import was still running. No run with this id will ever be reported.
        vm.startImport(app)
        assertFalse("the bar must not wait for a run that does not exist", vm.starting)
        assertNull(vm.startedRunId)
        assertTrue(vm.blocked)
        assertEquals(readyHere, vm.check)

        // The copy is this screen's again, so once the earlier run has ended Import starts from the same file.
        keep = true
        vm.startImport(app)
        assertEquals(2, started.size)
        assertEquals(ImportRequest(staged, ImportMode.MERGE), started.last())
        assertTrue(vm.starting)
        assertFalse(vm.blocked)
    }

    @Test
    fun aFailedEnqueueIsTreatedAsDropped() {
        val app = Application()
        val staged = tmp.newFile("staged-3.zip").absolutePath
        val vm = ImportViewModel(app, SavedStateHandle(), now) { _, _ ->
            ImportStart(UUID.randomUUID()) { throw IllegalStateException("enqueue failed") }
        }
        vm.checkedForTest(readyAt(staged))

        vm.startImport(app)
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
        val app = Application()
        val started = mutableListOf<ImportRequest>()
        val vm = ImportViewModel(app, SavedStateHandle(), now) { _, request ->
            started += request
            kept()
        }
        vm.checkedForTest(ready)
        vm.onRestoreDeletedChange(true)
        vm.startImport(app, skipUpdates = true)
        assertEquals(
            ImportRequest(ready.stagedPath, ImportMode.MERGE, restoreDeleted = true, skipUpdates = true),
            started.single(),
        )

        val copyStarts = mutableListOf<ImportRequest>()
        val copyVm = ImportViewModel(app, SavedStateHandle(), now) { _, request ->
            copyStarts += request
            kept()
        }
        copyVm.checkedForTest(ready)
        copyVm.onRestoreDeletedChange(true)
        copyVm.startImport(app, ImportMode.COPY, skipUpdates = true)
        assertEquals(ImportRequest(ready.stagedPath, ImportMode.COPY), copyStarts.single())
    }
}
