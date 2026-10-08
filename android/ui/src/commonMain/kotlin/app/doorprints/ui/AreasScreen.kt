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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity
import app.doorprints.shared.trace.RepeatLook
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.AreaNotes
import app.doorprints.shared.model.Distances
import app.doorprints.shared.model.HousePoint
import app.doorprints.shared.model.Place
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.ui.res.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

// Hunting areas, my places and area notes (docs/11 "Design of slice 4a", 5.17, 5.22, 5.23): Settings > My areas (the
// areas and every area note) and Settings > My places, their forms, the house page's Area notes and Distances, and the
// note dialog they share. The wake-up on entering an area (slice 4b) is AreaWakeupScreen.kt: *Wake me here* marks the
// areas it watches.

/** How long *Use my current location* waits for a fix, as on the house form. */
private const val POINT_LOCATION_TIMEOUT_MS = 15_000L

/** The house page's point for the derived rules. */
internal fun HouseEntity.point() = HousePoint(lat, lon, street, locationSource)

/** A screen with a top bar, a back arrow and a scrolling column no wider than [ContentMaxWidth]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), content = content)
        }
    }
}

// ---- My areas ----

/**
 * Settings > My areas: *Wake me in my hunting areas* (slice 4b; [onTurnOnWakeup] opens its rationale), the areas, *Add
 * area* (up to 20), and every area note with a filter; a row opens its form.
 */
@Composable
fun AreasScreen(onBack: () -> Unit, onOpenArea: (String) -> Unit, onTurnOnWakeup: () -> Unit = {}) {
    SubScreen(stringResource(Res.string.areas_title), onBack) { AreasEditor(onOpenArea, onTurnOnWakeup) }
}

/** The list and the notes without the screen around them (the screenshot and the tests show this). */
@Composable
fun AreasEditor(onOpenArea: (String) -> Unit, onTurnOnWakeup: () -> Unit = {}) {
    val repo = LocalAppServices.current.repository
    // null until the database answers, so the empty state does not flash on the way in.
    val areas: List<Area>? by remember(repo) { repo.observeAreas() }.collectAsStateWithLifecycle(initialValue = null)
    val notes: List<AreaNote> by remember(repo) { repo.observeAreaNotes() }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val list = areas ?: return@Column
        AreaWakeupSection(onTurnOnWakeup)
        AddButton(stringResource(Res.string.areas_add), list.size < Area.MAX_AREAS, stringResource(Res.string.areas_cap)) {
            onOpenArea(Routes.NEW_RECORD)
        }
        if (list.isEmpty()) {
            HeroEmptyState(
                icon = Icons.Default.Place,
                title = stringResource(Res.string.areas_empty_title),
                body = stringResource(Res.string.areas_empty_body),
                horizontalPadding = 0.dp,
            )
        }
        list.forEachIndexed { index, area ->
            if (index > 0) HorizontalDivider()
            val detail = listOfNotNull(
                stringResource(Res.string.areas_radius_value, area.radiusM),
                if (area.enabled) null else stringResource(Res.string.areas_wake_off),
            ).joinToString(" · ")
            NavListRow(area.name, detail) { onOpenArea(area.id) }
        }
        HorizontalDivider()
        AreaNotesList(list, notes)
    }
}

/** *Add …*, or at the cap the button disabled with [capText] under it (announced when it appears). */
@Composable
private fun AddButton(label: String, enabled: Boolean, capText: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        ButtonLabel(label)
    }
    LiveMessage { if (!enabled) Text(capText, style = MaterialTheme.typography.bodySmall) }
}

