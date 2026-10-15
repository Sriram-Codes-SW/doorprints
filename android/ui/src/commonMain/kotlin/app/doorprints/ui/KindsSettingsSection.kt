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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.model.DeleteScope
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.ScopedCandidate
import app.doorprints.shared.model.SpaceScope
import app.doorprints.shared.model.KindTally
import app.doorprints.shared.model.spaceCountsOf
import app.doorprints.ui.res.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A *Delete all in this space* waiting for its answer: the kind's name in words and the ids it would take. */
private class DeleteAsk(val name: String, val ids: List<String>)

/**
 * Settings > *What you track* (kinds PR C, S4b-BL-274; docs/03 §18.12), the twin of the website's *Your data* section:
 * *Experimental: more than houses* (off by default), then one row per kind the switch offers with *Show*, *Default* and
 * what is saved. Drawn only in a build that ships more than houses (`SettingsStore.availableKinds`), so a release shows
 * nothing and the Settings screen is byte for byte what it was. One kind always stays on (its *Show* is disabled), the
 * default is one of the kinds that are shown, and turning a kind off hides nothing and deletes nothing: the chooser keeps
 * it as a muted card. The choice is this phone's own (the settings store), never synced, shared or put in a copy.
 *
 * Each kind with entries also has *Delete all in this space* (deletion safeguards PR 3, docs/02 T-D): a question naming the
 * count and the kind, a tick box over 10 entries, *Save a copy first* and first focus on *Cancel*; it takes that space's
 * entries only ([DeleteScope.liveIdsOf]), as tombstones in one batch, with 10 seconds of *Undo* and 30 days of *Recently
 * deleted*; one bulk action per 10 seconds per device ([DeviceBulkGuard]).
 */
@Composable
fun KindsSettingsSection(
    /** *Save a copy first* in *Delete all in this space*: opens the copies screen (Settings > Your data > Save a copy). */
    onSaveCopy: () -> Unit = {},
) {
    val repo = LocalAppServices.current.repository
    val setting by rememberKindSetting(repo)
    val current = setting ?: return
    if (!current.hasUnreleased) return
    val scope = rememberCoroutineScope()
    val rows: List<KindTally> by remember(repo) { repo.kindTallies }
        .collectAsStateWithLifecycle(emptyList())
    val saved = spaceCountsOf(rows, current.default, current.available)
    // The live candidates by id: the ids a card's *Delete all in this space* takes are this space's and no other's.
    val live: List<ScopedCandidate> by remember(repo) { repo.houses.map { list -> list.map { ScopedCandidate(it.id, it.kind) } } }
        .collectAsStateWithLifecycle(emptyList())
    val snackbar = LocalRootSnackbar.current ?: remember { SnackbarHostState() }
    var ask by remember { mutableStateOf<DeleteAsk?>(null) }
    // Seconds the bulk pause still has to run, shown as a line (a snackbar would queue behind the Undo of the delete that started it).
    var pause by remember { mutableIntStateOf(0) }
    fun askDelete(kind: String, name: String) {
        pause = DeviceBulkGuard.waitSeconds()
        if (pause > 0) return
        val ids = DeleteScope.liveIdsOf(live, SpaceScope.holdsFor(kind, true, current.default, current.available))
        if (ids.isNotEmpty()) ask = DeleteAsk(name, ids)
    }
    ask?.let { a ->
        DeleteSpaceDialog(
            kindName = a.name, count = a.ids.size,
            onCancel = { ask = null },
            onSaveCopy = {
                ask = null
                onSaveCopy()
            },
            onConfirm = {
                ask = null
                scope.launch {
                    if (!DeviceBulkGuard.tryStart()) {
                        pause = DeviceBulkGuard.waitSeconds()
                        return@launch
                    }
                    val gone = repo.deleteHouses(a.ids)
                    if (gone.isNotEmpty()) offerSpaceDeleteUndo(snackbar, a.name, gone, restore = repo::restoreHouses, onFinal = repo::sweepWalksOfDeletedHouses)
                }
            },
        )
    }
    fun change(next: (KindSetting) -> KindSetting) {
        scope.launch { repo.settings.editKindSetting(next) }
    }
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(Res.string.kinds_heading))
        Text(stringResource(Res.string.kinds_body), style = MaterialTheme.typography.bodySmall)
        SwitchRow(
            text = stringResource(Res.string.kinds_switch), hint = stringResource(Res.string.kinds_switch_hint),
            checked = current.experimentalOn, horizontalPadding = 0.dp,
        ) { on -> change { it.withExperimental(on) } }
        // Rows only while the switch offers a second kind; a lone house row would have nothing to choose.
        if (current.offered.size > 1) {
            val enabled = current.enabled
            current.offered.forEach { kind ->
                val name = stringResource(SpaceWords.of(kind).many)
                val on = kind in enabled
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        Text(stringResource(Res.string.kinds_count, saved[kind] ?: 0), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // The last kind that is on cannot be turned off: its box is disabled and the section says so.
                    val last = on && enabled.size == 1
                    val show = stringResource(Res.string.kinds_show, name)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = on, enabled = !last, role = Role.Checkbox) { change { s -> s.withKindEnabled(kind, it) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, enabled = !last)
                        Text(show, Modifier.padding(start = 12.dp), color = if (last) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                    val savedHere = saved[kind] ?: 0
                    if (savedHere > 0) {
                        val deleteLabel = stringResource(Res.string.delete_space_button_aria, name)
                        DangerButton(
                            stringResource(Res.string.delete_space_button), { askDelete(kind, name) },
                            Modifier.fillMaxWidth().semantics { contentDescription = deleteLabel },
                        )
                    }
                    val makeDefault = stringResource(Res.string.kinds_default_aria, name)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = current.default == kind, enabled = on, role = Role.RadioButton) { change { s -> s.withDefault(kind) } }
                            .semantics { contentDescription = makeDefault },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current.default == kind, onClick = null, enabled = on)
                        Text(stringResource(Res.string.kinds_default), Modifier.padding(start = 12.dp), color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LiveMessage(assertive = true) {
                if (pause > 0) Text(stringResource(Res.string.delete_bulk_wait, pause), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(Res.string.kinds_default_hint) + (if (enabled.size == 1) " " + stringResource(Res.string.kinds_last_on) else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
