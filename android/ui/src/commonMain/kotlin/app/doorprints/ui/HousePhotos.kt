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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.doorprints.data.PhotoEntity
import app.doorprints.data.Repository
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.MAX_PHOTOS_PER_HOUSE
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.model.PhotoTags
import app.doorprints.ui.res.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

// A house's photos on the form (S4b-BL-168, slice 1): taking and picking, the strip of thumbnails, delete with Undo, the
// full-screen viewer and the room/tags/caption dialog. They live here, not in HouseEditScreen, because they are one
// kind of data with their own state, focus rules and snackbar; a new photo-related field edits this file and
// MovingInSection.kt (the photo's meta dialog), not the form.

/** Focus targets besides a photo's delete button (whose target is the photo id). */
private const val FOCUS_GALLERY = "gallery"
private const val FOCUS_CAMERA = "camera"

/** Prefix of a thumbnail's focus target (the viewer gives focus back to the photo it was opened from). */
private const val FOCUS_THUMB = "thumb:"

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
 * One house's photos as the form shows them, made by [rememberHousePhotos]: what is on screen and what the buttons,
 * thumbnails, the *Moving in* card and the dialogs do. Nothing here is saved with the house (the photo rows are
 * written by the repository at once).
 */
internal class HousePhotos(
    /** Every photo of the house, a delete still waiting for its *Undo* included. */
    val all: List<PhotoEntity>,
    /** The photos on screen: [all] without the deletes waiting for their *Undo*. */
    val shown: List<PhotoEntity>,
    /** Taking and picking are possible on this platform (PlatformFeatures.addPhotos; off on iOS for now). */
    val canAdd: Boolean,
    /** A photo is being shrunk and stored. */
    val adding: Boolean,
    /** The last photo that could not be added (limit or unreadable), shown under the buttons until closed. */
    val problem: Repository.AddPhotoResult?,
    /** The photo on screen in the viewer, by id so a delete elsewhere cannot shift it to another photo. */
    val viewerPhotoId: String?,
    /** The photo whose room, tags and caption are being edited (from the viewer). */
    val metaPhotoId: String?,
    /** Where focus goes next for a screen-reader user: [FOCUS_GALLERY], [FOCUS_CAMERA], [FOCUS_THUMB] plus a photo id, or a photo id. */
    val focusTarget: String?,
    /** The focus requesters of the delete buttons and thumbnails (by photo id) and of the two add buttons. */
    val deleteFocus: HashMap<String, FocusRequester>,
    val thumbFocus: HashMap<String, FocusRequester>,
    val galleryFocus: FocusRequester,
    val cameraFocus: FocusRequester,
    val takePhoto: () -> Unit,
    val pickFromGallery: () -> Unit,
    /** The *Moving in* card's *Add a photo*: the next photo gets the MOVE_IN tag. */
    val takeMoveInPhoto: () -> Unit,
    val dismissProblem: () -> Unit,
    val delete: (PhotoEntity, Int) -> Unit,
    val openViewer: (String) -> Unit,
    val closeViewer: (String) -> Unit,
    val dropViewer: () -> Unit,
    val showDetails: (String) -> Unit,
    val closeDetails: () -> Unit,
    /** Saves a photo's room, tags and caption and says so in the snackbar. */
    val saveDetails: (String, PhotoMeta) -> Unit,
)

/**
 * The photos of house [houseId] and everything the form does with them, moved here unchanged from the form. The state
 * that must survive a rotation, the language switch or the camera hand-off (the last problem, the next photo being for
 * the condition record, the open viewer and the open dialog) is `rememberSaveable`. [snackbar] is the form's, shared
 * with its other messages.
 */
