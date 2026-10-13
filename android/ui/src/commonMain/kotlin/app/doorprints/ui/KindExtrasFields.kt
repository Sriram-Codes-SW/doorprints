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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.doorprints.data.HouseEntity
import app.doorprints.shared.model.CalendarDate
import app.doorprints.shared.model.Candidates
import app.doorprints.shared.model.ExtraControl
import app.doorprints.shared.model.HouseValues
import app.doorprints.shared.model.KindExtra
import app.doorprints.shared.model.KindFields
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_ok
import app.doorprints.ui.res.house_available_format
import app.doorprints.ui.res.house_available_pick
import app.doorprints.ui.res.house_floor_basement
import app.doorprints.ui.res.house_floor_basement_hint
import app.doorprints.ui.res.house_floor_basement_invalid
import app.doorprints.ui.res.house_floor_hint
import app.doorprints.ui.res.house_floor_invalid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

// The typed-extras renderer of the phone's house form (kinds PR 4, S4b-BL-208; docs/03 ADR-36 §18.8), the twin of the
// website's `kind-extras.ts`: the BHK, area and floor in Details and the Cost fields, drawn from the kind's fields
// ([KindFields]) instead of written out one by one. `kind-field-vectors.json` and `HouseFormFieldsTest`'s golden file
// hold it to what the hand-written fields drew; the Roborazzi baselines to their pixels.

/**
 * The rows the phone draws a section's extras in: two fields side by side ([PairOrStack]), except that a switch, a
 * date and a level (with its Basement switch under it) take a row of their own, and a field before one of those does
 * too.
 */
internal fun phoneRows(extras: List<KindExtra>): List<List<KindExtra>> {
    fun alone(f: KindExtra) = f.control == ExtraControl.BOOL || f.control == ExtraControl.DATE || f.control == ExtraControl.LEVEL
    val rows = mutableListOf<List<KindExtra>>()
    var i = 0
    while (i < extras.size) {
        val f = extras[i]
        val next = extras.getOrNull(i + 1)
        if (!alone(f) && next != null && !alone(next)) {
            rows += listOf(f, next)
            i += 2
        } else {
            rows += listOf(f)
            i += 1
        }
    }
    return rows
}

/** The most digits a number or money field keeps: those of its `max`, two for a count without one (the BHK). */
internal fun maxDigits(f: KindExtra): Int? = when (f.control) {
    ExtraControl.NUMBER, ExtraControl.LEVEL, ExtraControl.MONEY -> f.max?.toString()?.length ?: 2
    else -> null
}

/** What the house holds for [f] (its candidate extras: a number, a money object, a yes/no or a date). */
internal fun HouseEntity.extraValue(f: KindExtra): JsonElement? = extras?.get(f.id)

/**
 * The house with [f] set to [value] (null: unknown), through its candidate extras ([Candidates.houseExtrasOf]), so the
 * renderer writes every extra the same way; a money extra is written with the cadence it is paid at.
 */
internal fun HouseEntity.withExtra(f: KindExtra, value: JsonElement?): HouseEntity {
    val next = LinkedHashMap<String, JsonElement>(extras ?: emptyMap())
    if (value == null) next.remove(f.id) else next[f.id] = value
    val v = Candidates.houseExtrasOf(JsonObject(next))
    return copy(bedrooms = v.bedrooms, areaSqft = v.areaSqft, floor = v.floor, cost = v.cost, extrasOther = v.other)
}

private fun HouseEntity.withNumber(f: KindExtra, typed: String): HouseEntity {
    val n = typed.filter(Char::isDigit).take(maxDigits(f) ?: 0).toLongOrNull()
        ?.takeIf { (f.min == null || it >= f.min!!) && (f.max == null || it <= f.max!!) }
    val value = when {
        n == null -> null
        f.control == ExtraControl.MONEY -> JsonObject(
            mapOf("amount" to JsonPrimitive(n), "cadence" to JsonPrimitive(f.cadences?.first() ?: money?.cadence ?: "month")),
        )
        else -> JsonPrimitive(n)
    }
    return withExtra(f, value)
}

private fun JsonElement?.number(): Long? = when (this) {
    is JsonObject -> (this["amount"] as? JsonPrimitive)?.longOrNull
    is JsonPrimitive -> longOrNull
    else -> null
}

/**
 * The extras of [section] for the house [d], in the kind file's order and the phone's rows ([phoneRows]); a field
 * whose `forCadences` leaves out the house's money cadence is not drawn (the rent-only cost fields for a sale).
 */
@Composable
internal fun KindExtrasFields(
    fields: KindFields,
    section: String,
    d: HouseEntity,
    update: ((HouseEntity) -> HouseEntity) -> Unit,
) {
    for (row in phoneRows(fields.shown(section, Candidates.cadenceOf(d.priceType)))) {
        if (row.size == 2) {
            PairOrStack(
                first = { m -> ExtraField(fields, row[0], d, m, update) },
                second = { m -> ExtraField(fields, row[1], d, m, update) },
            )
        } else {
            ExtraField(fields, row[0], d, Modifier.fillMaxWidth(), update)
        }
    }
}

