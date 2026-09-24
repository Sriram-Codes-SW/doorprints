package com.househunt.app.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunt.app.HouseHuntApp
import com.househunt.app.data.AppSettings
import com.househunt.app.data.HouseEntity
import com.househunt.app.data.HouseVisitCount
import com.househunt.app.data.SyncHealth
import com.househunt.app.data.glyph
import com.househunt.app.data.labelRes
import com.househunt.app.export.CopyImportUndo
import com.househunt.app.export.CopyRecord
import com.househunt.app.export.ImportUndo
import com.househunt.app.ui.res.*
import com.househunt.shared.model.HouseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class Sort(val label: StringResource) {
    RECENT(Res.string.sort_recent), SCORE(Res.string.sort_score), PRICE(Res.string.sort_price)
}

/** Saves a nullable status filter by its enum name ("" for all), so it survives rotation and process death. */
private val StatusFilterSaver = Saver<HouseStatus?, String>(
    save = { it?.name ?: "" },
    restore = { name -> HouseStatus.entries.firstOrNull { it.name == name } },
)

/** The sort order by its enum name, for the same reason. */
private val SortSaver = Saver<Sort, String>(
    save = { it.name },
    restore = { name -> Sort.entries.firstOrNull { it.name == name } ?: Sort.RECENT },
)

/** How long the results count waits for typing to settle before TalkBack reads it. */
private const val COUNT_ANNOUNCE_DELAY_MS = 500L

