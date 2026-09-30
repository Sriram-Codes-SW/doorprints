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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.AppSettings
import app.doorprints.data.ShareContact
import app.doorprints.data.toBundle
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.PhotoScope
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.back
import app.doorprints.ui.res.export_contacts
import app.doorprints.ui.res.export_contacts_hint
import app.doorprints.ui.res.export_contacts_left_out
import app.doorprints.ui.res.export_share_failed
import app.doorprints.ui.res.share_add
import app.doorprints.ui.res.share_add_name
import app.doorprints.ui.res.share_count
import app.doorprints.ui.res.share_failed
import app.doorprints.ui.res.share_first
import app.doorprints.ui.res.share_go
import app.doorprints.ui.res.share_intro
import app.doorprints.ui.res.share_last
import app.doorprints.ui.res.share_nothing
import app.doorprints.ui.res.share_photos
import app.doorprints.ui.res.share_preparing
import app.doorprints.ui.res.share_remove
import app.doorprints.ui.res.share_title
import app.doorprints.ui.res.share_who
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * *Share updates with…* (docs/11 5.28, S4b-FR-3): a file of what changed since the last share to a named person,
 * handed to the share sheet (WhatsApp, email, the Files app), which the other phone imports with merge. The names
 * live in the settings store on this phone only ([ShareContact]); the first share to a name is the whole list, every
 * later one what changed since. The file is a backup ZIP made by the export worker ([ExportServices], format
 * BACKUP) into the share-copies folder, so the Export screen's stop, cleanup and notifications apply; once the run
 * has written it, the share sheet opens and the name's `lastSharedAt` moves to the export's instant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareUpdatesScreen(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val repo = services.repository
    val exports = services.exportScreen
    val fileActions = exports.rememberFileActions()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by repo.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val contacts = settings.shareContacts
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = contacts.firstOrNull { it.id == chosenId } ?: contacts.firstOrNull()
    var newName by rememberSaveable { mutableStateOf("") }
    var includeContacts by rememberSaveable { mutableStateOf(true) }
    var includePhotos by rememberSaveable { mutableStateOf(true) }
    // The options of the share in progress, and the file it writes: matched against the finished run.
    var pending by remember { mutableStateOf<Pair<ShareContact, ExportOptions>?>(null) }
    var pendingTarget by remember { mutableStateOf<String?>(null) }

    // What the share would carry, counted from the rows as the Export screen counts its copy.
    val rows by remember(repo) { repo.localRowsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val defaults = exports.rememberDefaultOptions()
    fun optionsFor(contact: ShareContact): ExportOptions = defaults().copy(
        since = contact.lastSharedAt.takeIf { it > 0 },
        sharedTo = contact.name,
        includeContacts = includeContacts,
        photos = if (includePhotos) PhotoScope.ALL else PhotoScope.NONE,
    )
    val counts = remember(rows, chosen, includeContacts, includePhotos) {
        val r = rows ?: return@remember null
        val c = chosen ?: return@remember null
        val bundle = r.toBundle(optionsFor(c))
        Triple(bundle.houses.size, bundle.visits.size + bundle.unlinkedVisits.size, bundle.photos.size)
    }
    val nothing = counts != null && counts.first == 0 && counts.second == 0 && counts.third == 0

    // The run's outcome: the file to the share sheet, and the name's last share moved forward.
    val workFlow = remember(exports) { exports.runs() }
    val work by workFlow.collectAsStateWithLifecycle(emptyList())
    val running = work.any { it.state == RunState.RUNNING || it.state == RunState.ENQUEUED }
    LaunchedEffect(work, pendingTarget) {
        val target = pendingTarget ?: return@LaunchedEffect
        val (contact, options) = pending ?: return@LaunchedEffect
        val run = work.firstOrNull { it.target == target && it.state.isFinished } ?: return@LaunchedEffect
        pendingTarget = null
        pending = null
        if (run.state != RunState.SUCCEEDED || run.written == null) {
            snackbar.showSnackbar(getString(Res.string.share_failed), withDismissAction = true)
            return@LaunchedEffect
        }
        exports.clearDoneNotification()
        if (fileActions.share(run.written, ExportFormat.BACKUP)) {
            repo.settings.markShared(contact.id, options.exportedAtMillis)
        } else {
            snackbar.showSnackbar(getString(Res.string.export_share_failed), withDismissAction = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.share_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.share_intro), style = MaterialTheme.typography.bodyMedium)
            SectionHeading(stringResource(Res.string.share_who))
            Column(Modifier.selectableGroup()) {
                contacts.forEach { contact ->
                    val selected = chosen?.id == contact.id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { chosenId = contact.id }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(contact.name, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        IconButton(onClick = { scope.launch { repo.settings.removeShareContact(contact.id) } }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.share_remove, contact.name))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(ShareContact.MAX_NAME) },
                    label = { Text(stringResource(Res.string.share_add_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        val name = newName
                        newName = ""
                        scope.launch { repo.settings.addShareContact(name)?.let { chosenId = it.id } }
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.share_add)) }
            }
            chosen?.let { contact ->
                Text(
                    if (contact.lastSharedAt > 0) {
                        stringResource(Res.string.share_last, contact.name, contact.lastSharedAt.dateText())
                    } else {
                        stringResource(Res.string.share_first, contact.name)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                SwitchRow(
                    text = stringResource(Res.string.export_contacts),
                    hint = if (includeContacts) null else stringResource(Res.string.export_contacts_left_out),
                    checked = includeContacts,
                    horizontalPadding = 0.dp,
                    warning = if (includeContacts) stringResource(Res.string.export_contacts_hint) else null,
                    onChange = { includeContacts = it },
                )
                SwitchRow(
                    text = stringResource(Res.string.share_photos),
                    hint = null,
                    checked = includePhotos,
                    horizontalPadding = 0.dp,
                    onChange = { includePhotos = it },
                )
                counts?.let { (houses, visits, photos) ->
                    Text(
                        if (nothing) stringResource(Res.string.share_nothing) else stringResource(Res.string.share_count, houses, visits, photos),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = {
                        val options = optionsFor(contact).copy(exportedAtMillis = nowMillis())
                        val target = exports.shareCopyTarget(ExportFormat.BACKUP.fileName(options))
                        pending = contact to options
                        pendingTarget = target
                        exports.start(ExportFormat.BACKUP, target, options)
                    },
                    enabled = !running && pendingTarget == null && !nothing && counts != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (running || pendingTarget != null) stringResource(Res.string.share_preparing)
                        else stringResource(Res.string.share_go, contact.name),
                    )
                }
            }
        }
    }
}
