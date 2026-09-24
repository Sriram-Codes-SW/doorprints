package app.doorprints.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import app.doorprints.data.HouseEntity
import app.doorprints.data.PhotoEntity
import app.doorprints.data.Repository
import app.doorprints.ui.res.*
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.MAX_PHOTOS_PER_HOUSE
import kotlinx.coroutines.CoroutineScope
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Room's answer for the house on this route: null until the first answer, then [house] (null when there is no row).
 * Keeps "not loaded yet" apart from "not on this phone" (UX review, round 21).
 */
private class RoomAnswer(val house: HouseEntity?)

/** Focus targets besides a photo's delete button (whose target is the photo id). */
private const val FOCUS_GALLERY = "gallery"
private const val FOCUS_CAMERA = "camera"

/** Prefix of a thumbnail's focus target (the viewer gives focus back to the photo it was opened from). */
private const val FOCUS_THUMB = "thumb:"

/** How long "Use my current location" waits for a fix. */
private const val LOCATION_TIMEOUT_MS = 15_000L

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
            )
        }
    },
    restore = { saved -> (saved as? List<*>)?.let(::restoreDraft) },
)

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
    )
}.getOrNull()

/**
 * Photo deletes waiting for their *Undo* snackbar (UX review, whole-app audit). Held by the back-stack entry, not the
 * composition, so a rotation or the language switch during the 10 s no longer deletes the photo at once: the screen
 * shows the snackbar again for every delete still waiting. A delete is carried out when its snackbar ends without
 * *Undo* ([commit]), or when the entry is really closed ([onCleared], in the app scope). A delete still waiting when
 * the process dies keeps the photo, the safe side. Common since CMP-6 P6a: given the repository and the app scope
 * ([AppServices]), where it read them from the application before.
 */