/**
 * The saved houses.
 *
 * **State (UX review, round 11).** The search text, the sort and the status filter are `rememberSaveable` (the
 * enums by name), so a rotation, a language switch or the process being killed does not reset them. When the last
 * house goes, the search and the filter are cleared (round 15), so the first house added afterwards is not hidden
 * behind an old search or a "Rejected" filter.
 *
 * **First run.** With no house at all, the search field, the status chips and the sort control mean nothing and
 * pushed the one thing that matters on a new phone below the fold. They are hidden, and the list is the designed
 * empty state instead (docs/05 section 5: icon, message, primary action): the tagline, how to add a house, then a
 * centred stack of a filled **Add a house on the map** (`common_add_on_map`, the same primary as Export's empty
 * state and the web list's filled *Add*; the map then says how to add one) above an outlined **Import a backup**
 * with the restore glyph, the rarer path (design review, round 14). Both buttons share the wider one's width
 * (round 15). The import label is `import_title`, not a "restore" wording (docs/12 G.3 rule 3).
 *
 * **Before Room answers** only the heading is drawn: neither the hero nor the search/chips/sort chrome, so a new
 * install does not flash the list chrome for a frame and then collapse into the hero (round 14).
 *
 * **One scrolling list (round 15).** The heading, the search field, the status chips, the sort control and the
 * results count are items of the same `LazyColumn` as the houses, so in landscape, at 200 % font scale or with the
 * Indic chips on three rows they scroll away with the list instead of leaving it less than one card of height
 * (docs/05, 320 dp at 200 %; A11Y-A03). The list state is saveable, so the scroll position survives a visit to a house.
 *
 * **Just imported (UX review, rounds 16, 18 and 19).** After a copy import, *See your houses* passes its run
 * ([importedRun], with [importedOpen] counting the taps) and the list opens filtered to the copies it added, behind a
 * selected "Just imported (n)" chip: a copy keeps its original's name and times, so without it the copies could not be
 * told apart from the originals they sort next to. The chip is an applied filter on top of the statuses, not a fifth
 * status: an `InputChip` with the restore glyph in a row of its own above the status chips, outside their
 * single-choice `selectableGroup()`, with a close glyph while it is applied (M3's cue for a removable filter).
 * The ids come from the import's undo record (`ImportUndo`), so the chip goes with the record (a day, or an undo that
 * removed every copy; after one that kept houses edited since, the chip shows those).
 *
 * Which import (round 19): the list shows the run it was opened for, unless a newer copy import can still be undone,
 * and otherwise the newest one that can; Root also clears [importedRun] whenever the Import screen is opened. So after
 * a second copy import left with Back, the row and its undo are about the second one, never the first. The filter
 * turns itself on only the first time the list is shown for a *See your houses* tap, not on every return to the tab
 * (the Houses tab restores its saved state), and turns itself off when a house that is not one of the copies is added,
 * so a house just saved on the map is never hidden behind it.
 *
 * **No jump (round 19).** The record is read from disk. Opened for an import, the list draws only its heading until
 * the record is there, as before Room answers, so it never shows all 50 houses for a frame and then drops to the 40
 * copies. Opened from the tab, the list draws at once and the chip and the undo row ease in: the chrome items use
 * `animateItem()`, which follows the system animator scale (Remove animations makes it instant).
 *
 * **Undo from the list (UX review, rounds 18 to 20).** The user decides to undo while looking at the copies, and *See
 * your houses* has popped the Import screen, so the undo is here too, in the same [ResultCard] as the Export and Import
 * results, right under the "Just imported" chip: NEUTRAL "Imported from your backup at <time>: 40 copies." with "You
 * can undo this until …" and *Undo this import*; SUCCESS once the undo has run; ERROR if it failed. A trailing close
 * button hides the row for that run (saved in the record, [CopyRecord.rowHidden]) for a user who wants the copies; the
 * chip stays, and the Import screen still offers the undo. A failed undo's card has the close button too (round 20);
 * that hides it while that failure is the news and writes nothing to the record, so the chip, the Import screen's undo
 * and the row in a new list all stay. Which outcome was already news and which failure was closed are saved by the
 * outcome's key (run id + finish time, round 21), so a rotation neither drops "Removed 40 copies." nor brings a closed
 * red card back. Closing the row moves TalkBack's focus to the "Just imported" chip, or to the first status chip when
 * that has gone (round 21), instead of the top of the screen. The undo removes the copies from this phone, the server and every other device, and
 * nothing reverses it, so from this lasting row it asks first (UX-005): "Remove the 40 copies imported at <time>?" with
 * *Remove copies* (error colour) and *Keep them*.
 *
 * Focus after the dialog (round 20, the Export screen's `focusSaveAfterRecovery` pattern): with TalkBack on, closing
 * the dialog sets a flag held by this screen, not by the dialog. While it is set the row's button has `focusProperties
 * { canFocus = true }`, because a button is not focusable in touch mode (which TalkBack is) otherwise, and an effect
 * keyed on the flag and on the button being enabled focuses it a frame later, then clears the flag a frame after that.
 * After *Keep them* (or Back) that is *Undo this import* at once. After *Remove copies* the button is disabled while
 * the undo runs, and a disabled button has no focus target, so focus waits and lands on *Close* once the result is
 * there (or on *Undo this import* again if the undo failed). README section 8, device check 19, confirms both paths
 * on a phone. The Import screen's undo, straight after the import, stays one tap. The card sits in a polite live
 * region (assertive for a failure) that is there before it changes, so TalkBack hears "Removing the copies…" and then
 * "Removed 40 copies. Kept 2 houses you had edited since.". After an undo the "Just imported" filter goes off, and the
 * button becomes *Close* in the same place, so TalkBack's focus stays in the row.
 *
 * **Results count (round 16).** "Houses shown: n of m" is always drawn, like the web's `role="status"`, which is
 * always in the DOM, so its node exists before a filter is applied: a node that arrives already marked as a live
 * region is never announced. While a search or filter is on, its live-region text starts empty and is set 500 ms
 * after the last change, so the first filter always produces a change that TalkBack reads, even when the count
 * equals the unfiltered one.
 *
 * **No match.** With houses but nothing matching the search or filter, the list shows the same hero with a search
 * glyph, the web's `map.noMatch` sentence and **Clear search and filter** (`houses_clear_filters`, the web's
 * `map.clearFilters`), which resets both and puts focus back in the search field.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HouseListScreen(
    onOpenHouse: (String) -> Unit,
    onOpenImport: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    importedRun: String? = null,
    importedOpen: Int = 0,
    onOpenSettings: () -> Unit = {},
    deletedHouse: String? = null,
    onDeletedShown: () -> Unit = {},
) {
    val repo = repository()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // A house deleted from its form: "Deleted Green Villa" with Undo (whole-app audit).
    LaunchedEffect(deletedHouse) {
        val id = deletedHouse ?: return@LaunchedEffect
        onDeletedShown()
        scope.launch { offerDeletedHouseUndo(context, repo, snackbar, id) }
    }
    // Background sync that has been failing: said here, not only in Settings (see SyncHealth).
    val appSettings: AppSettings? by repo.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val syncSince = appSettings?.let {
        SyncHealth.warningSince(it.serverConfigured, it.lastSync?.kind, it.syncFailures, it.syncFailingSince, it.lastSyncOkAt, now)
    }
    val syncWarning: (@Composable () -> Unit)? = if (syncSince != null) {
        {
            val reason = appSettings?.lastSync?.text().orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WarnNote(stringResource(Res.string.sync_warning, syncSince.dateText(), reason))
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.houses_open_settings))
                }
            }
        }
    } else {
        null
    }
    // null until Room's first emission: until then neither the first-run hero nor the list chrome is drawn, so
    // neither flashes for a frame on launch.
    val loadedHouses: List<HouseEntity>? by repo.houses.collectAsStateWithLifecycle(initialValue = null)
    val loaded = loadedHouses != null
    val houses = loadedHouses.orEmpty()
    val counts by repo.visitCounts.collectAsStateWithLifecycle(emptyList())
    val visitsByHouse = counts.associateBy { it.houseId }
    var filter by rememberSaveable(stateSaver = StatusFilterSaver) { mutableStateOf<HouseStatus?>(null) }
    var sort by rememberSaveable(stateSaver = SortSaver) { mutableStateOf(Sort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    val firstRun = loaded && houses.isEmpty()
    // The copy import the list shows (see the KDoc): the one it was opened for, unless a newer one can still be
    // undone, else the newest that can. Read again whenever an undo ends, because the undo deletes or reduces the
    // record.
    val undoOutcome = CopyImportUndo.outcome
    val undoingRun = CopyImportUndo.undoingRun
    val loadedImport by produceState<ShownImport?>(initialValue = null, importedRun, undoOutcome) {
        val requested = importedRun
        // After an undo of the run on screen, stay on it (its record is gone or reduced), so the row can say what
        // the undo did.
        val onScreen = value?.takeIf { it.requested == requested }?.runId
        val stay = undoOutcome?.runId?.takeIf { it == onScreen }
        value = withContext(Dispatchers.IO) {
            if (stay != null) {
                ShownImport(requested, stay, ImportUndo.load(context, stay))
            } else {
                val asked = requested?.let { ImportUndo.load(context, it) }
                val latest = ImportUndo.latestUndoable(context)
                val newer = latest != null && asked != null && latest.finishedAt > asked.finishedAt
                val pick = if (asked == null || newer) latest else asked
                ShownImport(requested, pick?.runId, pick)
            }
        }
    }
    // null while the record is being read for this importedRun ("loading").
    val shownImport = loadedImport?.takeIf { it.requested == importedRun }
    val importLoading = shownImport == null
    val shownRun = shownImport?.runId
    val record = shownImport?.record
    // The copies of that import, from its record; null when there is none.
    val importedIds = record?.houses?.keys
    val importedCount = importedIds?.let { ids -> houses.count { it.id in ids } }?.takeIf { it > 0 }

    // The "Just imported" filter (round 19): on for the run in filterRun only, so a newer import never inherits it.
    var importedOnly by rememberSaveable { mutableStateOf(false) }
    var filterRun by rememberSaveable { mutableStateOf<String?>(null) }
    // The last "See your houses" tap this list has acted on: the filter turns itself on once per tap, not on every
    // return to the tab.
    var consumedOpen by rememberSaveable { mutableIntStateOf(0) }
    val firstOpen = importedRun != null && importedOpen != consumedOpen && shownRun == importedRun
    LaunchedEffect(importedOpen, importLoading) {
        if (importLoading || importedRun == null || importedOpen == consumedOpen) return@LaunchedEffect
        consumedOpen = importedOpen
        if (shownRun == importedRun) {
            importedOnly = true
            filterRun = importedRun
        }
    }
    // firstOpen covers the frame before the effect above has run, so the list never shows every house first.
    val onlyImported = (firstOpen || (importedOnly && filterRun == shownRun)) && importedCount != null
    // A house that is not one of the copies was added (on the map, while the filter was on): show everything again,
    // or the new house would seem to be missing. Saveable, so a return to the tab compares with the last visit.
    val otherCount = if (loaded && importedIds != null) houses.count { it.id !in importedIds } else null
    var seenRun by rememberSaveable { mutableStateOf<String?>(null) }
    var seenOtherCount by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(shownRun, otherCount) {
        if (shownRun == null || otherCount == null) return@LaunchedEffect
        val before = seenOtherCount.takeIf { seenRun == shownRun }
        if (before != null && otherCount > before) importedOnly = false
        seenRun = shownRun
        seenOtherCount = otherCount
    }

    // The undo row (see the KDoc). An outcome that was already there when the list was opened is not news. Saved by
    // its key (run id + finish time; round 21), not held as an object, so a rotation right after an undo does not turn
    // "Removed 40 copies." into old news before TalkBack has read it. "" = there was none.
    val outcomeBefore = rememberSaveable { undoOutcome?.key.orEmpty() }
    val rowOutcome = undoOutcome?.takeIf { shownRun != null && it.runId == shownRun && it.key != outcomeBefore }
    val undoingThis = undoingRun != null && undoingRun == shownRun
    val undoable = record?.takeIf { !it.undone }
    // Closed for this run: by its close button before an undo (also saved in the record), or by Close after one.
    var hiddenRun by rememberSaveable { mutableStateOf<String?>(null) }
    // A failed undo's card closed with its close button (round 20): hidden for as long as that failure is the news.
    // Saved by the failure's key (round 21), so a rotation does not bring the red card back; a new list (the next
    // launch, or the list opened afresh from Import) offers the undo again, as the failure is then outcomeBefore.
    var hiddenFailure by rememberSaveable { mutableStateOf<String?>(null) }
    val rowHidden = shownRun != null && (
        hiddenRun == shownRun ||
            (rowOutcome != null && rowOutcome.key == hiddenFailure && !undoingThis) ||
            (record?.rowHidden == true && rowOutcome == null && !undoingThis)
        )
    val showUndoRow = !rowHidden && (rowOutcome != null || undoingThis || (undoable != null && importedCount != null))
    // After an undo the copies (or all but the kept ones) are gone: the list shows every house again.
    LaunchedEffect(rowOutcome) {
        if (rowOutcome != null && !rowOutcome.failed) importedOnly = false
    }
    val app = context.applicationContext as HouseHuntApp
    // The confirmation (UX-005, round 19), and where focus goes back to when it closes (round 20; the Export screen's
    // focusSaveAfterRecovery pattern). Set when the dialog is closed with either button (or Back, or a tap outside),
    // and only while TalkBack's touch exploration is on: a touch user sees where they are. Remembered here, not in the
    // dialog, so it outlives the dialog and the undo that *Remove copies* starts.
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    val undoButtonFocus = remember { FocusRequester() }
    var focusUndoButton by remember { mutableStateOf(false) }
    val touchExploration = {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    // The row's one button is enabled as *Close* after an undo, or as *Undo this import* while one can be started
    // (again, after a failure). It is disabled while the undo runs, and a disabled button has no focus target at all,
    // so after *Remove copies* the focus waits for the result: *Close*, or *Undo this import* if the undo failed.
    val undoDone = rowOutcome != null && !rowOutcome.failed && !undoingThis
    val undoButtonEnabled = showUndoRow && (undoDone || (undoable != null && undoingRun == null && !undoingThis))
    LaunchedEffect(focusUndoButton, undoButtonEnabled) {
        if (!focusUndoButton || !undoButtonEnabled) return@LaunchedEffect
        // One frame for the dialog's window to go and the button's canFocus = true to apply, then focus it.
        withFrameNanos { }
        runCatching { undoButtonFocus.requestFocus() }
        // One frame for the focus event to reach TalkBack, then the button is left to the system again (so it is not
        // focusable in touch mode). README section 8, device check 19.
        withFrameNanos { }
        focusUndoButton = false
    }
    // Where focus goes when the row is closed (round 21), the same canFocus + two-frame pattern as the button above.
    val importedChipFocus = remember { FocusRequester() }
    val allChipFocus = remember { FocusRequester() }
    var focusChips by remember { mutableStateOf(false) }
    val chipTarget = if (importedCount != null) importedChipFocus else allChipFocus
    LaunchedEffect(focusChips) {
        if (!focusChips) return@LaunchedEffect
        withFrameNanos { }
        runCatching { chipTarget.requestFocus() }
        withFrameNanos { }
        focusChips = false
    }
    val undoRow: (@Composable (Modifier) -> Unit)? = if (showUndoRow) {
        { modifier ->
            ImportUndoRow(
                count = importedCount ?: 0,
                importedAt = record?.finishedAt,
                until = undoable?.let { it.finishedAt + ImportUndo.KEEP_MS },
                undoing = undoingThis,
                outcome = rowOutcome,
                canUndo = undoable != null && undoingRun == null,
                onUndo = {
                    focusUndoButton = false
                    confirmUndo = true
                },
                onHide = {
                    focusUndoButton = false
                    // The row and the focused button go (round 21): with TalkBack on, focus moves up to the "Just
                    // imported" chip, or the first status chip when that has gone too, not to the top of the screen.
                    focusChips = touchExploration()
                    shownRun?.let { run ->
                        when {
                            // After a failed undo (round 20): hidden while this failure is the news. Nothing is written
                            // to the record, so the chip, the Import screen's undo and the row in a new list (where the
                            // failure is no longer news) all stay, and the user can still try again.
                            rowOutcome?.failed == true -> {
                                hiddenFailure = rowOutcome?.key
                            }
                            // Before an undo the choice is kept with the record; after one there is nothing to offer.
                            rowOutcome == null -> {
                                hiddenRun = run
                                app.appScope.launch(Dispatchers.IO) { ImportUndo.hideRow(app, run) }
                            }
                            else -> {
                                hiddenRun = run
                            }
                        }
                    }
                },
                focusRequester = undoButtonFocus,
                takeFocus = focusUndoButton,
                modifier = modifier,
            )
        }
    } else {
        null
    }
    val confirmable = undoable?.takeIf { importedCount != null && undoingRun == null }
    if (confirmUndo && confirmable != null) {
        ConfirmUndoDialog(
            count = importedCount ?: 0,
            importedAt = confirmable.finishedAt,
            onRemove = {
                confirmUndo = false
                // Starts the undo at once (undoingRun is set before this returns), so the row's button is disabled
                // in the same frame and the focus waits for Close (or, after a failure, Undo this import).
                focusUndoButton = touchExploration()
                CopyImportUndo.start(app, confirmable)
            },
            // Keep them, Back or a tap outside: back to Undo this import.
            onKeep = {
                confirmUndo = false
                focusUndoButton = touchExploration()
            },
        )
    } else if (confirmUndo && !importLoading) {
        // The record expired, or another undo started, while the dialog was up: nothing is left to confirm. (While the
        // record is still being read, after a rotation or process death, the dialog just waits for it.)
        LaunchedEffect(Unit) { confirmUndo = false }
    }

    // The first-run hero hides the search and the chips, so a saved search or filter must not outlive the last
    // house: the next house added would come back as "No houses match your search or filter."
    LaunchedEffect(firstRun) {
        if (firstRun) {
            filter = null
            query = ""
            importedOnly = false
        }
    }

    val shown = houses
        .filter { !onlyImported || importedIds?.contains(it.id) == true }
        .filter { filter == null || it.status == filter }
        .filter {
            query.isBlank() || listOfNotNull(it.label, it.street, it.address, it.locality, it.notes)
                .any { f -> f.contains(query, ignoreCase = true) }
        }
        .let { list ->
            when (sort) {
                Sort.RECENT -> list
                Sort.SCORE -> list.sortedByDescending { it.score ?: -1.0 }
                // Rents first, then sale prices, each from low to high (whole-app audit).
                Sort.PRICE -> sortByPrice(list)
            }
        }

    Box(Modifier.fillMaxSize()) {
        when {
            // Room has not answered yet, or the list was opened for an import whose record is still being read: the
            // heading alone, usually for less than a frame, so the whole list is not drawn before the filter applies.
            !loaded || (importedRun != null && importLoading) ->
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { HousesHeading() }
            // Nothing saved yet: introduce the app, say how to add a house, and offer the backup a new phone needs.
            // No search, chips or sort: there is nothing for them to act on. The hero's own gutter is 0 dp because
            // this Column is already padded 16 dp, the same margin Export and Import give it.
            firstRun -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                HousesHeading()
                syncWarning?.invoke()
                HeroEmptyState(
                    icon = Icons.Default.Home,
                    title = stringResource(Res.string.app_tagline),
                    body = stringResource(Res.string.houses_empty),
                    horizontalPadding = 0.dp,
                    action = {
                        // IntrinsicSize.Max + fillMaxWidth: both buttons take the wider label's width (capped by the
                        // screen), so the stack is one clean column in every language rather than two ragged widths.
                        Column(
                            Modifier.width(IntrinsicSize.Max),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = onOpenMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                ButtonLabel(stringResource(Res.string.common_add_on_map))
                            }
                            OutlinedButton(
                                onClick = onOpenImport,
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Icon(RestoreIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                ButtonLabel(stringResource(Res.string.import_title))
                            }
                        }
                    },
                )
            }
            else -> HouseList(
                houses, shown, visitsByHouse, filter, sort, query,
                importedCount = importedCount, importedOnly = onlyImported, undoRow = undoRow,
                importedChipFocus = importedChipFocus, allChipFocus = allChipFocus, chipFocusable = focusChips,
                onFilter = { filter = it }, onSort = { sort = it }, onQuery = { query = it },
                onImportedOnly = {
                    importedOnly = it
                    filterRun = shownRun
                },
                onOpenHouse = onOpenHouse,
                syncWarning = syncWarning,
            )
        }
        // Over the list's foot: the deleted-house Undo.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun HousesHeading() {
    Text(stringResource(Res.string.houses_title), style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 16.dp).semantics { heading() })
}

/**
 * The heading, the search field, the status chips, the sort control, the results count and then the houses (or
 * the no-match hero), all as items of one `LazyColumn`, once there is at least one house.
 *
 * Every item below the search field uses `animateItem()` (round 19): the "Just imported" chip and the undo card fade
 * in and out, and what is below them slides, instead of the status chips jumping under the user's finger. Compose
 * scales these animations by the system animator duration scale, so *Remove animations* makes them instant.
 *
 * Chips (Design review, round 19) look like the web's `.chip`: a selected one has a ✓ ([ChipCheck]), a 2 dp primary
 * border and the `--primary-soft` fill; an unselected one a 1 dp `outline` (`--border-strong`) edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HouseList(
    houses: List<HouseEntity>,
    shown: List<HouseEntity>,
    visitsByHouse: Map<String, HouseVisitCount>,
    filter: HouseStatus?,
    sort: Sort,
    query: String,
    importedCount: Int?,
    importedOnly: Boolean,
    undoRow: (@Composable (Modifier) -> Unit)?,
    importedChipFocus: FocusRequester,
    allChipFocus: FocusRequester,
    chipFocusable: Boolean,
    onFilter: (HouseStatus?) -> Unit,
    onSort: (Sort) -> Unit,
    onQuery: (String) -> Unit,
    onImportedOnly: (Boolean) -> Unit,
    onOpenHouse: (String) -> Unit,
    syncWarning: (@Composable () -> Unit)? = null,
) {
    // rememberLazyListState is saveable: the scroll position survives opening a house and coming back.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val filtering = query.isNotBlank() || filter != null || importedOnly

    // The results count (docs/05 4.1.3, the web's role="status" "Houses shown: x of y"), always drawn (see the
    // screen's KDoc). The line on screen follows every keystroke; what TalkBack hears waits until typing has paused
    // for COUNT_ANNOUNCE_DELAY_MS, so each letter is not read out (LaunchedEffect restarts on every change, which
    // makes the delay a debounce). Reset to empty whenever no filter is on, so the first filter always changes it.
    val shownText = stringResource(Res.string.houses_shown, shown.size, houses.size)
    var announcedText by remember { mutableStateOf("") }
    LaunchedEffect(shownText, filtering) {
        if (!filtering) {
            announcedText = ""
            return@LaunchedEffect
        }
        delay(COUNT_ANNOUNCE_DELAY_MS)
        announcedText = shownText
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        item(key = "heading") { HousesHeading() }
        // Sync that has not worked for a while (SyncHealth): at the top, with the way to Settings.
        if (syncWarning != null) item(key = "sync") { Box(Modifier.animateItem()) { syncWarning() } }
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                label = { Text(stringResource(Res.string.houses_search)) },
                singleLine = true,
                // The Search key closes the keyboard, so it no longer covers the results or the no-match hero.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = if (query.isEmpty()) null else {
                    {
                        // Focus stays in the field when its clear button disappears from under TalkBack.
                        IconButton(onClick = { onQuery(""); runCatching { searchFocus.requestFocus() } }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.houses_clear_search))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            )
        }
        // The copies of the last copy import (rounds 16 and 18): a filter of its own, applied on top of the status
        // chips, so it is in its own row, 8 dp above them, and outside their single-choice group. While applied it
        // has a close glyph, M3's cue for a filter that can be removed (round 19).
        if (importedCount != null) {
            item(key = "imported") {
                Row(Modifier.animateItem().fillMaxWidth()) {
                    InputChip(
                        selected = importedOnly,
                        onClick = { onImportedOnly(!importedOnly) },
                        label = {
                            val label = stringResource(Res.string.houses_just_imported)
                            Text(stringResource(Res.string.status_filter, label, importedCount))
                        },
                        leadingIcon = {
                            Icon(RestoreIcon, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
                        },
                        trailingIcon = if (importedOnly) {
                            {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(InputChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                        border = brandInputChipBorder(importedOnly),
                        modifier = Modifier.focusRequester(importedChipFocus).then(
                            if (chipFocusable) Modifier.focusProperties { canFocus = true } else Modifier,
                        ),
                    )
                }
            }
        }
        // Right under the chip it belongs to: the same undo as the Import screen's (rounds 18 and 19). Chip, 8 dp,
        // card, then 16 dp (the item's own 8 dp on top of the list's spacing) before the status chips, so the import's
        // two pieces read as one group and the statuses as another.
        if (undoRow != null) item(key = "undo") { undoRow(Modifier.animateItem().padding(bottom = 8.dp)) }
        item(key = "chips") {
            // Only the status chips: one of them is selected at a time.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.animateItem().fillMaxWidth().selectableGroup(),
            ) {
                StatusChip(
                    selected = filter == null, onClick = { onFilter(null) },
                    label = stringResource(Res.string.status_filter, stringResource(Res.string.status_ALL), houses.size),
                    modifier = Modifier.focusRequester(allChipFocus).then(
                        if (chipFocusable) Modifier.focusProperties { canFocus = true } else Modifier,
                    ),
                )
                HouseStatus.entries.forEach { s ->
                    val n = houses.count { it.status == s }
                    StatusChip(
                        selected = filter == s, onClick = { onFilter(s) },
                        label = stringResource(Res.string.status_filter, stringResource(s.labelRes), n),
                    )
                }
            }
        }
        item(key = "sort") { SortMenu(sort, onSort, Modifier.animateItem()) }
        // Always composed (round 16), so the node is in place before the first filter makes it a live region.
        item(key = "count") {
            Text(
                shownText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.animateItem().clearAndSetSemantics {
                    contentDescription = if (filtering) announcedText else shownText
                    // Polite: read after whatever TalkBack is saying (the typed letter, the chip's new state).
                    if (filtering) liveRegion = LiveRegionMode.Polite
                },
            )
        }
        if (shown.isEmpty()) {
            // Houses exist but none match: the web's no-match state (a glyph and a way out), not a bare sentence.
            // The count line above already says "0 of n" through its live region, so the title is not a second one.
            item(key = "nomatch") {
                Box(Modifier.animateItem()) {
                    HeroEmptyState(
                        icon = Icons.Default.Search,
                        title = stringResource(Res.string.houses_no_match),
                        horizontalPadding = 0.dp,
                        action = {
                            OutlinedButton(
                                onClick = {
                                    onFilter(null)
                                    onQuery("")
                                    onImportedOnly(false)
                                    // The button vanishes with the no-match state; put focus (and TalkBack) back in the
                                    // search field rather than letting it drop to the top of the screen.
                                    scope.launch {
                                        listState.scrollToItem(0)
                                        withFrameNanos { }
                                        runCatching { searchFocus.requestFocus() }
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                ButtonLabel(stringResource(Res.string.houses_clear_filters))
                            }
                        },
                    )
                }
            }
        } else {
            items(shown, key = { it.id }) { h ->
                HouseCard(h, visitsByHouse[h.id]?.visits ?: 0, Modifier.animateItem()) { onOpenHouse(h.id) }
            }
        }
    }
}

/**
 * A status filter chip in the web's `.chip` look (Design review, round 19): ✓ and a 2 dp primary border when
 * selected, a 1 dp `outline` edge when not, so the choice never rests on the fill colour alone (WCAG 1.4.1).
 *
 * One status at a time, in a `selectableGroup()`, so a radio button for TalkBack (UX review, round 21): M3's
 * FilterChip reports `Role.Checkbox`, and the outer `semantics { role = Role.RadioButton }` wins over it, as in
 * [SortMenu]. TalkBack reads "Shortlisted (3), selected, radio button, 3 of 5".
 */
