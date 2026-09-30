/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity
import app.doorprints.data.VisitEntity
import app.doorprints.shared.model.HuntReminders
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingIcs
import app.doorprints.shared.model.ViewingGroup
import app.doorprints.shared.model.ViewingKind
import app.doorprints.shared.model.ViewingStatus
import app.doorprints.shared.model.Viewings
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.ui.res.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/*
 * Viewings (docs/11 5.8, slice 3b-1): the history screen (Settings > Viewings, and "All viewings of this house" from a
 * house), the viewing form, the house screen's Viewings card and the *Book a second viewing?* prompt. Nothing here
 * schedules a notification: the platform reschedules from the stored viewings after every change (slice 3b-2); the
 * form only asks for the notification permission when a reminder is first saved.
 */

/** A key for "no filter" in the kind and status menus. */
private const val ANY = "ANY"

/** Settings > Viewings: the timeline with filters and a search box; [houseId] shows one house's viewings only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewingsScreen(
    houseId: String?,
    onBack: () -> Unit,
    onOpenViewing: (String) -> Unit,
    onPlan: (houseId: String?, kind: ViewingKind) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.viewings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) { ViewingsHistory(houseId, onOpenViewing, onPlan) }
        }
    }
}

/** The screen's content without the bar (the screenshot shows it alone). [nowMs] is the clock the groups are read at. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewingsHistory(
    houseId: String?,
    onOpenViewing: (String) -> Unit,
    onPlan: (houseId: String?, kind: ViewingKind) -> Unit,
    nowMs: Long = remember { nowMillis() },
) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    // null until Room answers, so the empty state does not flash on the way in.
    val all: List<Viewing>? by remember(repo) { repo.observeViewings() }.collectAsStateWithLifecycle(initialValue = null)
    // null until Room answers too, so no row reads "a house that is gone" on the way in.
    val loadedHouses: List<HouseEntity>? by repo.houses.collectAsStateWithLifecycle(null)
    val houses = loadedHouses.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf(ANY) }
    var statusFilter by rememberSaveable { mutableStateOf(ANY) }
    var fromDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var toDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickFrom by rememberSaveable { mutableStateOf(false) }
    var pickTo by rememberSaveable { mutableStateOf(false) }
    var secondFor by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailed = stringResource(Res.string.viewings_save_failed)
    val gone = stringResource(Res.string.viewings_houseGone)
    val byId = houses.associateBy { it.id }
    val any = stringResource(Res.string.viewings_filterAny)

    fun save(block: suspend () -> Unit) {
        scope.launch {
            error = try {
                withContext(NonCancellable) { block() }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveFailed
            }
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val forHouse = houseId?.let { byId[it]?.label ?: gone }
        Text(
            forHouse?.let { stringResource(Res.string.viewings_forHouse, it) } ?: stringResource(Res.string.viewings_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { onPlan(houseId, ViewingKind.FIRST) }, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.viewings_plan))
        }
        val list = all ?: return@Column
        if (loadedHouses == null) return@Column
        val mine = if (houseId == null) list else list.filter { it.houseId == houseId }
        if (mine.isEmpty()) {
            Text(stringResource(Res.string.viewings_empty), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        OutlinedTextField(
            query, { query = it.take(100) },
            label = { Text(stringResource(Res.string.viewings_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.widthIn(min = 160.dp).weight(1f)) {
                ChoiceMenu(
                    label = stringResource(Res.string.viewings_filterKind),
                    options = listOf(ANY) + ViewingKind.entries.map { it.name },
                    chosen = kindFilter,
                    text = { if (it == ANY) any else stringResource(ViewingKind.fromWire(it).labelResource) },
                ) { kindFilter = it }
            }
            Column(Modifier.widthIn(min = 160.dp).weight(1f)) {
                ChoiceMenu(
                    label = stringResource(Res.string.viewings_filterStatus),
                    options = listOf(ANY) + ViewingStatus.entries.map { it.name },
                    chosen = statusFilter,
                    text = { if (it == ANY) any else stringResource(ViewingStatus.fromWire(it).labelResource) },
                ) { statusFilter = it }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val fromLabel = stringResource(Res.string.viewings_filterFrom)
            val toLabel = stringResource(Res.string.viewings_filterTo)
            OutlinedButton(onClick = { pickFrom = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel("$fromLabel: " + (fromDay?.let { Formats.date(utcDayMidday(it), uiLanguage()) } ?: any))
            }
            OutlinedButton(onClick = { pickTo = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel("$toLabel: " + (toDay?.let { Formats.date(utcDayMidday(it), uiLanguage()) } ?: any))
            }
            if (fromDay != null || toDay != null || kindFilter != ANY || statusFilter != ANY || query.isNotBlank()) {
                TextButton(
                    onClick = { fromDay = null; toDay = null; kindFilter = ANY; statusFilter = ANY; query = "" },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.viewings_clearFilters)) }
            }
        }

        val shown = mine.filter { v ->
            val h = byId[v.houseId]
            (kindFilter == ANY || v.kind == kindFilter) && (statusFilter == ANY || v.status == statusFilter) &&
                (fromDay == null || LocalClock.dayOf(v.startsAt) >= fromDay!!) &&
                (toDay == null || LocalClock.dayOf(v.startsAt) <= toDay!!) &&
                Viewings.matches(v, h?.label, h?.street, h?.locality, query)
        }
        LiveMessage {
            if (shown.isEmpty()) Text(stringResource(Res.string.viewings_noMatch), style = MaterialTheme.typography.bodyLarge)
            error?.let { WarnNote(it) }
        }
        for ((group, rows) in Viewings.timeline(shown, nowMs)) {
            HorizontalDivider()
            SectionHeading(stringResource(group.labelResource))
            for (v in rows) {
                key(v.id) {
                    val name = byId[v.houseId]?.label?.ifBlank { null } ?: gone
                    ViewingRow(
                        viewing = v,
                        houseName = name,
                        missed = group == ViewingGroup.MISSED,
                        onOpen = { onOpenViewing(v.id) },
                        onHappened = {
                            save { repo.markViewingDone(v.id) }
                            secondFor = v.houseId
                        },
                        onCancel = { save { repo.saveViewing(v.copy(status = ViewingStatus.CANCELLED.name)) } },
                    )
                }
            }
        }
    }

    if (pickFrom || pickTo) {
        DayPickerDialog(
            initial = if (pickFrom) fromDay else toDay,
            onPick = { day -> if (pickFrom) fromDay = day else toDay = day },
            onClose = { pickFrom = false; pickTo = false },
        )
    }
    secondFor?.let { id ->
        SecondViewingDialog(
            houseId = id,
            onBook = {
                secondFor = null
                onPlan(id, ViewingKind.SECOND)
            },
            onDismiss = { secondFor = null },
        )
    }
}

/** A day of the date picker (UTC midnight) as an instant inside it on any clock, for printing it as a date. */
private fun utcDayMidday(day: Long): Long = day + 12 * 3_600_000L - utcOffsetMillis(day)

