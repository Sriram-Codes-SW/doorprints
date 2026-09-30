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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity
import app.doorprints.shared.model.Broker
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.broker_agency
import app.doorprints.ui.res.broker_delete
import app.doorprints.ui.res.broker_delete_body
import app.doorprints.ui.res.broker_delete_title
import app.doorprints.ui.res.broker_fee_terms
import app.doorprints.ui.res.broker_gone
import app.doorprints.ui.res.broker_houses
import app.doorprints.ui.res.broker_houses_none
import app.doorprints.ui.res.broker_name
import app.doorprints.ui.res.broker_name_needed
import app.doorprints.ui.res.broker_notes
import app.doorprints.ui.res.broker_phone
import app.doorprints.ui.res.broker_rating
import app.doorprints.ui.res.broker_title
import app.doorprints.ui.res.brokers_add
import app.doorprints.ui.res.brokers_empty_body
import app.doorprints.ui.res.brokers_empty_title
import app.doorprints.ui.res.brokers_houses_count
import app.doorprints.ui.res.brokers_title
import app.doorprints.ui.res.common_back
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_delete
import app.doorprints.ui.res.common_save
import app.doorprints.ui.res.common_stars
import app.doorprints.ui.res.house_call
import app.doorprints.ui.res.house_call_desc
import app.doorprints.ui.res.house_clear_rating
import app.doorprints.ui.res.house_unnamed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Brokers (docs/11 5.25, slice 1b), Settings > Brokers: everyone who has shown you a house, kept once instead of on
 * every house. A broker is made when a house is saved with a phone number (`Repository.saveHouse`), or here with *Add
 * broker*; a row opens the broker's page ([BrokerScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrokersScreen(onBack: () -> Unit, onOpenBroker: (String) -> Unit) {
    val repo = LocalAppServices.current.repository
    // null until Room answers, so the empty state does not flash on the way in.
    val brokers: List<Pair<String, Broker>>? by remember(repo) { repo.observeBrokers() }
        .collectAsStateWithLifecycle(initialValue = null)
    val houses: List<HouseEntity> by repo.houses.collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.brokers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
                BrokerList(
                    brokers, houses.groupingBy { it.brokerId }.eachCount(),
                    onOpenBroker = onOpenBroker, onAdd = { onOpenBroker(Routes.NEW_BROKER) },
                )
            }
        }
    }
}

/** The list itself, without the screen around it (the screenshot shows it above a broker's page). */
@Composable
fun BrokerList(
    brokers: List<Pair<String, Broker>>?,
    houseCounts: Map<String?, Int>,
    onOpenBroker: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.brokers_add)) }
        when {
            brokers == null -> Unit
            brokers.isEmpty() -> HeroEmptyState(
                icon = Icons.Default.Person,
                title = stringResource(Res.string.brokers_empty_title),
                body = stringResource(Res.string.brokers_empty_body),
                horizontalPadding = 0.dp,
            )
            else -> brokers.forEachIndexed { index, (id, broker) ->
                if (index > 0) HorizontalDivider()
                BrokerRow(broker, houseCounts[id] ?: 0) { onOpenBroker(id) }
            }
        }
    }
}

/** One row: name, agency and phone, the stars, "N houses", a chevron; one 56 dp target that opens the broker. */
@Composable
private fun BrokerRow(broker: Broker, houses: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(broker.name, style = MaterialTheme.typography.bodyLarge)
            listOfNotNull(broker.agency, broker.phone).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                broker.rating?.let { stars ->
                    val desc = stringResource(Res.string.common_stars, stars)
                    Text(
                        "★".repeat(stars) + "☆".repeat(5 - stars), style = MaterialTheme.typography.bodySmall,
                        color = LocalDoorprintsColors.current.star,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = desc },
                    )
                }
                Text(
                    pluralStringResource(Res.plurals.brokers_houses_count, houses, houses),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One broker's page, or a new broker when [brokerId] is null: the fields (name, phone, agency, fee terms, notes, your
 * rating), *Call*, the houses from this broker (each opens the house), *Delete broker* with a confirmation that says
 * the houses keep their contact details, and *Save*. Saving rewrites the name and phone copies on the broker's houses
 * (`Repository.saveBroker`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrokerScreen(brokerId: String?, onBack: () -> Unit, onOpenHouse: (String) -> Unit) {
    val repo = LocalAppServices.current.repository
    val brokers: List<Pair<String, Broker>>? by remember(repo) { repo.observeBrokers() }
        .collectAsStateWithLifecycle(initialValue = null)
    val houses: List<HouseEntity> by remember(brokerId) { brokerId?.let(repo::brokerHouses) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val found = brokerId?.let { id -> brokers?.firstOrNull { it.first == id }?.second }
    // A broker deleted elsewhere while its page is open: nothing to edit.
    val gone = brokerId != null && brokers != null && found == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (brokerId == null) Res.string.brokers_add else Res.string.broker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
                when {
                    gone -> Text(stringResource(Res.string.broker_gone), Modifier.padding(16.dp))
                    brokerId != null && found == null -> Unit
                    else -> BrokerForm(brokerId, found, houses, onBack, onOpenHouse)
                }
            }
        }
    }
}

