package com.househunt.app.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.Data
import androidx.work.WorkInfo
import com.househunt.app.HouseHuntApp
import com.househunt.app.Notifications
import com.househunt.app.data.AppSettings
import com.househunt.app.data.ResultMarks
import com.househunt.app.data.ResultScreen
import com.househunt.app.export.CopyImportUndo
import com.househunt.app.export.ExportRequest
import com.househunt.app.export.ExportWorker
import com.househunt.app.export.ImportCheck
import com.househunt.app.export.ImportUndo
import com.househunt.app.export.ImportWorker
import com.househunt.app.export.OpenBackupDocument
import com.househunt.app.export.Saf
import com.househunt.app.export.ScreenWatch
import com.househunt.app.export.backupProblemOf
import com.househunt.app.export.messageRes
import com.househunt.app.ui.res.*
import com.househunt.shared.api.IsoTime
import com.househunt.shared.export.BackupProblem
import com.househunt.shared.export.ImportMode
import com.househunt.shared.export.ImportPreview
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * See ExportScreen: a finished import is shown if this screen started it or it ended this recently — or, however
 * old, if it failed or nobody was told about it, until it has been seen here once (UX review, round 11).
 */
private const val RECENT_RESULT_MS = 10 * 60 * 1000L

/**
 * What the action bar's status area shows; the bar cross-fades, and TalkBack hears it, when this changes. The
 * checked-file status is one kind per preview on screen (UX review, round 11), so switching the mode or the
 * undelete is announced, not only a switch made by a dialog.
 */
private enum class ImportStatus {
    RUNNING, BLOCKED, CHECKING, REFUSED, NOTHING, READY_MERGE, READY_RESTORE, READY_COPY, FINISH, UNDOING, UNDONE,
    RESULT, NONE,
}

/** What one button position of the bar says and does in the current state (see [BarButton]). */
private class BarAction(
    val text: String,
    val onClick: () -> Unit,
    val style: BarButtonStyle = BarButtonStyle.FILLED,
    val enabled: Boolean = true,
)

