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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.doorprints.shared.export.ExportStrings
import app.doorprints.shared.model.KindExtra
import app.doorprints.shared.model.KindFields
import app.doorprints.shared.model.KindFilter
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** The saved form of the list's filter by a kind's extras: the set rows as `id`, `choice` pairs (a row left on *Any* is not kept). */
internal val ExtraFilterSaver: Saver<Map<String, String?>, Any> = listSaver(
    save = { m -> m.entries.filter { it.value != null }.flatMap { listOf(it.key, it.value!!) } },
    restore = { l -> l.chunked(2).associate { it[0] to it[1] } },
)

/**
 * One chip row per enum or switch of the space's kind, under the status chips (S4b-BL-265; the website's `app-extra-filters`):
 * *Any* and the extra's choices, one chosen per row, each row under the extra's label. Drawn only for a kind that has such an
 * extra (the rows are [KindFilter.rows]), so a house's list shows nothing new. [onChange] gets the whole map after a tap, `null`
 * for a row on *Any*.
 */
@Composable
internal fun ExtraFilterRows(fields: KindFields, filter: Map<String, String?>, onChange: (Map<String, String?>) -> Unit, modifier: Modifier = Modifier) {
    val rows = KindFilter.rows(fields)
    if (rows.isEmpty()) return
    val yesNo = ExportStrings.of(uiLanguage())
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            val name = stringResource(kindStringResource(fields.label(row)))
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            if (KindFilter.isRange(row)) {
                RangeRow(row, name, filter[row.id]) { text -> onChange(rows.associate { it.id to if (it.id == row.id) text else filter[it.id] }) }
                continue
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
                fun pick(choice: String?) = onChange(rows.associate { it.id to if (it.id == row.id) choice else filter[it.id] })
                StatusChip(selected = filter[row.id] == null, onClick = { pick(null) }, label = stringResource(Res.string.list_filter_any))
                for (choice in KindFilter.choices(row)) {
                    StatusChip(selected = filter[row.id] == choice, onClick = { pick(choice) }, label = choiceText(row, choice, yesNo))
                }
            }
        }
    }
}

/**
 * The two fields of a number or an amount (docs/03 §18.27): at least and at most, each named after the row for TalkBack, blank for no
 * limit. [value] is the row's range text (`min:max`); [onChange] gets the new text (the ends swapped when the minimum is above the
 * maximum) or null once both are blank, which is *Any*.
 */
@Composable
private fun RangeRow(row: KindExtra, name: String, value: String?, onChange: (String?) -> Unit) {
    val ends = value?.split(':').orEmpty()
    var min by rememberSaveable(row.id) { mutableStateOf(ends.getOrNull(0).orEmpty()) }
    var max by rememberSaveable(row.id) { mutableStateOf(ends.getOrNull(1).orEmpty()) }
    // The row follows the filter when it is cleared or set from elsewhere (the list's *Clear filters*).
    LaunchedEffect(value) {
        if (KindFilter.rangeText(min, max) != value) {
            min = ends.getOrNull(0).orEmpty()
            max = ends.getOrNull(1).orEmpty()
        }
    }
    @Composable
    fun End(text: String, label: String, onText: (String) -> Unit, modifier: Modifier) = OutlinedTextField(
        text,
        { v ->
            onText(v.filter { it.isDigit() || it == '.' || it == '-' }.take(19))
            onChange(KindFilter.rangeText(min, max))
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        singleLine = true,
        modifier = modifier,
    )
    PairOrStack(
        first = { m -> End(min, stringResource(Res.string.list_filter_range_min, name), { min = it }, m) },
        second = { m -> End(max, stringResource(Res.string.list_filter_range_max, name), { max = it }, m) },
    )
}

@Composable
private fun choiceText(row: KindExtra, choice: String, yesNo: ExportStrings): String =
    if (row.type == "bool") yesNo[choice] else stringResource(kindStringResource(row.options.first { it.id == choice }.label))
