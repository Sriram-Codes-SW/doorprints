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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.doorprints.data.PhotoEntity
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.model.PhotoTags
import app.doorprints.ui.res.*
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource

/** [MoveIn] with its three values, or null when it has none (the format never writes an empty `moveIn`). */
internal fun moveInOf(date: Long?, notes: String?, items: List<MoveInItem>?): MoveIn? {
    val list = items?.takeIf { it.isNotEmpty() }
    val text = notes?.takeIf { it.isNotEmpty() }
    return if (date == null && text == null && list == null) null else MoveIn(date, text, list)
}

/**
 * The photos of the condition record (docs/11 5.24, slice 5): those tagged MOVE_IN, grouped by the room of [rooms] they
 * name in the rooms' order, then the ones with no room or a room that is gone (key null), each group oldest first.
 */
internal fun conditionRecord(photos: List<PhotoEntity>, rooms: List<HouseRoom>?): List<Pair<HouseRoom?, List<PhotoEntity>>> {
    val tagged = photos.filter { PhotoTags.MOVE_IN in it.tags.orEmpty() }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    val ordered = rooms.orEmpty().sortedWith(HouseRooms.ORDER)
    val known = ordered.mapTo(HashSet()) { it.id }
    val groups = ordered.mapNotNull { r -> tagged.filter { it.roomId == r.id }.takeIf { it.isNotEmpty() }?.let { r to it } }
    val rest = tagged.filter { it.roomId == null || it.roomId !in known }
    return groups + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { null to it })
}

/** A room's name as the form shows it: its own, else its type's. */
@Composable
internal fun roomLabel(room: HouseRoom): String = room.name?.takeIf { it.isNotBlank() } ?: stringResource(room.roomType.labelResource)

/**
 * The line under a photo (slice 5): its room (a room that is gone reads "Untagged"), its tags translated and its
 * caption, those it has, joined with " · "; null when it has none.
 */
