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

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.doorprints.shared.model.Broker
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

// The house form's Broker section, moved out of HouseEditScreen.kt unchanged with kinds PR 5 (S4b-BL-209) for the
// file-size budget; the form draws it only for a kind that shows its broker.

/**
 * The house form's Broker section (docs/11 5.25, slice 1b): the chosen broker (or *None*) as a menu of the brokers by
 * name, and *New broker*, a small dialog for a name, phone and agency. Choosing one fills the contact name and phone
 * from it ([onPick] with the broker; null for *None*); a new one is saved at once ([onCreate]) and chosen.
 */
@Composable
internal fun BrokerSection(
    brokerId: String?,
    brokers: List<Pair<String, Broker>>,
    onPick: (Pair<String, Broker>?) -> Unit,
    onCreate: (Broker) -> Unit,
) {
    val none = stringResource(Res.string.house_broker_none)
    val label = stringResource(kindField("brokerId"))
    val current = brokerId?.let { id -> brokers.firstOrNull { it.first == id } }
    val currentText = current?.second?.label ?: none
    var open by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    SectionHeading(label)
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = currentText
                role = Role.DropdownList
            },
        ) {
            Text(currentText, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.selectableGroup()) {
            (listOf<Pair<String, Broker>?>(null) + brokers).forEach { option ->
                val isCurrent = option?.first == current?.first
                DropdownMenuItem(
                    text = { Text(option?.second?.label ?: none) },
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
    Text(stringResource(Res.string.house_broker_hint), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { creating = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.house_broker_new))
    }
    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        var phone by rememberSaveable { mutableStateOf("") }
        var agency by rememberSaveable { mutableStateOf("") }
        val close = { creating = false; name = ""; phone = ""; agency = "" }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(Res.string.house_broker_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(Broker.MAX_NAME) }, label = { Text(stringResource(Res.string.broker_name)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        singleLine = true)
                    OutlinedTextField(phone, { phone = it.take(Broker.MAX_PHONE) }, label = { Text(stringResource(Res.string.broker_phone)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        singleLine = true)
                    OutlinedTextField(agency, { agency = it.take(Broker.MAX_AGENCY) }, label = { Text(stringResource(Res.string.broker_agency)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreate(Broker(name = name.trim(), phone = phone.ifBlank { null }, agency = agency.ifBlank { null }))
                        close()
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(Res.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}
