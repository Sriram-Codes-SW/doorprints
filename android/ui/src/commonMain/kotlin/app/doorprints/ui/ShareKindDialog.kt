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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.SpaceScope
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * *Add a shared listing* with two or more kinds on (kinds PR C, S4b-BL-289; docs/03 §18.13): *Save it in*, one radio row per
 * kind that is on, the default first and chosen, before the map asks where the listing is. [onChoose] gets the kind the
 * listing lands in (`SpaceScope.shareKind`: the chosen one while it is on, else the default). With one kind on the dialog
 * is never shown and the listing goes to the map as it always did.
 */
@Composable
fun ShareKindDialog(setting: KindSetting, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val choice = SpaceScope.shareKind(chosen, setting.enabled, setting.default)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.share_listing_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.share_kind), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    (listOf(setting.default) + setting.enabled.filter { it != setting.default }).forEach { kind ->
                        val name = stringResource(SpaceWords.of(kind).many)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = choice == kind, role = Role.RadioButton) { chosen = kind },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == kind, onClick = null)
                            Text(name, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(choice) }, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.common_cancel)) } },
    )
}