@Composable
internal fun rememberHousePhotos(houseId: String, snackbar: SnackbarHostState): HousePhotos {
    val services = LocalAppServices.current
    val repo = services.repository
    val form = services.houseForm
    val platform = LocalPlatformServices.current
    val scope = rememberCoroutineScope()
    val touchExploration = { platform.isScreenReaderOn() }

    val photosFlow = remember(houseId) { repo.photosFor(houseId) }
    val photos by photosFlow.collectAsStateWithLifecycle(emptyList())
    var problem by rememberSaveable { mutableStateOf<Repository.AddPhotoResult?>(null) }
    var adding by remember { mutableStateOf(false) }
    // The next photo taken is for the condition record: it gets the MOVE_IN tag (saveable across the camera hand-off).
    var moveInNext by rememberSaveable { mutableStateOf(false) }
    // The photo whose room, tags and caption are being edited (from the viewer).
    var metaPhotoId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerPhotoId by rememberSaveable { mutableStateOf<String?>(null) }

    // Photo delete with undo (see PhotoDeleteViewModel).
    val deletes: PhotoDeleteViewModel = viewModel { PhotoDeleteViewModel(repo, services.appScope) }
    val shown = photos.filter { it.id !in deletes.pending }
    val deleteFocus = remember { HashMap<String, FocusRequester>() }
    val thumbFocus = remember { HashMap<String, FocusRequester>() }
    // Taking and picking photos (PlatformFeatures.addPhotos, off on iOS for now). Without them the two buttons are not
    // composed, so their focus requesters are never used: requestFocus on an unattached requester throws.
    val canAdd = LocalPlatformFeatures.current.addPhotos
    val galleryFocus = remember { FocusRequester() }
    val cameraFocus = remember { FocusRequester() }
    var focusTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        // One frame for the row to drop the photo and canFocus = true to apply, then focus; one frame for TalkBack.
        withFrameNanos { }
        runCatching {
            when {
                target == FOCUS_GALLERY -> if (canAdd) galleryFocus.requestFocus()
                target == FOCUS_CAMERA -> if (canAdd) cameraFocus.requestFocus()
                target.startsWith(FOCUS_THUMB) -> thumbFocus[target.removePrefix(FOCUS_THUMB)]?.requestFocus()
                else -> deleteFocus[target]?.requestFocus()
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
            if (result == SnackbarResult.ActionPerformed) deletes.undo(p.photo.id) else deletes.commit(p.photo.id)
        }
    }
    // After a configuration change: the deletes still waiting get their snackbar back.
    LaunchedEffect(Unit) { deletes.pending.values.forEach(::showUndo) }
    fun deletePhoto(p: PhotoEntity, number: Int) {
        commitPendingDelete()
        val index = shown.indexOfFirst { it.id == p.id }
        val rest = shown.filter { it.id != p.id }
        val next = rest.getOrNull(index) ?: rest.lastOrNull()
        deletes.add(p, number)
        if (touchExploration()) focusTarget = next?.id ?: FOCUS_GALLERY.takeIf { canAdd }
        showUndo(PhotoDeleteViewModel.Pending(p, number))
    }

    fun addPhoto(photo: PickedPhoto) {
        if (adding) return
        adding = true
        problem = null
        scope.launch {
            try {
                // NonCancellable: a rotation while a big photo is being shrunk must not lose it.
                val tags = if (moveInNext) listOf(PhotoTags.MOVE_IN) else emptyList()
                moveInNext = false
                val result = withContext(NonCancellable) { form.addPhoto(houseId, photo, tags) }
                problem = result.takeIf { it != Repository.AddPhotoResult.ADDED }
            } finally {
                adding = false
            }
        }
    }

    // The camera and the photo picker (the app's, HouseFormServices): each photo is shrunk and stored the same way.
    val sources = form.rememberPhotoSources { addPhoto(it) }
    val savedText = stringResource(Res.string.photo_meta_saved)

    return HousePhotos(
        all = photos,
        shown = shown,
        canAdd = canAdd,
        adding = adding,
        problem = problem,
        viewerPhotoId = viewerPhotoId,
        metaPhotoId = metaPhotoId,
        focusTarget = focusTarget,
        deleteFocus = deleteFocus,
        thumbFocus = thumbFocus,
        galleryFocus = galleryFocus,
        cameraFocus = cameraFocus,
        takePhoto = {
            commitPendingDelete()
            moveInNext = false
            sources.takePhoto()
        },
        pickFromGallery = {
            commitPendingDelete()
            moveInNext = false
            sources.pickFromGallery()
        },
        takeMoveInPhoto = {
            commitPendingDelete()
            moveInNext = true
            sources.takePhoto()
        },
        dismissProblem = {
            problem = null
            if (touchExploration()) focusTarget = FOCUS_CAMERA
        },
        delete = ::deletePhoto,
        openViewer = { viewerPhotoId = it },
        closeViewer = { openId ->
            viewerPhotoId = null
            // Back to the thumbnail it was opened from, not the top of the form.
            if (touchExploration()) focusTarget = FOCUS_THUMB + openId
        },
        dropViewer = { viewerPhotoId = null },
        showDetails = { metaPhotoId = it },
        closeDetails = { metaPhotoId = null },
        saveDetails = { photoId, meta ->
            metaPhotoId = null
            scope.launch {
                if (withContext(NonCancellable) { repo.savePhotoMeta(photoId, meta) }) snackbar.showSnackbar(savedText)
            }
        },
    )
}