/** One 56 dp row that opens something: a title, a detail line and a chevron. */
@Composable
private fun NavListRow(title: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the notes list shows: all, one area's, or one street's (by its trimmed, lower-case name). */
private sealed interface NoteFilter {
    data object All : NoteFilter
    data class OfArea(val id: String) : NoteFilter
    data class OfStreet(val key: String) : NoteFilter
}

/** The filter as the string kept in saved state: empty for all, "a:" and an area id, or "s:" and a street key. */
private fun NoteFilter.save(): String = when (this) {
    NoteFilter.All -> ""
    is NoteFilter.OfArea -> "a:$id"
    is NoteFilter.OfStreet -> "s:$key"
}

private fun noteFilterOf(saved: String): NoteFilter = when {
    saved.startsWith("a:") -> NoteFilter.OfArea(saved.drop(2))
    saved.startsWith("s:") -> NoteFilter.OfStreet(saved.drop(2))
    else -> NoteFilter.All
}

private fun streetKey(street: String?) = street?.trim()?.lowercase().orEmpty()

/** Where a note comes from, as its row says it: "Area: Adyar", "Street: MG Road", or an area that is gone. */
@Composable
private fun noteSource(note: AreaNote, areas: List<Area>): String = when {
    note.areaId != null -> areas.firstOrNull { it.id == note.areaId }?.let { stringResource(Res.string.note_from_area, it.name) }
        ?: stringResource(Res.string.note_area_gone)
    else -> stringResource(Res.string.note_from_street, note.street.orEmpty())
}

/** Every area note, newest first, with the filter (all, an area, a street) and each note's edit and delete. */
@Composable
private fun AreaNotesList(areas: List<Area>, notes: List<AreaNote>) {
    SectionHeading(stringResource(Res.string.notes_heading))
    if (notes.isEmpty()) {
        Text(stringResource(Res.string.notes_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    var saved by rememberSaveable { mutableStateOf("") }
    val filter = noteFilterOf(saved)
    val streets = notes.mapNotNull { it.street?.trim() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    val options: List<Pair<NoteFilter, String>> = buildList {
        add(NoteFilter.All to stringResource(Res.string.notes_filter_all))
        areas.forEach { add(NoteFilter.OfArea(it.id) to stringResource(Res.string.note_from_area, it.name)) }
        streets.forEach { add(NoteFilter.OfStreet(it.lowercase()) to stringResource(Res.string.note_from_street, it)) }
    }
    val chosen = options.firstOrNull { it.first == filter } ?: options.first()
    ChoiceMenu(stringResource(Res.string.notes_filter), chosen.second, options.map { it.second }) { i -> saved = options[i].first.save() }
    val shown = notes.filter { n ->
        when (val f = chosen.first) {
            NoteFilter.All -> true
            is NoteFilter.OfArea -> n.areaId == f.id
            is NoteFilter.OfStreet -> n.street != null && streetKey(n.street) == f.key
        }
    }
    if (shown.isEmpty()) Text(stringResource(Res.string.notes_filter_none), style = MaterialTheme.typography.bodyMedium)
    shown.forEach { note -> NoteRow(note, noteSource(note, areas), areas) }
}

/** A labelled drop-down: [label] above [current], the choices in a menu; one 48 dp button. */
@Composable
private fun ChoiceMenu(label: String, current: String, choices: List<String>, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "$label: $current" },
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(current, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, c ->
                DropdownMenuItem(text = { Text(c) }, onClick = {
                    open = false
                    onChoose(i)
                })
            }
        }
    }
}

/** One note: its text, where it comes from, and a menu with *Edit note* and *Delete note* (confirmed). */
@Composable
private fun NoteRow(note: AreaNote, source: String, areas: List<Area>) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable(note.id) { mutableStateOf(false) }
    var confirm by rememberSaveable(note.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
            Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.note_actions, note.text.take(40)))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.note_edit)) }, onClick = {
                    menu = false
                    editing = true
                })
                DropdownMenuItem(text = { Text(stringResource(Res.string.note_delete)) }, onClick = {
                    menu = false
                    confirm = true
                })
            }
        }
    }
    if (editing) {
        AreaNoteDialog(
            title = stringResource(Res.string.note_edit), note = note, street = note.street, areas = areas, reaching = emptyList(),
            onDismiss = { editing = false },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(Res.string.note_delete_title)) },
            text = { Text(note.text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { withContext(NonCancellable) { repo.deleteAreaNote(note.id) } }
                }) { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

/**
 * The form of an area, or a new one when [areaId] is null: the name, the point (*Use my current location*, *Pick on the
 * map*, the latitude and longitude), the radius (200 to 2,000 m in 100 m steps), *Wake me here* (the areas *Wake me in
 * my hunting areas* watches, slice 4b), *Save* and *Delete area* after a confirmation.
 */
@Composable
fun AreaFormScreen(areaId: String?, onDone: () -> Unit) {
    val repo = LocalAppServices.current.repository
    val areas: List<Area>? by remember(repo) { repo.observeAreas() }.collectAsStateWithLifecycle(initialValue = null)
    val found = areaId?.let { id -> areas?.firstOrNull { it.id == id } }
    SubScreen(stringResource(if (areaId == null) Res.string.areas_add else Res.string.area_title), onDone) {
        when {
            areas == null -> Unit
            areaId != null && found == null -> Text(stringResource(Res.string.area_gone), Modifier.padding(16.dp))
            else -> AreaForm(areaId, found, areas.orEmpty().size, onDone)
        }
    }
}

/**
 * The form behind [AreaFormScreen] once the area is known: name, point, radius and *Wake me here* are saved state;
 * *Save* needs a name and a valid point, and a refused save at the cap of areas shows the cap message. Delete is
 * confirmed first.
 */
@Composable
private fun AreaForm(areaId: String?, found: Area?, liveCount: Int, onDone: () -> Unit) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(areaId) { mutableStateOf(found?.name ?: "") }
    var latText by rememberSaveable(areaId) { mutableStateOf(found?.let { Formats.coordinate(it.lat) } ?: "") }
    var lonText by rememberSaveable(areaId) { mutableStateOf(found?.let { Formats.coordinate(it.lon) } ?: "") }
    var radius by rememberSaveable(areaId) { mutableIntStateOf(found?.radiusM ?: Area.DEFAULT_RADIUS) }
    var enabled by rememberSaveable(areaId) { mutableStateOf(found?.enabled ?: true) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var capped by remember { mutableStateOf(areaId == null && liveCount >= Area.MAX_AREAS) }
    val lat = parseCoordinate(latText, 90.0)
    val lon = parseCoordinate(lonText, 180.0)
    val canSave = name.isNotBlank() && lat != null && lon != null && !busy && !capped

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NameField(name, Area.MAX_NAME) { name = it }
        PointFields(latText, lonText, onLat = { latText = it }, onLon = { lonText = it })
        val radiusText = stringResource(Res.string.area_radius, radius)
        val radiusLabel = stringResource(Res.string.area_radius_label)
        Text(radiusText, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = radius.toFloat(),
            onValueChange = { v -> radius = ((v / Area.RADIUS_STEP).roundToInt() * Area.RADIUS_STEP).coerceIn(Area.MIN_RADIUS, Area.MAX_RADIUS) },
            valueRange = Area.MIN_RADIUS.toFloat()..Area.MAX_RADIUS.toFloat(),
            steps = (Area.MAX_RADIUS - Area.MIN_RADIUS) / Area.RADIUS_STEP - 1,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = radiusLabel
                stateDescription = radiusText
            },
        )
        SwitchRow(
            text = stringResource(Res.string.area_wake), hint = stringResource(Res.string.area_wake_hint),
            checked = enabled, horizontalPadding = 0.dp, onChange = { enabled = it },
        )
        LiveMessage { if (capped) Text(stringResource(Res.string.areas_cap), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val id = areaId ?: repo.newAreaId()
                        withContext(NonCancellable) { repo.saveArea(Area(id, name.trim(), lat!!, lon!!, radius, enabled)) }
                        onDone()
                    } catch (_: RecordLimitException) {
                        capped = true
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { ButtonLabel(stringResource(Res.string.common_save)) }
        if (areaId != null) {
            HorizontalDivider()
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.area_delete))
            }
        }
    }
    if (confirmDelete && areaId != null) {
        ConfirmDelete(
            stringResource(Res.string.area_delete_title), stringResource(Res.string.area_delete_body),
            onDismiss = { confirmDelete = false },
        ) {
            scope.launch {
                withContext(NonCancellable) { repo.deleteArea(areaId) }
                onDone()
            }
        }
    }
}

