package com.househunt.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.Data
import androidx.work.WorkInfo
import com.househunt.app.HouseHuntApp
import com.househunt.app.Notifications
import com.househunt.app.R
import com.househunt.app.data.ResultMarks
import com.househunt.app.data.ResultScreen
import com.househunt.app.export.CreateExportDocument
import com.househunt.app.export.ExportBuilder
import com.househunt.app.export.ExportGrants
import com.househunt.app.export.ExportRequest
import com.househunt.app.export.ExportWorker
import com.househunt.app.export.ImportWorker
import com.househunt.app.export.ResultActions
import com.househunt.app.export.ScreenWatch
import com.househunt.app.export.messageRes
import com.househunt.shared.export.BackupCompleteness
import com.househunt.shared.export.BackupGap
import com.househunt.shared.export.ExportFormat
import com.househunt.shared.export.ExportLanguages
import com.househunt.shared.export.ExportOptions
import com.househunt.shared.export.ExportScope
import com.househunt.shared.export.PhotoScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FormatChoice(val format: ExportFormat, val label: Int, val hint: Int)

private val formatChoices = listOf(
    FormatChoice(ExportFormat.HTML, R.string.format_html, R.string.format_html_hint),
    FormatChoice(ExportFormat.PDF, R.string.format_pdf, R.string.format_pdf_hint),
    FormatChoice(ExportFormat.CSV, R.string.format_csv, R.string.format_csv_hint),
    FormatChoice(ExportFormat.XLSX, R.string.format_xlsx, R.string.format_xlsx_hint),
    FormatChoice(ExportFormat.MARKDOWN, R.string.format_markdown, R.string.format_markdown_hint),
    FormatChoice(ExportFormat.BACKUP, R.string.format_backup, R.string.format_backup_hint),
)

/**
 * A finished run's result is shown when this screen started it, or when it finished this recently (the user left
 * during a long export and came back). Anything older is history, not news: a "Saved…" from days ago shown on
 * entry reads as if something had just happened — **unless nobody was told** (see [ExportScreen], "Results nobody
 * was told about").
 */
private const val RECENT_RESULT_MS = 10 * 60 * 1000L

/** How long before the screen came back a Share export may have finished and still open the share sheet by itself. */
private const val SHARE_GRACE_MS = 5_000L

/** What the chosen options would export, for the live count next to the buttons (docs/05 section 14.1). */
private data class ExportCounts(val houses: Int, val visits: Int, val photos: Int)

/** What the action bar's status area shows; the bar cross-fades when this changes (see [ActionBar]). */
private enum class ExportStatus { RUNNING, MESSAGE, RESULT, COUNTS, NOTHING_MATCHES, NONE }

