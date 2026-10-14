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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.model.BulkGuard
import app.doorprints.shared.model.DeleteScope
import app.doorprints.shared.model.ScopedCandidate
import app.doorprints.ui.res.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The root screen's snackbar, for a screen that shows a message with *Undo* but has no host of its own (Settings > What you
 * track, *Recently deleted*). Null outside the app (tests, previews): the message is then shown nowhere.
 */
val LocalRootSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/** One bulk action per 10 seconds per device (deletion safeguards PR 3): *Delete all in this space* asks it first. */
val DeviceBulkGuard = BulkGuard { nowMillis() }

/**
 * The question of *Delete all in this space* (deletion safeguards PR 3, docs/02 T-D): the count and the kind in the words,
 * a tick box when the space holds more than [DeleteScope.TICK_OVER], *Save a copy first*, and the first focus on *Cancel*
 * (a danger dialog never starts on the destructive button). The caller deletes only on [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteSpaceDialog(kindName: String, count: Int, onCancel: () -> Unit, onSaveCopy: () -> Unit, onConfirm: () -> Unit) {
    var ticked by rememberSaveable { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    BasicAlertDialog(onDismissRequest = onCancel) {
        DeleteSpaceDialogCard(kindName, count, ticked, { ticked = it }, onCancel, onSaveCopy, onConfirm, cancelFocus)
    }
}

/** The dialog's card, apart from its window, so a screenshot can draw it (the tick box state is the caller's). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeleteSpaceDialogCard(
    kindName: String,
    count: Int,
    ticked: Boolean,
    onTicked: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSaveCopy: () -> Unit,
    onConfirm: () -> Unit,
    cancelFocus: FocusRequester = remember { FocusRequester() },
) {
    val needsTick = count > DeleteScope.TICK_OVER
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.delete_space_button), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.delete_space_confirm, kindName, count.toString()), style = MaterialTheme.typography.bodyMedium)
            if (needsTick) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ticked, role = Role.Checkbox, onValueChange = onTicked),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null)
                    Text(stringResource(Res.string.drive_delete_confirm_checkbox), Modifier.padding(start = 12.dp))
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).focusRequester(cancelFocus)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
                TextButton(onClick = onSaveCopy, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.drive_delete_save_copy_first))
                }
                DangerButton(stringResource(Res.string.delete_space_do), onConfirm, enabled = !needsTick || ticked)
            }
        }
    }
}

/** The newest rows *Recently deleted* draws; the rest stay kept for their 30 days and come into view as these are restored. */
private const val BIN_SHOWN = 50

/** One line of *Recently deleted*: the entry's id, the name it shows, and when it was deleted, already in words. */
class BinLine(val id: String, val name: String, val deletedOn: String)

/**
 * Settings > *Recently deleted* (deletion safeguards PR 3, docs/04 tombstone and restore, S4b-BL-285): this space's deleted
 * entries from the last 30 days, newest first, each with *Restore*. A delete is a tombstone that syncs, so *Restore* writes
 * the row back live with a newer `updatedAt` (`Repository.restoreHouse`); the phone does not remove a house's photos or
 * visits when it deletes it, so they are back with it. The twin of the website's *Recently deleted* in *Your data*. Only
 * this space's rows are read.
 */
@Composable
fun RecentlyDeletedSection() {
    val repo = LocalAppServices.current.repository
    val space = LocalSpace.current
    val snackbar = LocalRootSnackbar.current
    val scope = rememberCoroutineScope()
    val deleted by remember(repo) { repo.observeDeletedHouses() }.collectAsStateWithLifecycle(emptyList())
    val now = remember(deleted) { nowMillis() }
    val bin = remember(deleted, space.view, now) {
        val byId = deleted.associateBy { it.id }
        DeleteScope.binRows(deleted.map { ScopedCandidate(it.id, it.kind, deleted = true, updatedAt = it.updatedAt) }, space.view::holds, now)
            .map { byId.getValue(it.id) }
    }
    val unnamed = stringResource(space.words.unnamed)
    val lines = bin.take(BIN_SHOWN).map { BinLine(it.id, it.label.ifBlank { unnamed }, stringResource(Res.string.delete_deleted_on, it.updatedAt.dateText())) }
    RecentlyDeletedList(lines, hidden = bin.size - lines.size) { id ->
        scope.launch {
            val name = lines.first { it.id == id }.name
            if (repo.restoreHouse(id)) snackbar?.showSnackbar(getString(Res.string.delete_restored, name))
        }
    }
}

/** The heading, the sentence and the lines of *Recently deleted*, apart from where they come from (a screenshot draws it). */
@Composable
fun RecentlyDeletedList(lines: List<BinLine>, hidden: Int = 0, onRestore: (String) -> Unit) {
    SectionHeading(stringResource(Res.string.delete_recently_deleted))
    Text(stringResource(Res.string.delete_bin_body), style = MaterialTheme.typography.bodySmall)
    if (lines.isEmpty()) {
        Text(stringResource(Res.string.delete_bin_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for (line in lines) {
        val restoreLabel = stringResource(Res.string.delete_restore_aria, line.name)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.bodyLarge)
                Text(line.deletedOn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onRestore(line.id) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = restoreLabel }) {
                ButtonLabel(stringResource(Res.string.delete_restore))
            }
        }
    }
    if (hidden > 0) Text(stringResource(Res.string.delete_bin_more, hidden), style = MaterialTheme.typography.bodySmall)
}