/**
 * "Import a backup" (S4-04): pick a file, see exactly what would change, choose merge or copy, and only then
 * write. Nothing existing is ever replaced without the confirmation dialog, and that dialog only appears when the
 * import really would replace something.
 *
 * **Layout (Design review, round 5).** The same pattern as the Export screen: what to read scrolls — the file, the
 * mode and the preview — and what to do sits in the shared [ActionBar] at the bottom, one status line and
 * full-width 48 dp buttons, so *Import* is always within thumb reach and never below a long Tamil preview. The
 * bar, by state:
 *
 * | State | Status | Buttons |
 * |---|---|---|
 * | No file | — (the screen shows the empty state) | **Choose a backup file** |
 * | Checking | "Checking the file…" and an indeterminate bar | Cancel (outlined) |
 * | Refused | the reason, as an error card (assertive) | **Choose another file** |
 * | Merge preview | "Showing what a merge would change…" | Choose another file (outlined) · **Import** |
 * | Merge preview, deleted houses brought back | "Showing what a merge would change, with n houses … brought back…" | Choose another file · **Import** |
 * | Copy preview (the Copy radio, *Import as a copy instead* or *Show as copies*) | "Showing what adding copies would change…" (with the duplicates when there are any) | Choose another file · **Import**, or **Add *n* copies** when some houses would appear twice |
 * | Nothing to do | "Nothing would change…", as a neutral card | **Choose another file** |
 * | Nothing to do, houses deleted here | "Nothing would change in a merge: n houses … were deleted on this phone. Tap Bring them back…" | Choose another file · **Bring them back** |
 * | Importing | "Importing…" and a determinate bar | Stop (outlined) |
 * | Blocked by another import | its progress, "Another import was still running…" | Stop the other import (outlined) |
 * | Stopped merge | "Import stopped. What was already added stays…" | Choose another file · **Finish import** |
 * | Imported | "Import finished." (the sentence itself is the body's heading), or the lost-photos card | Choose a backup file (outlined) · **See your houses** |
 * | Imported as copies, undo on offer | the same; the body says until when it can be undone | Undo this import (outlined) · **See your houses** (opens the list on the copies); *Choose a backup file* is under the body's heading |
 * | Undoing | "Removing the copies…" and an indeterminate bar | Undo this import (disabled) · See your houses (disabled) |
 * | Undone | "Undo finished." (what it did is the body's heading), or the error card | Choose a backup file · **See your houses** (on the kept copies, if any) |
 * | Stopped copy, or failed | the result card (a failure is assertive) | **Choose a backup file** |
 *
 * **Body (Design review, round 10).** The file-type fine print (`import_intro`) is shown only where it matters:
 * in the empty state and under a refused file. Once a file is picked, the body opens with a compact file header —
 * its name as the provider shows it and "Backup made on …" — so the user can see *which* file was checked, and
 * the preview is not pushed below the fold. After a successful import the body is the success itself, on the
 * same designed empty state as the start. Sections are 24 dp apart and 8 dp under their heading, as on Export.
 *
 * **Safety (UX review, 2026-09-22).** The Replace dialog's safe way out, *Import as a copy instead*, only switches
 * the mode: the preview redraws with what adding copies would do and the status line says so, and nothing is
 * written until the user taps Import — a copy of a 40-house backup adds 40 houses, and the user must see that
 * first. The mode is locked while an import runs, so the preview always describes the running work. A stopped or
 * failed copy is rolled back (`Repository.applyImport`), and its message says nothing was added.
 *
 * **Houses deleted on this phone (UX review, rounds 10 and 11).** Restoring houses deleted by mistake is the most
 * common reason to open a backup, and a plain merge cannot do it: the tombstone is newer. The merge preview says so
 * ("Deleted on this phone; they stay deleted", [ImportPreview.deletedHereHouses]) and, under the mode, offers the
 * opt-in undelete as a switch, "Also bring back *n* houses deleted on this phone" (off by default,
 * [ImportViewModel.restoreDeleted]); turned on, the preview is the merge that brings exactly those houses back with
 * their own ids ([ImportPreview.restoredHouses]) and nothing else. When the deleted houses are all the backup holds,
 * the bar says why nothing would change and its primary is **Bring them back**, which turns the switch on and shows
 * that preview. *Show as copies* stays as a secondary text button under the switch. Nothing is written by either
 * until the user taps Import.
 *
 * **Replace dialog (UX review, round 11).** Its title names what is replaced ("Replace 3 houses and 5 visits?"),
 * its body lists up to five of the houses by name, and its first, tonal choice is *Keep mine, add only what's new*
 * (a merge with `skipUpdates`, [ImportCheck.Ready.keepMine]), which replaces nothing; *Replace* and *Import as a copy
 * instead* follow.
 *
 * **Staying honest (UX review, round 11).** The preview is worked out again when the screen resumes after a while
 * ([ImportViewModel.refreshPreview]), so the numbers match what the worker will write; *Choose a backup file*
 * cannot stack two pickers; a result nobody was told about (notifications off) is shown here however old it is
 * until it has been seen; and the first Import while notifications are off asks for them once, in context.
 *
 * **A stopped merge is finished in place (UX review, round 10).** A merge is idempotent, so after Stop the file is
 * previewed again with what is left, and the bar offers **Finish import** ([ImportViewModel.finishing]) instead of
 * asking the user to find and pick the same file again. While another import blocks this one, the bar's button
 * says *Stop the other import*, because that is the run it would stop.
 *
 * **Buttons that keep their place (UX review, round 16).** The bar has two button positions, an optional outlined
 * secondary and the primary, and each is drawn by **one** [BarButton] call site whatever the state, so a tap never
 * removes the node TalkBack has focus on: *Import* becomes *Stop* in the same place, *Stop* becomes *Finish import*
 * or *See your houses*, *Bring them back* becomes *Import*, and after the Replace dialog's *Keep mine* or *Replace*
 * focus returns to the primary, which by then reads *Stop*. What each position says and does per state is worked out
 * first ([BarAction]) and then drawn.
 *
 * **A copy import can be undone (UX review, rounds 16 and 18).** "Add everything as new copies" keeps each house's
 * name and times, so the copies sort next to their originals and sync out, and removing them one by one risked
 * deleting the wrong one of a pair. Its worker records the new ids ([ImportUndo]); for a day the result offers *Undo
 * this import* and says until when ("You can undo this until …"). The undo ([CopyImportUndo], shared with the house
 * list) removes exactly those rows in one transaction and keeps any house edited since ("Removed 40 copies. Kept 2
 * houses you had edited since."), with no confirmation because it only takes away what that import just added.
 * *See your houses* opens the list on the copies, behind a "Just imported" chip, and the list has the same undo in a
 * row under that chip, so the undo is where the user looks at the copies (the Import screen is popped on the way).
 * After an undo that kept houses, *See your houses* opens the list on those. While the undo runs, *See your houses* is
 * disabled, so the list never opens on copies that vanish from under the user. Before the import, the copy preview's
 * button says **Add *n* copies** whenever some houses would appear twice, so the number is in the verb the user taps.
 * *Choose a backup file* closes the result once a file has actually been picked, but does **not** end the undo: the
 * record stays for its day, and the house list keeps offering it.
 *
 * **Announcements.** The bar's status container is the live region (see [ActionBar]), so every change of state is
 * read, assertively for a refused file and a failed import (A11Y-A09).
 *
 * The flow lives in [ImportViewModel], so a rotation or a process restart keeps the picked file, its name and its
 * preview; the Replace dialog is `rememberSaveable` for the same reason. The picker opens at the weekly backup
 * folder when one is set. Tapping Import switches the bar to "Importing…" at once ([ImportViewModel.starting]),
 * and a second tap cannot start a second import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, onOpenHouses: (importedRunId: String?) -> Unit = { onBack() }) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ImportViewModel = viewModel { ImportViewModel(app, createSavedStateHandle()) }
    val repo = repository()
    var confirm by rememberSaveable { mutableStateOf(false) }
    /** True from the tap on a choose button until the picker returns, so a double tap cannot stack two pickers. */
    var picking by rememberSaveable { mutableStateOf(false) }
    /** Closed or moved-on-from runs: here for an instant redraw, and in the DataStore (see ExportScreen). */
    var dismissedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    val marks: ResultMarks? by repo.settings.resultMarks.collectAsStateWithLifecycle(initialValue = null)
    /** A run whose result this visit of the screen has shown; it stays up until the user moves on. */
    var shownRunId by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberNotificationAsk()

    // While this is true a finished import is shown here; while it is false the worker posts a notification.
    LifecycleStartEffect(Unit) {
        ScreenWatch.importScreen = true
        onStopOrDispose { ScreenWatch.importScreen = false }
    }

    // Where the picker opens: the weekly backup folder, when there is one (see OpenBackupDocument).
    val settings by repo.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val backupFolder = remember(settings.autoBackupFolder) {
        settings.autoBackupFolder.takeIf { it.isNotBlank() }
            ?.let { folder -> runCatching { Saf.treeRoot(Uri.parse(folder)) }.getOrNull() }
    }

    /**
     * Closes the result on screen; set further down, once the result's state is known. Called only when a file has
     * actually been picked (Android review, round 17): cancelling the picker is not closing the result. Picking a file
     * does not end a copy's undo either (UX review, round 18): the record stays for its day, and the house list keeps
     * offering *Undo this import*.
     */
    val closeResultOnPick = remember { arrayOfNulls<() -> Unit>(1) }
    val pick = rememberLauncherForActivityResult(OpenBackupDocument()) { uri ->
        picking = false
        if (uri != null) {
            // Before vm.pick: the result is still the one on screen, so it is closed rather than skipped.
            closeResultOnPick[0]?.invoke()
            vm.pick(uri)
        }
    }

    // remember()ed: WorkManager hands back a new Flow instance on every call, and re-subscribing on
    // every recomposition would be a new query each time.
    val workFlow = remember(context) { ImportWorker.observe(context) }
    val work by workFlow.collectAsStateWithLifecycle(emptyList())
    val info = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        ?: work.lastOrNull()
    // From the Import tap itself, not only once WorkManager reports the run 50–300 ms later.
    val running = vm.starting || info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    // Until then, `info` may still be the previous, finished run: its numbers are not this run's.
    val reported = info != null && info.id.toString() == vm.startedRunId

    LaunchedEffect(info?.id) {
        info?.let { vm.onRunObserved(it.id.toString()) }
    }

    // Once the import this screen started has ended — any way — its preview is stale; see ImportViewModel. A
    // stopped merge is previewed again from its own copy, so it can be finished in place.
    LaunchedEffect(info?.id, info?.state) {
        val current = info ?: return@LaunchedEffect
        if (!current.state.isFinished) return@LaunchedEffect
        NotificationManagerCompat.from(context).cancel(Notifications.IMPORT_DONE_ID)
        vm.onRunEnded(
            current.id.toString(),
            stopped = current.state == WorkInfo.State.CANCELLED,
            mode = ImportWorker.modeOf(current),
        )
    }

    val check = vm.check
    val checking = vm.checking
    val mode = vm.mode
    val fileName = vm.fileName
    val ready = check as? ImportCheck.Ready
    val restore = vm.restoreDeleted
    // A merge's preview depends on the undelete switch; a copy's does not.
    val preview = ready?.let { if (mode == ImportMode.MERGE) it.mergeFor(restore, skipUpdates = false) else it.copy }
    // Houses in the file that are live on this phone, by id: a copy adds each of them a second time. Worked out
    // with the data in Imports.preview (ImportPlan.copyDuplicates), not from the merge preview here, which counts a
    // house deleted on this phone as "already here" although a copy is its only visible row.
    val duplicates = ready?.duplicateHouses ?: 0
    // A stopped merge's file, previewed again: Import reads "Finish import".
    val resume = vm.finishing && mode == ImportMode.MERGE
    // Houses a plain merge leaves deleted, from the merge *without* the undelete: the switch that brings them back
    // keeps its count while it is on (the restoring preview has none left "deleted here").
    val deletedHere = if (mode == ImportMode.MERGE) ready?.merge?.deletedHereHouses ?: 0 else 0

    // This screen's own run, one that ended moments ago, or — however old — a failure or a result nobody was told
    // about, until it has been seen here once; never one the user closed, and never under a newly picked file.
    val finished = info != null && info.state.isFinished
    val runId = info?.id?.toString()
    val loadedMarks = marks
    val output = info?.outputData ?: Data.EMPTY
    // A copy import that succeeded and left an undo record behind (UX review, round 16); the record is loaded from
    // its file, and offers no undo once it is older than a day or has been used (closing the result keeps it).
    val succeededRun = info?.takeIf { it.state == WorkInfo.State.SUCCEEDED }
    val undoableCopy = succeededRun != null && ImportWorker.modeOf(succeededRun) == ImportMode.COPY &&
        output.getBoolean(ImportWorker.KEY_UNDOABLE, false)
    // Loaded again when an undo ends, here or on the house list: it deletes or reduces the record.
    val undoOutcome = CopyImportUndo.outcome
    LaunchedEffect(runId, undoableCopy, undoOutcome) { vm.loadUndo(if (undoableCopy) runId else null) }
    val undone = vm.undone?.takeIf { it.runId == runId }
    // Not once this run's copies are gone, even for the moment before the record is loaded again.
    val undoRecord = vm.undoRecord?.takeIf { it.runId == runId && (undone == null || undone.failed) }
    val showResult = finished && check == null && !checking && loadedMarks != null && runId != null &&
        runId != dismissedRunId && (
            // Undone on this visit: what the undo did stays on screen until the user moves on.
            undone != null || (
                runId != loadedMarks.importDismissed && (
                    runId == vm.startedRunId || runId == shownRunId ||
                        // A copy that can still be undone keeps its result, and so its undo, on offer.
                        undoRecord != null ||
                        System.currentTimeMillis() - output.getLong(ExportRequest.KEY_FINISHED_AT, 0L) <
                        RECENT_RESULT_MS ||
                        (runId != loadedMarks.importTold &&
                            (info?.state == WorkInfo.State.FAILED || !ExportWorker.notifiedOf(output)))
                    )
                )
            )
    LaunchedEffect(showResult, runId) {
        if (!showResult || runId == null) return@LaunchedEffect
        shownRunId = runId
        if (loadedMarks?.importTold != runId) repo.settings.markResultTold(ResultScreen.IMPORT, runId)
    }
    fun dismissResult() {
        val id = runId ?: return
        if (!showResult) return
        dismissedRunId = id
        // A copy's undo record is not deleted (UX review, round 18): it stays on offer from the house list.
        (context.applicationContext as HouseHuntApp).appScope.launch {
            repo.settings.markResultDismissed(ResultScreen.IMPORT, id)
        }
    }
    closeResultOnPick[0] = { dismissResult() }

    // Back after a while (Hunt mode, a sync): the numbers are worked out again, so they match what gets written.
    val runningNow by rememberUpdatedState(running)
    LifecycleResumeEffect(vm) {
        vm.refreshPreview(runningNow)
        onPauseOrDispose { }
    }

    val statusKind = when {
        vm.blocked && (running || preview != null) -> ImportStatus.BLOCKED
        running -> ImportStatus.RUNNING
        checking -> ImportStatus.CHECKING
        check is ImportCheck.Refused -> ImportStatus.REFUSED
        preview != null && preview.isEmpty -> ImportStatus.NOTHING
        preview != null && resume -> ImportStatus.FINISH
        preview != null && mode == ImportMode.COPY -> ImportStatus.READY_COPY
        preview != null && preview.restoredHouses > 0 -> ImportStatus.READY_RESTORE
        preview != null -> ImportStatus.READY_MERGE
        showResult && vm.undoing && undoRecord != null -> ImportStatus.UNDOING
        showResult && undone != null -> ImportStatus.UNDONE
        showResult && info != null -> ImportStatus.RESULT
        else -> ImportStatus.NONE
    }
    // Four different "nothing to do"s, each said as what actually happened (UX review, 2026-09-22 and round 10): a
    // backup with no houses or visits at all (a copy would add nothing either), houses that were deleted on this
    // phone (the bar's Bring them back turns the undelete on), a phone that already has the same or newer
    // versions, or a backup that is simply already here.
    val nothingText = if (ready != null && preview != null && preview.isEmpty) {
        when {
            ready.copy.newHouses == 0 && ready.copy.newVisits == 0 -> stringResource(Res.string.import_nothing_empty)
            deletedHere > 0 -> pluralStringResource(Res.plurals.import_nothing_deleted, deletedHere, deletedHere)
            preview.newerHereHouses > 0 || preview.newerHereVisits > 0 -> stringResource(Res.string.import_nothing_newer)
            else -> stringResource(Res.string.import_nothing)
        }
    } else {
        null
    }
    // What an undo did, said once, as the body's heading ("Removed 40 copies. Kept 2 houses you had edited since.");
    // the bar says only "Undo finished." (UX review, round 11's rule, kept for the undo in round 18).
    val undoneText = undoneSentence(undone)
    // Nothing picked, nothing running, nothing to report: the designed empty state.
    val idle = check == null && !checking && !running && !showResult

    val choose: () -> Unit = {
        if (!picking) {
            // The result on screen is closed only once a file is picked (see closeResultOnPick): a cancelled picker
            // leaves it, and a copy's undo, as they were.
            picking = true
            try {
                pick.launch(backupFolder)
            } catch (_: ActivityNotFoundException) {
                // No document picker on the device: nothing will call back, so do not stay locked.
                picking = false
            }
        }
    }
    // Only switches the mode, like the Replace dialog's safe way out: the copy preview is seen before any write.
    val showAsCopies = { vm.choose(ImportMode.COPY) }
    // The undelete, from the bar's "Bring them back": turns the switch on, which shows its preview.
    val bringBack = { vm.onRestoreDeletedChange(true) }
    // Asks for notifications first, once, when they are off, and imports whatever the answer.
    val startImport: (Boolean) -> Unit = { skipUpdates ->
        askNotifications { vm.startImport(context, skipUpdates = skipUpdates) }
    }

    // What each button position of the bar says and does now: worked out here, so the bar can also measure the labels
    // it will draw (Design review, round 18: it stacks the buttons when a label would not fit side by side).
    val succeeded = showResult && info?.state == WorkInfo.State.SUCCEEDED
    // The optional outlined secondary, drawn first, and the primary, drawn last (nearest the thumb when
    // the buttons stack): each position is one BarButton call site in every state (see the KDoc).
    val secondary: BarAction? = when {
        running || checking -> null
        preview != null && !preview.isEmpty ->
            BarAction(stringResource(Res.string.import_pick_another), choose, enabled = !picking)
        preview != null && deletedHere > 0 && !restore ->
            BarAction(stringResource(Res.string.import_pick_another), choose, enabled = !picking)
        check != null -> null
        // A copy that can be undone: the undo takes the secondary place (Choose a backup file moves
        // under the body's heading). Kept, disabled, while the undo runs, so focus stays on it; it
        // becomes Choose a backup file in the same place once the copies are gone.
        succeeded && undoRecord != null -> BarAction(
            stringResource(Res.string.import_undo_copy),
            { vm.undoCopy() },
            enabled = !vm.undoing,
        )
        succeeded -> BarAction(stringResource(Res.string.import_pick), choose, enabled = !picking)
        else -> null
    }
    val primary: BarAction = when {
        // While blocked, the running import is the other one: say which import Stop stops.
        running -> BarAction(
            stringResource(if (vm.blocked) Res.string.import_stop_other else Res.string.export_stop),
            { ImportWorker.cancel(context) },
            BarButtonStyle.OUTLINED,
        )
        // A 1 GB file takes a while to copy; the user can change their mind.
        checking -> BarAction(
            stringResource(Res.string.common_cancel),
            { vm.cancelCheck() },
            BarButtonStyle.OUTLINED,
        )
        preview != null && !preview.isEmpty -> BarAction(
            when {
                resume -> stringResource(Res.string.import_finish)
                // Some houses would appear twice: the number of copies is in the verb (UX review,
                // round 16), "Add 40 copies", not a bare "Import".
                mode == ImportMode.COPY && duplicates > 0 -> pluralStringResource(
                    Res.plurals.import_go_copies, preview.newHouses, preview.newHouses,
                )
                else -> stringResource(Res.string.import_go)
            },
            { if (preview.overwrites > 0) confirm = true else startImport(false) },
        )
        // Everything in the backup was deleted on this phone: the way forward is to bring them
        // back, which turns the undelete on and shows its preview (a copy is the text button in
        // the body).
        preview != null && deletedHere > 0 && !restore ->
            BarAction(stringResource(Res.string.import_bring_back), bringBack)
        check != null ->
            BarAction(stringResource(Res.string.import_pick_another), choose, enabled = !picking)
        // After a successful import the next step is to look at the houses, not to import again
        // (which in copy mode would add everything a second time); picking is still offered.
        succeeded -> BarAction(
            stringResource(Res.string.import_see_houses),
            {
                // Copies that can still be undone: the list opens on them, with the same undo in a row
                // under the "Just imported" chip (UX review, round 18). After an undo that kept houses
                // edited since, it opens on those. Anything else is done with.
                val copies = undoRecord?.runId ?: undone?.takeIf { !it.failed && it.kept > 0 }?.runId
                if (copies == null) dismissResult()
                onOpenHouses(copies)
            },
            // Not while the undo runs: the list would open on copies that vanish from under the
            // user. The same node, so TalkBack keeps its focus on it (UX review, round 18).
            enabled = !vm.undoing,
        )
        else -> BarAction(stringResource(Res.string.import_pick), choose, enabled = !picking)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // One line, ellipsised: "காப்புப்பிரதியை இறக்குமதி செய்" at 200% font would otherwise be clipped.
                title = { Text(stringResource(Res.string.import_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                },
            )
        },
        bottomBar = {
            ActionBar(
                // Each kind reads the screen's state null-safely: while a kind fades out, the state it was drawn
                // from may already have moved on.
                statusKey = statusKind,
                // A refused file, a failed import and a failed undo interrupt TalkBack; everything else is polite.
                assertive = statusKind == ImportStatus.REFUSED ||
                    (statusKind == ImportStatus.RESULT && info?.state == WorkInfo.State.FAILED) ||
                    (statusKind == ImportStatus.UNDONE && undone?.failed == true),
                status = { kind ->
                    when (kind) {
                        ImportStatus.RUNNING -> {
                            val (done, total) =
                                if (reported) ExportWorker.progressOf(info?.progress ?: Data.EMPTY) else (0 to 0)
                            WorkProgress(done, total, stringResource(Res.string.import_working))
                        }
                        // The run on screen is the earlier import that kept this tap from starting (see
                        // ImportViewModel); its own numbers are the honest ones to show.
                        ImportStatus.BLOCKED -> if (running) {
                            val (done, total) = ExportWorker.progressOf(info?.progress ?: Data.EMPTY)
                            WorkProgress(done, total, stringResource(Res.string.import_blocked))
                        } else {
                            StatusLine(stringResource(Res.string.import_blocked))
                        }
                        ImportStatus.CHECKING -> {
                            StatusLine(stringResource(Res.string.import_checking))
                            ProgressBar()
                        }
                        ImportStatus.REFUSED -> (check as? ImportCheck.Refused)?.let {
                            ResultCard(
                                ResultTone.ERROR,
                                stringResource(Res.string.import_failed, stringResource(it.problem.messageRes())),
                            )
                        }
                        ImportStatus.NOTHING -> nothingText?.let { ResultCard(ResultTone.NEUTRAL, it) }
                        // One kind per preview, each saying which one is shown and that nothing is imported yet,
                        // so TalkBack hears every switch of the mode or of the undelete (UX review, round 11).
                        ImportStatus.READY_MERGE -> StatusLine(stringResource(Res.string.import_ready_merge))
                        ImportStatus.READY_RESTORE -> {
                            val n = preview?.restoredHouses ?: 0
                            StatusLine(pluralStringResource(Res.plurals.import_ready_restore, n, n))
                        }
                        // ...and for a copy, how many houses would then be on the phone twice.
                        ImportStatus.READY_COPY -> StatusLine(
                            if (duplicates > 0) {
                                pluralStringResource(Res.plurals.import_copy_shown_duplicates, duplicates, duplicates)
                            } else {
                                stringResource(Res.string.import_copy_shown)
                            }
                        )
                        ImportStatus.FINISH ->
                            ResultCard(ResultTone.NEUTRAL, stringResource(Res.string.import_stopped_resume))
                        ImportStatus.UNDOING -> {
                            StatusLine(stringResource(Res.string.import_undoing))
                            ProgressBar()
                        }
                        // Said once (UX review, round 11's rule): what the undo did is the body's heading, so the bar
                        // says only "Undo finished.", as it says "Import finished." (Design review, round 18).
                        ImportStatus.UNDONE -> if (undone?.failed == true) {
                            ResultCard(ResultTone.ERROR, stringResource(Res.string.import_undo_failed))
                        } else {
                            StatusLine(stringResource(Res.string.import_undo_finished))
                        }
                        ImportStatus.RESULT -> info?.let { ImportResult(it, onDismiss = { dismissResult() }) }
                        ImportStatus.NONE -> Unit
                    }
                },
                labels = listOfNotNull(secondary?.text, primary.text),
                actions = {
                    secondary?.let { BarButton(it.text, it.onClick, BarButtonStyle.OUTLINED, enabled = it.enabled) }
                    BarButton(primary.text, primary.onClick, primary.style, enabled = primary.enabled)
                },
            )
        },
    ) { padding ->
        // No horizontal padding here: the mode rows run edge to edge; everything else is inset by [inset].
        Column(
            // The same rhythm as the Export screen (Design review, round 10): no uniform gap, but 24 dp before a
            // section heading (the divider's 12 dp above and below) and 8 dp after it.
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            val inset = Modifier.padding(horizontal = 16.dp)
            val divider = inset.padding(vertical = 12.dp)
            val headingPad = inset.padding(bottom = 4.dp)
            when {
                // What this screen is for in one line, and which files it can read as the fine print under it.
                idle -> HeroEmptyState(
                    icon = RestoreIcon,
                    title = stringResource(Res.string.import_lead),
                    body = stringResource(Res.string.import_intro),
                )
                // The name is known before the (possibly long) copy finishes.
                checking -> FileHeader(fileName, madeOn = null, modifier = inset)
                // Which file, and — the reason it was refused — which files can be read.
                check is ImportCheck.Refused -> {
                    if (fileName != null) {
                        FileHeader(fileName, madeOn = null, modifier = inset)
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        stringResource(Res.string.import_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = inset,
                    )
                }
                ready != null && preview != null -> {
                    val madeOn = ready.manifest?.createdAt
                        ?.let { iso -> runCatching { IsoTime.parseMillis(iso) }.getOrNull() }
                        ?.let { millis -> stringResource(Res.string.import_backup_of, millis.dateText()) }
                    FileHeader(ready.displayName ?: fileName, madeOn, inset)

                    HorizontalDivider(divider)
                    SectionHeading(stringResource(Res.string.import_mode), headingPad)
                    // Locked while an import runs: the preview must keep describing the work being written.
                    Column(Modifier.selectableGroup()) {
                        RadioRow(
                            AnnotatedString(stringResource(Res.string.import_mode_merge)),
                            stringResource(Res.string.import_mode_merge_hint),
                            mode == ImportMode.MERGE,
                            enabled = !running,
                        ) { vm.choose(ImportMode.MERGE) }
                        RadioRow(
                            AnnotatedString(stringResource(Res.string.import_mode_copy)),
                            stringResource(Res.string.import_mode_copy_hint),
                            mode == ImportMode.COPY,
                            enabled = !running,
                        ) { vm.choose(ImportMode.COPY) }
                    }
                    // The merge's opt-in undelete (off by default): exactly the houses deleted on this phone come
                    // back, with their own ids, their visits and their photos; nothing else is added twice. A copy
                    // of everything stays one tap away, as a secondary text button.
                    if (deletedHere > 0) {
                        SwitchRow(
                            text = pluralStringResource(Res.plurals.import_restore_switch, deletedHere, deletedHere),
                            hint = stringResource(Res.string.import_restore_switch_hint),
                            checked = restore,
                            enabled = !running,
                            onChange = { vm.onRestoreDeletedChange(it) },
                        )
                        if (!running) {
                            // The label starts at the text's edge: 16 dp less the TextButton's own 12 dp padding.
                            TextButton(
                                onClick = showAsCopies,
                                modifier = Modifier.padding(start = 4.dp, end = 16.dp).heightIn(min = 48.dp),
                            ) { ButtonLabel(stringResource(Res.string.import_show_copies)) }
                        }
                    }

                    // An empty preview is said once, in the action bar, next to the only thing left to do.
                    if (!preview.isEmpty) {
                        HorizontalDivider(divider)
                        SectionHeading(stringResource(Res.string.import_preview), headingPad)
                        // 4 dp on top: the card has no row padding of its own, and heading to content is 8 dp.
                        PreviewCard(
                            preview,
                            if (mode == ImportMode.COPY) duplicates else 0,
                            inset.padding(top = 4.dp),
                        )
                    }
                }
                // The payoff: the success itself, said once, here, with the same weight as the empty state it
                // replaced. The bar only says "Import finished." (or which photos were lost), so the sentence is
                // not heard twice (UX review, round 11). A success is the tick in the success colours, as Export's
                // result card is green (Design review, round 18: in brand teal it looked like "no file yet"); what an
                // undo did is a reversal, so it is the restore glyph in neutral colours, not a success tick.
                showResult && info?.state == WorkInfo.State.SUCCEEDED -> HeroEmptyState(
                    icon = if (undoneText != null) RestoreIcon else Icons.Default.CheckCircle,
                    // After an undo, what the undo did; the import's own sentence would name houses that are gone.
                    title = undoneText ?: importedTextOf(context, output),
                    // How long the undo is on offer (UX review, round 18): it is withdrawn after a day, and without
                    // this line the button would just be gone the next morning.
                    body = undoRecord?.let { record ->
                        stringResource(Res.string.import_undo_until, (record.finishedAt + ImportUndo.KEEP_MS).dateText())
                    },
                    iconTint = if (undoneText != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        LocalHouseHuntColors.current.onSuccess
                    },
                    iconContainer = if (undoneText != null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        LocalHouseHuntColors.current.success
                    },
                    // While the undo holds the bar's secondary place, choosing another file is offered here.
                    action = if (undoRecord != null) {
                        {
                            TextButton(
                                onClick = choose,
                                enabled = !picking,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { ButtonLabel(stringResource(Res.string.import_pick)) }
                        }
                    } else {
                        null
                    },
                )
                // A stopped copy or a failed run (its card is in the bar), or a run started elsewhere.
                else -> HeroEmptyState(
                    icon = RestoreIcon,
                    title = stringResource(Res.string.import_lead),
                    body = stringResource(Res.string.import_intro),
                )
            }
        }
    }

    if (confirm && preview != null && ready != null) {
        // "Keep mine" writes only new rows; offered first when it would add anything at all.
        val keepMine = ready.mergeFor(restore, skipUpdates = true)
        ReplaceDialog(
            houses = preview.updatedHouses,
            visits = preview.updatedVisits,
            labels = ready.replacedHouseLabels,
            canKeepMine = !keepMine.isEmpty,
            onKeepMine = {
                // Replaces nothing, so it needs no further confirmation: only new rows (and, with the switch on,
                // the houses deleted here) are written, and the result says what was added.
                confirm = false
                startImport(true)
            },
            onCopyInstead = {
                // Only the mode changes. The preview redraws with the copy numbers, the status line says so, and
                // nothing is written until the user taps Import: a copy can add many houses, and they must see
                // how many first (docs/05 section 14.3, the preview is the safety mechanism).
                confirm = false
                showAsCopies()
            },
            onReplace = {
                confirm = false
                startImport(false)
            },
            onCancel = { confirm = false },
        )
    }
}

/**
 * Which file this is (Design review, round 10): its name as the provider shows it, in titleSmall, and "Backup made
 * on …" under it in muted bodySmall, in a quiet outlined card. Draws nothing when neither is known.
 */
@Composable
private fun FileHeader(name: String?, madeOn: String?, modifier: Modifier) {
    if (name == null && madeOn == null) return
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            name?.let {
                // A long name with no spaces wraps anywhere rather than running off; three lines is plenty.
                Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            madeOn?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The success sentence of a finished import ("Brought back 3 houses. Added 20 photos. Updated 3 houses."). */
private fun importedTextOf(context: Context, output: Data): String = ImportWorker.importedText(
    context,
    output.getInt(ImportWorker.KEY_HOUSES, 0),
    output.getInt(ImportWorker.KEY_VISITS, 0),
    output.getInt(ImportWorker.KEY_PHOTOS, 0),
    output.getInt(ImportWorker.KEY_UPDATED_HOUSES, 0),
    output.getInt(ImportWorker.KEY_UPDATED_VISITS, 0),
    output.getInt(ImportWorker.KEY_RESTORED_HOUSES, 0),
)

/**
 * What an undo of a copy import did, as one sentence ("Removed 40 copies. Kept 2 houses you had edited since."), or
 * null for no outcome or a failed one. The Import screen's heading and the house list's undo row both say it.
 */
@Composable
internal fun undoneSentence(outcome: CopyImportUndo.Outcome?): String? = outcome?.takeIf { !it.failed }?.let { u ->
    buildList {
        if (u.removed > 0) add(pluralStringResource(Res.plurals.import_undone, u.removed, u.removed))
        if (u.kept > 0) add(pluralStringResource(Res.plurals.import_undone_kept, u.kept, u.kept))
        if (isEmpty()) add(stringResource(Res.string.import_undone_nothing))
    }.joinToString(" ")
}

/**
 * "Replace 3 houses and 5 visits?" (UX review, round 11: houses and visits are counted apart, not lumped together as
 * "saved items") with stacked, full-width buttons (Design review, round 5), safest first:
 *
 *  1. *Keep mine, add only what's new* (tonal) — a merge with `skipUpdates`, which replaces nothing. Shown when it
 *     would add anything at all ([canKeepMine]); otherwise *Import as a copy instead* takes the tonal slot, as before.
 *  2. *Replace* (outlined in the error colour: the one irreversible action).
 *  3. *Import as a copy instead* (text) — only switches the mode; the copy preview is seen before anything is written.
 *  4. *Cancel*.
 *
 * The scrolling body names up to [REPLACED_LABELS] of the houses that would be replaced ([labels], blank for a house
 * with no name) and "and *n* more", so the user can judge what they are overwriting. Stacked, so the long Tamil and
 * Telugu labels wrap inside their own button instead of pushing each other around.
 *
 * Design review, 2026-09-22: the headline is centred under the hero icon, as the M3 dialog spec does when a hero
 * icon is present, with 16 dp between icon, title and body and 24 dp before the buttons. The safe tonal button is
 * on `primaryContainer` ([tonalPrimaryColors]), not M3's default `secondaryContainer`, which was amber until round
 * 19 and next to a red icon and a red Replace painted the safe choice as a caution too. The container is the M3
 * dialog role `surfaceContainerHigh`, not a primary-tinted elevated surface.
 *
 * **Large text (Design review, round 10).** In Tamil or Telugu at 200% font the dialog is taller than a portrait
 * phone, and `BasicAlertDialog` only limits the size: the window cut off the ways forward. The title and body
 * scroll between the icon and the buttons, which stay pinned and always visible (WCAG 1.4.4, 1.4.10). Where even
 * the icon and the buttons alone do not fit — landscape at 200% — the whole dialog scrolls instead, so every button
 * can still be reached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceDialog(
    houses: Int,
    visits: Int,
    labels: List<String>,
    canKeepMine: Boolean,
    onKeepMine: () -> Unit,
    onCopyInstead: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val parts = buildList {
        if (houses > 0) add(pluralStringResource(Res.plurals.count_houses, houses, houses))
        if (visits > 0) add(pluralStringResource(Res.plurals.count_visits, visits, visits))
    }
    val title = stringResource(Res.string.import_confirm_title_parts, ImportWorker.joined(context, parts))
    val unnamed = stringResource(Res.string.house_unnamed)
    val more = houses - labels.size
    BasicAlertDialog(onDismissRequest = onCancel) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            BoxWithConstraints {
                // Too short to pin the buttons and still show some text: scroll the whole dialog instead. Only one
                // of the two scrolls is ever applied (a scroll inside a scroll has no height to measure against).
                val compact = maxHeight < COMPACT_DIALOG_HEIGHT
                val outer = if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier
                Column(outer.padding(24.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(16.dp))
                    val text = if (compact) {
                        Modifier
                    } else {
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    }
                    Column(text) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().semantics { heading() },
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(Res.string.import_confirm_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (labels.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            labels.forEach { label ->
                                Text(
                                    "• " + label.ifBlank { unnamed },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (more > 0) {
                                Text(
                                    pluralStringResource(Res.plurals.import_confirm_more, more, more),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val full = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        if (canKeepMine) {
                            FilledTonalButton(onClick = onKeepMine, modifier = full, colors = tonalPrimaryColors()) {
                                ButtonLabel(stringResource(Res.string.import_keep_mine))
                            }
                        } else {
                            FilledTonalButton(onClick = onCopyInstead, modifier = full, colors = tonalPrimaryColors()) {
                                ButtonLabel(stringResource(Res.string.import_as_copy))
                            }
                        }
                        OutlinedButton(
                            onClick = onReplace,
                            modifier = full,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) { ButtonLabel(stringResource(Res.string.import_confirm_yes)) }
                        if (canKeepMine) {
                            TextButton(onClick = onCopyInstead, modifier = full) {
                                ButtonLabel(stringResource(Res.string.import_as_copy))
                            }
                        }
                        TextButton(onClick = onCancel, modifier = full) {
                            ButtonLabel(stringResource(Res.string.common_cancel))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Below this height the Replace dialog scrolls as a whole: the icon, the spacing and four two-line buttons at 200%
 * font take about 440 dp, and the pinned layout needs room left for at least a few lines of the question.
 */
private val COMPACT_DIALOG_HEIGHT = 540.dp

/**
 * The finished import in the bar (UX review, round 11). A success is said in full once, as the body's heading, so
 * the bar only says "Import finished." (enough for the live region to announce it once) — or, when photos could not
 * be restored, that instead, as an error card. A failure or a stop is a card here with a close button ([onDismiss]).
 */
@Composable
private fun ImportResult(info: WorkInfo, onDismiss: () -> Unit) {
    val output = info.outputData
    when (info.state) {
        WorkInfo.State.SUCCEEDED -> {
            val lost = output.getInt(ImportWorker.KEY_PHOTOS_SKIPPED, 0)
            if (lost > 0) {
                // A photo that was in the file but could not be read or verified is not a clean import; say so
                // rather than letting the success heading imply everything arrived.
                ResultCard(ResultTone.ERROR, pluralStringResource(Res.plurals.import_photos_lost, lost, lost))
            } else {
                StatusLine(stringResource(Res.string.import_finished))
            }
        }
        WorkInfo.State.FAILED -> {
            val problem = backupProblemOf(output.getString(ExportRequest.KEY_ERROR))
            ResultCard(
                ResultTone.ERROR,
                // A write failure is not a problem with the file. A merge writes row by row, so it may be partial
                // and importing the file again finishes it; a copy is rolled back, so nothing was added.
                if (problem == BackupProblem.WRITE_FAILED) {
                    stringResource(ImportWorker.writeFailedRes(ImportWorker.modeOf(info)))
                } else {
                    stringResource(Res.string.import_failed, stringResource(problem.messageRes()))
                },
                onDismiss = onDismiss,
            )
        }
        // A stopped merge whose file could be taken back is shown as "Finish import" instead (see ImportViewModel);
        // this is a stopped copy, or a merge whose copy had gone.
        WorkInfo.State.CANCELLED -> ResultCard(
            ResultTone.NEUTRAL,
            stringResource(ImportWorker.stoppedRes(ImportWorker.modeOf(info))),
            onDismiss = onDismiss,
        )
        else -> Unit
    }
}

/** One preview line: its sign, its label, and its number. */
private data class PreviewLine(val icon: ImageVector, val label: StringResource, val count: Int, val loss: Boolean = false)

/**
 * The preview (docs/05 section 14.3, "the safety mechanism"), grouped as houses / visits / photos with a thin
 * divider between groups. Lines that are zero are hidden. Each line is a sign, a label and the number in its own
 * right-aligned column with tabular figures, so the numbers can be scanned down one edge instead of sitting at
 * the ragged end of a wrapped Tamil or Telugu sentence: + for something new, a refresh sign for something the
 * backup has a newer version of, the restore glyph for houses deleted on this phone that the undelete brings back,
 * an info sign for "kept" and for "deleted on this phone; they stay deleted" (houses a plain merge will not bring
 * back; the switch above offers to), and a warning sign in the error colour for the lines about something lost or
 * missing (the text itself stays plain: no scare styling, section 14.2).
 *
 * In copy mode, [duplicates] (houses already on this phone) comes first, as a warning line: "Already on this phone,
 * will appear twice: 37" is the number to weigh before adding copies. For a day after the import they can all be
 * removed at once with *Undo this import* (on the result and on the house list); after 24 hours they can only be
 * removed one by one.
 */
@Composable
private fun PreviewCard(preview: ImportPreview, duplicates: Int, modifier: Modifier) {
    val groups = listOf(
        listOf(
            PreviewLine(Icons.Default.Warning, Res.string.import_copy_duplicates, duplicates, loss = true),
            PreviewLine(Icons.Default.Add, Res.string.import_new_houses, preview.newHouses),
            PreviewLine(Icons.Default.Refresh, Res.string.import_updated_houses, preview.updatedHouses),
            PreviewLine(Icons.Default.Warning, Res.string.import_checklists_cleared, preview.checklistsCleared, loss = true),
            // The undelete (UX review, round 11): houses deleted on this phone that come back with their own ids.
            PreviewLine(RestoreIcon, Res.string.import_restored_houses, preview.restoredHouses),
            PreviewLine(Icons.Default.Info, Res.string.import_newer_here, preview.newerHereHouses),
            // Not "kept": these houses are not on the phone, and a merge leaves them deleted (UX review, round 10).
            PreviewLine(Icons.Default.Info, Res.string.import_deleted_here, preview.deletedHereHouses),
        ),
        listOf(
            PreviewLine(Icons.Default.Add, Res.string.import_new_visits, preview.newVisits),
            PreviewLine(Icons.Default.Refresh, Res.string.import_updated_visits, preview.updatedVisits),
        ),
        listOf(
            PreviewLine(Icons.Default.Add, Res.string.import_new_photos, preview.newPhotos),
            PreviewLine(Icons.Default.Warning, Res.string.import_photos_missing, preview.photosMissingFromFile, loss = true),
        ),
    ).map { group -> group.filter { it.count > 0 } }.filter { it.isNotEmpty() }
    if (groups.isEmpty()) return
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                group.forEach { line ->
                    Line(
                        icon = line.icon,
                        label = stringResource(line.label),
                        count = line.count,
                        tint = if (line.loss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Read by TalkBack as one item, "New houses 3". Label and number share one baseline (they are different sizes,
 * and top-aligned their baselines differed by 2–3 sp on every line of a column meant to be scanned); the icon sits
 * 2 dp down, centred on the label's first line.
 */
@Composable
private fun Line(icon: ImageVector, label: String, count: Int, tint: Color) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).alignByBaseline())
        Spacer(Modifier.width(12.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.alignByBaseline(),
        )
    }
}