@Composable
internal fun photoMetaSummary(photo: PhotoEntity, rooms: List<HouseRoom>?): String? {
    val room = photo.roomId?.let { id ->
        rooms.orEmpty().firstOrNull { it.id == id }?.let { roomLabel(it) } ?: stringResource(Res.string.photo_untagged)
    }
    val tags = photo.tags.orEmpty().map { photoTagLabel(it) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
    return listOfNotNull(room, tags, photo.caption).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * The **Moving in** card of a TAKEN house (docs/11 5.24, slice 5), after the Viewings: *Start moving in* adds the six
 * default items once ([MoveIn.addDefaults], in the app's language); each item is ticked by tapping its row, edited or
 * removed with its buttons; *Add your own* adds one (at most [MoveIn.MAX], "At most 30 items"); the move-in date and
 * notes; the **condition record** ([conditionRecord]: photos tagged MOVE_IN by room, with their date and caption, and
 * *Add a photo*, which takes one with MOVE_IN chosen); and **Close this hunt**. [onChange] gets the move-in, null for
 * none; the repository's save coerces it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovingInCard(
    moveIn: MoveIn?,
    onChange: (MoveIn?) -> Unit,
    rooms: List<HouseRoom>?,
    photos: List<PhotoEntity>,
    canAddPhoto: Boolean,
    addingPhoto: Boolean,
    onAddPhoto: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    closeEnabled: Boolean,
    onCloseHunt: () -> Unit,
) {
    val items = moveIn?.items.orEmpty()
    val date = moveIn?.date
    val notes = moveIn?.notes
    fun change(d: Long? = date, n: String? = notes, list: List<MoveInItem>? = items) = onChange(moveInOf(d, n, list))
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var newText by rememberSaveable { mutableStateOf("") }
    val full = items.size >= MoveIn.MAX

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.movein_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(Res.string.movein_intro), style = MaterialTheme.typography.bodySmall)
            LiveMessage {
                val (done, total) = moveIn?.progress ?: (0 to 0)
                Text(
                    if (total == 0) stringResource(Res.string.movein_empty) else stringResource(Res.string.movein_progress, done, total),
                    style = if (total == 0) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (total == 0) null else FontWeight.SemiBold,
                )
            }
            MoveIn.ordered(items).forEach { item ->
                key(item.id) {
                    MoveInItemRow(
                        item,
                        onToggle = { on ->
                            change(list = items.map { if (it.id == item.id) it.copy(done = if (on) true else null) else it })
                        },
                        onEdit = { editing = item.id },
                        onRemove = { change(list = items.filter { it.id != item.id }) },
                    )
                }
            }
            if (MoveIn.canAddDefaults(items)) {
                val language = appLanguage()
                OutlinedButton(
                    onClick = { change(list = MoveIn.addDefaults(items, language)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.movein_start)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    newText, { newText = it.take(MoveIn.MAX_TEXT) },
                    label = { Text(stringResource(Res.string.movein_add_label)) },
                    enabled = !full,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        MoveIn.add(items, newText)?.let { change(list = it); newText = "" }
                    }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { MoveIn.add(items, newText)?.let { change(list = it); newText = "" } },
                    enabled = !full && newText.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.movein_add))
                }
            }
            LiveMessage {
                if (full) Text(stringResource(Res.string.movein_max), style = MaterialTheme.typography.bodySmall)
            }

            // The move-in date (optional): the picker's day, stored as local noon so every time zone reads the same day.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.movein_date), style = MaterialTheme.typography.labelLarge)
                    Text(
                        date?.let { Formats.date(it) } ?: stringResource(Res.string.movein_date_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = { picking = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.DateRange, contentDescription = stringResource(Res.string.movein_date_pick))
                }
                if (date != null) {
                    IconButton(onClick = { change(d = null) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.movein_date_clear))
                    }
                }
            }
            OutlinedTextField(
                notes ?: "", { v -> change(n = v.take(MoveIn.MAX_NOTES)) },
                label = { Text(stringResource(Res.string.movein_notes)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )

            // The condition record: the photos tagged MOVE_IN, by room, with their date and caption.
            Text(
                stringResource(Res.string.movein_record_title), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
            Text(stringResource(Res.string.movein_record_hint), style = MaterialTheme.typography.bodySmall)
            val record = conditionRecord(photos, rooms)
            if (record.isEmpty()) {
                Text(stringResource(Res.string.movein_record_empty), style = MaterialTheme.typography.bodySmall)
            }
            val form = LocalAppServices.current.houseForm
            val openLabel = stringResource(Res.string.house_photo_open)
            record.forEach { (room, list) ->
                Text(
                    room?.let { roomLabel(it) } ?: stringResource(Res.string.photo_no_room),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { p ->
                        Column(Modifier.width(96.dp)) {
                            val caption = p.caption
                            val when_ = Formats.date(p.createdAt)
                            AsyncImage(
                                model = form.photoModel(p.id),
                                contentDescription = listOfNotNull(when_, caption).joinToString(", "),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.small)
                                    .clickable(role = Role.Button, onClickLabel = openLabel) { onOpenPhoto(p.id) },
                            )
                            Text(when_, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (caption != null) {
                                Text(caption, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (canAddPhoto) {
                OutlinedButton(
                    onClick = onAddPhoto,
                    enabled = !addingPhoto,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.movein_add_photo)) }
            }

            OutlinedButton(
                onClick = onCloseHunt,
                enabled = closeEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
            ) { ButtonLabel(stringResource(Res.string.movein_close)) }
            LiveMessage {
                if (!closeEnabled) Text(stringResource(Res.string.movein_close_save_first), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    editing?.let { editId ->
        val item = items.firstOrNull { it.id == editId }
        if (item == null) {
            editing = null
        } else {
            MoveInItemDialog(
                item.text,
                onDismiss = { editing = null },
                onSave = { text ->
                    editing = null
                    change(list = items.map { if (it.id == editId) it.copy(text = text) else it })
                },
            )
        }
    }
    if (picking) MoveInDatePicker(date, onPick = { change(d = it) }, onClose = { picking = false })
}

/** One item: the whole row ticks it (a checkbox), then 48 dp *Edit* and *Remove* buttons named after the item. */
@Composable
private fun MoveInItemRow(item: MoveInItem, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .toggleable(value = item.isDone, role = Role.Checkbox, onValueChange = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.isDone, onCheckedChange = null)
            Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.movein_edit_desc, item.text))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.movein_remove_desc, item.text))
        }
    }
}

/** Edits an item's text (1..[MoveIn.MAX_TEXT]); *Save* is off while it is blank. */
@Composable
private fun MoveInItemDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.movein_item_text)) },
        text = {
            OutlinedTextField(
                text, { text = it.take(MoveIn.MAX_TEXT) },
                label = { Text(stringResource(Res.string.movein_item_text)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** Material's date picker for the move-in date: the chosen day at local noon ([LocalClock.at]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveInDatePicker(initial: Long?, onPick: (Long) -> Unit, onClose: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.let(LocalClock::dayOf))
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(LocalClock.at(it, 12, 0)) }
                onClose()
            }) { Text(stringResource(Res.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.common_cancel)) } },
    ) { DatePicker(state = state) }
}