/**
 * One viewing of the timeline: date and time, house, kind and status, with whom and the notes' start; a tap opens the
 * form. A *Missed?* one also has *It happened* and *Cancel*, each naming the viewing for TalkBack.
 */
@Composable
private fun ViewingRow(
    viewing: Viewing,
    houseName: String,
    missed: Boolean,
    onOpen: () -> Unit,
    onHappened: () -> Unit,
    onCancel: () -> Unit,
) {
    val whenText = viewing.startsAt.dateText()
    val rowDesc = stringResource(Res.string.viewings_rowAria, houseName, whenText)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = rowDesc }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(whenText, style = MaterialTheme.typography.titleSmall)
            Text(houseName, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(viewing.viewingKind.labelResource) + " · " + stringResource(viewing.viewingStatus.labelResource),
                style = MaterialTheme.typography.bodyMedium,
            )
            viewing.withWhom?.let { Text(stringResource(Res.string.viewings_with, it), style = MaterialTheme.typography.bodySmall) }
            viewing.notes?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (missed) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val happenedDesc = stringResource(Res.string.viewings_happenedAria, houseName, whenText)
                TextButton(onClick = onHappened, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = happenedDesc }) {
                    ButtonLabel(stringResource(Res.string.viewings_happened))
                }
                val cancelDesc = stringResource(Res.string.viewings_cancelAria, houseName, whenText)
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cancelDesc }) {
                    ButtonLabel(stringResource(Res.string.viewings_cancelRow))
                }
            }
        }
    }
}

