package com.househunt.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.househunt.app.HouseHuntApp
import com.househunt.app.export.CopyImportUndo
import com.househunt.app.export.CopyRecord
import com.househunt.app.export.ImportCheck
import com.househunt.app.export.ImportRequest
import com.househunt.app.export.ImportStart
import com.househunt.app.export.ImportUndo
import com.househunt.app.export.ImportWorker
import com.househunt.app.export.Imports
import com.househunt.shared.export.ImportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The import flow's state (S4-04), kept out of the composable so that it survives what the screen does not: a
 * rotation, a language switch, and the process being killed while the file picker is open.
 *
 * What survives how:
 *  - The **check** runs in [viewModelScope], so a rotation does not cancel it. Leaving the screen for good does,
 *    and the staging copy then stops at its next 64 KB chunk and deletes itself (see [Imports.stage]).
 *  - The **staged path, the mode and the id of the import this screen started** are in [SavedStateHandle].
 *    After process death the preview is not saved — it depends on what is on the phone *now* — but re-worked out
 *    from the staged copy ([Imports.preview]); if that copy has gone (the app sweeps staging files older than six
 *    hours on start), the screen simply starts over at the file picker, with no error about a file the user did
 *    not pick this time.
 *  - A staged copy that was never handed to [ImportWorker] is deleted when the screen is left ([onCleared]),
 *    instead of sitting in the cache for six hours.
 *
 * **One import per tap (UX review, 2026-09-22).** [startImport] hands a staged copy to the worker at most once:
 * a double tap on Import, or Replace followed by Import, used to call it again before WorkManager had reported the
 * first run, and the second request — dropped by `ExistingWorkPolicy.KEEP` — overwrote [startedRunId]. The real
 * run then never cleared the preview, no success card appeared, and a stale Import button handed the worker a copy
 * it had already deleted. [starting] switches the screen to "Importing…" on the tap itself.
 *
 * **A run that never shows up (Android review, 2026-09-22).** `ExistingWorkPolicy.KEEP` drops the request when an
 * earlier import is still ENQUEUED or RUNNING (a retry after a system stop, say). No run with [startedRunId] is then
 * ever reported, so without a check [starting] would stay true for good: "Importing…" and a Stop that changes
 * nothing, with [KEY_STAGED] already handed off. After every start the ViewModel therefore asks
 * [ImportStart.queued]; when the answer is no (or the enqueue failed) it takes the copy back ([onStartDropped]),
 * clears [starting] and [startedRunId], and says so ([blocked]). The earlier run stays on screen as what is
 * running; when it ends the preview is worked out again, because that run may have changed what is on the phone.
 *
 * **A stopped merge can be finished (UX review, round 10).** A merge is idempotent, so after Stop the worker's
 * copy (kept on purpose for a retry) is taken back as this screen's staged copy and previewed again, and the bar
 * offers *Finish import* ([finishing]) instead of telling the user to find and pick the same file again. A stopped
 * copy is rolled back and its staged file deleted, as before.
 *
 * **Bring back deleted houses (UX review, round 11).** [restoreDeleted] is the merge's opt-in undelete ("Also bring
 * back *n* houses deleted on this phone"), saved with the mode; it picks which of the merge previews the screen shows
 * and travels to the worker in the [ImportRequest]. A new pick or a cancel turns it off again.
 *
 * **A preview that goes stale (UX review, round 11).** While the user is away from the screen Hunt mode records
 * visits and sync pulls rows, so the numbers can drift from what the worker will re-plan and write. [refreshPreview]
 * re-works them out quietly from the same staged copy when the screen resumes, at most every [REFRESH_AFTER_MS].
 *
 * **Undo a copy import (UX review, rounds 16 and 18).** A copy import's worker records the ids it added
 * ([ImportUndo]). While its result is on screen, [undoRecord] holds that record (loaded by [loadUndo]) and [undoCopy]
 * hands it to [CopyImportUndo], the one implementation the house list's undo row uses too, which removes exactly those
 * rows in one transaction in the application's scope. [undoing] and [undone] read its state, so the outcome of an undo
 * that ends after this ViewModel is gone is still seen by whichever screen is showing. [undone] is only an outcome
 * reached while this ViewModel exists, so a later visit to the screen does not bring back an old "Removed 40 copies".
 *
 * [startWork] is the one seam for tests: it enqueues the run and returns its id and the "was it kept" check.
 * [scope] replaces [viewModelScope] for that check in a JVM test, which has no main dispatcher to run it on.
 */