/**
 * The form's *Photos* section. Without adding photos (iOS for now; PlatformFeatures.addPhotos) there is no take or pick
 * button and no "save first" prompt, and a house with no photos has no Photos section at all; photos it already has
 * (from a server) are still shown, and can be opened and deleted. A new house ([isSaved] false) has its photos after
 * the first save: one tap on *Save and add photos* ([onSave]) saves and continues on it (whole-app audit).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HousePhotosSection(
    photos: HousePhotos,
    isNew: Boolean,
    isSaved: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    name: String,
    rooms: List<HouseRoom>?,
) {
    if (!photos.canAdd && photos.all.isEmpty()) return
    val form = LocalAppServices.current.houseForm
    HorizontalDivider()
    SectionHeading(stringResource(Res.string.house_photos))
    if (!isSaved) {
        // A new house: its photos belong to a saved row.
        if (isNew && photos.canAdd) {
            Text(stringResource(Res.string.house_save_first_photos), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { ButtonLabel(stringResource(Res.string.house_save_add_photos)) }
        }
        return
    }
    if (photos.canAdd) {
        Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = photos.takePhoto,
                    enabled = !photos.adding,
                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(photos.cameraFocus).then(
                        if (photos.focusTarget == FOCUS_CAMERA) Modifier.focusProperties { canFocus = true } else Modifier,
                    ),
                ) { Text(stringResource(Res.string.house_take_photo)) }
                OutlinedButton(
                    onClick = photos.pickFromGallery,
                    enabled = !photos.adding,
                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(photos.galleryFocus).then(
                        if (photos.focusTarget == FOCUS_GALLERY) Modifier.focusProperties { canFocus = true } else Modifier,
                    ),
                ) { Text(stringResource(Res.string.house_from_gallery)) }
            }
            // Where the user is looking after taking or picking a photo (round 21), not at the top of the form.
            LiveMessage {
                val problem = photos.problem
                when {
                    photos.adding -> Text(
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
                            onDismiss = photos.dismissProblem,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
    val openLabel = stringResource(Res.string.house_photo_open)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.shown.forEachIndexed { index, p ->
            val deleteFocus = photos.deleteFocus.getOrPut(p.id) { FocusRequester() }
            val openFocus = photos.thumbFocus.getOrPut(p.id) { FocusRequester() }
            Column(Modifier.width(120.dp)) {
                Box {
                    // A button: opens the photo larger (the web's photo tile, docs/05 §5).
                    AsyncImage(
                        model = form.photoModel(p.id),
                        contentDescription = stringResource(Res.string.house_photo_desc, index + 1, name),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.small)
                            .focusRequester(openFocus)
                            .then(
                                if (photos.focusTarget == FOCUS_THUMB + p.id) {
                                    Modifier.focusProperties { canFocus = true }
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(role = Role.Button, onClickLabel = openLabel) { photos.openViewer(p.id) },
                    )
                    // IconButton is 48 dp, on a surface so it stays visible on light photos.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        tonalElevation = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        IconButton(
                            onClick = { photos.delete(p, index + 1) },
                            modifier = Modifier.focusRequester(deleteFocus).then(
                                if (photos.focusTarget == p.id) Modifier.focusProperties { canFocus = true } else Modifier,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(Res.string.house_delete_photo, index + 1),
                            )
                        }
                    }
                }
                // Its room, tags and caption (slice 5), those it has; edited from the viewer.
                photoMetaSummary(p, rooms)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // The photo being added, so the row shows that something is happening (decoding a 12 MP photo
        // takes seconds on a budget phone). Decorative: the live message above says it.
        if (photos.adding) {
            Box(
                Modifier.size(120.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }
}

/** The full-screen viewer, while its photo is still there. */
@Composable
internal fun HousePhotoViewer(photos: HousePhotos, name: String, rooms: List<HouseRoom>?) {
    val openId = photos.viewerPhotoId ?: return
    val start = photos.shown.indexOfFirst { it.id == openId }
    if (start < 0) {
        // Deleted (here or by sync) while open: nothing to show.
        LaunchedEffect(openId) { photos.dropViewer() }
    } else {
        PhotoViewer(
            photos = photos.shown,
            start = start,
            name = name,
            rooms = rooms,
            onDetails = photos.showDetails,
            onClose = { photos.closeViewer(openId) },
        )
    }
}

/** The room, tags and caption dialog (opened from the viewer), while its photo is still there. */
@Composable
internal fun HousePhotoDetails(photos: HousePhotos, rooms: List<HouseRoom>?) {
    val pid = photos.metaPhotoId ?: return
    val photo = photos.all.firstOrNull { it.id == pid }
    if (photo == null) {
        LaunchedEffect(pid) { photos.closeDetails() }
    } else {
        PhotoMetaDialog(
            photo = photo,
            rooms = rooms,
            onDismiss = photos.closeDetails,
            onSave = { meta -> photos.saveDetails(pid, meta) },
        )
    }
}

/**
 * The full-screen photo viewer (whole-app audit; the web's "Photo viewer", docs/05 §5): the house's photos in a
 * horizontal pager, each fitted to the screen, "Photo 2 of 5" and a 48 dp Close. Back closes it too. Its pane title
 * tells TalkBack where it is.
 */
@Composable
private fun PhotoViewer(
    photos: List<PhotoEntity>,
    start: Int,
    name: String,
    rooms: List<HouseRoom>?,
    onDetails: (String) -> Unit,
    onClose: () -> Unit,
) {
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
                    // Room, tags and caption (slice 5) of the photo on screen.
                    photos.getOrNull(pager.currentPage)?.let { p ->
                        IconButton(onClick = { onDetails(p.id) }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(Res.string.photo_details_desc, pager.currentPage + 1),
                            )
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_close))
                    }
                }
                // What the photo shows (slice 5): its room, tags and caption, when it has any.
                photos.getOrNull(pager.currentPage)?.let { p ->
                    photoMetaSummary(p, rooms)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    key = { page -> photos.getOrNull(page)?.id ?: page },
                ) { page ->
                    photos.getOrNull(page)?.let { p ->
                        AsyncImage(
                            model = form.photoModel(p.id),
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
