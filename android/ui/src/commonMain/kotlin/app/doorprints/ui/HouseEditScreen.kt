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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity
import app.doorprints.ui.res.*
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.ai.ListingCut
import app.doorprints.shared.listing.ListingText
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.CostSummary
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.ViewingKind
import app.doorprints.shared.model.LengthUnit
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.DuplicateFlat
import app.doorprints.shared.model.KindFields
import app.doorprints.shared.model.Candidates
import app.doorprints.shared.model.DevKinds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.model.MoveIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Room's answer for the house on this route: null until the first answer, then [house] (null when there is no row).
 * Keeps "not loaded yet" apart from "not on this phone" (UX review, round 21).
 */
private class RoomAnswer(val house: HouseEntity?)

/** How long "Use my current location" waits for a fix. */
private const val LOCATION_TIMEOUT_MS = 15_000L

/** How far below the questions heading a reminder's *Questions* brings into view: more than any screen is tall. */
private const val QUESTIONS_VIEW_PX = 10_000f

/** How the form leaves once its write is done (see the KDoc's "One exit"). */
private sealed interface FormExit {
    /** Back to where the form was opened from. */
    data object Done : FormExit

    /** A new house was saved (or a removed one saved as new): continue on house [id] as an existing house. */
    data class Created(val id: String) : FormExit

    /** This house was deleted: back, and the Map or the list offers *Undo*. */
    data object Deleted : FormExit
}

/**
 * Saves the form's draft (UX review, round 21, major 1): the fields as a flat list of Bundle-safe values (String,
 * Double, Long, Int, Boolean, null), the status by name and the checklist as "key=score" strings. A rotation, the
 * in-app language switch, a dark-mode change or the process being killed while the camera or photo picker is open
 * therefore brings back exactly what was typed. A list that does not decode (an app update between save and restore)
 * restores nothing, and the house is read from Room again.
 */
private val HouseDraftSaver = Saver<HouseEntity?, Any>(
    save = { h ->
        h?.let {
            arrayListOf<Any?>(
                it.id, it.label, it.address, it.street, it.locality, it.lat, it.lon, it.status.name,
                it.price, it.priceType, it.bedrooms, it.rating, it.contactName, it.contactPhone, it.listingUrl,
                it.notes, ArrayList(it.checklist.map { (k, v) -> "$k=$v" }), it.createdAt, it.updatedAt,
                it.deleted, it.dirty,
                // Slice 1a: the area, the location source and the cost's eleven values (null each when unknown).
                it.areaSqft, it.locationSource,
                it.cost?.deposit, it.cost?.depositMonths, it.cost?.maintenance, it.cost?.maintenanceIncluded,
                it.cost?.brokerage, it.cost?.brokerageMonths, it.cost?.lockInMonths, it.cost?.noticeMonths,
                it.cost?.availableFrom, it.cost?.myOffer, it.cost?.agreedPrice,
                // Slice 1b: the broker's record id.
                it.brokerId,
                // Slice 1c: the rooms, as the JSON text Room keeps them in (null for none).
                HouseRooms.encode(it.rooms),
                // Slice 3a: the questions asked, as the JSON text Room keeps them in (null for none).
                HouseAnswers.encode(it.answers),
                // Slice 5: the move-in, as the JSON text Room keeps it in (null for none).
                MoveIn.encode(it.moveIn),
                // S4b-BL-87: the floor (null when unknown). S4b-BL-209: the kind, and the extras the house model does
                // not know as JSON text (a school's values, a newer app's extras), so a rotation keeps them too.
                it.floor, it.kind, it.extrasOther?.toString(),
            )
        }
    },
    restore = { saved -> (saved as? List<*>)?.let(::restoreDraft) },
)

/**
 * The inverse of [HouseDraftSaver]'s save: reads the flat list back into a house by position. Fields added since the
 * first version are read with a default when the list is shorter (a draft saved by an older version); a list that does
 * not fit gives null, so the house is read from Room again.
 */
private fun restoreDraft(v: List<*>): HouseEntity? = runCatching {
    HouseEntity(
        id = v[0] as String,
        label = v[1] as String,
        address = v[2] as String?,
        street = v[3] as String?,
        locality = v[4] as String?,
        lat = v[5] as Double,
        lon = v[6] as Double,
        status = HouseStatus.entries.firstOrNull { it.name == v[7] } ?: HouseStatus.NEW,
        price = v[8] as Long?,
        priceType = v[9] as String?,
        bedrooms = v[10] as Int?,
        rating = v[11] as Int?,
        contactName = v[12] as String?,
        contactPhone = v[13] as String?,
        listingUrl = v[14] as String?,
        notes = v[15] as String?,
        checklist = (v[16] as List<*>).filterIsInstance<String>().mapNotNull { e ->
            val i = e.lastIndexOf('=')
            if (i <= 0) null else e.substring(i + 1).toIntOrNull()?.let { e.substring(0, i) to it }
        }.toMap(),
        createdAt = v[17] as Long,
        updatedAt = v[18] as Long,
        deleted = v[19] as Boolean,
        dirty = v[20] as Boolean,
        areaSqft = v[21] as Int?,
        locationSource = v[22] as String?,
        cost = HouseCost(
            deposit = v[23] as Long?, depositMonths = v[24] as Int?, maintenance = v[25] as Long?,
            maintenanceIncluded = v[26] as Boolean?, brokerage = v[27] as Long?, brokerageMonths = v[28] as Int?,
            lockInMonths = v[29] as Int?, noticeMonths = v[30] as Int?, availableFrom = v[31] as String?,
            myOffer = v[32] as Long?, agreedPrice = v[33] as Long?,
        ).orNull(),
        // Absent in a draft saved by the version before slice 1b: no broker.
        brokerId = v.getOrNull(34) as String?,
        // Absent before slice 1c: no rooms.
        rooms = HouseRooms.decode(v.getOrNull(35) as String?),
        // Absent before slice 3a: no questions.
        answers = HouseAnswers.decode(v.getOrNull(36) as String?),
        // Absent before slice 5: no move-in.
        moveIn = MoveIn.decode(v.getOrNull(37) as String?),
        // Absent before S4b-BL-87: no floor.
        floor = v.getOrNull(38) as Int?,
        // Absent before S4b-BL-209: a house, with no extras it does not know.
        kind = v.getOrNull(39) as String? ?: Candidates.HOUSE_KIND,
        extrasOther = (v.getOrNull(40) as String?)?.let { Json.parseToJsonElement(it) as? JsonObject },
    )
}.getOrNull()