/** Material's date picker in a dialog: [onPick] gets the day (UTC midnight of the chosen date), or null when cleared. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(initial: Long?, onPick: (Long?) -> Unit, onClose: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.selectedDateMillis)
                onClose()
            }) { Text(stringResource(Res.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.common_cancel)) } },
    ) { DatePicker(state = state) }
}

/**
 * *Book a second viewing?* after a viewing is DONE (docs/11 5.8): the re-check list (the house's open questions, its
 * criteria scored 2 or less; PROBLEM photos come with 5.7), *Book a second viewing* (the form: SECOND, this house)
 * and *Not now*.
 */
@Composable
fun SecondViewingDialog(houseId: String, onBook: () -> Unit, onDismiss: () -> Unit) {
    val repo = LocalAppServices.current.repository
    val house: HouseEntity? by remember(houseId) { repo.house(houseId) }.collectAsStateWithLifecycle(initialValue = null)
    val scoring: Scoring by remember(repo) { repo.observeScoring() }.collectAsStateWithLifecycle(Scoring.DEFAULT)
    val recheck = Viewings.recheck(house?.answers, house?.checklist.orEmpty(), scoring)
    val names = scoring.criteria.filter { it.key in recheck.lowCriteria }.map { it.displayName() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.viewings_secondTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.viewings_recheck), style = MaterialTheme.typography.titleSmall)
                if (recheck.isEmpty) Text(stringResource(Res.string.viewings_recheckNothing))
                if (recheck.openQuestions > 0) Text(stringResource(Res.string.viewings_recheckQuestions, recheck.openQuestions))
                if (names.isNotEmpty()) Text(stringResource(Res.string.viewings_recheckScores, names.joinToString(", ")))
            }
        },
        confirmButton = { TextButton(onClick = onBook) { Text(stringResource(Res.string.viewings_secondBook)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.viewings_secondNotNow)) } },
    )
}

/**
 * The house screen's **Viewings** card (docs/11 5.8): the next PLANNED viewing (date, kind) or "No viewing planned";
 * *Plan a viewing*; *Mark viewing done* when a visit of this house is within two hours of the next (or last) PLANNED
 * viewing ([Viewings.suggestedVisitFor]), which then offers *Book a second viewing?*; and "All viewings of this house".
 */
@Composable
fun ViewingsCard(
    houseId: String,
    visits: List<VisitEntity>,
    onPlan: (ViewingKind) -> Unit,
    onOpenAll: () -> Unit,
    nowMs: Long = remember { nowMillis() },
) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    val all: List<Viewing> by remember(repo) { repo.observeViewings() }.collectAsStateWithLifecycle(emptyList())
    val mine = all.filter { it.houseId == houseId }
    val next = Viewings.nextOf(mine, houseId, nowMs)
    // The one to mark done: the next PLANNED viewing, else the latest PLANNED one already past.
    val candidate = next ?: mine.filter { it.viewingStatus == ViewingStatus.PLANNED }.maxWithOrNull(Viewing.ORDER)
    val refs = visits.map { Viewings.VisitRef(it.id, it.houseId, it.arrivedAt) }
    val suggested = candidate?.let { Viewings.suggestedVisitFor(it, refs) }
    var secondFor by rememberSaveable { mutableStateOf(false) }

    HorizontalDivider()
    SectionHeading(stringResource(Res.string.viewings_cardHeading))
    Text(
        next?.let { stringResource(Res.string.viewings_next, it.startsAt.dateText(), stringResource(it.viewingKind.labelResource)) }
            ?: stringResource(Res.string.viewings_none),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (candidate != null && suggested != null) {
        Text(stringResource(Res.string.viewings_markDoneHint, suggested.arrivedAt.dateText()), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                scope.launch { withContext(NonCancellable) { repo.markViewingDone(candidate.id, suggested.id) } }
                secondFor = true
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { ButtonLabel(stringResource(Res.string.viewings_markDone)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onPlan(ViewingKind.FIRST) }, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.viewings_plan))
        }
        TextButton(onClick = onOpenAll, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.viewings_allOfHouse))
        }
    }
    if (secondFor) {
        SecondViewingDialog(
            houseId = houseId,
            onBook = {
                secondFor = false
                onPlan(ViewingKind.SECOND)
            },
            onDismiss = { secondFor = false },
        )
    }
}

