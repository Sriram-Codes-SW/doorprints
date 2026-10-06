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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.SavedWalkSummary
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_delete
import app.doorprints.ui.res.trace_house_delete
import app.doorprints.ui.res.trace_house_delete_confirm
import app.doorprints.ui.res.trace_house_empty
import app.doorprints.ui.res.trace_house_local
import app.doorprints.ui.res.trace_house_row
import app.doorprints.ui.res.trace_house_show
import app.doorprints.ui.res.trace_house_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The house page's *Saved walks* card (docs/11 5.27.6): newest first, each row its date, distance and minutes, with *Show
 * on map* (the Map fits the walk and outlines it for three seconds, [ShowOnMap]; [onShowOnMap] opens the Map) and *Delete
 * walk* (asks first). The card says the walks stay on this phone only. A deleted house's walks are hidden at once by the
 * repository, so a house being deleted shows none. Not for a house not yet saved.
 */
@Composable
fun SavedWalksCard(houseId: String, onShowOnMap: () -> Unit) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    val walks by repo.savedWalksOf(houseId).collectAsStateWithLifecycle(initialValue = emptyList())
    var confirm by remember { mutableStateOf<SavedWalkSummary?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(stringResource(Res.string.trace_house_title))
        if (walks.isEmpty()) {
            Text(stringResource(Res.string.trace_house_empty), style = MaterialTheme.typography.bodyMedium)
        }
        walks.forEach { walk ->
            val row = stringResource(
                Res.string.trace_house_row, Formats.date(walk.startedAt), distanceText(walk.lengthM),
                ((walk.endedAt - walk.startedAt) / 60_000.0).let { kotlin.math.round(it).toInt() },
            )
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(row, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val points = repo.savedWalkPoints(walk.id) ?: return@launch
                                ShowOnMap.show(MapFocus(CheckOverlay.ofWalk(points)))
                                onShowOnMap()
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { ButtonLabel(stringResource(Res.string.trace_house_show)) }
                    TextButton(onClick = { confirm = walk }, modifier = Modifier.heightIn(min = 48.dp)) {
                        ButtonLabel(stringResource(Res.string.trace_house_delete))
                    }
                }
            }
        }
        Text(stringResource(Res.string.trace_house_local), style = MaterialTheme.typography.bodySmall)
    }
    confirm?.let { walk ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(Res.string.trace_house_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = { confirm = null; scope.launch { repo.deleteSavedWalk(walk.id) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}
