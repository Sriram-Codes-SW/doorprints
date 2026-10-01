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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.doorprints.shared.model.CostFilter
import app.doorprints.shared.model.CostRange
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The house list's *Filters* button and its sheet (docs/11 5.21, S4b-BL-84): from and up to, in whole rupees, for the
 * monthly cost, the money to move in and the cost per sq ft ([CostFilter]). Each change applies at once, so the list
 * and its count follow behind the sheet; *Clear cost filters* empties the six fields. The button says how many ranges
 * are set ("Filters (2)"). The status chips and the search are untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CostFilterButton(filter: CostFilter, onChange: (CostFilter) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp)) {
        ButtonLabel(
            if (filter.active == 0) stringResource(Res.string.houses_filters)
            else stringResource(Res.string.houses_filters_count, filter.active),
        )
    }
    if (!open) return
    ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.houses_filters_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(Res.string.houses_filters_hint), style = MaterialTheme.typography.bodyMedium)
            RangeFields(Res.string.compare_monthly_cost, filter.monthly) { onChange(filter.copy(monthly = it)) }
            RangeFields(Res.string.compare_move_in, filter.moveIn) { onChange(filter.copy(moveIn = it)) }
            RangeFields(Res.string.compare_per_sqft, filter.perSqFt) { onChange(filter.copy(perSqFt = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                OutlinedButton(
                    onClick = { onChange(CostFilter()) },
                    enabled = filter.active > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.houses_filters_clear)) }
                Button(onClick = { open = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_close))
                }
            }
        }
    }
}

/** The two ends of one range, named after it for TalkBack ("Monthly cost from (₹)", "Monthly cost up to (₹)"). */
@Composable
private fun RangeFields(name: StringResource, range: CostRange, onChange: (CostRange) -> Unit) {
    val label = stringResource(name)
    PairOrStack(
        first = { m -> RupeeEnd(stringResource(Res.string.houses_filter_min, label), range.min, m) { onChange(range.copy(min = it)) } },
        second = { m -> RupeeEnd(stringResource(Res.string.houses_filter_max, label), range.max, m) { onChange(range.copy(max = it)) } },
    )
}

/** Whole rupees, digits only (up to 13), kept as typed while it means [value] and following it when it is cleared. */
@Composable
private fun RupeeEnd(label: String, value: Long?, modifier: Modifier, onChange: (Long?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value?.toString() ?: "") }
    LaunchedEffect(value) { if (text.toLongOrNull() != value) text = value?.toString() ?: "" }
    OutlinedTextField(
        text,
        { v ->
            text = v.filter(Char::isDigit).take(13)
            onChange(text.toLongOrNull())
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        singleLine = true,
        modifier = modifier,
    )
}