/** The broker's fields and actions: [found] is the saved broker (null for a new one). */
@Composable
fun BrokerForm(
    brokerId: String?,
    found: Broker?,
    houses: List<HouseEntity>,
    onDone: () -> Unit,
    onOpenHouse: (String) -> Unit,
) {
    val repo = LocalAppServices.current.repository
    val platform = LocalPlatformServices.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(brokerId) { mutableStateOf(found?.name ?: "") }
    var phone by rememberSaveable(brokerId) { mutableStateOf(found?.phone ?: "") }
    var agency by rememberSaveable(brokerId) { mutableStateOf(found?.agency ?: "") }
    var fee by rememberSaveable(brokerId) { mutableStateOf(found?.feeTerms ?: "") }
    var notes by rememberSaveable(brokerId) { mutableStateOf(found?.notes ?: "") }
    var rating by rememberSaveable(brokerId) { mutableIntStateOf(found?.rating ?: 0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val nameMissing = name.isBlank()
    val unnamed = stringResource(Res.string.house_unnamed)

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            name, { name = it.take(Broker.MAX_NAME) }, label = { Text(stringResource(Res.string.broker_name)) },
            isError = nameMissing, supportingText = if (nameMissing) ({ Text(stringResource(Res.string.broker_name_needed)) }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                phone, { phone = it.take(Broker.MAX_PHONE) }, label = { Text(stringResource(Res.string.broker_phone)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                singleLine = true, modifier = Modifier.weight(1f),
            )
            if (phone.isNotBlank()) {
                val callDesc = stringResource(Res.string.house_call_desc, phone)
                TextButton(
                    onClick = { platform.dial(phone.filter { it.isDigit() || it == '+' }) },
                    modifier = Modifier.heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = callDesc },
                ) { Text(stringResource(Res.string.house_call)) }
            }
        }
        OutlinedTextField(
            agency, { agency = it.take(Broker.MAX_AGENCY) }, label = { Text(stringResource(Res.string.broker_agency)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            fee, { fee = it.take(Broker.MAX_FEE_TERMS) }, label = { Text(stringResource(Res.string.broker_fee_terms)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            notes, { notes = it.take(Broker.MAX_NOTES) }, label = { Text(stringResource(Res.string.broker_notes)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        SectionHeading(stringResource(Res.string.broker_rating))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatingRow(rating.takeIf { it > 0 }) { star -> rating = if (rating == star) 0 else star }
            TextButton(onClick = { rating = 0 }, enabled = rating > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.house_clear_rating))
            }
        }
        Button(
            onClick = {
                busy = true
                val broker = Broker(
                    name = name.trim().ifEmpty { unnamed }, phone = phone.ifBlank { null }, agency = agency.ifBlank { null },
                    feeTerms = fee.ifBlank { null }, notes = notes.ifBlank { null }, rating = rating.takeIf { it in 1..5 },
                )
                scope.launch {
                    repo.saveBroker(broker, brokerId)
                    onDone()
                }
            },
            enabled = !nameMissing && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { ButtonLabel(stringResource(Res.string.common_save)) }

        if (brokerId != null) {
            HorizontalDivider()
            SectionHeading(stringResource(Res.string.broker_houses))
            if (houses.isEmpty()) {
                Text(stringResource(Res.string.broker_houses_none), style = MaterialTheme.typography.bodyMedium)
            }
            houses.forEach { house ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpenHouse(house.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(house.label.ifBlank { unnamed }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.broker_delete))
            }
        }
    }

    if (confirmDelete && brokerId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.broker_delete_title)) },
            text = { Text(stringResource(Res.string.broker_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteBroker(brokerId)
                        onDone()
                    }
                }) { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}