@Composable
private fun ExtraField(
    fields: KindFields,
    f: KindExtra,
    d: HouseEntity,
    modifier: Modifier,
    update: ((HouseEntity) -> HouseEntity) -> Unit,
) {
    val label = kindStringResource(fields.label(f))
    when (f.control) {
        ExtraControl.LEVEL -> FloorField(d.id, d.extraValue(f).number()?.toInt(), label) { floor ->
            update { it.withExtra(f, floor?.let(::JsonPrimitive)) }
        }
        ExtraControl.BOOL -> SwitchRow(
            text = stringResource(label),
            hint = null,
            checked = (d.extraValue(f) as? JsonPrimitive)?.booleanOrNull == true,
            horizontalPadding = 0.dp,
        ) { on -> update { it.withExtra(f, JsonPrimitive(on)) } }
        ExtraControl.DATE -> DateField((d.extraValue(f) as? JsonPrimitive)?.content, label) { v ->
            update { it.withExtra(f, v?.let(::JsonPrimitive)) }
        }
        ExtraControl.NUMBER, ExtraControl.MONEY -> OutlinedTextField(
            d.extraValue(f).number()?.toString() ?: "",
            { v -> update { it.withNumber(f, v) } },
            label = { Text(stringResource(label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            singleLine = true, modifier = modifier,
        )
    }
}

/** The floor typed in the form (S4b-BL-87): an optional "-" and up to three digits, null outside -5..200 or when blank. */
internal fun floorOf(text: String): Int? = text.trim().takeIf { Regex("-?\\d{1,3}").matches(it) }?.toIntOrNull()?.let(HouseValues::floor)

/**
 * The floor from the field and the **Basement** switch (S4b-BL-104 c; the website's `typeFloor`): with the switch off, as
 * [floorOf]; with it on, the digits are the level below the ground, 1 to 5, so "2" is -2, without typing a minus that
 * some number keyboards do not have. Null when blank or out of range (a basement 0 included).
 */
internal fun floorOf(text: String, basement: Boolean): Int? {
    if (!basement) return floorOf(text)
    val level = text.trim().removePrefix("-").takeIf { Regex("\\d{1,3}").matches(it) }?.toIntOrNull() ?: return null
    return if (level >= 1) HouseValues.floor(-level) else null
}

/**
 * A level extra, the house's **Floor** (S4b-BL-87), and under it the **Basement** switch (S4b-BL-104 c): the field holds
 * the digits, the switch the sign, so a basement needs no minus key; a "-" typed anyway turns the switch on. Both follow
 * [floor] when it changes elsewhere; text that is not a floor (or a basement level 1 to 5) says so and leaves it unknown.
 */
@Composable
private fun FloorField(houseId: String, floor: Int?, label: StringResource, onChange: (Int?) -> Unit) {
    var basement by rememberSaveable(houseId) { mutableStateOf(floor != null && floor < 0) }
    var text by rememberSaveable(houseId) { mutableStateOf(floor?.let { abs(it).toString() } ?: "") }
    LaunchedEffect(floor) {
        if (floorOf(text, basement) != floor) {
            basement = floor != null && floor < 0
            text = floor?.let { abs(it).toString() } ?: ""
        }
    }
    val invalid = text.isNotBlank() && floorOf(text, basement) == null
    OutlinedTextField(
        text,
        { v ->
            if ('-' in v) basement = true
            text = v.filter { it.isDigit() }.take(3)
            onChange(floorOf(text, basement))
        },
        label = { Text(stringResource(label)) },
        supportingText = {
            Text(
                stringResource(
                    when {
                        invalid && basement -> Res.string.house_floor_basement_invalid
                        invalid -> Res.string.house_floor_invalid
                        basement -> Res.string.house_floor_basement_hint
                        else -> Res.string.house_floor_hint
                    },
                ),
            )
        },
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(
        text = stringResource(Res.string.house_floor_basement),
        hint = null,
        checked = basement,
        horizontalPadding = 0.dp,
    ) { on ->
        basement = on
        onChange(floorOf(text, basement))
    }
}

/**
 * A date extra (*Available from*): a `YYYY-MM-DD` text field (the format's calendar date, no time zone) whose trailing
 * button opens Material's date picker in the person's locale. What is typed stays on screen while it is not a date
 * yet; the house takes only a valid date, or nothing when the field is cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(value: String?, label: StringResource, onChange: (String?) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value ?: "") }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val invalid = text.isNotBlank() && !CalendarDate.isValid(text.trim())
    OutlinedTextField(
        text,
        { v ->
            text = v
            val trimmed = v.trim()
            if (trimmed.isEmpty()) onChange(null) else if (CalendarDate.isValid(trimmed)) onChange(trimmed)
        },
        label = { Text(stringResource(label)) },
        isError = invalid,
        supportingText = { Text(stringResource(Res.string.house_available_format)) },
        trailingIcon = {
            IconButton(onClick = { showPicker = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.DateRange, contentDescription = stringResource(Res.string.house_available_pick))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value?.let(CalendarDate::toEpochMillis))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(CalendarDate.fromEpochMillis(it)) }
                    showPicker = false
                }) { Text(stringResource(Res.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(Res.string.common_cancel)) } },
        ) { DatePicker(state = state) }
    }
}