class ImportViewModel(
    private val app: Application,
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope? = null,
    private val startWork: (Context, ImportRequest) -> ImportStart = { context, request ->
        ImportWorker.start(context, request)
    },
) : AndroidViewModel(app) {

    private val repository get() = (app as HouseHuntApp).container.repository

    /** True while a picked file is being copied and checked. */
    var checking by mutableStateOf(false)
        private set

    /** The preview, or why the file was refused; null before a file is picked (or after an import finished). */
    var check by mutableStateOf<ImportCheck?>(null)
        private set

    var mode by mutableStateOf(
        saved.get<String>(KEY_MODE)?.let { m -> ImportMode.entries.firstOrNull { it.name == m } } ?: ImportMode.MERGE
    )
        private set

    /** The import run this screen started, so its result is shown and an unrelated old one is not. */
    var startedRunId by mutableStateOf(saved.get<String>(KEY_STARTED))
        private set

    /**
     * True from the Import tap until WorkManager first reports that run (50–300 ms later), it has ended, or it turns
     * out WorkManager did not keep it ([onStartDropped]), so the screen shows "Importing…" and Stop in the same frame
     * as the tap and the Import button is gone at once.
     */
    var starting by mutableStateOf(false)
        private set

    /**
     * True when the last Import tap did not start anything because another import was still running (see the
     * class KDoc). The staged copy and the preview are kept; the screen says to tap Import again once it ends.
     */
    var blocked by mutableStateOf(false)
        private set

    /**
     * The picked file's display name, for the header shown while it is checked and previewed; kept in
     * [SavedStateHandle] with the staged path, so a restored screen still names its file.
     */
    var fileName by mutableStateOf(saved.get<String>(KEY_NAME))
        private set

    /**
     * True when the staged copy is a stopped merge's file taken back ([onRunEnded]): the preview then shows what is
     * still to import, and the Import button reads *Finish import*. Cleared by a new pick, a cancel or a start.
     */
    var finishing by mutableStateOf(saved.get<Boolean>(KEY_FINISHING) ?: false)
        private set

    /**
     * The merge's opt-in undelete (see the class KDoc): true shows [ImportCheck.Ready.mergeRestored] and asks the
     * worker to bring back the houses deleted on this phone. Only means something in [ImportMode.MERGE].
     */
    var restoreDeleted by mutableStateOf(saved.get<Boolean>(KEY_RESTORE) ?: false)
        private set

    /** The record of the copy import on screen while it can still be undone; null otherwise. */
    var undoRecord by mutableStateOf<CopyRecord?>(null)
        private set

    /** True while an undo of a copy import is writing, started here or from the house list. */
    val undoing: Boolean get() = CopyImportUndo.undoingRun != null

    /** The outcome already there when this ViewModel was made: not news for this visit (see [undone]). */
    private val outcomeBefore = CopyImportUndo.outcome

    /** The outcome of the last undo that ended while this ViewModel existed; null otherwise. */
    val undone: CopyImportUndo.Outcome? get() = CopyImportUndo.outcome?.takeIf { it !== outcomeBefore }

    private var job: Job? = null

    /** When the current preview was worked out (`SystemClock.elapsedRealtime`), for [refreshPreview]. */
    private var checkedAt = 0L

    /** Bumped for every check started or cancelled; declared before `init`, which may start one. */
    private var checks = 0

    init {
        val staged = saved.get<String>(KEY_STAGED)
        if (staged != null) {
            // Checked here rather than left to [Imports.preview], which would call a vanished copy READ_FAILED and
            // show "it could not be read" for a file the user never re-picked.
            if (File(staged).isFile) {
                runCheck { Imports.preview(repository, staged, fileName) }
            } else {
                saved.remove<String>(KEY_STAGED)
                setFinishing(false)
            }
        }
    }

    fun pick(uri: Uri) {
        blocked = false
        setFinishing(false)
        setRestoreDeleted(false)
        job?.cancel()
        discardStaged()
        setFileName(null)
        runCheck {
            // First, so the header can name the file while the (possibly long) copy runs.
            val name = Imports.displayName(app, uri)
            setFileName(name)
            when (val staging = Imports.stage(app, uri)) {
                is Imports.Staging.Refused -> ImportCheck.Refused(staging.problem)
                is Imports.Staging.Staged -> {
                    saved[KEY_STAGED] = staging.path
                    Imports.preview(repository, staging.path, name)
                }
            }
        }
    }

    /** Cancel while "Checking the file…": stops the copy and deletes whatever was staged. */
    fun cancelCheck() {
        job?.cancel()
        job = null
        checks++
        checking = false
        blocked = false
        setFinishing(false)
        setRestoreDeleted(false)
        discardStaged()
        setFileName(null)
        check = null
    }

    fun choose(newMode: ImportMode) {
        mode = newMode
        saved[KEY_MODE] = newMode.name
    }

    /** "Also bring back *n* houses deleted on this phone", and the bar's *Bring them back*; see [restoreDeleted]. */
    fun setRestoreDeleted(on: Boolean) {
        restoreDeleted = on
        saved[KEY_RESTORE] = on
    }

    /**
     * Works the preview out again from the same staged copy, quietly (no "Checking the file…"), when the screen
     * comes back to the foreground: what is on the phone may have changed meanwhile (see the class KDoc). Only when a
     * checked file is waiting and nothing is running, starting or blocked, and at most every [REFRESH_AFTER_MS]. The
     * new numbers replace the old only if the screen has not moved on in the meantime (a pick, a cancel, a start).
     */
    fun refreshPreview(running: Boolean) {
        val ready = check as? ImportCheck.Ready ?: return
        if (running || starting || checking || blocked) return
        val staged = saved.get<String>(KEY_STAGED) ?: return
        if (staged != ready.stagedPath || !File(staged).isFile) return
        if (SystemClock.elapsedRealtime() - checkedAt < REFRESH_AFTER_MS) return
        val generation = checks
        job = viewModelScope.launch {
            val result = try {
                Imports.preview(repository, staged, fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A quiet refresh that fails keeps the numbers already on screen; the worker re-plans anyway.
                return@launch
            }
            if (generation == checks && !starting && saved.get<String>(KEY_STAGED) == staged &&
                result is ImportCheck.Ready
            ) {
                check = result
                checkedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * Hands the staged copy to [ImportWorker]; from here on the worker owns (and deletes) it. At most once per
     * copy: [KEY_STAGED] is removed below, so a second call for the same copy is a no-op (see the class KDoc).
     */
    fun startImport(context: Context, withMode: ImportMode = mode, skipUpdates: Boolean = false) {
        val ready = check as? ImportCheck.Ready ?: return
        if (saved.get<String>(KEY_STAGED) != ready.stagedPath) return
        choose(withMode)
        val merge = withMode == ImportMode.MERGE
        // The flags of the preview on screen (Imports.preview), so the worker re-plans the same import.
        val request = ImportRequest(
            ready.stagedPath, withMode,
            restoreDeleted = merge && restoreDeleted,
            skipUpdates = merge && skipUpdates,
        )
        val start = startWork(context, request)
        val runId = start.id.toString()
        blocked = false
        setFinishing(false)
        starting = true
        startedRunId = runId
        saved[KEY_STARTED] = runId
        // Handed off: not ours to delete any more, and not to be re-previewed after a restart. The path is kept
        // under another key only so that a *stopped* run's copy can be cleaned up (see [onRunEnded]), or taken
        // back when WorkManager did not keep the request ([onStartDropped]).
        saved[KEY_HANDED] = ready.stagedPath
        saved.remove<String>(KEY_STAGED)
        (scope ?: viewModelScope).launch {
            val queued = try {
                start.queued()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The enqueue itself failed: nothing will run, which for this screen is the same as dropped.
                false
            }
            if (!queued) onStartDropped(runId)
        }
    }

    /**
     * WorkManager did not keep the request [runId] (see the class KDoc): nothing of this tap will ever run. The
     * staged copy is this screen's again, so Import can be tapped once the other run has ended, and the bar stops
     * waiting for a run that does not exist. A no-op once the screen has moved on (another start, or a new pick).
     */
    internal fun onStartDropped(runId: String) {
        if (runId != startedRunId) return
        starting = false
        startedRunId = null
        saved.remove<String>(KEY_STARTED)
        val handed = saved.get<String>(KEY_HANDED)
        saved.remove<String>(KEY_HANDED)
        if (handed != null && File(handed).isFile) {
            saved[KEY_STAGED] = handed
            blocked = true
        } else {
            // Nothing left to import from (swept from the cache meanwhile): start over at the file picker.
            check = null
        }
    }

    /**
     * Called with the import run the screen observes. When the run this screen started has ended, the preview is
     * stale (and an Import button left enabled would re-run against a copy the worker has deleted), so it goes —
     * once: the run is remembered as handled, so a rotation later does not wipe a preview of the *next* file. After
     * a Stop the worker keeps its copy for a retry that will never come, so it is deleted here.
     */
    fun onRunEnded(runId: String, stopped: Boolean, mode: ImportMode? = null) {
        if (runId != startedRunId) {
            // The run that blocked this screen's own has ended, and may have changed what is on the phone: the
            // preview is worked out again from the copy this screen took back, before Import is offered again.
            val staged = saved.get<String>(KEY_STAGED)
            if (blocked && staged != null && saved.get<String>(KEY_HANDLED) != runId) {
                saved[KEY_HANDLED] = runId
                blocked = false
                runCheck { Imports.preview(repository, staged, fileName) }
            }
            return
        }
        if (saved.get<String>(KEY_HANDLED) == runId) return
        starting = false
        saved[KEY_HANDLED] = runId
        val handed = saved.get<String>(KEY_HANDED)
        saved.remove<String>(KEY_HANDED)
        if (stopped && mode == ImportMode.MERGE && handed != null && File(handed).isFile) {
            // A stopped merge: its copy is this screen's again, and the preview now shows what is left to import.
            // If the worker deleted the copy after all (it finished just as Stop arrived), the check comes back
            // without a Ready and the screen falls back to the stopped result card.
            saved[KEY_STAGED] = handed
            setFinishing(true)
            runCheck {
                val result = Imports.preview(repository, handed, fileName)
                if (result is ImportCheck.Refused && !File(handed).isFile) null else result
            }
            return
        }
        if (stopped) Imports.discard(handed)
        check = null
    }

    /** WorkManager now reports [runId]; once that is the run this screen started, its own state takes over. */
    fun onRunObserved(runId: String) {
        if (runId == startedRunId) starting = false
    }

    /**
     * Loads the undo record of [runId], the succeeded copy import on screen, or forgets it ([runId] null). Loaded
     * again whenever an undo ends (the screen passes [CopyImportUndo.outcome] as a key), because the undo deletes or
     * reduces the record. A record older than a day, or the kept-houses record an undo leaves behind, offers no undo,
     * and the screen then shows none.
     */
    fun loadUndo(runId: String?) {
        if (runId == null) {
            if (!undoing) undoRecord = null
            return
        }
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { ImportUndo.load(app, runId) }?.takeUnless { it.undone }
            if (!undoing) undoRecord = record
        }
    }

    /**
     * *Undo this import*: hands [undoRecord] to [CopyImportUndo] (see the class KDoc). No confirmation: it only
     * removes what that import just added, and keeps any house the user has changed since.
     */
    fun undoCopy() {
        val record = undoRecord ?: return
        CopyImportUndo.start(app as HouseHuntApp, record)
    }

    /** Puts the flow in the state a finished check leaves it in (a staged copy and its preview), for unit tests. */
    @VisibleForTesting
    internal fun checkedForTest(ready: ImportCheck.Ready) {
        saved[KEY_STAGED] = ready.stagedPath
        check = ready
    }

    override fun onCleared() {
        // The check job is cancelled with viewModelScope; the copy it was staging deletes itself.
        discardStaged()
    }

    /** [block] returning null means "nothing to show": the screen goes back to the file picker (or a result). */
    private fun runCheck(block: suspend () -> ImportCheck?) {
        check = null
        checking = true
        // A newer pick replaces an older check; the older one's cancellation must not touch the newer's state.
        val generation = ++checks
        job = viewModelScope.launch {
            try {
                val result = block()
                if (result !is ImportCheck.Ready) {
                    saved.remove<String>(KEY_STAGED)
                    setFinishing(false)
                }
                check = result
                checkedAt = SystemClock.elapsedRealtime()
            } finally {
                if (generation == checks) checking = false
            }
        }
    }

    /**
     * Deletes the copy this screen still owns, and only that: [KEY_STAGED] is the one source of truth for it.
     *
     * Deliberately **not** `(check as? ImportCheck.Ready)?.stagedPath`: after [startImport] the check stays Ready
     * until [onRunEnded], but its copy now belongs to [ImportWorker]. Deleting it from here (Import, then Back while
     * the run is ENQUEUED or RUNNING, which clears this ViewModel) would make the worker — or its retry after a
     * system stop, for which it keeps the copy on purpose — report a good backup as "could not be read". Nothing
     * is lost by not falling back: [pick] sets [KEY_STAGED] before previewing, and a restored screen starts from it.
     * A handed-off copy is only ever deleted by the worker, or by [onRunEnded] once WorkManager says the run was
     * cancelled and so will never need it again.
     */
    private fun discardStaged() {
        Imports.discard(saved.get<String>(KEY_STAGED))
        saved.remove<String>(KEY_STAGED)
    }

    private fun setFileName(name: String?) {
        fileName = name
        if (name == null) saved.remove<String>(KEY_NAME) else saved[KEY_NAME] = name
    }

    private fun setFinishing(value: Boolean) {
        finishing = value
        saved[KEY_FINISHING] = value
    }

    private companion object {
        const val KEY_STAGED = "stagedPath"
        const val KEY_MODE = "mode"
        const val KEY_STARTED = "startedRunId"
        const val KEY_HANDED = "handedPath"
        const val KEY_HANDLED = "handledRunId"
        const val KEY_NAME = "fileName"
        const val KEY_FINISHING = "finishing"
        const val KEY_RESTORE = "restoreDeleted"

        /** [refreshPreview] re-checks no more often than this. */
        const val REFRESH_AFTER_MS = 30_000L
    }
}
