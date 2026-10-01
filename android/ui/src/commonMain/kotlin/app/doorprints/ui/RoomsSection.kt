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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.LengthUnit
import app.doorprints.shared.model.RoomSizes
import app.doorprints.shared.model.RoomType
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The house form's **Rooms** section (docs/11 5.6, slice 1c), after Cost: one card per room in the order added (type,
 * name, length and width in [unit], the area under them, condition 1..5 or *Not checked*, notes, *Delete room*), then
 * the total of the areas from two sized rooms, and *Add room* (a bedroom, next in order; disabled at [HouseRooms.MAX]
 * with "At most 30 rooms"). [onChange] gets the new list, null for none; the repository's save coerces it. Sizes
 * are typed as feet and inches or as metres with up to two decimals, and stored in centimetres. Each card moves its
 * room up or down (S4b-BL-87, `HouseRooms.move`), the buttons named after the room for TalkBack.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun RoomsSection(rooms: List<HouseRoom>?, unit: LengthUnit, onChange: (List<HouseRoom>?) -> Unit) {
    val list = rooms.orEmpty().sortedWith(HouseRooms.ORDER)
    SectionHeading(stringResource(Res.string.house_rooms))
    if (list.isEmpty()) Text(stringResource(Res.string.house_rooms_empty), style = MaterialTheme.typography.bodySmall)
    list.forEachIndexed { index, room ->
        key(room.id) {
            RoomCard(
                room, unit,
                canMoveUp = index > 0,
                canMoveDown = index < list.lastIndex,
                onMove = { by -> onChange(HouseRooms.move(list, room.id, by)) },
                onChange = { updated -> onChange(list.map { if (it.id == room.id) updated else it }) },
                onDelete = { onChange(list.filter { it.id != room.id }.takeIf { it.isNotEmpty() }) },
            )
        }
    }
    // The total under the list, from two rooms with both sizes; always composed, so a new total is announced.
    val (total, sized) = HouseRooms.totalAreaSqCm(list)
    LiveMessage {
        if (sized >= 2) {
            Text(
                stringResource(Res.string.house_rooms_total, RoomSizes.areaText(total, unit)),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            )
        }
    }
    val full = list.size >= HouseRooms.MAX
    OutlinedButton(
        onClick = {
            onChange(list + HouseRoom(id = Uuid.random().toString(), type = RoomType.BEDROOM.name, sort = HouseRooms.nextSort(list)))
        },
        enabled = !full,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
        ButtonLabel(stringResource(Res.string.house_room_add))
    }
    // Always composed, so reaching the cap is announced.
    LiveMessage {
        if (full) Text(stringResource(Res.string.house_rooms_max, HouseRooms.MAX), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RoomCard(
    room: HouseRoom,
    unit: LengthUnit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onChange: (HouseRoom) -> Unit,
    onDelete: () -> Unit,
) {
    val typeName = stringResource(room.roomType.labelResource)
    val title = room.name?.takeIf { it.isNotBlank() } ?: typeName
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The card's title: the room's own name, else its type's (what the copies write too).
            // Beside it, the move buttons (48 dp each, named after the room).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.house_room_move_up, title))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.house_room_move_down, title))
                }
            }
            ChoiceMenu(
                label = stringResource(Res.string.house_room_type),
                options = RoomType.entries,
                chosen = room.roomType,
                text = { stringResource(it.labelResource) },
            ) { onChange(room.copy(type = it.name)) }
            OutlinedTextField(
                room.name ?: "", { v -> onChange(room.copy(name = v.take(HouseRooms.MAX_NAME).ifEmpty { null })) },
                label = { Text(stringResource(Res.string.house_room_name)) },
                placeholder = { Text(typeName) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SizeField(
                room.lengthCm, unit,
                Res.string.house_room_length_feet, Res.string.house_room_length_inches, Res.string.house_room_length_metres,
            ) { onChange(room.copy(lengthCm = it)) }
            SizeField(
                room.widthCm, unit,
                Res.string.house_room_width_feet, Res.string.house_room_width_inches, Res.string.house_room_width_metres,
            ) { onChange(room.copy(widthCm = it)) }
            LiveMessage {
                HouseRooms.areaSqCm(room)?.let {
                    Text(stringResource(Res.string.house_room_area, RoomSizes.areaText(it, unit)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val notChecked = stringResource(Res.string.house_room_condition_none)
            val conditionFormat = stringResource(Res.string.house_room_condition_value)
            ChoiceMenu(
                label = stringResource(Res.string.house_room_condition),
                options = listOf<Int?>(null, 1, 2, 3, 4, 5),
                chosen = room.condition,
                text = { c -> c?.let { formatPositional(conditionFormat, it) } ?: notChecked },
            ) { onChange(room.copy(condition = it)) }
            OutlinedTextField(
                room.notes ?: "", { v -> onChange(room.copy(notes = v.take(HouseRooms.MAX_NOTES).ifEmpty { null })) },
                label = { Text(stringResource(Res.string.house_room_notes)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
                ButtonLabel(stringResource(Res.string.house_room_delete))
            }
        }
    }
}

/**
 * A length or width: feet and inches side by side (feet mode), or metres with up to two decimals. The typed text
 * stays as typed while it means the stored size ("3." is 3 m), and follows the size when it changes elsewhere (another
 * device's version); text that is not a size up to 50 m clears the size and says so.
 */
@Composable
private fun SizeField(
    cm: Int?,
    unit: LengthUnit,
    feetLabel: StringResource,
    inchesLabel: StringResource,
    metresLabel: StringResource,
    onChange: (Int?) -> Unit,
) {
    val error = stringResource(Res.string.house_room_size_invalid)
    if (unit == LengthUnit.M) {
        var text by rememberSaveable(unit) { mutableStateOf(cm?.let(RoomSizes::metresText) ?: "") }
        LaunchedEffect(cm) { if (RoomSizes.parseMetres(text) != cm) text = cm?.let(RoomSizes::metresText) ?: "" }
        val invalid = text.isNotBlank() && RoomSizes.parseMetres(text) == null
        OutlinedTextField(
            text,
            { v ->
                text = v.take(6)
                onChange(RoomSizes.parseMetres(text))
            },
            label = { Text(stringResource(metresLabel)) },
            isError = invalid,
            supportingText = if (invalid) ({ Text(error) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val parts = cm?.let(RoomSizes::cmToFeetInches)
    var feet by rememberSaveable(unit) { mutableStateOf(parts?.feet?.toString() ?: "") }
    var inches by rememberSaveable(unit) { mutableStateOf(parts?.inches?.toString() ?: "") }
    fun typed(): Int? = if (feet.isBlank() && inches.isBlank()) null else RoomSizes.parseFeetInches(feet, inches)
    LaunchedEffect(cm) {
        if (typed() != cm) {
            val p = cm?.let(RoomSizes::cmToFeetInches)
            feet = p?.feet?.toString() ?: ""
            inches = p?.inches?.toString() ?: ""
        }
    }
    val invalid = !(feet.isBlank() && inches.isBlank()) && typed() == null
    PairOrStack(
        first = { m ->
            OutlinedTextField(
                feet, { v -> feet = v.filter(Char::isDigit).take(3); onChange(typed()) },
                label = { Text(stringResource(feetLabel)) },
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                singleLine = true, modifier = m,
            )
        },
        second = { m ->
            OutlinedTextField(
                inches, { v -> inches = v.filter(Char::isDigit).take(2); onChange(typed()) },
                label = { Text(stringResource(inchesLabel)) },
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                singleLine = true, modifier = m,
            )
        },
    )
    LiveMessage(assertive = true) {
        if (invalid) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** A menu of [options] shown as a full-width button with the choice, as the Broker section's (a radio per option). */
@Composable
internal fun <T> ChoiceMenu(
    label: String,
    options: List<T>,
    chosen: T,
    text: @Composable (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = text(chosen)
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = current
                role = Role.DropdownList
            },
        ) {
            Text("$label: $current", modifier = Modifier.weight(1f, fill = false), maxLines = 2)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.width(24.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.selectableGroup()) {
            options.forEach { option ->
                val isCurrent = option == chosen
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    onClick = {
                        open = false
                        if (!isCurrent) onPick(option)
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
