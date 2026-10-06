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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.data.HouseEntity
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_delete
import app.doorprints.ui.res.house_unnamed
import app.doorprints.ui.res.trace_end_body
import app.doorprints.ui.res.trace_end_delete
import app.doorprints.ui.res.trace_end_delete_confirm
import app.doorprints.ui.res.trace_end_keep
import app.doorprints.ui.res.trace_end_save
import app.doorprints.ui.res.trace_end_summary
import app.doorprints.ui.res.trace_end_title
import app.doorprints.ui.res.trace_pick_confirm
import app.doorprints.ui.res.trace_pick_near
import app.doorprints.ui.res.trace_pick_nearest
import app.doorprints.ui.res.trace_pick_none
import app.doorprints.ui.res.trace_pick_search
import app.doorprints.ui.res.trace_pick_title
import app.doorprints.ui.res.trace_unit_km
import app.doorprints.ui.res.trace_unit_m
import org.jetbrains.compose.resources.stringResource

/** A distance as the person reads it: "350 m", "1.2 km", in the language's own units. */
@Composable
fun distanceText(metres: Int): String =
    formatDistanceM(metres, stringResource(Res.string.trace_unit_m), stringResource(Res.string.trace_unit_km))

/**
 * *Save this walk?* (docs/11 5.27.6) as a bottom sheet: the walk's distance and minutes, the sentence about 30 days and
 * "phone only", then **Save with a house** (opens the house picker), **Keep for 30 days** (the primary button: it is the
 * default) and **Delete this walk** (asks first). Closing the sheet any other way (back, outside tap) is *Keep for 30 days*
 * ([onKeep]). [refusal] is the sentence of a refused save, in a polite live region.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkEndSheet(
    summary: WalkSummary,
    houses: List<HouseEntity>,
    alertRadiusM: Int,
    refusal: String?,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onSave: (houseId: String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onKeep) {
        WalkEndSheetContent(summary, houses, alertRadiusM, refusal, onKeep, onDelete, onSave)
    }
}

/** The body of [WalkEndSheet], without the sheet's window, so a test and a screenshot can show it. */
@Composable
fun WalkEndSheetContent(
    summary: WalkSummary,
    houses: List<HouseEntity>,
    alertRadiusM: Int,
    refusal: String?,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onSave: (houseId: String) -> Unit,
    initiallyPicking: Boolean = false,
) {
    var picking by rememberSaveable { mutableStateOf(initiallyPicking) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (picking) {
            HousePicker(summary, houses, alertRadiusM, refusal, onBack = { picking = false }, onKeep = onKeep, onSave = onSave)
        } else {
            Text(
                stringResource(Res.string.trace_end_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(Res.string.trace_end_summary, distanceText(summary.distanceM), summary.minutes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(Res.string.trace_end_body), style = MaterialTheme.typography.bodyMedium)
            RefusalNote(refusal)
            // The default is the primary button; the other two are quieter.
            Button(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.trace_end_keep))
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.trace_end_save))
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.trace_end_delete))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.trace_end_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun RefusalNote(text: String?) {
    // Always composed, so the sentence is announced once when it appears.
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * *Which house was this walk to?* (docs/11 5.27.6): the house nearest to where the walk stopped first and preselected when
 * there is one (within [alertRadiusM]), under it the houses within 150 m of the walk with their distances, then a search
 * box over all the houses (the list's `HouseSearch.matches`). *Save walk* is enabled once a house is chosen. With no houses
 * the sheet says so and offers only *Keep for 30 days*.
 */
@Composable
private fun HousePicker(
    summary: WalkSummary,
    houses: List<HouseEntity>,
    alertRadiusM: Int,
    refusal: String?,
    onBack: () -> Unit,
    onKeep: () -> Unit,
    onSave: (houseId: String) -> Unit,
) {
    val picker = remember(summary, houses, alertRadiusM) { WalkPicker.of(summary.points, houses, alertRadiusM) }
    var chosen by rememberSaveable(picker.nearest?.house?.id) { mutableStateOf(picker.nearest?.house?.id) }
    var query by rememberSaveable { mutableStateOf("") }
    val language = uiLanguage()
    val found = remember(query, houses, language) { WalkPicker.search(query, houses, language) }
    Text(
        stringResource(Res.string.trace_pick_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    if (houses.isEmpty()) {
        Text(stringResource(Res.string.trace_pick_none), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.trace_end_keep))
        }
        return
    }
    Column(Modifier.selectableGroup()) {
        picker.nearest?.let { row ->
            PickRow(row.house, stringResource(Res.string.trace_pick_nearest, distanceText(row.distanceM)), chosen == row.house.id) { chosen = row.house.id }
        }
        picker.near.forEach { row ->
            PickRow(row.house, stringResource(Res.string.trace_pick_near, distanceText(row.distanceM)), chosen == row.house.id) { chosen = row.house.id }
        }
    }
    OutlinedTextField(
        query, { query = it },
        label = { Text(stringResource(Res.string.trace_pick_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Column(Modifier.selectableGroup()) {
        found.take(MAX_FOUND).forEach { h -> PickRow(h, null, chosen == h.id) { chosen = h.id } }
    }
    RefusalNote(refusal)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.common_cancel)) }
        Button(onClick = { chosen?.let(onSave) }, enabled = chosen != null, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.trace_pick_confirm))
        }
    }
}

private const val MAX_FOUND = 20

/** One house to choose: its name and, where there is one, the line about its distance; the whole row a 48 dp radio target. */
@Composable
private fun PickRow(house: HouseEntity, detail: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(house.label.ifBlank { stringResource(Res.string.house_unnamed) }, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