internal class PhotoDeleteViewModel(
    private val repository: Repository,
    private val appScope: CoroutineScope,
) : ViewModel() {
    /** A photo removed from the row, and its number in the row when it was deleted ("Photo 2 deleted"). */
    data class Pending(val photo: PhotoEntity, val number: Int)

    var pending by mutableStateOf<Map<String, Pending>>(emptyMap())
        private set

    fun add(photo: PhotoEntity, number: Int) {
        pending = pending + (photo.id to Pending(photo, number))
    }

    fun undo(id: String) {
        pending = pending - id
    }

    fun commit(id: String) {
        val p = pending[id] ?: return
        pending = pending - id
        appScope.launch { repository.deletePhoto(p.photo) }
    }

    override fun onCleared() {
        val left = pending.values.toList()
        pending = emptyMap()
        left.forEach { p -> appScope.launch { repository.deletePhoto(p.photo) } }
    }
}

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
    onOpenHouses: () -> Unit = onDone,
    onCreated: (String) -> Unit = { onDone() },
    onDeleted: (String) -> Unit = { onDone() },
    showSaved: Boolean = false,
    onSavedShown: () -> Unit = {},
) {
    val platform = LocalPlatformServices.current
    val services = LocalAppServices.current
    val repo = services.repository
    val form = services.houseForm
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val isNew = houseId == null
    val id = rememberSaveable { houseId ?: Uuid.random().toString() }
    val defaultLabel = stringResource(Res.string.house_default_label)
    val streetLabel = stringResource(Res.string.house_default_label_street)
    val unnamed = stringResource(Res.string.house_unnamed)
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
                createdAt = now, updatedAt = now,
            )
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
    val photosFlow = remember(id) { repo.photosFor(id) }
    val photos by photosFlow.collectAsStateWithLifecycle(emptyList())
    val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmVisitDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pasteMessage by rememberSaveable { mutableStateOf<String?>(null) }
    // The last photo that could not be added (limit or unreadable), shown under the photo buttons until closed.
    var photoProblem by rememberSaveable { mutableStateOf<Repository.AddPhotoResult?>(null) }
    var addingPhoto by remember { mutableStateOf(false) }
    val latInvalid = latText?.let { parseCoordinate(it, 90.0) == null } == true
    val lonInvalid = lonText?.let { parseCoordinate(it, 180.0) == null } == true
    var locating by remember { mutableStateOf(false) }
    var locationFailed by rememberSaveable { mutableStateOf(false) }
    // Location was refused, or only approximate location was allowed: the shared note under *Use my current location*.
    var locationDenied by rememberSaveable { mutableStateOf(false) }
    val canSave = !busy && !removed && draft != null && !conflict && !latInvalid && !lonInvalid

    LaunchedEffect(dirty) { if (!dirty) confirmLeave = false }

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
        val toSave = d.copy(label = d.label.ifBlank { unnamed })
        scope.launch {
            withContext(NonCancellable) {
                repo.saveHouse(toSave)
                if (visitId != null) {
                    repo.getVisit(visitId)?.let { repo.saveVisit(it.copy(houseId = toSave.id)) }
                    // The "Are you at a house?" alert is answered: tapping it again must not open a second form.
                    form.clearVisitAlert(visitId)
                }
            }
            draft = toSave
            baseline = toSave
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
        )
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

    // Photo delete with undo (see PhotoDeleteViewModel).
    val snackbar = remember { SnackbarHostState() }
    val photoDeletes: PhotoDeleteViewModel = viewModel { PhotoDeleteViewModel(repo, services.appScope) }
    val pendingDelete = photoDeletes.pending
    val shownPhotos = photos.filter { it.id !in pendingDelete }
    val photoFocus = remember { HashMap<String, FocusRequester>() }
    val thumbFocus = remember { HashMap<String, FocusRequester>() }
    val galleryFocus = remember { FocusRequester() }
    val cameraFocus = remember { FocusRequester() }
    var focusTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        // One frame for the row to drop the photo and canFocus = true to apply, then focus; one frame for TalkBack.
        withFrameNanos { }
        runCatching {
            when {
                target == FOCUS_GALLERY -> galleryFocus.requestFocus()
                target == FOCUS_CAMERA -> cameraFocus.requestFocus()
                target.startsWith(FOCUS_THUMB) -> thumbFocus[target.removePrefix(FOCUS_THUMB)]?.requestFocus()
                else -> photoFocus[target]?.requestFocus()
            }
        }
        withFrameNanos { }
        focusTarget = null
    }
    val undoLabel = stringResource(Res.string.common_undo)
    // A delete still waiting for its snackbar is carried out now (before another photo is added or deleted).
    fun commitPendingDelete() {
        snackbar.currentSnackbarData?.dismiss()
    }
    fun showUndo(p: PhotoDeleteViewModel.Pending) {
        scope.launch {
            // Cancelled (a rotation, or the screen closing) throws here and leaves the delete pending: the new
            // composition shows the snackbar again, or the view model carries it out when the entry is closed.
            val result = snackbar.showSnackbar(
                message = getString(Res.string.house_photo_deleted, p.number),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) photoDeletes.undo(p.photo.id) else photoDeletes.commit(p.photo.id)
        }
    }
    // After a configuration change: the deletes still waiting get their snackbar back.
    LaunchedEffect(Unit) { photoDeletes.pending.values.forEach(::showUndo) }
    fun deletePhoto(p: PhotoEntity, number: Int) {
        commitPendingDelete()
        val index = shownPhotos.indexOfFirst { it.id == p.id }
        val rest = shownPhotos.filter { it.id != p.id }
        val next = rest.getOrNull(index) ?: rest.lastOrNull()
        photoDeletes.add(p, number)
        if (touchExploration()) focusTarget = next?.id ?: FOCUS_GALLERY
        showUndo(PhotoDeleteViewModel.Pending(p, number))
    }

    fun addPhoto(photo: PickedPhoto) {
        if (addingPhoto) return
        addingPhoto = true
        photoProblem = null
        scope.launch {
            try {
                // NonCancellable: a rotation while a big photo is being shrunk must not lose it.
                val result = withContext(NonCancellable) { form.addPhoto(id, photo) }
                photoProblem = result.takeIf { it != Repository.AddPhotoResult.ADDED }
            } finally {
                addingPhoto = false
            }
        }
    }

    // The camera and the photo picker (the app's, HouseFormServices): each photo is shrunk and stored the same way.
    val photoSources = form.rememberPhotoSources { addPhoto(it) }

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
                update { it.copy(lat = here.first, lon = here.second) }
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

    // The photo viewer, by photo id so a delete elsewhere cannot shift it to another photo.
    var viewerPhotoId by rememberSaveable { mutableStateOf<String?>(null) }

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
                                notFound -> Res.string.house_not_found_title
                                isNew -> Res.string.house_new_title
                                else -> Res.string.house_details_title
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
                            Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.house_delete))
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
                    title = stringResource(Res.string.house_not_found),
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
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
                    if (aiEnabled) {
                        // The entry point's one name, as on the web and in docs/12 G.1: not "Paste listing".
                        OutlinedButton(
                            onClick = { showPaste = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { ButtonLabel(stringResource(Res.string.house_paste_title)) }
                        LiveMessage {
                            pasteMessage?.let {
                                ResultCard(
                                    tone = ResultTone.NEUTRAL,
                                    text = it,
                                    onDismiss = { pasteMessage = null },
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(d.label, { v -> update { it.copy(label = v) } },
                        label = { Text(stringResource(Res.string.house_name)) },
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
                SectionHeading(stringResource(Res.string.house_status))
                Column(Modifier.selectableGroup()) {
                    HouseStatus.entries.forEach { s ->
                        val text = stringResource(s.labelResource)
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
                        ) { update { it.copy(status = s) } }
                    }
                }

                SectionHeading(stringResource(Res.string.house_rating))
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

                // Rent or buy: one of two short labels, so the single-choice segmented row stays (round 21).
                val rent = d.priceType != "SALE"
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = rent, onClick = { update { it.copy(priceType = "RENT") } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(Res.string.house_rent)) }
                    SegmentedButton(
                        selected = !rent, onClick = { update { it.copy(priceType = "SALE") } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(Res.string.house_buy)) }
                }
                // The amount as the app shows it, live, in the app language: ₹1,00,00,000 or ₹25,000 / month, so a
                // missing or extra zero is visible while typing. Always there (empty at first), so the row does not grow.
                val preview = priceText(d.price, d.priceType).orEmpty()
                PairOrStack(
                    first = { m ->
                        OutlinedTextField(
                            d.price?.toString() ?: "", { v -> update { it.copy(price = v.filter(Char::isDigit).take(12).toLongOrNull()) } },
                            label = { Text(stringResource(if (d.priceType == "SALE") Res.string.house_price_sale else Res.string.house_price_rent)) },
                            supportingText = { Text(preview) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            singleLine = true,
                            modifier = m,
                        )
                    },
                    second = { m ->
                        OutlinedTextField(
                            d.bedrooms?.toString() ?: "", { v -> update { it.copy(bedrooms = v.filter(Char::isDigit).take(2).toIntOrNull()) } },
                            label = { Text(stringResource(Res.string.house_bhk)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            singleLine = true, modifier = m,
                        )
                    },
                    secondFixedWidth = true,
                )

                OutlinedTextField(d.address ?: "", { v -> update { it.copy(address = v) } },
                    label = { Text(stringResource(Res.string.house_address)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth())
                PairOrStack(
                    first = { m ->
                        OutlinedTextField(d.street ?: "", { v -> update { it.copy(street = v.ifBlank { null }) } },
                            label = { Text(stringResource(Res.string.house_street)) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            singleLine = true, modifier = m)
                    },
                    second = { m ->
                        OutlinedTextField(d.locality ?: "", { v -> update { it.copy(locality = v.ifBlank { null }) } },
                            label = { Text(stringResource(Res.string.house_locality)) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            singleLine = true, modifier = m)
                    },
                )

                // Where the house is, and a way to correct it (whole-app audit; the web's coordinate fields and "Use my
                // location", WCAG 2.5.7): a house saved from a stale fix or a stray long-press is no longer stuck there.
                // Right after the address (round 3), in the web's order: Details, then Location. A mini-map with a
                // draggable pin is a Sprint 4b item (README section 8).
                SectionHeading(stringResource(Res.string.house_location_heading))
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
                            approximateText = approximateLocationText(Res.string.house_needs_precise),
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

                SectionHeading(stringResource(Res.string.house_checklist))
                ChecklistResources.items.forEach { (key, label) ->
                    ChecklistRow(stringResource(label), d.checklist[key]) { n ->
                        update {
                            val current = it.checklist[key]
                            // "–" clears; tapping the chosen score again is kept as a shortcut for the same.
                            it.copy(checklist = if (n == null || current == n) it.checklist - key else it.checklist + (key to n))
                        }
                    }
                }
                Text(stringResource(Res.string.house_overall, d.score.scoreText()), fontWeight = FontWeight.SemiBold)

                // Multi-line: the Enter key starts a new line here, so no Next action; sentences start with a capital.
                OutlinedTextField(d.notes ?: "", { v -> update { it.copy(notes = v) } },
                    label = { Text(stringResource(Res.string.house_notes)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 3, modifier = Modifier.fillMaxWidth())

                SectionHeading(stringResource(Res.string.house_contact))
                OutlinedTextField(d.contactName ?: "", { v -> update { it.copy(contactName = v) } },
                    label = { Text(stringResource(Res.string.house_contact_name)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(d.contactPhone ?: "", { v -> update { it.copy(contactPhone = v) } },
                        label = { Text(stringResource(Res.string.house_phone)) },
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
                        label = { Text(stringResource(Res.string.house_listing)) },
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

                HorizontalDivider()
                SectionHeading(stringResource(Res.string.house_photos))
                if (saved == null) {
                    // A new house: its photos belong to a saved row. One tap saves and continues on it (whole-app audit).
                    if (isNew) {
                        Text(stringResource(Res.string.house_save_first_photos), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { save() },
                            enabled = canSave,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { ButtonLabel(stringResource(Res.string.house_save_add_photos)) }
                    }
                } else {
                    Column {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    commitPendingDelete()
                                    photoSources.takePhoto()
                                },
                                enabled = !addingPhoto,
                                modifier = Modifier.heightIn(min = 48.dp).focusRequester(cameraFocus).then(
                                    if (focusTarget == FOCUS_CAMERA) Modifier.focusProperties { canFocus = true } else Modifier,
                                ),
                            ) { Text(stringResource(Res.string.house_take_photo)) }
                            OutlinedButton(
                                onClick = {
                                    commitPendingDelete()
                                    photoSources.pickFromGallery()
                                },
                                enabled = !addingPhoto,
                                modifier = Modifier.heightIn(min = 48.dp).focusRequester(galleryFocus).then(
                                    if (focusTarget == FOCUS_GALLERY) Modifier.focusProperties { canFocus = true } else Modifier,
                                ),
                            ) { Text(stringResource(Res.string.house_from_gallery)) }
                        }
                        // Where the user is looking after taking or picking a photo (round 21), not at the top of the form.
                        LiveMessage {
                            val problem = photoProblem
                            when {
                                addingPhoto -> Text(
                                    stringResource(Res.string.house_adding_photo),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                problem != null -> {
                                    val text = when (problem) {
                                        Repository.AddPhotoResult.LIMIT_REACHED ->
                                            stringResource(Res.string.house_photo_limit, MAX_PHOTOS_PER_HOUSE)
                                        else -> stringResource(Res.string.house_photo_unreadable)
                                    }
                                    ResultCard(
                                        tone = ResultTone.ERROR,
                                        text = text,
                                        onDismiss = {
                                            photoProblem = null
                                            if (touchExploration()) focusTarget = FOCUS_CAMERA
                                        },
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    val name = d.label.ifBlank { unnamed }
                    val openLabel = stringResource(Res.string.house_photo_open)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shownPhotos.forEachIndexed { index, p ->
                            val deleteFocus = photoFocus.getOrPut(p.id) { FocusRequester() }
                            val openFocus = thumbFocus.getOrPut(p.id) { FocusRequester() }
                            Box {
                                // A button: opens the photo larger (the web's photo tile, docs/05 §5).
                                AsyncImage(
                                    model = form.photoModel(p.path),
                                    contentDescription = stringResource(Res.string.house_photo_desc, index + 1, name),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.small)
                                        .focusRequester(openFocus)
                                        .then(
                                            if (focusTarget == FOCUS_THUMB + p.id) {
                                                Modifier.focusProperties { canFocus = true }
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .clickable(role = Role.Button, onClickLabel = openLabel) { viewerPhotoId = p.id },
                                )
                                // IconButton is 48 dp, on a surface so it stays visible on light photos.
                                Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp,
                                    modifier = Modifier.align(Alignment.TopEnd)) {
                                    IconButton(
                                        onClick = { deletePhoto(p, index + 1) },
                                        modifier = Modifier.focusRequester(deleteFocus).then(
                                            if (focusTarget == p.id) Modifier.focusProperties { canFocus = true } else Modifier,
                                        ),
                                    ) {
                                        Icon(Icons.Default.Delete,
                                            contentDescription = stringResource(Res.string.house_delete_photo, index + 1))
                                    }
                                }
                            }
                        }
                        // The photo being added, so the row shows that something is happening (decoding a 12 MP photo
                        // takes seconds on a budget phone). Decorative: the live message above says it.
                        if (addingPhoto) {
                            Box(
                                Modifier.size(120.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                        }
                    }
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.house_visits, visits.size), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() })
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

                Button(onClick = { save() }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(saveLabel)
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        viewerPhotoId?.let { openId ->
            val start = shownPhotos.indexOfFirst { it.id == openId }
            if (start < 0) {
                // Deleted (here or by sync) while open: nothing to show.
                LaunchedEffect(openId) { viewerPhotoId = null }
            } else {
                PhotoViewer(
                    photos = shownPhotos,
                    start = start,
                    name = d.label.ifBlank { unnamed },
                    onClose = {
                        viewerPhotoId = null
                        // Back to the thumbnail it was opened from, not the top of the form.
                        if (touchExploration()) focusTarget = FOCUS_THUMB + openId
                    },
                )
            }
        }
    }

    if (confirmLeave && dirty) {
        // UX-005: the web's canDeactivate question, in the app language (`confirm.leaveUnsaved`, `house.discard`).
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = {
                Text(stringResource(if (isNew) Res.string.house_discard_new_title else Res.string.house_leave_title))
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
            title = { Text(stringResource(Res.string.house_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.house_delete_confirm_body)) },
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

    if (showPaste) {
        PasteListingDialog(
            onDismiss = { showPaste = false },
            onDraft = { draftFromAi, warnings ->
                draft?.let { current ->
                    // The name is still the form's own (the default, or the geocoder's "House on …") on a new house.
                    val placeholder = current.label.isBlank() || current.label == defaultLabel ||
                        (isNew && current.label == baseline?.label)
                    val merged = mergeListing(current, draftFromAi, labelIsPlaceholder = placeholder)
                    draft = merged.house
                    // Compose resources are read with a suspend call outside composition (cached after the first read),
                    // so the summary lands one dispatch after the fields; if the screen is recreated in between, the
                    // fields are kept and only the summary is lost (CMP-3 review: accepted).
                    scope.launch { pasteMessage = pasteResultText(merged, warnings) }
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
private fun PairOrStack(
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

/** The field names a listing fill reports ("Filled in: price and contact name."). */
private val ListingField.nameRes: StringResource
    get() = when (this) {
        ListingField.NAME -> Res.string.paste_field_name
        ListingField.ADDRESS -> Res.string.paste_field_address
        ListingField.STREET -> Res.string.paste_field_street
        ListingField.LOCALITY -> Res.string.paste_field_locality
        ListingField.PRICE -> Res.string.paste_field_price
        ListingField.BHK -> Res.string.paste_field_bhk
        ListingField.CONTACT -> Res.string.paste_field_contact
        ListingField.PHONE -> Res.string.paste_field_phone
        ListingField.LISTING -> Res.string.paste_field_listing
        ListingField.NOTES -> Res.string.paste_field_notes
    }

/** "Filled in: price and contact name. Check them, then save." plus what was kept and what the AI flagged. */
private suspend fun pasteResultText(merge: ListingMerge, warnings: List<String>): String {
    suspend fun names(fields: List<ListingField>) = joinedListText(fields.map { getString(it.nameRes) })
    val parts = mutableListOf<String>()
    parts += if (merge.filled.isEmpty()) {
        getString(Res.string.house_paste_nothing)
    } else {
        getString(Res.string.house_paste_filled, names(merge.filled))
    }
    if (merge.kept.isNotEmpty()) parts += getString(Res.string.house_paste_kept, names(merge.kept))
    if (warnings.isNotEmpty()) parts += getString(Res.string.house_paste_check, warnings.joinToString("; "))
    return parts.joinToString(" ")
}

/**
 * The full-screen photo viewer (whole-app audit; the web's "Photo viewer", docs/05 §5): the house's photos in a
 * horizontal pager, each fitted to the screen, "Photo 2 of 5" and a 48 dp Close. Back closes it too. Its pane title
 * tells TalkBack where it is.
 */
@Composable
private fun PhotoViewer(photos: List<PhotoEntity>, start: Int, name: String, onClose: () -> Unit) {
    val title = stringResource(Res.string.house_photo_viewer)
    val form = LocalAppServices.current.houseForm
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { photos.size }
        Surface(
            color = Color.Black,
            contentColor = Color.White,
            modifier = Modifier.fillMaxSize().semantics { paneTitle = title },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.house_photo_position, pager.currentPage + 1, photos.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_close))
                    }
                }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    key = { page -> photos.getOrNull(page)?.id ?: page },
                ) { page ->
                    photos.getOrNull(page)?.let { p ->
                        AsyncImage(
                            model = form.photoModel(p.path),
                            contentDescription = stringResource(Res.string.house_photo_desc, page + 1, name),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Fill in from listing text". While the AI call runs, *Cancel* (and Back) stay available and cancel it (round 21).
 * A tap outside closes it only while nothing has been pasted, so a stray touch never throws a long ad away, and the
 * text area scrolls, so *Fill in* stays reachable with the keyboard up at 200 % (whole-app audit). The busy line and
 * the error are in a live region that is there before either appears.
 */
@Composable
private fun PasteListingDialog(onDismiss: () -> Unit, onDraft: (HouseDraftDto, List<String>) -> Unit) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val errorText = aiErrorText()
    val cancel = {
        job?.cancel()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = cancel,
        properties = DialogProperties(dismissOnClickOutside = text.isBlank() && !busy),
        title = { Text(stringResource(Res.string.house_paste_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.house_paste_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text, { text = it.take(8000) },
                    label = { Text(stringResource(Res.string.house_paste_field)) },
                    minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(Res.string.ai_disclosure), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LiveMessage(assertive = error != null && !busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (busy) {
                            ProgressBar()
                            Text(stringResource(Res.string.house_paste_busy))
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                job = scope.launch {
                    busy = true
                    error = null
                    val result = runCatching { repo.extractListing(text) }
                    // Cancelled: the dialog is closing, so neither a draft nor an error.
                    ensureActive()
                    result.onSuccess { onDraft(it, it.warnings) }.onFailure { error = errorText(it) }
                    busy = false
                }
            }) { Text(stringResource(Res.string.house_paste_go)) }
        },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/** 1-5 stars as a radio group: TalkBack says "3 out of 5, selected, radio button, 3 of 5". Tap again to clear. */
@Composable
private fun RatingRow(rating: Int?, onPick: (Int) -> Unit) {
    val starColor = LocalDoorprintsColors.current.star
    val none = stringResource(Res.string.house_no_rating)
    val current = rating?.let { stringResource(Res.string.common_stars, it) } ?: none
    Row(Modifier.selectableGroup().semantics { stateDescription = current }) {
        (1..5).forEach { star ->
            val selected = rating == star
            val desc = stringResource(Res.string.common_stars, star)
            Box(
                Modifier.size(48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(star) })
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.Center,
            ) {
                Text(if ((rating ?: 0) >= star) "★" else "☆", style = MaterialTheme.typography.headlineSmall, color = starColor)
            }
        }
    }
}

/** The checklist options in order: 0–5, then "–" (not scored) last; see [ChecklistRow]. */
private val CHECK_OPTIONS: List<Int?> = listOf(0, 1, 2, 3, 4, 5, null)

/**
 * One checklist item: its label and a segmented radio of 0–5 and "–" (not scored), the web's `.options .option`
 * (docs/05 §5; Design review, round 21). Each option is a fixed 48 dp square with an 8 dp corner: the chosen one is
 * filled `primary` with an `onPrimary` label and a 2 dp `primary` edge, the others `surface` with a 1 dp `outline`
 * edge. No ✓ (that is for toggle chips), and nothing changes size, so no option moves when another is chosen. The
 * chosen state is a change of lightness, not only of hue (`primary` on `surface` 6.02:1 light, over 3:1 dark;
 * WCAG 1.4.1); the label is 6.02:1 light, about 10:1 dark; the unselected edge 3.63:1 (1.4.11).
 *
 * The row wraps, never scrolls. **"–" is last** (whole-app audit): 7 × 48 + 6 × 4 = 360 dp needs a 392 dp phone, but
 * most Indian budget phones are 360–391 dp (328–359 dp inside the gutters). With "–" first, "5" went alone to a second
 * line on every one of the ten rows, and the 0–5 scale read as split; now 0–5 stay on one line (6 × 48 + 5 × 4 =
 * 308 dp) and only "–" wraps. "–" makes "not scored" an explicit choice (UX-005: every rating can be cleared); tapping
 * the chosen score again still clears it, as a shortcut. README section 8, device check 19 (h).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChecklistRow(label: String, value: Int?, onPick: (Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state = value?.let { stringResource(Res.string.house_check_value, it) } ?: stringResource(Res.string.house_not_rated)
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label · $state", style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CHECK_OPTIONS.forEach { n ->
                val sel = value == n
                val desc = if (n == null) stringResource(Res.string.house_check_option_none, label)
                else stringResource(Res.string.house_check_option, label, n)
                Box(
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(shape)
                        .background(if (sel) scheme.primary else scheme.surface, shape)
                        .border(if (sel) 2.dp else 1.dp, if (sel) scheme.primary else scheme.outline, shape)
                        .selectable(selected = sel, role = Role.RadioButton, onClick = { onPick(n) })
                        .semantics { contentDescription = desc },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        n?.toString() ?: "–",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sel) scheme.onPrimary else scheme.onSurface,
                    )
                }
            }
        }
    }
}