@Composable
private fun StatusChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) ChipCheck else null,
        border = brandFilterChipBorder(selected),
        modifier = modifier.semantics { role = Role.RadioButton },
    )
}

/**
 * The copy import the list shows (round 19): [record] for [runId], read from disk for [requested] (the list's
 * `importedRun` at the time). [runId] stays set after an undo deleted the record, so the row can still say what the
 * undo did.
 */
private class ShownImport(val requested: String?, val runId: String?, val record: CopyRecord?)

/**
 * The house list's undo, in the same [ResultCard] as the Export and Import results (UX review, rounds 18 and 19; see
 * [HouseListScreen]). NEUTRAL "Imported from your backup at <time>: 40 copies." with "You can undo this until …" while
 * the undo is on offer and while it runs ("Removing the copies…"), SUCCESS with what the undo did, ERROR (warning sign,
 * error colours) when it failed. The card sits in a live region on a [Box] that is there before anything changes
 * (polite; assertive for a failure), so each change is announced. The button is one call site in the card's actions:
 * *Undo this import* ([onUndo], which asks first; disabled while [undoing] or when [canUndo] is false) and, once the
 * copies are gone, *Close* ([onHide]) in the same place, so TalkBack's focus stays in the row. Before an undo the card
 * also has its 48 dp close button ([onHide]), for a user who wants to keep the copies, and so does a failed undo's card
 * (round 20), for a user who does not want to retry. While [takeFocus] is set the button can take focus in touch mode
 * (`canFocus = true`), so the list can put TalkBack back on it after the confirm dialog ([focusRequester]).
 */