/**
 * A house's form: a new one (from the map or a visit) or an existing one. Common since CMP-6 P6a: the geocoder, the
 * visit alert and the photos (camera, picker, storing) are the app's ([AppServices.houseForm]), the dialler, the
 * browser and the location permission the platform's ([LocalPlatformServices]).
 *
 * **State (UX review, round 21).** The draft, the loaded version it is compared with (`baseline`), the newest row
 * version the user has seen (`seenUpdatedAt`) and a new house's id are `rememberSaveable` ([HouseDraftSaver]), so a
 * rotation, the language switch or process death during the camera hand-off keeps what was typed.
 *
 * **Changed elsewhere (UX-005; whole-app audit).** When sync brings a newer version of the house while the form is
 * open, an untouched form simply shows it. A form with unsaved changes shows a warning, "This house was changed on
 * another device.", with *Show their version* and *Keep mine*; Save waits for the choice, so a stale draft can no
 * longer silently undo the other device's change. Save with nothing changed leaves without writing.
 *
 * **Unsaved changes (UX-005).** `dirty` is `draft != baseline`. While it is set, the back arrow and system Back
 * ([PlatformBackHandler]) ask first: "Leave without saving?" (or "Discard this new house?"), with *Keep editing*, *Discard*
 * ([DangerButton]) and *Save*.
 *
 * **One exit.** A tap on either Save, *Save* in the leave dialog or the delete dialog's *Delete* sets `busy`; a second
 * one is ignored. The screen leaves through a `LifecycleResumeEffect` once the write is done ([FormExit]), so an exit
 * that lands while the app is paused happens on its return. The Room write is `NonCancellable`. A new house's first
 * save does not go back to the map: the form continues on it as an existing house ([FormExit.Created]) with a
 * "Saved" snackbar, so photos can be added straight away (the web's New house → Create → house page).
 *
 * **New house.** The form shows at once with the default name and the coordinates; the reverse geocoder runs beside it
 * ("Finding the address…") and fills the name, street, locality and address only where they are still untouched
 * ([fillPlace]). The location can be corrected: *Use my current location* and Latitude / Longitude fields, right after
 * the street and locality (round 3, the web's order). A refused or approximate-only location shows the shared
 * [LocationPermissionNote] with its next step and the way forward without it (round 4): "Location is off for
 * Doorprints. Allow it, or type the latitude and longitude below." or "Doorprints has only your approximate location.
 * Placing this house needs precise location, or type the latitude and longitude."; only a real failure ("Could not
 * find your location") is in error red.
 *
 * **Width.** The form is at most [ContentMaxWidth] (640 dp, shared with the Assistant and Settings) wide, centred, in
 * landscape and on tablets.
 *
 * **Not on this phone.** An unknown or deleted id shows a not-found state with *Back to your houses*. A house deleted
 * while the form is open shows an ERROR card with *Save as a new house*.
 *
 * **Photos.** A thumbnail opens a full-screen viewer (swipe between photos, "Photo 2 of 5", Close; Back closes and focus
 * returns to the thumbnail). Delete removes the photo from the row at once with a 10 s *Undo* snackbar
 * ([PhotoDeleteViewModel]). While a photo is being added a placeholder tile and "Adding photo…" show and the photo
 * buttons are disabled.
 *
 * **Keyboard.** The form, Settings and the Assistant apply the IME insets (`imePadding`): with edge-to-edge, the
 * window no longer resizes for the keyboard, and fields near the bottom were left under it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
fun HouseEditScreen(
    houseId: String?,
    newLat: Double?,
    newLon: Double?,
    visitId: String?,
    onDone: () -> Unit,
    /** A shared listing's text (docs/11 5.29), parsed into a fresh form once; [onListingConsumed] then clears it. */
    listingText: String? = null,
    onListingConsumed: () -> Unit = {},
    onOpenHouses: () -> Unit = onDone,
    onCreated: (String) -> Unit = { onDone() },
    onDeleted: (String) -> Unit = { onDone() },
    showSaved: Boolean = false,
    onSavedShown: () -> Unit = {},
    /** The Viewings card (slice 3b-1): plan a viewing of this house of a kind, and this house's viewings. */
    onPlanViewing: (houseId: String, kind: ViewingKind) -> Unit = { _, _ -> },
    onOpenViewings: (houseId: String) -> Unit = {},
    /** *Save a copy* after *Close this hunt* (slice 5): the Export screen. */
    onSaveCopy: () -> Unit = {},
    /** A reminder's *Questions* action (S4b-BL-93b): the form opens scrolled to its questions, once. */
    showQuestions: Boolean = false,
    onQuestionsShown: () -> Unit = {},
    /** *Show on map* of a saved walk (docs/11 5.27.6): the Map, which outlines the walk it was asked to show. */
    onShowOnMap: () -> Unit = {},
) {
    val platform = LocalPlatformServices.current
    val services = LocalAppServices.current
    val repo = services.repository
    val form = services.houseForm
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val isNew = houseId == null
    // *Did I walk past this house?* (docs/11 5.27.13): the answer lives here, in memory, until the sheet closes.
    val placeCheck = remember(repo, scope) { PlaceCheckController(scope, repo, { null }, { 0L }) }
    val id = rememberSaveable { houseId ?: Uuid.random().toString() }
    val defaultLabel = stringResource(LocalSpace.current.pages.defaultLabel) // a new candidate is of the space's kind (kinds PR 16)
    val streetLabel = stringResource(LocalSpace.current.pages.defaultLabelStreet)
    val unnamed = stringResource(LocalSpace.current.words.unnamed)
    val touchExploration = { platform.isScreenReaderOn() }

    // What is on screen, and what it was when loaded (or the new-house default); dirty when they differ.
    var draft by rememberSaveable(stateSaver = HouseDraftSaver) { mutableStateOf<HouseEntity?>(null) }
    var baseline by rememberSaveable(stateSaver = HouseDraftSaver) { mutableStateOf<HouseEntity?>(null) }
    // The newest version of the row the user has seen or chosen to override (see "Changed elsewhere").
    var seenUpdatedAt by rememberSaveable { mutableLongStateOf(0L) }
    // A save or delete is running: ignore a second tap. Not saveable: a rotation cancels the coroutine that set it,
    // and a restored true would leave Save disabled for good.
    var busy by remember { mutableStateOf(false) }
    // The write is done: leave, as soon as this screen is resumed.
    var exit by remember { mutableStateOf<FormExit?>(null) }
    LifecycleResumeEffect(exit) {
        when (val e = exit) {
            FormExit.Done -> onDone()
            is FormExit.Created -> onCreated(e.id)
            FormExit.Deleted -> onDeleted(id)
            null -> Unit
        }
        onPauseOrDispose { }
    }

    // Remembered, so recomposition does not start a new Room query each time.
    val answerFlow = remember(id) { if (isNew) flowOf(RoomAnswer(null)) else repo.house(id).map { RoomAnswer(it) } }
    val answer: RoomAnswer? by answerFlow.collectAsStateWithLifecycle(initialValue = null)
    // The live row; a tombstone counts as gone.
    val saved = answer?.house?.takeIf { !it.deleted }
    // Existing-house route, Room has answered, and there is no live row.
    val gone = !isNew && answer != null && saved == null
    val notFound = gone && draft == null
    // Deleted while the form was open (not by this form's own Delete, which sets busy and leaves).
    val removed = gone && draft != null && !busy
    val dirty = draft != null && baseline != null && draft != baseline && !removed
    // A newer version arrived while there were unsaved changes: the user chooses (see the KDoc).
    val conflict = !isNew && !busy && dirty && saved != null && saved.updatedAt > seenUpdatedAt
    // The candidate's kind (kinds PR 5, S4b-BL-209): the house, or the school while the developer flag is on.
    val devKinds = LocalDevKinds.current; val kindsOn = kindsOn(); val space = LocalSpace.current // a new candidate is of the space's kind (kinds PR C)
    val kind = DevKinds.of(draft?.kind ?: space.creating.id, kindsOn) // before the row is loaded, the space's kind (a new candidate's, a missing one's)
    val L = kind.labels
    val pages = PageWords.of(kind.id) // the house words left on this candidate's kind's page (kinds PR 16)
    placeCheck.state?.let { st ->
        PlaceCheckSheet(st, { overlay -> placeCheck.close(); ShowOnMap.show(MapFocus(overlay, seconds = 10)); onShowOnMap() }, {}, { placeCheck.close() }, pages)
    }

    // The typed latitude / longitude while it differs from the draft's (null: show the draft's value). Cleared
    // wherever the draft is replaced from a stored row.
    var latText by rememberSaveable { mutableStateOf<String?>(null) }
    var lonText by rememberSaveable { mutableStateOf<String?>(null) }

    // A new house: the form at once, the address when (if) the geocoder answers.
    var geocoding by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (!isNew) return@LaunchedEffect
        if (draft == null) {
            val now = nowMillis()
            val fresh = HouseEntity(
                id = id, label = defaultLabel, lat = newLat ?: 0.0, lon = newLon ?: 0.0,
                // Where the point came from (FR-068): a stay alert brings the phone's own fix, the map a tap or the
                // crosshair; a form opened with no point has no source until *Use my current location*.
                locationSource = when {
                    newLat == null || newLon == null -> null
                    visitId != null -> LocationSource.GPS
                    else -> LocationSource.MAP
                },
                createdAt = now, updatedAt = now,
            ).let { if (space.creating.id == "house") it else it.forKind(space.creating) }
            draft = fresh
            baseline = fresh
        }
        val start = baseline ?: return@LaunchedEffect
        // Restored after a rotation with the address already there, or no real coordinates to look up.
        if (!start.street.isNullOrBlank() || !start.address.isNullOrBlank() || newLat == null || newLon == null) {
            return@LaunchedEffect
        }
        geocoding = true
        val place = try {
            form.reverseGeocode(start.lat, start.lon)
        } finally {
            geocoding = false
        }
        val d = draft ?: return@LaunchedEffect
        val b = baseline ?: return@LaunchedEffect
        if (place != null) {
            val (filledDraft, filledBaseline) = fillPlace(d, b, place, defaultLabel) { formatPositional(streetLabel, it) }
            draft = filledDraft
            baseline = filledBaseline
        }
    }
    // An existing house: the first live row Room gives, then every newer one (see "Changed elsewhere"). Keyed on dirty
    // too, so a form whose edits were undone by hand takes a version that arrived meanwhile.
    LaunchedEffect(saved, dirty) {
        val row = saved ?: return@LaunchedEffect
        if (draft == null) {
            draft = row
            baseline = row
            seenUpdatedAt = row.updatedAt
            latText = null
            lonText = null
            return@LaunchedEffect
        }
        if (busy || row.updatedAt <= seenUpdatedAt) return@LaunchedEffect
        if (draft == baseline) {
            draft = row
            baseline = row
            seenUpdatedAt = row.updatedAt
            // A typed coordinate that never parsed ("1x") left the draft untouched: it goes with the old version, or
            // its error would stay and keep Save disabled over the other device's coordinates.
            latText = null
            lonText = null
        }
    }

    val visitsFlow = remember(id) { repo.visitsFor(id) }
    val visits by visitsFlow.collectAsStateWithLifecycle(emptyList())
    val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
    val brokers: List<Pair<String, Broker>> by remember(repo) { repo.observeBrokers() }.collectAsStateWithLifecycle(emptyList())
    // Every live house, for the duplicate-flat warning (S4b-BL-85) under the floor.
    val allHouses: List<HouseEntity> by remember(repo) { repo.houses }.collectAsStateWithLifecycle(emptyList())
    // The effective scoring (docs/11 5.4, slice 2): which criteria the checklist shows, the score and its coverage line.
    val scoring: Scoring by remember(repo) { repo.observeScoring() }.collectAsStateWithLifecycle(Scoring.DEFAULT)
    // How the rooms' sizes are shown and typed (slice 1c, this phone's setting).
    val lengthUnit by remember(repo) { repo.settings.lengthUnit }.collectAsStateWithLifecycle(LengthUnit.FT)
    // The question bank (slice 3a), for *Add the usual questions* and *Add a question*.
    val questionBank: List<Question> by remember(repo) { repo.observeQuestions() }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmVisitDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pasteMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val fill = remember { ListingFillState() } // the last fill, for Undo fill and the marks (S4b-BL-238)
    // A shared listing (docs/11 5.29): the no-AI parser fills the fresh form once; the summary says what it did. The
    // text is cleared by the caller as soon as it is taken, so a recreated screen does not fill the form again.
    LaunchedEffect(listingText) {
        val text = listingText ?: return@LaunchedEffect
        if (!isNew) return@LaunchedEffect
        val current = draft ?: return@LaunchedEffect
        onListingConsumed()
        val parsed = ListingText.parse(text)
        val merged = mergeListing(current, parsed, labelIsPlaceholder = current.label == defaultLabel)
        draft = merged.house.also { fill.record(current, merged) }
        pasteMessage = pasteResultText(merged, parsed.warnings)
    }
    val latInvalid = latText?.let { parseCoordinate(it, 90.0) == null } == true
    val lonInvalid = lonText?.let { parseCoordinate(it, 180.0) == null } == true
    var locating by remember { mutableStateOf(false) }
    var locationFailed by rememberSaveable { mutableStateOf(false) }
    // Location was refused, or only approximate location was allowed: the shared note under *Use my current location*.
    var locationDenied by rememberSaveable { mutableStateOf(false) }
    // What the location source was before *Approximate location* was turned on, for turning it off (FR-068).
    var sourceBeforeApprox by rememberSaveable { mutableStateOf<String?>(null) }
    val canSave = !busy && !removed && draft != null && !conflict && !latInvalid && !lonInvalid

    LaunchedEffect(dirty) { if (!dirty) confirmLeave = false }

    // Slice 5 (docs/11 5.24): choosing Taken asks *Mark the other houses Not chosen?*; the answer is carried out when the
    // house is saved. *Close this hunt* confirms with the number of houses, then offers *Save a copy*.
    var askMarkOthers by rememberSaveable { mutableStateOf(false) }
    var markOthersOnSave by rememberSaveable { mutableStateOf(false) }
    var confirmClose by rememberSaveable { mutableStateOf<Int?>(null) }
    var closedCount by rememberSaveable { mutableStateOf<Int?>(null) }

    fun update(transform: (HouseEntity) -> HouseEntity) {
        draft = draft?.let(transform)
    }

    fun save() {
        val d = draft ?: return
        if (!canSave) return
        // Nothing changed: leave without writing, so an untouched form never re-stamps the house (UX-005).
        if (!isNew && !dirty) {
            exit = FormExit.Done
            return
        }
        busy = true
        val toSave = d.copy(label = d.label.ifBlank { unnamed }).withInRangeExtras(kind.fields) // a number outside the kind file's range is not saved (S4b-BL-261)
        scope.launch {
            withContext(NonCancellable) {
                repo.saveHouse(toSave)
                // *Mark them Not chosen* (slice 5): the other houses, once this one is saved TAKEN.
                if (markOthersOnSave && toSave.status == HouseStatus.TAKEN) repo.markOthersNotChosen(toSave.id)
                if (visitId != null) {
                    repo.getVisit(visitId)?.let { repo.saveVisit(it.copy(houseId = toSave.id)) }
                    // The "Are you at a house?" alert is answered: tapping it again must not open a second form.
                    form.clearVisitAlert(visitId)
                }
            }
            draft = toSave
            baseline = toSave
            markOthersOnSave = false
            exit = if (isNew) FormExit.Created(toSave.id) else FormExit.Done
        }
    }

    // Removed on another device while open: keep the typed work as a new house.
    fun saveAsNew() {
        val d = draft ?: return
        if (busy) return
        busy = true
        val now = nowMillis()
        val copy = d.copy(
            id = Uuid.random().toString(), label = d.label.ifBlank { unnamed }, deleted = false,
            createdAt = now, updatedAt = now,
        ).withInRangeExtras(kind.fields)
        scope.launch {
            withContext(NonCancellable) { repo.saveHouse(copy) }
            exit = FormExit.Created(copy.id)
        }
    }

    // The back arrow and system Back: ask first when there are unsaved changes; nothing while a save runs.
    fun leave() {
        when {
            busy -> Unit
            dirty -> confirmLeave = true
            else -> onDone()
        }
    }
    PlatformBackHandler(enabled = dirty || busy) { leave() }

    val snackbar = remember { SnackbarHostState() }
    // This house's photos (HousePhotos.kt): the strip, the viewer, delete with Undo in this snackbar.
    val photos = rememberHousePhotos(id, snackbar)
    // A reminder's *Questions* (S4b-BL-93b): once the form is drawn, its questions heading goes to the top of the
    // screen (a box taller than the screen from the heading down, so the questions show under it, not the fields above).
    val questionsView = remember { BringIntoViewRequester() }
    LaunchedEffect(showQuestions, draft != null) {
        if (!showQuestions || draft == null) return@LaunchedEffect
        withFrameNanos { }
        questionsView.bringIntoView(Rect(0f, 0f, 1f, QUESTIONS_VIEW_PX))
        onQuestionsShown()
    }
    // "Use my current location": asks for the permission first when needed, then tries again once. The "asked" flag
    // is shared with the Map and the Assistant (LocationPermission.kt). After a refusal, or an approximate-only
    // answer, the note under the button ([LocationPermissionNote], round 3) says why and offers the next step:
    // *Allow location* / *Turn on precise location* while Android will ask, *Open settings* once it will not.
    val locationAsk = rememberLocationAsk()
    var locationGrants by remember { mutableIntStateOf(0) }
    val askLocation = rememberLocationPermissionRequest {
        locationAsk.refresh()
        // Precise location is what the form needs. Approximate alone shows the note ("only your approximate
        // location", with *Turn on precise location*) and does not start the request by itself, so it cannot loop.
        if (platform.locationAccess() == LocationAccess.PRECISE) {
            locationDenied = false
            locationGrants++
        } else {
            locationDenied = true
        }
    }
    fun useMyLocation() {
        if (locating) return
        if (platform.locationAccess() != LocationAccess.PRECISE) {
            locationFailed = false
            if (locationAsk.canAsk) {
                locationAsk.markAsked()
                askLocation()
            } else {
                locationDenied = true
            }
            return
        }
        locating = true
        locationFailed = false
        locationDenied = false
        scope.launch {
            val here = try {
                withTimeoutOrNull(LOCATION_TIMEOUT_MS) { services.location.current() }
            } finally {
                locating = false
            }
            if (here == null) {
                locationFailed = true
            } else {
                update { it.copy(lat = here.first, lon = here.second, locationSource = LocationSource.GPS) }
                latText = null
                lonText = null
            }
        }
    }
    LaunchedEffect(locationGrants) { if (locationGrants > 0) useMyLocation() }

    // "Saved": the first save of a new house continues here as an existing house (Root passes showSaved once).
    val savedText = stringResource(Res.string.house_saved)
    LaunchedEffect(showSaved) {
        if (showSaved) {
            onSavedShown()
            scope.launch { snackbar.showSnackbar(savedText) }
        }
    }

    val saveLabel = stringResource(Res.string.common_save)
    val savingLabel = stringResource(Res.string.house_saving)
    Scaffold(
        // Above the keyboard, so "Photo deleted / Undo" is not hidden under it.
        snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding()) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                notFound -> pages.notFoundTitle
                                isNew -> L.refText("kind.new")
                                else -> pages.detailsTitle
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back))
                    }
                },
                actions = {
                    // No Save or Delete for a house that is not on this phone.
                    if (!notFound) {
                        if (!isNew) IconButton(onClick = { confirmDelete = true }, enabled = !busy && !removed) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(L.refText("kind.delete")))
                        }
                        TextButton(
                            onClick = { save() },
                            enabled = canSave,
                            modifier = if (busy) Modifier.semantics { contentDescription = savingLabel } else Modifier,
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(saveLabel)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (notFound) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                HeroEmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(pages.notFound),
                    body = stringResource(Res.string.house_not_found_body),
                    horizontalPadding = 0.dp,
                    action = {
                        Button(onClick = onOpenHouses, modifier = Modifier.heightIn(min = 48.dp)) {
                            ButtonLabel(stringResource(Res.string.house_back_to_list))
                        }
                    },
                )
            }
            return@Scaffold
        }
        val d = draft
        if (d == null) {
            // Named, so TalkBack says what is loading rather than a bare "in progress" (Wave D).
            val loading = stringResource(Res.string.house_loading)
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
            }
            return@Scaffold
        }

        // consumeWindowInsets, then imePadding: the keyboard's height less what the bars already take, so the
        // scroll viewport ends at the keyboard and bring-into-view keeps the focused field above it. The scroll is
        // the full width (a drag beside the form still scrolls it); the form itself is at most 640 dp wide and
        // centred (round 3), as the web's container is, so landscape and tablets do not stretch the fields.
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // One item of the form's 12 dp rhythm: the "removed" and "changed elsewhere" cards and the paste result
                // sit in live regions that are there (1 dp tall) before a message arrives, so TalkBack reads it.
                Column {
                    LiveMessage(assertive = removed) {
                        when {
                            removed -> ResultCard(
                                tone = ResultTone.ERROR,
                                text = stringResource(Res.string.house_removed_while_open),
                                modifier = Modifier.padding(bottom = 12.dp),
                                actions = {
                                    ResultActionsRow {
                                        TextButton(
                                            onClick = { saveAsNew() },
                                            enabled = !busy,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { ButtonLabel(stringResource(Res.string.house_save_as_new)) }
                                    }
                                },
                            )
                            conflict -> Column(Modifier.padding(bottom = 12.dp)) {
                                WarnNote(stringResource(Res.string.house_changed_elsewhere))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            val row = saved
                                            if (row != null) {
                                                draft = row
                                                baseline = row
                                                seenUpdatedAt = row.updatedAt
                                                latText = null
                                                lonText = null
                                            }
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { ButtonLabel(stringResource(Res.string.house_show_theirs)) }
                                    TextButton(
                                        onClick = { saved?.let { seenUpdatedAt = it.updatedAt } },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { ButtonLabel(stringResource(Res.string.house_keep_mine)) }
                                }
                            }
                        }
                    }
                    // The developer's kind picker (S4b-BL-209): never drawn while the flag is off.
                    if (isNew && devKinds && !space.multi) DevKindPicker(DevKinds.creatable(true), kind.id) { k -> update { it.forKind(k) } }
                    if (aiEnabled && kind.extracts) {
                        // The entry point's one name, as on the web and in docs/12 G.1: not "Paste listing".
                        OutlinedButton(
                            onClick = { showPaste = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { ButtonLabel(stringResource(Res.string.house_paste_title)) }
                        // The result line, Undo fill and the "from the listing" marks (ListingFillUndo.kt, S4b-BL-238).
                        PasteResult(pasteMessage, fill, draft, kind.fields, onDismiss = { pasteMessage = null },
                            onUndo = { draft = it; fill.clear(); scope.launch { pasteMessage = getString(Res.string.house_paste_undone) } })
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(d.label, { v -> update { it.copy(label = v) } },
                        label = { Text(stringResource(L.fieldText("label"))) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    LiveMessage {
                        if (geocoding) {
                            Text(
                                stringResource(Res.string.house_finding_address),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                // Status: three whole-row radios (whole-app audit). The segmented row broke "நிராகரிக்கப்பட்டது" and
                // "తిరస్కరించబడింది" mid-word at 100 %; rows wrap in every script. The glyph (UX-002) is not read out.
                SectionHeading(stringResource(L.fieldText("status")))
                Column(Modifier.selectableGroup()) {
                    HouseStatus.entries.forEach { s ->
                        val text = stringResource(L.statusText(s))
                        val color = s.color()
                        RadioRow(
                            label = buildAnnotatedString {
                                withStyle(SpanStyle(color = color)) { append(s.glyph) }
                                append(" ")
                                append(text)
                            },
                            hint = null,
                            selected = d.status == s,
                            horizontalPadding = 0.dp,
                            spokenLabel = text,
                        ) {
                            val was = d.status
                            update { it.copy(status = s) }
                            if (s != HouseStatus.TAKEN) {
                                markOthersOnSave = false
                            } else if (was != HouseStatus.TAKEN) {
                                // Only when there is someone to mark: the dialog would ask about nothing otherwise.
                                scope.launch { if (repo.closeTargetCount(id) > 0) askMarkOthers = true }
                            }
                        }
                    }
                }

                SectionHeading(stringResource(L.fieldText("rating")))
                // The stars, then Clear rating (the web's `house.clearRating`), always there so nothing jumps when a star
                // is picked; disabled while there is no rating. Tap-again on the chosen star still clears it too.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RatingRow(d.rating) { star -> update { it.copy(rating = if (it.rating == star) null else star) } }
                    TextButton(
                        onClick = { update { it.copy(rating = null) } },
                        enabled = d.rating != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { ButtonLabel(stringResource(Res.string.house_clear_rating)) }
                }

                // Rent or buy: one of two short labels, so the single-choice segmented row stays (round 21). Not for a
                // kind that hides its own money (S4b-BL-209: a school's fees are extras).
                if (L.isVisible("field.money")) {
                    val rent = d.priceType != "SALE"
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = rent, onClick = { update { it.copy(priceType = "RENT") } },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text(stringResource(L.refText("cadence.month"))) }
                        SegmentedButton(
                            selected = !rent, onClick = { update { it.copy(priceType = "SALE") } },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text(stringResource(L.refText("cadence.once"))) }
                    }
                    // The amount as the app shows it, live, in the app language: ₹1,00,00,000 or ₹25,000 / month, so a
                    // missing or extra zero is visible while typing. Always there (empty at first), so the row does not grow.
                    val preview = priceText(d.price, d.priceType).orEmpty()
                    OutlinedTextField(
                        d.price?.toString() ?: "", { v -> update { it.copy(price = v.filter(Char::isDigit).take(12).toLongOrNull()) } },
                        label = { Text(stringResource(L.fieldText("money", if (d.priceType == "SALE") "once" else "month"))) },
                        supportingText = { Text(preview) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // The Details extras (kinds PR 4, S4b-BL-208): BHK beside the carpet area (slice 1a), the price having the
                // row above to itself with its preview, then the floor (S4b-BL-87) with its Basement switch (S4b-BL-104 c),
                // drawn from the kind file (KindExtrasFields.kt).
                KindExtrasFields(kind.fields, "details", d, ::update)
                // Non-blocking: another house within about 30 m with the same bedrooms and floor (docs/11 5.25).
                val sameFlat = remember(d.id, d.lat, d.lon, d.locationSource, d.bedrooms, d.rooms, d.floor, allHouses) {
                    DuplicateFlat.of(d.flatFacts(), allHouses.map { it.flatFacts() })
                        .mapNotNull { other -> allHouses.firstOrNull { it.id == other }?.label?.ifBlank { unnamed } }
                }
                DuplicateFlatWarning(sameFlat)

                // The real cost of the house (docs/11 5.21, slice 1a): the fields, then what they add up to.
                SectionHeading(stringResource(L.sectionText("cost")))
                CostSection(d, ::update, kind.fields)

                // The rooms with their sizes and condition (docs/11 5.6, slice 1c), after the cost.
                if (L.isVisible("section.rooms")) RoomsSection(d.rooms, lengthUnit) { rooms -> update { it.copy(rooms = rooms) } }

                // The questions to ask at the viewing (docs/11 5.5, slice 3a), after the rooms.
                if (L.isVisible("section.questions")) QuestionsSection(d.answers, questionBank, d.priceType, d.cost, Modifier.bringIntoViewRequester(questionsView)) { answers ->
                    update { it.copy(answers = answers) }
                }

                OutlinedTextField(d.address ?: "", { v -> update { it.copy(address = v) } },
                    label = { Text(stringResource(L.fieldText("address"))) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth())
                PairOrStack(
                    first = { m ->
                        OutlinedTextField(d.street ?: "", { v -> update { it.copy(street = v.ifBlank { null }) } },
                            label = { Text(stringResource(L.fieldText("street"))) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            singleLine = true, modifier = m)
                    },
                    second = { m ->
                        OutlinedTextField(d.locality ?: "", { v -> update { it.copy(locality = v.ifBlank { null }) } },
                            label = { Text(stringResource(L.fieldText("locality"))) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            singleLine = true, modifier = m)
                    },
                )

                // Where the house is, and a way to correct it (whole-app audit; the web's coordinate fields and "Use my
                // location", WCAG 2.5.7): a house saved from a stale fix or a stray long-press is no longer stuck there.
                // Right after the address (round 3), in the web's order: Details, then Location. A mini-map with a
                // draggable pin is a Sprint 4b item (README section 8).
                SectionHeading(stringResource(L.sectionText("location")))
                OutlinedButton(
                    onClick = { useMyLocation() },
                    enabled = !locating,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (locating) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    ButtonLabel(stringResource(Res.string.house_use_location))
                }
                LiveMessage {
                    when {
                        locating -> Text(stringResource(Res.string.common_finding_location), style = MaterialTheme.typography.bodySmall)
                        // Refused, or approximate only: the shared note (amber, not error red: it was the user's choice),
                        // with the next step visible (*Allow location*, *Turn on precise location* or *Open settings*).
                        // Both texts offer typing the coordinates below (round 4, as the web's house.locationDenied).
                        // The note's button asks again or opens the settings, as its label says.
                        locationDenied && !locationAsk.granted -> LocationPermissionNote(
                            ask = locationAsk,
                            deniedText = stringResource(Res.string.house_location_denied),
                            approximateText = approximateLocationText(pages.needsPrecise),
                            launchRequest = { askLocation() },
                        )
                        locationFailed -> Text(
                            stringResource(Res.string.house_location_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                PairOrStack(
                    first = { m ->
                        OutlinedTextField(
                            latText ?: Formats.coordinate(d.lat),
                            { v ->
                                latText = v
                                parseCoordinate(v, 90.0)?.let { lat -> update { it.copy(lat = lat) } }
                            },
                            label = { Text(stringResource(Res.string.house_lat)) },
                            isError = latInvalid,
                            supportingText = if (latInvalid) {
                                { Text(stringResource(Res.string.house_lat_range)) }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            singleLine = true, modifier = m,
                        )
                    },
                    second = { m ->
                        OutlinedTextField(
                            lonText ?: Formats.coordinate(d.lon),
                            { v ->
                                lonText = v
                                parseCoordinate(v, 180.0)?.let { lon -> update { it.copy(lon = lon) } }
                            },
                            label = { Text(stringResource(Res.string.house_lon)) },
                            isError = lonInvalid,
                            supportingText = if (lonInvalid) {
                                { Text(stringResource(Res.string.house_lon_range)) }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            singleLine = true, modifier = m,
                        )
                    },
                )
                // The spot is approximate (FR-068): a hollow marker, and no Hunt-mode alert here. Off goes back to the
                // source the point had before (the map's when it had none).
                val approx = d.locationSource == LocationSource.APPROX
                SwitchRow(
                    text = stringResource(L.fieldText("locationSource")),
                    hint = stringResource(Res.string.house_approx_hint),
                    checked = approx,
                    horizontalPadding = 0.dp,
                ) { on ->
                    if (on) {
                        sourceBeforeApprox = d.locationSource
                        update { it.copy(locationSource = LocationSource.APPROX) }
                    } else {
                        update { it.copy(locationSource = sourceBeforeApprox ?: LocationSource.MAP) }
                    }
                }

                // *Did I walk past this house?* (docs/11 5.27.13): on demand, from the house's saved spot; an area-only house
                // answers that it has no exact spot; a house not yet saved has no button. Then its saved walks (5.27.6).
                if (!isNew) {
                    OutlinedButton(
                        onClick = { placeCheck.start(PlaceKind.HOUSE, d.lat, d.lon, approximate = d.locationSource == LocationSource.APPROX) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(FootprintsIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        ButtonLabel(stringResource(pages.walkButton))
                    }
                    if (L.isVisible("section.walks")) SavedWalksCard(id, pages.walksEmpty, onShowOnMap)
                }

                if (L.isVisible("section.checklist")) ChecklistSection(d.checklist, d.rating, scoring) { m -> update { it.copy(checklist = m) } }

                // Multi-line: the Enter key starts a new line here, so no Next action; sentences start with a capital.
                OutlinedTextField(d.notes ?: "", { v -> update { it.copy(notes = v) } },
                    label = { Text(stringResource(L.fieldText("notes"))) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 3, modifier = Modifier.fillMaxWidth())

                SectionHeading(stringResource(Res.string.house_contact))
                // A house with a broker shows the broker's name and phone, which follow the broker (slice 1b): they are
                // edited on the broker's page, or the broker is set to None here.
                val linkedBroker = d.brokerId != null && brokers.any { it.first == d.brokerId }
                OutlinedTextField(d.contactName ?: "", { v -> update { it.copy(contactName = v) } },
                    label = { Text(stringResource(L.fieldText("contactName"))) },
                    readOnly = linkedBroker,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(d.contactPhone ?: "", { v -> update { it.copy(contactPhone = v) } },
                        label = { Text(stringResource(L.fieldText("contactPhone"))) },
                        readOnly = linkedBroker,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        singleLine = true, modifier = Modifier.weight(1f))
                    // A local: HouseEntity is in :shared since CMP-4 P4a, and Kotlin does not smart-cast another
                    // module's public property.
                    val phone = d.contactPhone
                    if (!phone.isNullOrBlank()) {
                        val callDesc = stringResource(Res.string.house_call_desc, phone)
                        TextButton(
                            onClick = { platform.dial(phone.filter { it.isDigit() || it == '+' }) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = callDesc },
                        ) { Text(stringResource(Res.string.house_call)) }
                    }
                }
                // The last text field: Done closes the keyboard. *Open* (the web's "Open the listing") when it is a link.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(d.listingUrl ?: "", { v -> update { it.copy(listingUrl = v) } },
                        label = { Text(stringResource(L.fieldText("listingUrl"))) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        singleLine = true, modifier = Modifier.weight(1f))
                    val link = d.listingUrl?.trim()?.takeIf { isWebLink(it) }
                    if (link != null) {
                        val openDesc = stringResource(Res.string.house_listing_open_desc)
                        val openFailed = stringResource(Res.string.house_listing_open_failed)
                        TextButton(
                            onClick = {
                                if (!platform.openUrl(link)) scope.launch { snackbar.showSnackbar(openFailed) }
                            },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = openDesc },
                        ) { Text(stringResource(Res.string.house_listing_open)) }
                    }
                }

                // The broker (docs/11 5.25, slice 1b) right after the contact: pick one, or none, or make a new one.
                if (L.isVisible("field.brokerId")) BrokerSection(
                    brokerId = d.brokerId,
                    brokers = brokers,
                    onPick = { picked ->
                        update {
                            if (picked == null) {
                                // None: the contact it holds stays and can be edited. A phone number still finds or
                                // makes its broker when the house is saved; clear the number to stay unlinked.
                                it.copy(brokerId = null)
                            } else {
                                it.copy(brokerId = picked.first, contactName = picked.second.name, contactPhone = picked.second.phone)
                            }
                        }
                    },
                    onCreate = { broker ->
                        scope.launch {
                            val newId = repo.saveBroker(broker)
                            update { it.copy(brokerId = newId, contactName = broker.name, contactPhone = broker.phone) }
                        }
                    },
                )

                HousePhotosSection(
                    photos = photos,
                    isNew = isNew,
                    isSaved = saved != null,
                    canSave = canSave,
                    onSave = { save() },
                    name = d.label.ifBlank { unnamed },
                    rooms = d.rooms,
                    savePhotosFirst = pages.savePhotosFirst,
                )

                HorizontalDivider()
                // A FlowRow, so *I'm here now* goes under the heading when both do not fit (Wave D: in a Row the
                // button's Tamil label at 200 % squeezed the heading to no width).
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(L.sectionText("visits"), visits.size), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    if (saved != null) {
                        val recorded = stringResource(Res.string.house_visit_recorded)
                        val recent = stringResource(Res.string.house_visit_recent)
                        // The stored house, not the draft: a visit is recorded where the house is saved, never at a
                        // typed coordinate or street that has not been saved.
                        val storedHouse: HouseEntity = saved
                        TextButton(
                            onClick = {
                                // A second tap within ten minutes does not add a duplicate visit; either way the snackbar
                                // (a polite live region) says what happened, as the web's "Visit recorded".
                                val tooSoon = visitIsRecent(visits.maxOfOrNull { it.arrivedAt }, nowMillis())
                                scope.launch {
                                    if (!tooSoon) repo.markVisitedNow(storedHouse)
                                    snackbar.showSnackbar(if (tooSoon) recent else recorded)
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(Res.string.house_here_now)) }
                    }
                }
                visits.forEach { v ->
                    key(v.id) {
                        val source = stringResource(if (v.source.name == "AUTO") Res.string.visit_auto else Res.string.visit_manual)
                        val text = v.leftAt?.let {
                            stringResource(Res.string.visit_line_duration, v.arrivedAt.dateText(), ((it - v.arrivedAt) / 60_000).toInt(), source)
                        } ?: stringResource(Res.string.visit_line, v.arrivedAt.dateText(), source)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            // A visit Hunt mode recorded by mistake can be deleted (the web's "Remove this visit").
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(Res.string.house_visit_more, v.arrivedAt.dateText()),
                                    )
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.house_visit_delete)) },
                                        onClick = {
                                            menu = false
                                            confirmVisitDelete = v.id
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // The Viewings card (docs/11 5.8, slice 3b-1), once the house is saved: a viewing names a stored house.
                if (saved != null) {
                    if (L.isVisible("section.viewings")) ViewingsCard(
                        houseId = id, visits = visits,
                        onPlan = { kind -> onPlanViewing(id, kind) },
                        onOpenAll = { onOpenViewings(id) },
                    )
                    // The area notes that reach the house and its distances to my places (docs/11 slice 4a), from the
                    // saved point and street.
                    if (L.isVisible("section.areaNotes")) HouseAreaNotesSection(saved, pages.areaNotesNone)
                    if (L.isVisible("section.distances")) HouseDistancesSection(saved)
                }

                // Moving in (docs/11 5.24, slice 5): the card of a TAKEN house, after the Viewings.
                if (d.status == HouseStatus.TAKEN && L.isVisible("section.moveIn")) {
                    MovingInCard(
                        moveIn = d.moveIn,
                        onChange = { m -> update { it.copy(moveIn = m) } },
                        rooms = d.rooms,
                        photos = photos.shown,
                        canAddPhoto = photos.canAdd && saved != null,
                        addingPhoto = photos.adding,
                        onAddPhoto = photos.takeMoveInPhoto,
                        onOpenPhoto = photos.openViewer,
                        closeEnabled = !busy && saved?.status == HouseStatus.TAKEN,
                        onCloseHunt = { scope.launch { confirmClose = repo.closeTargetCount(id) } },
                    )
                }

                Button(onClick = { save() }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(saveLabel)
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        HousePhotoViewer(photos, name = d.label.ifBlank { unnamed }, rooms = d.rooms)
    }

    if (confirmLeave && dirty) {
        // UX-005: the web's canDeactivate question, in the app language (`confirm.leaveUnsaved`, `house.discard`).
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = {
                Text(stringResource(if (isNew) pages.discardNew else Res.string.house_leave_title))
            },
            text = { Text(stringResource(Res.string.house_unsaved_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        save()
                    },
                    enabled = canSave,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(saveLabel) }
            },
            dismissButton = {
                // M3 lays both slots out in one wrapping row: Keep editing, Discard, then Save.
                TextButton(onClick = { confirmLeave = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.house_keep_editing))
                }
                DangerButton(
                    text = stringResource(Res.string.house_discard),
                    onClick = {
                        confirmLeave = false
                        onDone()
                    },
                )
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(pages.deleteTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.house_delete_confirm_body))
                    // A saved walk is the house's: it goes with it (docs/11 5.27.6).
                    Text(stringResource(Res.string.trace_house_delete_note))
                }
            },
            confirmButton = {
                DangerButton(
                    text = stringResource(Res.string.common_delete),
                    enabled = !busy,
                    onClick = {
                        // Closed first, and busy before the coroutine starts: a second tap has nothing to hit.
                        confirmDelete = false
                        if (!busy) {
                            busy = true
                            scope.launch {
                                withContext(NonCancellable) { repo.deleteHouse(id) }
                                exit = FormExit.Deleted
                            }
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    confirmVisitDelete?.let { visitToDelete ->
        AlertDialog(
            onDismissRequest = { confirmVisitDelete = null },
            title = { Text(stringResource(Res.string.house_visit_delete_title)) },
            text = { Text(stringResource(Res.string.house_visit_delete_body)) },
            confirmButton = {
                DangerButton(
                    text = stringResource(Res.string.common_delete),
                    onClick = {
                        confirmVisitDelete = null
                        scope.launch { withContext(NonCancellable) { repo.deleteVisit(visitToDelete) } }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmVisitDelete = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    if (askMarkOthers) {
        AlertDialog(
            onDismissRequest = { askMarkOthers = false },
            title = { Text(stringResource(Res.string.taken_others_title)) },
            text = { Text(stringResource(Res.string.taken_others_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askMarkOthers = false
                        markOthersOnSave = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.taken_mark_them)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askMarkOthers = false
                        markOthersOnSave = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.taken_keep_them)) }
            },
        )
    }

    confirmClose?.let { count ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text(stringResource(Res.string.movein_close_title)) },
            text = { Text(stringResource(Res.string.movein_close_body, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClose = null
                        scope.launch { closedCount = withContext(NonCancellable) { repo.markOthersNotChosen(id) } }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.movein_close)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    closedCount?.let { count ->
        // Then the offer of a readable copy of everything (docs/11 5.24): *Save a copy* opens the Export screen.
        AlertDialog(
            onDismissRequest = { closedCount = null },
            text = { Text(stringResource(Res.string.movein_closed, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        closedCount = null
                        onSaveCopy()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.settings_export)) }
            },
            dismissButton = {
                TextButton(onClick = { closedCount = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_close))
                }
            },
        )
    }

    HousePhotoDetails(photos, rooms = draft?.rooms)

    if (showPaste) {
        PasteListingDialog(
            onDismiss = { showPaste = false }, kind = pasteKind(kind),
            onDraft = { draftFromAi, warnings ->
                draft?.let { current ->
                    // The name is still the form's own (the default, or the geocoder's "House on …") on a new house.
                    val placeholder = current.label.isBlank() || current.label == defaultLabel ||
                        (isNew && current.label == baseline?.label)
                    val merged = mergeListingExtras(mergeListing(current, draftFromAi, labelIsPlaceholder = placeholder), draftFromAi, kind.fields)
                    draft = merged.house.also { fill.record(current, merged) }
                    // Compose resources are read with a suspend call outside composition (cached after the first read),
                    // so the summary lands one dispatch after the fields; if the screen is recreated in between, the
                    // fields are kept and only the summary is lost (CMP-3 review: accepted).
                    scope.launch { pasteMessage = pasteResultText(merged, warnings, kind.fields) }
                }
                showPaste = false
            },
        )
    }
}

/**
 * Two fields side by side (price and BHK, street and locality, latitude and longitude), stacked one above the other
 * when they would not fit (whole-app audit): at 1.3× font or more, where "வாடகை ₹/மாதம்" and "பகுதி" were cut off,
 * or when the room inside the form's gutters is under [PAIR_STACK_BELOW_DP] (300 dp; round 2 of the audit: the
 * earlier 360 dp was measured inside the 16 dp gutters and stacked every phone narrower than 392 dp, most budget
 * phones, at 100 % font). A 360 dp phone has 328 dp here, which is side by side. See [stackFieldPair]. With
 * [secondFixedWidth] the second field is 100 dp wide beside the first (BHK).
 */
@Composable
internal fun PairOrStack(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    secondFixedWidth: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = stackFieldPair(maxWidth.value, LocalDensity.current.fontScale)
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(1f))
                second(if (secondFixedWidth) Modifier.width(100.dp) else Modifier.weight(1f))
            }
        }
    }
}

/**
 * The **Cost** section of the form (docs/11 5.21, slice 1a): the fields from the kind file (kinds PR 4, S4b-BL-208;
 * [KindExtrasFields]: two per row where they pair, the rent-only ones hidden for a sale by their `forCadences`,
 * *Available from* as a `YYYY-MM-DD` field with the platform's date picker), and under them one line of what it adds
 * up to ([CostSummary]) when something computes.
 */
@Composable
private fun CostSection(d: HouseEntity, update: ((HouseEntity) -> HouseEntity) -> Unit, fields: KindFields) {
    KindExtrasFields(fields, "cost", d, update)
    // "Monthly cost ₹34,500 · To move in ₹1,28,000 · ₹27 per sq ft": only the parts that compute, none when none does.
    val summary = CostSummary.of(d.price, d.priceType, d.areaSqft, d.cost)
    val parts = listOfNotNull(
        summary.monthlyCost?.let { stringResource(Res.string.house_cost_monthly, Formats.rupees(it)) },
        summary.moveIn?.let { stringResource(Res.string.house_cost_move_in, Formats.rupees(it)) },
        summary.perSqFt?.let { stringResource(Res.string.house_cost_per_sqft, Formats.rupees(it.roundToLong())) },
    )
    LiveMessage {
        if (parts.isNotEmpty()) {
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 1-5 stars as a radio group: TalkBack says "3 out of 5, selected, radio button, 3 of 5". Tap again to clear. */
@Composable
internal fun RatingRow(rating: Int?, onPick: (Int) -> Unit) {
    val starColor = LocalDoorprintsColors.current.star
    val none = stringResource(Res.string.house_no_rating)
    val current = rating?.let { stringResource(Res.string.common_stars, it) } ?: none
    Row(Modifier.selectableGroup().semantics { stateDescription = current }) {
        (1..5).forEach { star ->
            val selected = rating == star
            val desc = stringResource(Res.string.common_stars, star)
            // At least 48 dp, and larger when the star does (Wave D: a fixed 48 dp box cut the star at 200 % text).
            Box(
                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(star) })
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.Center,
            ) {
                Text(if ((rating ?: 0) >= star) "★" else "☆", style = MaterialTheme.typography.headlineSmall, color = starColor)
            }
        }
    }
}