/**
 * "Save a copy" (S4-02). Everything on this screen is a choice docs/11 section 5.2 lists: format, which houses,
 * photos, contact details and the language of the copy. The export itself runs in [ExportWorker], so leaving this
 * screen — or rotating the phone — does not interrupt it; coming back shows the same progress again.
 *
 * **Layout (Design review, rounds 4 and 5).** The options scroll; the actions do not. Save, Share, the progress
 * bar, Stop and the result sit in the shared [ActionBar], always within thumb reach, so someone who comes back
 * mid-export sees the progress and the Stop control, not a screenful of options. The bar's status area is the live
 * count while idle (three chips: "12 houses" "30 visits" "80 photos" — the honest answer to "did Shortlisted only
 * do what I meant?"), "Saving your copy…" while running, and the result afterwards. **Changing any option, or
 * closing the result card, puts the live count back** (as the web page clears its result when the format changes):
 * the result is about a file made with other options. The format is a group of selectable cards, the one choice
 * that decides what the rest of the screen means, as on the web; the other options are edge-to-edge rows
 * ([RadioRow], [SwitchRow]). *Photos* and *Include rejected houses* ease in and out over 150 ms when they stop
 * applying. With no house at all the options are replaced by the same empty state as the web page (a glyph on a
 * soft circle, one sentence, and a way to the map).
 *
 * **Two "Share"s, one meaning each.** The bar's *Share* makes a new copy with the options on screen and shares it;
 * the result card's action is *Share this file*, the file that was just made (WCAG 3.2.4).
 *
 * **State.** Every option, the pending share and the id of the run this screen started are `rememberSaveable`, so
 * a rotation, a language switch, or the process being killed while the file picker is open loses nothing: the
 * picker's callback builds the options from the restored state. Which results have been seen or closed outlives the
 * screen itself, in the DataStore (see "Results nobody was told about" below).
 *
 * **Feedback on the tap (UX review, 2026-09-22).** *Save to…* is disabled from the tap until the file picker
 * returns, so a double tap cannot stack two pickers; and from the moment a run is started until WorkManager first
 * reports it, the bar already shows "Saving your copy…" and Stop, instead of the old result or the idle buttons.
 *
 * **Accessibility.** Every option is a whole-row (or whole-card) target of at least 48 dp; the radio and switch
 * controls carry `onClick = null` so TalkBack reads the row once with its state, and a locked row's text is drawn
 * disabled too. The bar's status container is the live region (UX review, round 10; see [ActionBar]), so each
 * change of kind is read — the count, "Saving your copy…", the result — assertively for a problem or a failed run.
 * The count keeps a live region of its own too (read as one sentence, not three chips), because it changes while
 * its node stays in place. The running numbers are on the progress bar's state description, read when the bar is
 * focused, not announced every 400 ms.
 *
 * **Round 10 (Design and UX review).** The bar's buttons are *Share* (outlined) then **Save to…** (filled), the
 * filled primary last as on the Import screen, so it is at the bottom when the bar stacks them at large text. A
 * Share export opens the share sheet by itself only if it finished while the screen was visible or within
 * [SHARE_GRACE_MS] of it coming back; after a longer absence the result card waits for a tap. Sections are 24 dp
 * apart and 8 dp under their heading.
 *
 * **A partial "Full backup" (UX review, round 11).** The scope, rejected, photos and contacts options apply to the
 * backup too, and a backup is the file a user keeps in order to wipe or replace the phone. When the backup format is
 * chosen with any of them narrowed, an amber note right under the format cards names exactly what the file will
 * leave out ([BackupCompleteness]: "This backup leaves out houses that are not shortlisted and contact details.
 * Restoring from it will not bring those back.") with *Use everything*, which resets the four options in one tap.
 * The result card and the notification then say "Saved a partial backup…", never plain "Saved".
 *
 * **Results nobody was told about (UX review, round 11).** A run's result used to reach the user only on this
 * screen within [RECENT_RESULT_MS], or by a notification, which is silently skipped without notification
 * permission. The worker now records whether anyone was told (`ExportRequest.KEY_NOTIFIED`), and a finished run is
 * shown however old it is when it **failed** or **nobody was told**, until the user has seen it here once. What has
 * been seen and what was closed are kept in the Settings DataStore ([com.househunt.app.data.ResultMarks]), not in
 * `rememberSaveable`, so backing out of the screen does not forget them. The first *Save to…* or *Share* while
 * notifications are not allowed asks for them in context, once ([rememberNotificationAsk]), and goes ahead whatever
 * the answer.
 *
 * **Buttons that keep their place (UX review, round 16).** The bar's two buttons are [BarButton]s, one call site per
 * position, so a tap never removes the node TalkBack has focus on: *Save to…* turns into *Stop* (outlined) in the
 * same place, and back into *Save to…* when the run ends; a Share run's *Stop* is drawn in *Share*'s place. With
 * nothing matching because every house is rejected and rejected houses are left out, the status offers *Include
 * rejected houses*, as it offers *All houses* for an empty shortlist.
 *
 * **Contact details.** While they are included, the privacy note is part of the switch row ([SwitchRow]'s
 * `warning`), so TalkBack reads it with the control rather than one swipe later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(onBack: () -> Unit, onOpenMap: () -> Unit = onBack) {
    val context = LocalContext.current
    val repo = repository()

    var format by rememberSaveable { mutableStateOf(ExportFormat.HTML) }
    var scope by rememberSaveable { mutableStateOf(ExportScope.ALL) }
    var includeRejected by rememberSaveable { mutableStateOf(true) }
    var photos by rememberSaveable { mutableStateOf(PhotoScope.ALL) }
    var includeContacts by rememberSaveable { mutableStateOf(true) }
    var language by rememberSaveable { mutableStateOf(ExportBuilder.defaults(context).language) }

    /** Set while a "Share" export is running, so the share sheet opens with the finished file. */
    var pendingShare by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * When this screen last reached STARTED (wall clock, as the worker's KEY_FINISHED_AT is). A Share export that
     * finished well before that — the user left during a long PDF and came back minutes later — does not throw the
     * share sheet at them on return; its result card's *Share this file* does the same on a tap (Design review,
     * round 10). Not state: nothing redraws from it.
     */
    val visibleSince = remember { longArrayOf(System.currentTimeMillis()) }
    /** The run this screen started; see [RECENT_RESULT_MS]. */
    var startedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * A finished run whose result the user has moved on from: they changed an option or closed its card. Kept here
     * for an instant redraw, and in the DataStore ([ResultMarks]) so that leaving the screen does not bring it back.
     */
    var dismissedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Seen and closed runs, persisted; null until the DataStore has answered, and no old result is shown till then. */
    val marks: ResultMarks? by repo.settings.resultMarks.collectAsStateWithLifecycle(initialValue = null)
    /** A run whose result this visit of the screen has shown; it stays up until the user moves on. */
    var shownRunId by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberNotificationAsk()
    /** A problem with a follow-up (no app to share or open with); cleared by the next action. */
    var message by remember { mutableStateOf<String?>(null) }
    /** True from the Save to… tap until the file picker returns, so a double tap cannot open a second picker. */
    var picking by rememberSaveable { mutableStateOf(false) }
    /**
     * A run this screen has started that WorkManager has not reported yet; the bar treats it as running.
     * `remember`, not `rememberSaveable` (Android review, 2026-09-22): after a restore WorkManager already knows the
     * run, and a restored id of a run it has since pruned (results are kept for about a day) would never be seen
     * again, leaving the bar on "Saving your copy…" with a Stop that cannot clear it. Stop clears it too.
     */
    var awaitingRunId by remember { mutableStateOf<String?>(null) }
    /**
     * Set by a recovery button in the "nothing matches" status (*All houses*, *Include rejected houses*; Android
     * review, round 17). That button goes away with the status it sits in, which took TalkBack's focus back to the top
     * of the screen; instead focus moves to *Save to…*, the action the recovery has just made possible, as soon as it
     * is enabled. Only with TalkBack (touch exploration) on, and cleared a frame after the request (UX review, round
     * 18): for a touch user the forced focus left M3's focused highlight on *Save to…*, which looked stuck.
     */
    var focusSaveAfterRecovery by remember { mutableStateOf(false) }
    val saveFocus = remember { FocusRequester() }

    // While this is true a finished export is shown here; while it is false the worker posts a notification.
    LifecycleStartEffect(Unit) {
        ScreenWatch.exportScreen = true
        visibleSince[0] = System.currentTimeMillis()
        onStopOrDispose { ScreenWatch.exportScreen = false }
    }

    // Files shared earlier are removed after a day (docs/11 section 5.2); this is the only place they pile up.
    LaunchedEffect(Unit) { cleanSharedExports(context) }

    fun options(): ExportOptions = ExportBuilder.defaults(context).copy(
        scope = scope,
        includeRejected = includeRejected,
        photos = if (format.usesPhotos) photos else PhotoScope.NONE,
        includeContacts = includeContacts,
        language = language,
    )

    // Every row, read again whenever the houses, visits or photos tables change (Android review, 2026-09-22):
    // Hunt mode records visits and photos are added while this screen is open, and the chips must keep matching
    // the file, which the worker reads fresh. Not once per option tap (UX review, 2026-09-22). null until Room has
    // answered, so "nothing to save" never flashes up on a phone that has houses.
    val rowsFlow = remember(repo) { repo.localRowsFlow() }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = null)
    var counts by remember { mutableStateOf<ExportCounts?>(null) }
    // The same filter the exporter uses, so the number is what the file will hold. Mapping and filtering every
    // row is CPU work, so it runs on Dispatchers.Default, not on the main thread under the option rows.
    LaunchedEffect(rows, format, scope, includeRejected, photos, includeContacts) {
        val current = rows ?: return@LaunchedEffect
        val chosen = options()
        val backup = format == ExportFormat.BACKUP
        counts = withContext(Dispatchers.Default) {
            val bundle = ExportBuilder.build(current, chosen)
            // A JSON backup also carries the visits that belong to no house yet (Hunt mode); the tables do not.
            val unlinked = if (backup) bundle.unlinkedVisits.size else 0
            ExportCounts(bundle.houses.size, bundle.visits.size + unlinked, bundle.photos.size)
        }
    }
    val databaseEmpty = rows?.houses?.isEmpty() == true
    val matching = counts?.houses ?: 0

    val saveTo = rememberLauncherForActivityResult(CreateExportDocument()) { uri ->
        picking = false
        // Reads the restored options: this callback can arrive in a new activity after the picker.
        if (uri != null) {
            pendingShare = null
            message = null
            // Before the run starts, while this activity still holds the picker's grant: that grant ends with the
            // activity, and the export is built to outlive it (Stop's delete, a retry, the notification's Open and
            // Share). Recorded off the main thread in the app's scope, which leaving this screen does not cancel;
            // older grants beyond ExportGrants.KEPT are released there.
            ExportGrants.take(context, uri)
            val app = context.applicationContext
            (app as HouseHuntApp).appScope.launch { ExportGrants.hold(app, uri.toString()) }
            startedRunId = ExportWorker.start(context, ExportRequest(format, uri.toString(), options())).toString()
            awaitingRunId = startedRunId
        }
    }

    // remember()ed: WorkManager hands back a new Flow instance on every call, and re-subscribing on
    // every recomposition would be a new query each time.
    val workFlow = remember(context) { ExportWorker.observe(context) }
    val work by workFlow.collectAsStateWithLifecycle(emptyList())
    // REPLACE deletes a replaced run's row, so this is normally one row; but the Flow's order is not promised,
    // so if it ever holds more, a live run wins over a finished one.
    val info = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        ?: work.lastOrNull()
    // Until WorkManager reports the run just started, the bar already shows it as running (see the KDoc). Cleared
    // as soon as the run appears, so a run WorkManager later prunes can never leave the bar stuck on "Saving".
    val awaiting = awaitingRunId != null && work.none { it.id.toString() == awaitingRunId }
    LaunchedEffect(work, awaitingRunId) {
        if (awaitingRunId != null && work.any { it.id.toString() == awaitingRunId }) awaitingRunId = null
    }
    val running = awaiting || info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val output = info?.outputData ?: Data.EMPTY

    LaunchedEffect(info?.id, info?.state) {
        val current = info ?: return@LaunchedEffect
        if (!current.state.isFinished) return@LaunchedEffect
        // Seen here, so the "Your copy is saved" notification (posted if the run ended while the screen was in
        // the background) has done its job.
        NotificationManagerCompat.from(context).cancel(Notifications.EXPORT_DONE_ID)
        // A "Share" export finishes in the cache; when it does, hand the file to the share sheet.
        val share = pendingShare ?: return@LaunchedEffect
        if (current.state != WorkInfo.State.SUCCEEDED) {
            pendingShare = null
            return@LaunchedEffect
        }
        if (current.outputData.getString(ExportRequest.KEY_WRITTEN) != share) return@LaunchedEffect
        pendingShare = null
        // Only a run that finished while the screen was visible, or moments before it came back (see
        // visibleSince); otherwise the result card stays and offers *Share this file*.
        if (ExportWorker.finishedAtOf(current.outputData) < visibleSince[0] - SHARE_GRACE_MS) return@LaunchedEffect
        val sharedFormat = ExportWorker.formatOf(current.outputData) ?: return@LaunchedEffect
        message = shareTarget(context, share, sharedFormat)
    }

    /** The user closed the result of [id], or moved on from it; it is not shown again, now or on a later visit. */
    fun dismiss(id: String) {
        dismissedRunId = id
        val app = context.applicationContext as HouseHuntApp
        app.appScope.launch { repo.settings.markResultDismissed(ResultScreen.EXPORT, id) }
    }

    /**
     * Any option changed. The last result describes a file made with other options, so it makes way for the live
     * count, as the web page clears its result on every change; a follow-up problem goes with it. (Options are
     * locked while a copy is being made, so this never hides a running export.)
     */
    fun optionChanged() {
        if (info != null && info.state.isFinished) dismiss(info.id.toString())
        message = null
        focusSaveAfterRecovery = false
    }

    val finished = !awaiting && info != null && info.state.isFinished
    val runId = info?.id?.toString()
    val loadedMarks = marks
    val showResult = finished && message == null && loadedMarks != null && runId != null &&
        runId != dismissedRunId && runId != loadedMarks.exportDismissed && (
            runId == startedRunId || runId == shownRunId ||
                System.currentTimeMillis() - ExportWorker.finishedAtOf(output) < RECENT_RESULT_MS ||
                // However old: a failure, or a result nobody was told about, until it has been seen here once.
                (runId != loadedMarks.exportTold &&
                    (info?.state == WorkInfo.State.FAILED || !ExportWorker.notifiedOf(output)))
            )
    // Seen now: remembered for this visit (so it stays up) and in the DataStore (so it is not brought back later).
    LaunchedEffect(showResult, runId) {
        if (!showResult || runId == null) return@LaunchedEffect
        shownRunId = runId
        if (loadedMarks?.exportTold != runId) repo.settings.markResultTold(ResultScreen.EXPORT, runId)
    }
    // A full backup with narrowing options leaves data out; the note under the format cards names what.
    val gaps = if (format == ExportFormat.BACKUP) {
        BackupCompleteness.gaps(ExportOptions(scope, includeRejected = includeRejected, photos = photos,
            includeContacts = includeContacts))
    } else {
        emptyList()
    }
    // Nothing to save and nothing to report: the options and the bar give way to the empty state.
    val emptyState = databaseEmpty && !running && !showResult && message == null

    val statusKind = when {
        running -> ExportStatus.RUNNING
        message != null -> ExportStatus.MESSAGE
        showResult && info != null -> ExportStatus.RESULT
        counts != null && matching > 0 -> ExportStatus.COUNTS
        counts != null && !databaseEmpty -> ExportStatus.NOTHING_MATCHES
        else -> ExportStatus.NONE
    }

    // After a recovery tap, once the new count has enabled Save (it is disabled while nothing matches, and a
    // disabled button cannot take focus): wait a frame for the bar to settle, then focus it.
    val saveEnabled = matching > 0 && !picking && !running
    LaunchedEffect(focusSaveAfterRecovery, saveEnabled) {
        if (!focusSaveAfterRecovery || !saveEnabled) return@LaunchedEffect
        withFrameNanos { }
        runCatching { saveFocus.requestFocus() }
        // One frame for the focus event to reach TalkBack, then the button is left to the system again (and so is not
        // focusable in touch mode): the flag is not kept until the next option change (UX review, round 18). Whether
        // the count is still heard after the focus moves is README section 8, device check 16.
        withFrameNanos { }
        focusSaveAfterRecovery = false
    }
    // TalkBack is what needs the focus moved; a touch user sees where they are.
    val touchExploration = {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }

    // The labels the bar's two button positions draw now (see "actions" below), so the bar can measure them and stack
    // the buttons when one would not fit side by side (Design review, round 18). Share carries the icon.
    val sharingRun = pendingShare != null
    val firstLabel = if (!running || sharingRun) {
        stringResource(if (running) R.string.export_stop else R.string.export_share)
    } else {
        null
    }
    val secondLabel = if (!running || !sharingRun) {
        stringResource(if (running) R.string.export_stop else R.string.export_save)
    } else {
        null
    }

    val motionIn = expandVertically(tween(ANIMATION_MS)) + fadeIn(tween(ANIMATION_MS))
    val motionOut = shrinkVertically(tween(ANIMATION_MS)) + fadeOut(tween(ANIMATION_MS))

    Scaffold(
        topBar = {
            TopAppBar(
                // One line, ellipsised: the Tamil title is long, and at 200% font it would otherwise be clipped.
                title = { Text(stringResource(R.string.export_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            if (!emptyState) ActionBar(
                // (1) The status area. Each kind reads the screen's current state null-safely: while a kind fades
                // out, the state it was drawn from may already have moved on.
                statusKey = statusKind,
                // A problem or a failed run interrupts TalkBack; everything else is polite (see ActionBar).
                assertive = statusKind == ExportStatus.MESSAGE ||
                    (statusKind == ExportStatus.RESULT && info?.state == WorkInfo.State.FAILED),
                status = { kind ->
                    when (kind) {
                        // (2) The bar carries the numbers for TalkBack, read when it is focused.
                        ExportStatus.RUNNING -> {
                            val (done, total) =
                                if (awaiting) (0 to 0) else ExportWorker.progressOf(info?.progress ?: Data.EMPTY)
                            WorkProgress(done, total, stringResource(R.string.export_working))
                        }
                        ExportStatus.MESSAGE -> message?.let { ResultCard(ResultTone.ERROR, it, onDismiss = { message = null }) }
                        ExportStatus.RESULT -> info?.let { run ->
                            RunResult(
                                info = run,
                                onMessage = { message = it },
                                onDismiss = { dismiss(run.id.toString()) },
                            )
                        }
                        ExportStatus.COUNTS -> counts?.let { CountChips(it) }
                        // Houses exist, but none survives these options: say so rather than make an empty file.
                        ExportStatus.NOTHING_MATCHES -> {
                            StatusLine(stringResource(R.string.export_nothing_matches))
                            // The web page's recovery: "Shortlisted only" with nothing shortlisted is one tap from
                            // a useful copy. So is "All houses" without rejected ones when every house is rejected
                            // (UX review, round 16): the switch that caused it may be scrolled out of view.
                            // One call site for both (Android review, round 17): when *All houses* still leaves
                            // nothing because every house is rejected, the same node becomes *Include rejected
                            // houses* and keeps TalkBack's focus. When the tap makes houses match, this status goes
                            // and focus moves to Save to… (focusSaveAfterRecovery).
                            val recovery: Pair<Int, () -> Unit>? = when {
                                scope == ExportScope.SHORTLISTED -> R.string.export_scope_all to {
                                    scope = ExportScope.ALL
                                }
                                !includeRejected -> R.string.export_include_rejected to { includeRejected = true }
                                else -> null
                            }
                            if (recovery != null) {
                                TextButton(
                                    onClick = {
                                        recovery.second()
                                        optionChanged()
                                        focusSaveAfterRecovery = touchExploration()
                                    },
                                    // The label, not the button's edge, lines up with the line above: the
                                    // TextButton's own 12 dp are pulled back, as for *Use everything* (Design
                                    // review, round 18).
                                    modifier = Modifier.offset(x = (-12).dp).heightIn(min = 48.dp),
                                ) { ButtonLabel(stringResource(recovery.first)) }
                            }
                        }
                        ExportStatus.NONE -> Unit
                    }
                },
                labels = listOfNotNull(firstLabel, secondLabel),
                iconLabel = firstLabel?.takeIf { !running },
                // (3) The actions: two button positions, each one BarButton call site that keeps its node (and
                // TalkBack's focus) while its label and action follow the state (UX review, round 16). The run's
                // Stop takes the place of the button that started it: *Save to…* becomes *Stop* in the second
                // position, *Share* becomes *Stop* in the first; the other position is hidden meanwhile.
                actions = {
                    val enabled = matching > 0 && !picking
                    val stop = {
                        // The worker deletes its own half-written file once its run has really ended; see
                        // ExportWorker.cancel for why the screen does not delete anything itself.
                        // While the new run is not reported yet, `info` is the previous, finished one.
                        ExportWorker.cancel(context, info?.takeIf { !it.state.isFinished })
                        // A run not reported yet is cancelled by name above; the bar stops waiting for it.
                        awaitingRunId = null
                    }
                    // A Share run: pendingShare is set from the tap until the run has ended.
                    val sharing = pendingShare != null
                    if (!running || sharing) {
                        BarButton(
                            text = stringResource(if (running) R.string.export_stop else R.string.export_share),
                            icon = if (running) null else Icons.Default.Share,
                            style = BarButtonStyle.OUTLINED,
                            enabled = running || enabled,
                            onClick = {
                                focusSaveAfterRecovery = false
                                if (running) {
                                    stop()
                                } else {
                                    message = null
                                    // Asks for notifications first, once, when they are off; goes ahead either way.
                                    askNotifications {
                                        val chosen = options()
                                        val target = File(sharedExportDir(context), format.fileName(chosen))
                                        pendingShare = target.absolutePath
                                        startedRunId = ExportWorker
                                            .start(context, ExportRequest(format, target.absolutePath, chosen))
                                            .toString()
                                        awaitingRunId = startedRunId
                                    }
                                }
                            },
                        )
                    }
                    // The filled primary last, as on the Import screen: at the right side by side, and at the
                    // bottom, nearest the thumb, when the bar stacks the buttons at large text (see ActionBar).
                    // While running it is drawn outlined, as Stop.
                    if (!running || !sharing) {
                        BarButton(
                            text = stringResource(if (running) R.string.export_stop else R.string.export_save),
                            style = if (running) BarButtonStyle.OUTLINED else BarButtonStyle.FILLED,
                            enabled = running || enabled,
                            // The target of focusSaveAfterRecovery. A button only takes focus in touch mode (which
                            // TalkBack is) while that is set: canFocus is otherwise left to the system, as for every
                            // other button, so keyboard and D-pad focus is unchanged.
                            modifier = Modifier.focusRequester(saveFocus).then(
                                if (focusSaveAfterRecovery) Modifier.focusProperties { canFocus = true } else Modifier,
                            ),
                            onClick = {
                                focusSaveAfterRecovery = false
                                if (running) {
                                    stop()
                                } else {
                                    message = null
                                    askNotifications {
                                        picking = true
                                        try {
                                            saveTo.launch(
                                                CreateExportDocument.Request(format.mimeType, format.fileName(options())),
                                            )
                                        } catch (_: ActivityNotFoundException) {
                                            // No document picker on the device: nothing will call back, so do not
                                            // stay locked (Share still works without one).
                                            picking = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        // No horizontal padding here: the option rows run edge to edge (their ripple reaches both screen edges),
        // and everything else is inset by [inset].
        Column(
            // The same rhythm as the Import screen, so the intro does not jump between the two sibling screens.
            // No uniform gap (Design review, round 10): 24 dp before a section heading (the divider's 12 dp above
            // and below) and 8 dp after it (the heading's 4 dp and the rows' own 4 dp), so the groups separate.
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            val inset = Modifier.padding(horizontal = 16.dp)
            val divider = inset.padding(vertical = 12.dp)
            val heading = inset.padding(bottom = 4.dp)
            if (emptyState) {
                // No house at all (the web page's `.empty-state`): one sentence and the way to the map, where
                // houses are added. No options and no disabled buttons: there is nothing for them to act on.
                HeroEmptyState(
                    icon = Icons.Default.Home,
                    title = stringResource(R.string.export_empty),
                    action = {
                        // An action, not the bare noun "Map": it says what the tap is for.
                        Button(onClick = onOpenMap, modifier = Modifier.heightIn(min = 48.dp)) {
                            ButtonLabel(stringResource(R.string.common_add_on_map))
                        }
                    },
                )
            } else {
                Text(stringResource(R.string.export_intro), style = MaterialTheme.typography.bodyMedium, modifier = inset)

                // The options apply to the next copy; while one is being made they are locked, so they cannot look
                // as if they changed the running one.
                val editable = !running

                HorizontalDivider(divider)
                SectionHeading(stringResource(R.string.export_format), heading)
                // One column on a phone, two from 600 dp (see RadioCardGroup).
                // 4 dp on top: the cards have no row padding of their own, and heading to content is 8 dp.
                RadioCardGroup(
                    formatChoices,
                    Modifier.selectableGroup().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                ) { choice, card ->
                    RadioCard(
                        label = AnnotatedString(stringResource(choice.label)),
                        hint = stringResource(choice.hint),
                        selected = format == choice.format,
                        enabled = editable,
                        modifier = card,
                        onSelect = {
                            format = choice.format
                            optionChanged()
                        },
                    )
                }
                // Directly under the cards, where "Full backup" was just chosen: what this backup will not hold.
                val partialNote = remember { arrayOfNulls<String>(1) }
                if (gaps.isNotEmpty()) partialNote[0] = partialBackupText(context, gaps)
                AnimatedVisibility(visible = gaps.isNotEmpty(), enter = motionIn, exit = motionOut) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                        partialNote[0]?.let { WarnNote(it) }
                        // Starts 12 dp to the left so the label, not the button's edge, lines up with the note.
                        TextButton(
                            onClick = {
                                scope = ExportScope.ALL
                                includeRejected = true
                                photos = PhotoScope.ALL
                                includeContacts = true
                                optionChanged()
                            },
                            enabled = editable,
                            modifier = Modifier.offset(x = (-12).dp).heightIn(min = 48.dp),
                        ) { ButtonLabel(stringResource(R.string.export_use_everything)) }
                    }
                }

                HorizontalDivider(divider)
                SectionHeading(stringResource(R.string.export_scope), heading)
                // docs/11 section 5.2 lists a third scope, "selected houses". ExportScope.SELECTED is implemented
                // and tested in :shared, but picking individual houses needs a checkable list (the Compare screen's
                // multi-select is the pattern) and is deferred to Sprint 4b — see android/shared/README.md section 8.
                Column(Modifier.selectableGroup()) {
                    RadioRow(
                        AnnotatedString(stringResource(R.string.export_scope_all)), null, scope == ExportScope.ALL, editable,
                    ) {
                        scope = ExportScope.ALL
                        optionChanged()
                    }
                    RadioRow(
                        AnnotatedString(stringResource(R.string.export_scope_shortlisted)), null,
                        scope == ExportScope.SHORTLISTED, editable,
                    ) {
                        scope = ExportScope.SHORTLISTED
                        optionChanged()
                    }
                }
                // Only where it changes something; the stored choice is kept while it is hidden.
                AnimatedVisibility(visible = scope == ExportScope.ALL, enter = motionIn, exit = motionOut) {
                    SwitchRow(
                        text = stringResource(R.string.export_include_rejected),
                        hint = null,
                        checked = includeRejected,
                        enabled = editable,
                        onChange = {
                            includeRejected = it
                            optionChanged()
                        },
                    )
                }

                // Only for the formats that can carry photos; the stored choice is kept while it is hidden.
                AnimatedVisibility(visible = format.usesPhotos, enter = motionIn, exit = motionOut) {
                    Column {
                        HorizontalDivider(divider)
                        SectionHeading(stringResource(R.string.export_photos), heading)
                        Column(Modifier.selectableGroup()) {
                            RadioRow(
                                AnnotatedString(stringResource(R.string.export_photos_all)), null,
                                photos == PhotoScope.ALL, editable,
                            ) {
                                photos = PhotoScope.ALL
                                optionChanged()
                            }
                            RadioRow(
                                AnnotatedString(stringResource(R.string.export_photos_shortlisted)), null,
                                photos == PhotoScope.SHORTLISTED, editable,
                            ) {
                                photos = PhotoScope.SHORTLISTED
                                optionChanged()
                            }
                            RadioRow(
                                AnnotatedString(stringResource(R.string.export_photos_none)), null,
                                photos == PhotoScope.NONE, editable,
                            ) {
                                photos = PhotoScope.NONE
                                optionChanged()
                            }
                        }
                    }
                }

                HorizontalDivider(divider)
                // Both states say what will happen, as the web page does. While contacts are included that is a
                // privacy caution, drawn as the web's amber `.warn-box` (WarnNote) rather than as one more grey
                // hint, and drawn inside the row so TalkBack reads it with the switch (UX review, round 11); left
                // out, it is a plain hint on the row.
                SwitchRow(
                    text = stringResource(R.string.export_contacts),
                    hint = if (includeContacts) null else stringResource(R.string.export_contacts_left_out),
                    checked = includeContacts,
                    enabled = editable,
                    warning = if (includeContacts) stringResource(R.string.export_contacts_hint) else null,
                    onChange = {
                        includeContacts = it
                        optionChanged()
                    },
                )

                HorizontalDivider(divider)
                SectionHeading(stringResource(R.string.export_language), heading)
                Column(Modifier.selectableGroup()) {
                    ExportLanguages.NATIVE_NAMES.forEach { (code, name) ->
                        // Tagged with its own locale, so TalkBack reads "தமிழ்" with a Tamil voice (WCAG 3.1.2), not
                        // with whatever voice the interface language uses.
                        val label = buildAnnotatedString {
                            withStyle(SpanStyle(localeList = LocaleList(code))) { append(name) }
                        }
                        RadioRow(label, null, language == code, editable) {
                            language = code
                            optionChanged()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The live count as three chips with tabular figures, as on the web page. TalkBack hears it as the one sentence
 * "12 houses, 30 visits, 80 photos" rather than three separate items. Unlike the other statuses it keeps a polite
 * live region of its own: when an option changes, the sentence changes on the *same* node, which the bar's
 * container region may not re-read (README §8, device check 1).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountChips(c: ExportCounts) {
    val houses = pluralStringResource(R.plurals.count_houses, c.houses, c.houses)
    val visits = pluralStringResource(R.plurals.count_visits, c.visits, c.visits)
    val photos = pluralStringResource(R.plurals.count_photos, c.photos, c.photos)
    val sentence = stringResource(R.string.export_counts, houses, visits, photos)
    FlowRow(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = sentence
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(houses, visits, photos).forEach { text ->
            // A visible container on any surface: `--surface-2` fill with a 1 dp `--border` line, as the web's
            // `.stats li` (Design review, 2026-09-22).
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The finished run as a result card, with Open and *Share this file* after a success (docs/11 section 5.2), and a
 * close button that brings the live count back.
 */
@Composable
private fun RunResult(info: WorkInfo, onMessage: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val output = info.outputData
    when (info.state) {
        WorkInfo.State.SUCCEEDED -> {
            val target = output.getString(ExportRequest.KEY_WRITTEN)
            val format = ExportWorker.formatOf(output)
            val name = ExportWorker.nameOf(output)
            val shareCopy = target != null && ResultActions.isShareCopy(target)
            // The notification's own sentence: where it went when the provider says, and "partial" for a backup
            // that leaves something out (UX review, round 11).
            val text = ExportWorker.resultText(
                context, target, name,
                location = output.getString(ExportRequest.KEY_LOCATION),
                partial = output.getBoolean(ExportRequest.KEY_PARTIAL, false),
            )
            ResultCard(ResultTone.SUCCESS, text, onDismiss = onDismiss) {
                if (target != null && format != null) {
                    // Wraps for Tamil and Telugu at 200% font, and lines up with the message text.
                    ResultActionsRow {
                        if (!shareCopy) {
                            TextButton(
                                onClick = { onMessage(openTarget(context, target, format)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { ButtonLabel(stringResource(R.string.export_open)) }
                        }
                        // Not plain "Share": the bar's Share makes a *new* copy; this one shares the file just made.
                        TextButton(
                            onClick = { onMessage(shareTarget(context, target, format)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { ButtonLabel(stringResource(R.string.export_share_file)) }
                    }
                }
            }
        }
        // A translated reason from a stable code, never the exception text (see ExportProblem).
        WorkInfo.State.FAILED -> ResultCard(
            ResultTone.ERROR,
            stringResource(R.string.export_failed, stringResource(ExportWorker.problemOf(output).messageRes())),
            onDismiss = onDismiss,
        )
        // Silence here would be the worst outcome: the progress bar vanishes and the user is left guessing
        // whether the half-finished file in their folder is usable. It is not, and the worker has deleted it.
        WorkInfo.State.CANCELLED -> ResultCard(
            ResultTone.NEUTRAL,
            stringResource(R.string.export_stopped),
            onDismiss = onDismiss,
        )
        else -> Unit
    }
}

/**
 * The share sheet for a finished copy: the `content://` document the user saved, or the cache file behind the
 * app's `FileProvider` (`file_paths.xml`), so the receiving app gets a one-off read grant and no storage
 * permission is involved. Returns a message to show when nothing can take it, else null.
 */
private fun shareTarget(context: Context, target: String, format: ExportFormat): String? {
    val uri = ResultActions.readableUri(context, target) ?: return context.getString(R.string.export_share_failed)
    return try {
        context.startActivity(ResultActions.share(context, uri, format))
        null
    } catch (_: ActivityNotFoundException) {
        context.getString(R.string.export_share_failed)
    } catch (_: SecurityException) {
        context.getString(R.string.export_share_failed)
    }
}

/** Opens a saved copy in the app that handles its format; a message when there is none. */
private fun openTarget(context: Context, target: String, format: ExportFormat): String? {
    val uri = ResultActions.readableUri(context, target) ?: return context.getString(R.string.export_open_failed)
    return try {
        context.startActivity(ResultActions.view(uri, format))
        null
    } catch (_: ActivityNotFoundException) {
        context.getString(R.string.export_open_failed)
    } catch (_: SecurityException) {
        context.getString(R.string.export_open_failed)
    }
}

/**
 * "This backup leaves out houses that are not shortlisted, rejected houses and contact details. Restoring from it
 * will not bring those back." — every [BackupGap] named, in the language's own list pattern.
 */
private fun partialBackupText(context: Context, gaps: List<BackupGap>): String {
    val items = gaps.map { gap ->
        context.getString(
            when (gap) {
                BackupGap.HOUSES_NOT_SHORTLISTED -> R.string.export_gap_not_shortlisted
                BackupGap.HOUSES_NOT_SELECTED -> R.string.export_gap_not_selected
                BackupGap.REJECTED_HOUSES -> R.string.export_gap_rejected
                BackupGap.PHOTOS_NOT_SHORTLISTED -> R.string.export_gap_photos_not_shortlisted
                BackupGap.PHOTOS -> R.string.export_gap_photos
                BackupGap.CONTACTS -> R.string.export_gap_contacts
            }
        )
    }
    return context.getString(R.string.export_partial_note, ImportWorker.joined(context, items))
}

internal fun sharedExportDir(context: Context): File =
    File(context.cacheDir, "exports").apply { mkdirs() }

/** Shared copies are deleted after 24 hours, so an old export is not left readable in the cache. */
private fun cleanSharedExports(context: Context) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    sharedExportDir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
}