@Composable
private fun ImportUndoRow(
    count: Int,
    importedAt: Long?,
    until: Long?,
    undoing: Boolean,
    outcome: CopyImportUndo.Outcome?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onHide: () -> Unit,
    focusRequester: FocusRequester,
    takeFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    // Not while a retry runs: the last outcome is still the failure until the new one arrives, and the card then shows
    // "Removing the copies…" as NEUTRAL, with no close button.
    val failed = outcome?.failed == true && !undoing
    val done = outcome != null && !failed && !undoing
    val offered = outcome == null && !undoing
    val text = when {
        undoing -> stringResource(Res.string.import_undoing)
        failed -> stringResource(Res.string.import_undo_failed)
        outcome != null -> undoneSentence(outcome).orEmpty()
        else -> pluralStringResource(Res.plurals.houses_imported_row, count, count, importedAt?.dateText().orEmpty())
    }
    val tone = when {
        failed -> ResultTone.ERROR
        done -> ResultTone.SUCCESS
        else -> ResultTone.NEUTRAL
    }
    Box(
        modifier.fillMaxWidth().semantics {
            liveRegion = if (failed) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    ) {
        ResultCard(
            tone = tone,
            text = text,
            // Closable before an undo (to keep the copies) and after a failed one (round 20: a user who does not want
            // to retry can clear the red card); after a successful one the button itself is Close.
            onDismiss = if (offered || failed) onHide else null,
            actions = {
                if (until != null && offered) {
                    // Under the card's text: the 24 dp icon and its 12 dp gap.
                    Text(
                        stringResource(Res.string.import_undo_until, until.dateText()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp),
                    )
                }
                ResultActionsRow {
                    TextButton(
                        onClick = { if (done) onHide() else onUndo() },
                        enabled = done || (canUndo && !undoing),
                        // The target of the list's focusUndoButton (round 20). A button only takes focus in touch
                        // mode (which TalkBack is) while that is set: canFocus is otherwise left to the system, as for
                        // every other button, so keyboard and D-pad focus is unchanged.
                        modifier = Modifier.heightIn(min = 48.dp).focusRequester(focusRequester).then(
                            if (takeFocus) Modifier.focusProperties { canFocus = true } else Modifier,
                        ),
                    ) { ButtonLabel(stringResource(if (done) Res.string.common_close else Res.string.import_undo_copy)) }
                }
            },
        )
    }
}

/**
 * "Remove the 40 copies imported at <time>?" (UX review, round 19; UX-005). The undo writes synced tombstones and
 * deletes photo files, so the copies go from this phone, the server and every other device, and nothing brings them
 * back: from the lasting house-list row it is confirmed, like every other bulk or house delete in the app. *Remove
 * copies* is a [DangerButton] (error outline, round 21: the same look as Import's *Replace* and the house form's
 * *Delete*); *Keep them* is the dismiss action, and so are Back and a tap outside.
 */
@Composable
private fun ConfirmUndoDialog(count: Int, importedAt: Long, onRemove: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = {
            Text(pluralStringResource(Res.plurals.houses_undo_confirm_title, count, count, importedAt.dateText()))
        },
        text = { Text(stringResource(Res.string.houses_undo_confirm_body)) },
        confirmButton = {
            // The app's one look for an irreversible choice (Design review, round 21): as Import's *Replace*.
            DangerButton(text = stringResource(Res.string.houses_undo_confirm_remove), onClick = onRemove)
        },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.houses_undo_confirm_keep))
            }
        },
    )
}