/** The form's working copy: every field, with the house and start the person may not have chosen yet. */
private data class ViewingDraft(
    val houseId: String?,
    val startsAt: Long,
    val durationMin: Int,
    val kind: ViewingKind,
    val remindMin: Int,
    val withWhom: String,
    val notes: String,
    val status: ViewingStatus = ViewingStatus.PLANNED,
    /** *Offer Hunt mode before this viewing* (slice 3c): off unless turned on. */
    val huntReminder: Boolean = false,
)

/**
 * The draft as the viewing record [id] of [houseId] (on top of [stored], so what the form does not show is kept).
 * Without Hunt mode on this phone ([huntMode] off) the switch is hidden and the stored value is kept as it is.
 */
private fun ViewingDraft.toViewing(stored: Viewing?, id: String, houseId: String, status: ViewingStatus, huntMode: Boolean) =
    (stored ?: Viewing(id = id)).copy(
        id = id, houseId = houseId, startsAt = startsAt, durationMin = durationMin,
        kind = kind.name, status = status.name, remindMin = remindMin,
        withWhom = withWhom, notes = notes,
        huntReminder = if (huntMode) huntReminder else stored?.huntReminder ?: false,
    )

/**
 * The name and text of the `.ics` the iPhone shares for [viewing] (S4b-BL-92a; [ViewingIcs], docs/11 5.8):
 * `<id>.ics` as the website names it, the house's label (or [untitled]) in the summary after [word], its address or
 * else its street as the location, the notes and never `withWhom`; stamped [stampMs].
 */
internal fun viewingCalendarFile(
    viewing: Viewing,
    house: HouseEntity?,
    untitled: String,
    word: String,
    stampMs: Long,
): Pair<String, String> = "${viewing.id}.ics" to ViewingIcs.build(
    viewing.copy(notes = viewing.notes?.trim()?.ifEmpty { null }),
    house?.label?.ifBlank { null } ?: untitled,
    house?.address?.ifBlank { null } ?: house?.street?.ifBlank { null },
    word,
    stampMs,
)

/** [ViewingDraft] in the saved-state bundle: plain values only. */
private val ViewingDraftSaver = listSaver<ViewingDraft, Any?>(
    save = { listOf(it.houseId, it.startsAt, it.durationMin, it.kind.name, it.remindMin, it.withWhom, it.notes, it.status.name, it.huntReminder) },
    restore = {
        ViewingDraft(
            it[0] as String?, it[1] as Long, it[2] as Int, ViewingKind.fromWire(it[3] as String), it[4] as Int, it[5] as String,
            it[6] as String, ViewingStatus.fromWire(it[7] as String), it[8] as Boolean,
        )
    },
)

/** The durations the form offers: 5 to 480 minutes in 5-minute steps. */
private val DURATIONS: List<Int> = (Viewing.MIN_DURATION..Viewing.MAX_DURATION step 5).toList()