/** The name field of an area or a place: capped at [max] characters, with an error while it is empty. */
@Composable
private fun NameField(name: String, max: Int, onName: (String) -> Unit) {
    val missing = name.isBlank()
    OutlinedTextField(
        name, { onName(it.take(max)) }, label = { Text(stringResource(Res.string.area_name)) },
        isError = missing, supportingText = if (missing) ({ Text(stringResource(Res.string.area_name_needed)) }) else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

/** The delete confirmation of an area or a place: [onConfirm] runs after the dialog closes. */
@Composable
private fun ConfirmDelete(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/**
 * Where an area or a place is: *Use my current location* (the house form's permission steps and notes), *Pick on the
 * map* ([MapPointPicker], where the platform has the map) and the latitude and longitude, typed.
 */
@Composable
private fun PointFields(latText: String, lonText: String, onLat: (String) -> Unit, onLon: (String) -> Unit) {
    val services = LocalAppServices.current
    val platform = LocalPlatformServices.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLocationAsk()
    var grants by remember { mutableIntStateOf(0) }
    val request = rememberLocationPermissionRequest {
        ask.refresh()
        if (platform.locationAccess() == LocationAccess.PRECISE) {
            denied = false
            grants++
        } else {
            denied = true
        }
    }
    fun locate() {
        if (locating) return
        if (platform.locationAccess() != LocationAccess.PRECISE) {
            failed = false
            if (ask.canAsk) {
                ask.markAsked()
                request()
            } else {
                denied = true
            }
            return
        }
        locating = true
        failed = false
        denied = false
        scope.launch {
            val here = try {
                withTimeoutOrNull(POINT_LOCATION_TIMEOUT_MS) { services.location.current() }
            } finally {
                locating = false
            }
            if (here == null) {
                failed = true
            } else {
                onLat(Formats.coordinate(here.first))
                onLon(Formats.coordinate(here.second))
            }
        }
    }
    LaunchedEffect(grants) { if (grants > 0) locate() }
    val lat = parseCoordinate(latText, 90.0)
    val lon = parseCoordinate(lonText, 180.0)

    SectionHeading(stringResource(Res.string.area_point))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { locate() }, enabled = !locating, modifier = Modifier.heightIn(min = 48.dp)) {
            if (locating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            }
            ButtonLabel(stringResource(Res.string.house_use_location))
        }
        if (LocalPlatformFeatures.current.map) {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.point_pick_map))
            }
        }
    }
    LiveMessage {
        when {
            locating -> Text(stringResource(Res.string.common_finding_location), style = MaterialTheme.typography.bodySmall)
            denied && !ask.granted -> LocationPermissionNote(
                ask = ask,
                deniedText = stringResource(Res.string.house_location_denied),
                approximateText = approximateLocationText(Res.string.area_needs_precise),
                launchRequest = { request() },
            )
            failed -> Text(
                stringResource(Res.string.house_location_failed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
    val latBad = latText.isNotEmpty() && lat == null
    val lonBad = lonText.isNotEmpty() && lon == null
    PairOrStack(
        first = { m ->
            OutlinedTextField(
                latText, onLat, label = { Text(stringResource(Res.string.house_lat)) }, isError = latBad,
                supportingText = if (latBad) ({ Text(stringResource(Res.string.house_lat_range)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                singleLine = true, modifier = m,
            )
        },
        second = { m ->
            OutlinedTextField(
                lonText, onLon, label = { Text(stringResource(Res.string.house_lon)) }, isError = lonBad,
                supportingText = if (lonBad) ({ Text(stringResource(Res.string.house_lon_range)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                singleLine = true, modifier = m,
            )
        },
    )
    if (lat == null || lon == null) {
        Text(stringResource(Res.string.area_point_needed), style = MaterialTheme.typography.bodySmall)
    }
    if (picking) {
        MapPointPicker(
            start = if (lat != null && lon != null) lat to lon else null,
            onDismiss = { picking = false },
        ) { (pickedLat, pickedLon) ->
            picking = false
            onLat(Formats.coordinate(pickedLat))
            onLon(Formats.coordinate(pickedLon))
        }
    }
}

/**
 * *Pick on the map*: the app's map ([PlatformMap], with India's boundaries as on the Map and the saved houses) full
 * screen, a cross at its centre, and *Use this point* for the spot under the cross; a long press picks that spot.
 */
@Composable
private fun MapPointPicker(start: Pair<Double, Double>?, onDismiss: () -> Unit, onPick: (Pair<Double, Double>) -> Unit) {
    val repo = LocalAppServices.current.repository
    val houses: List<HouseEntity> by repo.houses.collectAsStateWithLifecycle(emptyList())
    var control by remember { mutableStateOf<MapControl?>(null) }
    val fontScale = LocalDensity.current.fontScale
    val gutter = with(LocalDensity.current) { 16.dp.roundToPx() }
    val bottom = with(LocalDensity.current) { 120.dp.roundToPx() }
    val ready: (MapControl) -> Unit = { control = it }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                PlatformMap(
                    houses = houses, track = TraceDrawing.EMPTY, repeatLook = RepeatLook.CLEAR, check = null, labelSizeSp = markerLabelSizeSp(fontScale), showLocation = false,
                    attribution = MapAttribution(gutter, bottom, shown = true),
                    events = object : MapEvents {
                        override fun onReady(control: MapControl) {
                            control.setCamera(
                                start?.first ?: INDIA_START_LAT, start?.second ?: INDIA_START_LON,
                                if (start != null) 15.0 else INDIA_START_ZOOM, bearing = 0.0,
                            )
                            ready(control)
                        }
                        override fun onStyleLoaded() = Unit
                        override fun onFailed() = Unit
                        override fun onUserGesture() = Unit
                        override fun onCameraIdle(spot: CameraSpot) = Unit
                        override fun onHouseTap(id: String) = Unit
                        override fun onLongPress(lat: Double, lon: Double) = onPick(lat to lon)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.point_pick_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(Res.string.point_pick_hint), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(Res.string.common_cancel))
                            }
                            Button(
                                onClick = { control?.camera()?.let { onPick(it.lat to it.lon) } },
                                enabled = control != null,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { ButtonLabel(stringResource(Res.string.point_pick_use)) }
                        }
                    }
                }
            }
        }
    }
}

// ---- My places ----

/** Settings > My places: the places (up to 10) and *Add place*; a row opens its form. */
@Composable
fun PlacesScreen(onBack: () -> Unit, onOpenPlace: (String) -> Unit) {
    SubScreen(stringResource(Res.string.places_title), onBack) { PlacesList(onOpenPlace) }
}

/**
 * The list of my places with *Add place* (off at the cap, with the cap text under it), the empty state, and a row per
 * place that opens its form.
 */
@Composable
fun PlacesList(onOpenPlace: (String) -> Unit) {
    val repo = LocalAppServices.current.repository
    val places: List<Place>? by remember(repo) { repo.observePlaces() }.collectAsStateWithLifecycle(initialValue = null)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val list = places ?: return@Column
        AddButton(stringResource(Res.string.places_add), list.size < Place.MAX_PLACES, stringResource(Res.string.places_cap)) {
            onOpenPlace(Routes.NEW_RECORD)
        }
        if (list.isEmpty()) {
            HeroEmptyState(
                icon = Icons.Default.Home,
                title = stringResource(Res.string.places_empty_title),
                body = stringResource(Res.string.places_empty_body),
                horizontalPadding = 0.dp,
            )
        }
        list.forEachIndexed { index, place ->
            if (index > 0) HorizontalDivider()
            NavListRow(place.name, "${Formats.coordinate(place.lat)}, ${Formats.coordinate(place.lon)}") { onOpenPlace(place.id) }
        }
    }
}

/** The form of a place, or a new one when [placeId] is null: the name, the point, *Save* and *Delete place*. */
@Composable
fun PlaceFormScreen(placeId: String?, onDone: () -> Unit) {
    val repo = LocalAppServices.current.repository
    val places: List<Place>? by remember(repo) { repo.observePlaces() }.collectAsStateWithLifecycle(initialValue = null)
    val found = placeId?.let { id -> places?.firstOrNull { it.id == id } }
    SubScreen(stringResource(if (placeId == null) Res.string.places_add else Res.string.place_title), onDone) {
        when {
            places == null -> Unit
            placeId != null && found == null -> Text(stringResource(Res.string.place_gone), Modifier.padding(16.dp))
            else -> PlaceForm(placeId, found, places.orEmpty().size, onDone)
        }
    }
}

/**
 * The form behind [PlaceFormScreen] once the place is known: name and point, *Save* (needs a name and a valid point; a
 * refused save at the cap shows the cap message) and *Delete place* after a confirmation.
 */
@Composable
private fun PlaceForm(placeId: String?, found: Place?, liveCount: Int, onDone: () -> Unit) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(placeId) { mutableStateOf(found?.name ?: "") }
    var latText by rememberSaveable(placeId) { mutableStateOf(found?.let { Formats.coordinate(it.lat) } ?: "") }
    var lonText by rememberSaveable(placeId) { mutableStateOf(found?.let { Formats.coordinate(it.lon) } ?: "") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var capped by remember { mutableStateOf(placeId == null && liveCount >= Place.MAX_PLACES) }
    val lat = parseCoordinate(latText, 90.0)
    val lon = parseCoordinate(lonText, 180.0)
    val canSave = name.isNotBlank() && lat != null && lon != null && !busy && !capped

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NameField(name, Place.MAX_NAME) { name = it }
        PointFields(latText, lonText, onLat = { latText = it }, onLon = { lonText = it })
        LiveMessage { if (capped) Text(stringResource(Res.string.places_cap), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val id = placeId ?: repo.newPlaceId()
                        withContext(NonCancellable) { repo.savePlace(Place(id, name.trim(), lat!!, lon!!)) }
                        onDone()
                    } catch (_: RecordLimitException) {
                        capped = true
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { ButtonLabel(stringResource(Res.string.common_save)) }
        if (placeId != null) {
            HorizontalDivider()
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.place_delete))
            }
        }
    }
    if (confirmDelete && placeId != null) {
        ConfirmDelete(
            stringResource(Res.string.place_delete_title), stringResource(Res.string.place_delete_body),
            onDismiss = { confirmDelete = false },
        ) {
            scope.launch {
                withContext(NonCancellable) { repo.deletePlace(placeId) }
                onDone()
            }
        }
    }
}

// ---- The note dialog, and the house page's sections ----

/**
 * Adds or edits an area note. With [note] it edits that note's text (its target stays). Without it, a [street] makes a
 * note for that street, and otherwise the person picks the area among [areas], those that reach the house
 * ([reaching]) first and marked. "At most 200 notes" when the cap is reached.
 */
@Composable
fun AreaNoteDialog(
    title: String,
    note: AreaNote?,
    street: String?,
    areas: List<Area>,
    reaching: List<Area>,
    onDismiss: () -> Unit,
) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(note?.id) { mutableStateOf(note?.text ?: "") }
    val ordered = reaching + areas.filter { a -> reaching.none { it.id == a.id } }
    var areaId by rememberSaveable(note?.id) { mutableStateOf(note?.areaId ?: ordered.firstOrNull()?.id) }
    var capped by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val forArea = note?.areaId != null || (note == null && street == null)
    val canSave = text.isNotBlank() && !busy && (!forArea || areaId != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !forArea -> Text(stringResource(Res.string.note_from_street, street ?: note?.street.orEmpty()))
                    note != null -> Text(noteSource(note, areas))
                    ordered.isEmpty() -> Text(stringResource(Res.string.note_no_areas))
                    else -> {
                        val here = reaching.map { it.id }.toSet()
                        val labels = ordered.map { a ->
                            if (a.id in here) stringResource(Res.string.note_area_here, a.name) else a.name
                        }
                        val current = ordered.indexOfFirst { it.id == areaId }.coerceAtLeast(0)
                        ChoiceMenu(stringResource(Res.string.note_area), labels[current], labels) { areaId = ordered[it].id }
                    }
                }
                val missing = text.isBlank()
                OutlinedTextField(
                    text, { text = it.take(AreaNote.MAX_TEXT) }, label = { Text(stringResource(Res.string.note_text)) },
                    isError = missing, supportingText = if (missing) ({ Text(stringResource(Res.string.note_text_needed)) }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                LiveMessage { if (capped) Text(stringResource(Res.string.notes_cap), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val saved = when {
                                note != null -> note.copy(text = text)
                                forArea -> AreaNote(repo.newAreaNoteId(), areaId = areaId, text = text)
                                else -> AreaNote(repo.newAreaNoteId(), street = street, text = text)
                            }
                            withContext(NonCancellable) { repo.saveAreaNote(saved) }
                            onDismiss()
                        } catch (_: RecordLimitException) {
                            capped = true
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/**
 * The house page's *Area notes* (docs/11 5.23): the notes that reach [house] ([AreaNotes.reaching]), newest first,
 * each with where it comes from, and *Add a note for this street* (only when the house has a street) and *Add a note
 * for an area* (the areas the house is in first).
 */
@Composable
fun HouseAreaNotesSection(house: HouseEntity) {
    val repo = LocalAppServices.current.repository
    val areas: List<Area> by remember(repo) { repo.observeAreas() }.collectAsStateWithLifecycle(emptyList())
    val notes: List<AreaNote> by remember(repo) { repo.observeAreaNotes() }.collectAsStateWithLifecycle(emptyList())
    val point = house.point()
    val reaching = AreaNotes.reaching(point, areas, notes)
    val street = house.street?.trim()?.takeIf { it.isNotEmpty() }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    HorizontalDivider()
    SectionHeading(stringResource(Res.string.notes_heading))
    if (reaching.isEmpty()) Text(stringResource(Res.string.house_area_notes_none), style = MaterialTheme.typography.bodyMedium)
    reaching.forEach { note ->
        Column(Modifier.fillMaxWidth()) {
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
            Text(noteSource(note, areas), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (street != null) {
            OutlinedButton(onClick = { adding = "street" }, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.note_add_street))
            }
        }
        OutlinedButton(onClick = { adding = "area" }, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.note_add_area))
        }
    }
    when (adding) {
        "street" -> AreaNoteDialog(
            stringResource(Res.string.note_add_street), null, street, areas, emptyList(), onDismiss = { adding = null },
        )
        "area" -> AreaNoteDialog(
            stringResource(Res.string.note_add_area), null, null, areas, AreaNotes.areasReaching(point, areas),
            onDismiss = { adding = null },
        )
    }
}

/**
 * The house page's *Distances* (docs/11 5.22): per place, its name, the straight-line km and Plan's walking estimate.
 * Nothing without places or without the house's point.
 */
@Composable
fun HouseDistancesSection(house: HouseEntity) {
    val repo = LocalAppServices.current.repository
    val places: List<Place> by remember(repo) { repo.observePlaces() }.collectAsStateWithLifecycle(emptyList())
    val distances = Distances.toPlaces(house.point(), places)
    if (distances.isEmpty()) return
    HorizontalDivider()
    SectionHeading(stringResource(Res.string.house_distances))
    distances.forEach { d ->
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.place.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.house_distance_value, d.km, d.minutes), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(stringResource(Res.string.house_distances_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