/**
 * The sort order as one 48 dp control, "Sort: Best score ▾", that opens a menu of the three orders, like the web's
 * single labelled select (UX review, round 15). The inline row it replaces could not wrap: in Tamil at 360 dp its
 * last option was squeezed to nothing. The label is one Text, so at 320 dp and 200 % it wraps inside the button
 * and the arrow stays visible. Each menu item is a radio button for TalkBack, with the current order selected.
 *
 * Round 16: the label is the format string `houses_sort_value` ("Sort: %1$s"), so each language orders its own
 * words, and TalkBack hears the button as "Sort, Best score, drop-down list" (name, state and role).
 */
@Composable
private fun SortMenu(sort: Sort, onSort: (Sort) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val name = stringResource(Res.string.houses_sort)
    val current = stringResource(sort.label)
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            // Outside TextButton's own role = Button, so this one wins: it opens a menu.
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = name
                stateDescription = current
                role = Role.DropdownList
            },
        ) {
            Text(
                stringResource(Res.string.houses_sort_value, current),
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.selectableGroup()) {
            Sort.entries.forEach { s ->
                val isCurrent = s == sort
                DropdownMenuItem(
                    text = { Text(stringResource(s.label)) },
                    onClick = {
                        onSort(s)
                        open = false
                    },
                    leadingIcon = { RadioButton(selected = isCurrent, onClick = null) },
                    modifier = Modifier.semantics {
                        selected = isCurrent
                        role = Role.RadioButton
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HouseCard(h: HouseEntity, visits: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // clickable merges the card's texts into one TalkBack item: "name, status, place, price, score, visits", and
    // (round 16) says what a double-tap does, "double-tap to open details", with the button role.
    OutlinedCard(
        modifier.fillMaxWidth().clickable(
            onClickLabel = stringResource(Res.string.house_open),
            role = Role.Button,
            onClick = onClick,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.label.ifBlank { stringResource(Res.string.house_unnamed) }, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                // The status glyph (UX-002: ● ★ ✕, so the status is not colour alone), hidden from TalkBack, which
                // reads the status text in the card's merged description.
                Text(
                    h.status.glyph + " ",
                    color = h.status.color(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(stringResource(h.status.labelRes), color = h.status.color(), style = MaterialTheme.typography.labelLarge)
            }
            val place = listOfNotNull(h.street, h.locality).joinToString(", ")
            if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodySmall)
            // FlowRow wraps at large font scales instead of clipping (A11Y-A03).
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                h.priceText()?.let { Text(it, fontWeight = FontWeight.Medium) }
                h.bedrooms?.let { Text(stringResource(Res.string.common_bhk, it)) }
                Text(stringResource(Res.string.common_score_value, h.score.scoreText()))
                Text(stringResource(Res.string.common_visits_count, visits))
            }
        }
    }
}