/**
 * A photo's **Room, tags and caption** (docs/11 5.7, slice 5), from the photo viewer: the room (the house's rooms and
 * *No room*), the tags (the fixed ones as chips, translated, and the person's own with a field; at most
 * [PhotoTags.MAX], each up to [PhotoTags.MAX_LENGTH]), and the caption (at most [PhotoMeta.MAX_CAPTION]). [onSave] gets
 * the meta; the repository writes it only when it changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoMetaDialog(photo: PhotoEntity, rooms: List<HouseRoom>?, onDismiss: () -> Unit, onSave: (PhotoMeta) -> Unit) {
    var roomId by rememberSaveable(photo.id) { mutableStateOf(photo.roomId) }
    // Tags as one string (one per line): saveable as it is, and a tag is a single line.
    var tagText by rememberSaveable(photo.id) { mutableStateOf(photo.tags.orEmpty().joinToString("\n")) }
    var caption by rememberSaveable(photo.id) { mutableStateOf(photo.caption ?: "") }
    var custom by rememberSaveable(photo.id) { mutableStateOf("") }
    val tags = tagText.split('\n').filter { it.isNotEmpty() }
    fun setTags(list: List<String>) { tagText = list.joinToString("\n") }
    val full = tags.size >= PhotoTags.MAX
    val sortedRooms = rooms.orEmpty().sortedWith(HouseRooms.ORDER)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.photo_meta_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.photo_room), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Column(Modifier.selectableGroup()) {
                    RadioRow(
                        label = AnnotatedString(stringResource(Res.string.photo_no_room)), hint = null,
                        selected = roomId == null || sortedRooms.none { it.id == roomId }, horizontalPadding = 0.dp,
                    ) { roomId = null }
                    sortedRooms.forEach { r ->
                        RadioRow(
                            label = AnnotatedString(roomLabel(r)), hint = null, selected = roomId == r.id, horizontalPadding = 0.dp,
                        ) { roomId = r.id }
                    }
                }
                Text(stringResource(Res.string.photo_tags), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoTags.FIXED.forEach { t ->
                        val on = t in tags
                        FilterChip(
                            selected = on,
                            onClick = { setTags(if (on) PhotoTags.without(tags, t) else PhotoTags.with(tags, t)) },
                            enabled = on || !full,
                            label = { Text(photoTagLabel(t)) },
                            leadingIcon = if (on) ChipCheck else null,
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                    tags.filter { !PhotoTags.isFixed(it) }.forEach { t ->
                        val remove = stringResource(Res.string.photo_tag_remove_desc, t)
                        InputChip(
                            selected = true,
                            onClick = { setTags(PhotoTags.without(tags, t)) },
                            label = { Text(t) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = remove },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        custom, { custom = it.take(PhotoTags.MAX_LENGTH) },
                        label = { Text(stringResource(Res.string.photo_tag_custom)) },
                        enabled = !full, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            setTags(PhotoTags.with(tags, custom)); custom = ""
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { setTags(PhotoTags.with(tags, custom)); custom = "" },
                        enabled = !full && custom.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(Res.string.photo_tag_add)) }
                }
                Text(stringResource(Res.string.photo_tags_max), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    caption, { caption = it.take(PhotoMeta.MAX_CAPTION) },
                    label = { Text(stringResource(Res.string.photo_caption)) },
                    supportingText = { Text("${caption.length} / ${PhotoMeta.MAX_CAPTION}") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(PhotoMeta(roomId?.takeIf { id -> sortedRooms.any { it.id == id } || id == photo.roomId }, tags, caption.ifBlank { null })) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { ButtonLabel(stringResource(Res.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.common_cancel)) }
        },
    )
}
