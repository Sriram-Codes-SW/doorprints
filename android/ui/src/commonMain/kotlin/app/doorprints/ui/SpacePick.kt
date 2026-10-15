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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.doorprints.shared.model.DevKinds
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * *Save a copy of* (kinds, S4b-BL-292), the twin of the website's `space-pick.ts`: with two or more kinds on, the Export screen
 * copies the space on screen, and this chooses the list a copy is made of without going back to the chooser. A menu of the
 * enabled kinds, each by its plural in the app's language ([kinds] in the build's order; the space on screen is the chosen one);
 * choosing one calls [onPick], which changes the space the screens read, so the card's words, the counts and the file follow.
 * Drawn by the caller only while [SpaceUi.multi]; locked ([enabled] false) while a copy is being made.
 */
@Composable
internal fun SpacePick(kinds: List<String>, current: String, enabled: Boolean, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val name = stringResource(Res.string.export_copy_of)
    val currentText = stringResource(DevKinds.of(current, on = true).labels.refText("kind.many"))
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = name
                    stateDescription = currentText
                    role = Role.DropdownList
                },
            ) {
                Text(currentText, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.selectableGroup()) {
                kinds.forEach { kind ->
                    val isCurrent = kind == current
                    DropdownMenuItem(
                        text = { Text(stringResource(DevKinds.of(kind, on = true).labels.refText("kind.many"))) },
                        onClick = {
                            open = false
                            if (!isCurrent) onPick(kind)
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
}