/**
 * The viewing form (docs/11 5.8): house (preselected when opened from one), date and time (the phone's locale), duration,
 * kind, reminder, with whom and notes; *Save*, *Cancel viewing* (CANCELLED), *Delete* and *Add to calendar* (the phone's
 * calendar app, no permission). House and time are required; a past time is fine (a viewing logged afterwards). Where
 * the phone has Hunt mode, *Offer Hunt mode before this viewing* (slice 3c, off by default; the record keeps
 * `huntReminder` only when it is on). [viewingId] null plans a new one for [houseId] of [kind].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewingFormScreen(
    viewingId: String?,
    houseId: String?,
    kind: ViewingKind = ViewingKind.FIRST,
    onDone: () -> Unit,
    nowMs: Long = remember { nowMillis() },
) {
    val repo = LocalAppServices.current.repository
    val platform = LocalPlatformServices.current
    val scope = rememberCoroutineScope()
    val loadedHouses: List<HouseEntity>? by repo.houses.collectAsStateWithLifecycle(null)
    val houses = loadedHouses.orEmpty()
    var stored by remember { mutableStateOf<Viewing?>(null) }
    var loaded by remember { mutableStateOf(viewingId == null) }
    // Saveable, so a rotation keeps what was typed; [filled] says the stored viewing is already in it.
    var draft by rememberSaveable(stateSaver = ViewingDraftSaver) {
        // The next whole hour, the usual time to plan for.
        mutableStateOf(
            ViewingDraft(houseId, (nowMs / 3_600_000L + 1) * 3_600_000L, Viewing.DEFAULT_DURATION, kind, Viewing.DEFAULT_REMIND, "", ""),
        )
    }
    var filled by rememberSaveable { mutableStateOf(viewingId == null) }
    LaunchedEffect(viewingId) {
        val id = viewingId ?: return@LaunchedEffect
        val v = repo.getViewing(id)
        stored = v
        if (v != null && !filled) {
            draft = ViewingDraft(
                v.houseId, v.startsAt, v.durationMin, v.viewingKind, v.remindMin, v.withWhom.orEmpty(), v.notes.orEmpty(), v.viewingStatus,
                v.huntReminder,
            )
            filled = true
        }
        loaded = true
    }
    var tried by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    /** A new viewing's id, made when its calendar file is shared before it is saved (the iPhone's `.ics`). */
    var newId by rememberSaveable { mutableStateOf<String?>(null) }
    val saveFailed = stringResource(Res.string.viewings_save_failed)
    val maxText = stringResource(Res.string.viewings_max)
    val calendarNone = stringResource(Res.string.viewings_calendar_none)
    val gone = stringResource(Res.string.viewings_houseGone)
    val word = stringResource(Res.string.viewings_icsWord)
    // Slice 3b-2: the first save of a viewing with a reminder ahead asks for notifications (Android 13+, iOS), once
    // and never at start-up; whatever the answer, the viewing is already saved.
    val askNotifications = rememberNotificationAsk(Res.string.viewings_notify_rationale)
    val remindOn by remember(repo) { repo.settings.viewingsRemind() }.collectAsStateWithLifecycle(initialValue = true)
    val huntRemindOn by remember(repo) { repo.settings.huntRemind() }.collectAsStateWithLifecycle(initialValue = true)
    val huntLead by remember(repo) { repo.settings.huntReminderMin() }.collectAsStateWithLifecycle(initialValue = HuntReminders.DEFAULT_LEAD)
    val huntMode = LocalPlatformFeatures.current.huntMode

    fun write(status: ViewingStatus = draft.status, then: () -> Unit = onDone) {
        tried = true
        val house = draft.houseId ?: return
        if (busy) return
        busy = true
        scope.launch {
            error = try {
                val saved = withContext(NonCancellable) {
                    // The id a shared calendar file already carries, so its event and this viewing stay one.
                    val id = stored?.id ?: newId ?: repo.newViewingId()
                    draft.toViewing(stored, id, house, status, huntMode).also { repo.saveViewing(it) }
                }
                val t = nowMillis()
                val ahead = HuntReminders.merged(listOf(saved), huntLead, t, viewingReminders = remindOn, huntReminders = huntRemindOn)
                if (ahead.isNotEmpty()) askNotifications(then) else then()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecordLimitException) {
                maxText
            } catch (e: Exception) {
                saveFailed
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewingId == null) Res.string.viewings_formNew else Res.string.viewings_formEdit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!loaded || loadedHouses == null) return@Column
                if (viewingId != null && stored == null) {
                    Text(stringResource(Res.string.viewings_not_found), style = MaterialTheme.typography.bodyLarge)
                    return@Column
                }
                val d = draft
                val byId = houses.associateBy { it.id }
                // A house that is gone stays chosen (its viewing keeps its house id); the list offers the live ones.
                val options = houses.map { it.id } + listOfNotNull(d.houseId?.takeIf { it !in byId })
                val houseMissing = tried && d.houseId == null
                ChoiceMenu(
                    label = stringResource(Res.string.viewings_house),
                    options = listOf<String?>(null) + options,
                    chosen = d.houseId,
                    text = { id -> id?.let { byId[it]?.label?.ifBlank { null } ?: gone } ?: stringResource(Res.string.viewings_housePick) },
                ) { draft = draft.copy(houseId = it) }
                if (houseMissing) {
                    LiveMessage { Text(stringResource(Res.string.viewings_houseRequired), color = MaterialTheme.colorScheme.error) }
                }

                Text(stringResource(Res.string.viewings_when), style = MaterialTheme.typography.bodySmall)
                Text(d.startsAt.dateText(), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        ButtonLabel(stringResource(Res.string.viewings_pick_date))
                    }
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        ButtonLabel(stringResource(Res.string.viewings_pick_time))
                    }
                }
                ChoiceMenu(
                    label = stringResource(Res.string.viewings_duration),
                    options = DURATIONS,
                    chosen = d.durationMin,
                    text = { stringResource(Res.string.viewings_duration_min, it) },
                ) { draft = draft.copy(durationMin = it) }
                ChoiceMenu(
                    label = stringResource(Res.string.viewings_kindLabel),
                    options = ViewingKind.entries,
                    chosen = d.kind,
                    text = { stringResource(it.labelResource) },
                ) { draft = draft.copy(kind = it) }
                ChoiceMenu(
                    label = stringResource(Res.string.viewings_remind),
                    options = Viewing.REMIND_CHOICES,
                    chosen = d.remindMin,
                    text = { stringResource(remindResource(it)) },
                ) { draft = draft.copy(remindMin = it) }
                if (huntMode) {
                    SwitchRow(
                        text = stringResource(Res.string.viewings_huntReminder),
                        hint = stringResource(Res.string.viewings_huntReminder_hint, huntLead),
                        checked = d.huntReminder,
                        horizontalPadding = 0.dp,
                        onChange = { draft = draft.copy(huntReminder = it) },
                    )
                }
                OutlinedTextField(
                    d.withWhom, { draft = draft.copy(withWhom = it.take(Viewing.MAX_WITH_WHOM)) },
                    label = { Text(stringResource(Res.string.viewings_withWhomLabel)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    d.notes, { draft = draft.copy(notes = it.take(Viewing.MAX_NOTES)) },
                    label = { Text(stringResource(Res.string.viewings_notes)) },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                LiveMessage { error?.let { WarnNote(it) } }
                Button(onClick = { write() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.viewings_save))
                }
                if (platform.canAddToCalendar) {
                    OutlinedButton(
                        onClick = {
                            val house = d.houseId?.let { byId[it] }
                            val label = house?.label?.ifBlank { null } ?: gone
                            // The notes, never withWhom: the calendar may sync to other accounts.
                            val ok = platform.addToCalendar(
                                CalendarEvent(
                                    "$word: $label", d.startsAt, d.startsAt + d.durationMin * 60_000L,
                                    house?.address?.ifBlank { null } ?: house?.street, d.notes.trim().ifEmpty { null },
                                ),
                            )
                            if (!ok) error = calendarNone
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { ButtonLabel(stringResource(Res.string.viewings_addToCalendar)) }
                } else if (platform.canShareCalendarFile) {
                    // The iPhone (S4b-BL-92a): the form as it stands, as a `.ics` through the share sheet, as the website
                    // downloads it; the house must be chosen first.
                    OutlinedButton(
                        onClick = {
                            val houseId = d.houseId
                            if (houseId == null) {
                                tried = true
                                return@OutlinedButton
                            }
                            scope.launch {
                                val id = stored?.id ?: newId ?: repo.newViewingId().also { newId = it }
                                val house = byId[houseId]
                                val (name, ics) = viewingCalendarFile(
                                    d.toViewing(stored, id, houseId, d.status, huntMode), house, gone, word, nowMillis(),
                                )
                                if (!platform.shareCalendarFile(name, ics)) error = calendarNone
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { ButtonLabel(stringResource(Res.string.viewings_addToCalendar)) }
                }
                if (stored != null) {
                    if (d.status != ViewingStatus.CANCELLED) {
                        OutlinedButton(
                            onClick = { write(ViewingStatus.CANCELLED) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { ButtonLabel(stringResource(Res.string.viewings_cancelViewing)) }
                    }
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(Res.string.viewings_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (pickDate) {
        DayPickerDialog(
            initial = LocalClock.dayOf(draft.startsAt),
            onPick = { day ->
                if (day != null) {
                    val (h, m) = LocalClock.hourMinuteOf(draft.startsAt)
                    draft = draft.copy(startsAt = LocalClock.at(day, h, m))
                }
            },
            onClose = { pickDate = false },
        )
    }
    if (pickTime) {
        val (h, m) = LocalClock.hourMinuteOf(draft.startsAt)
        // The phone's own 12 h or 24 h setting (Material's default).
        val state = rememberTimePickerState(initialHour = h, initialMinute = m)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(startsAt = LocalClock.at(LocalClock.dayOf(draft.startsAt), state.hour, state.minute))
                    pickTime = false
                }) { Text(stringResource(Res.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(Res.string.common_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.viewings_confirmDelete)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = stored?.id ?: return@TextButton
                    scope.launch {
                        withContext(NonCancellable) { repo.deleteViewing(id) }
                        onDone()
                    }
                }) { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}
