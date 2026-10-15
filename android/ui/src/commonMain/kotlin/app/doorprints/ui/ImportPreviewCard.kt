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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.ui.res.*
import app.doorprints.shared.export.ImportPreview
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One preview line: its sign, its label, and its number. */
internal data class PreviewLine(val icon: ImageVector, val label: StringResource, val count: Int, val loss: Boolean = false)

/**
 * The preview (docs/05 section 14.3, "the safety mechanism"), grouped as houses / visits / photos / brokers with a thin
 * divider between groups. Lines that are zero are hidden. Each line is a sign, a label and the number in its own
 * right-aligned column with tabular figures, so the numbers can be scanned down one edge instead of sitting at
 * the ragged end of a wrapped Tamil or Telugu sentence: + for something new, a refresh sign for something the
 * backup has a newer version of, the restore glyph for houses deleted on this phone that the undelete brings back,
 * an info sign for "kept" and for "deleted on this phone; they stay deleted" (houses a plain merge will not bring
 * back; the switch above offers to), and a warning sign in the error colour for the lines about something lost or
 * missing (the text itself stays plain: no scare styling, section 14.2).
 *
 * In copy mode, [duplicates] (houses already on this phone) comes first, as a warning line: "Already on this phone,
 * will appear twice: 37" is the number to weigh before adding copies. For a day after the import they can all be
 * removed at once with *Undo this import* (on the result and on the house list); after 24 hours they can only be
 * removed one by one.
 */
@Composable
internal fun PreviewCard(preview: ImportPreview, duplicates: Int, modifier: Modifier, neutral: Boolean = false) {
    val groups = previewGroups(preview, duplicates, neutral)
    if (groups.isEmpty()) return
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                group.forEach { line ->
                    Line(
                        icon = line.icon,
                        label = stringResource(line.label),
                        count = line.count,
                        tint = if (line.loss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * [PreviewCard]'s groups of lines, in order, without the lines that are zero and the groups left empty. With [neutral] (two or
 * more kinds on, S4b-BL-291) the five labels that name houses say *entries*, as the website's `imp.*Any` words do: a backup
 * holds every kind.
 */
internal fun previewGroups(preview: ImportPreview, duplicates: Int, neutral: Boolean = false): List<List<PreviewLine>> =
    listOf(
        listOf(
            PreviewLine(Icons.Default.Warning, Res.string.import_copy_duplicates, duplicates, loss = true),
            PreviewLine(Icons.Default.Add, if (neutral) Res.string.import_new_any else Res.string.import_new_houses, preview.newHouses),
            PreviewLine(Icons.Default.Refresh, if (neutral) Res.string.import_updated_any else Res.string.import_updated_houses, preview.updatedHouses),
            PreviewLine(Icons.Default.Warning, if (neutral) Res.string.import_checklists_cleared_any else Res.string.import_checklists_cleared, preview.checklistsCleared, loss = true),
            // S4b-BL-104 (d): a floor outside -5..200 in the file lands blank (the server refuses such a file).
            PreviewLine(Icons.Default.Warning, if (neutral) Res.string.import_floors_left_blank_any else Res.string.import_floors_left_blank, preview.floorsLeftBlank, loss = true),
            // The undelete (UX review, round 11): houses deleted on this phone that come back with their own ids.
            PreviewLine(RestoreIcon, Res.string.import_restored_houses, preview.restoredHouses),
            PreviewLine(Icons.Default.Info, Res.string.import_newer_here, preview.newerHereHouses),
            // Not "kept": these houses are not on the phone, and a merge leaves them deleted (UX review, round 10).
            PreviewLine(Icons.Default.Info, Res.string.import_deleted_here, preview.deletedHereHouses),
            // An update file's deletions (S4b-BL-82): houses here the sender deleted after editing them last.
            PreviewLine(Icons.Default.Warning, if (neutral) Res.string.import_removed_any else Res.string.import_removed_houses, preview.removedHouses, loss = true),
        ),
        listOf(
            PreviewLine(Icons.Default.Add, Res.string.import_new_visits, preview.newVisits),
            PreviewLine(Icons.Default.Refresh, Res.string.import_updated_visits, preview.updatedVisits),
        ),
        listOf(
            PreviewLine(Icons.Default.Add, Res.string.import_new_photos, preview.newPhotos),
            PreviewLine(Icons.Default.Warning, Res.string.import_photos_missing, preview.photosMissingFromFile, loss = true),
        ),
        // Brokers of a `/2` file (S4b-BL-86): new here, and newer in the file (a merge only; a copy adds them all as new).
        listOf(
            PreviewLine(Icons.Default.Add, Res.string.import_new_brokers, preview.newBrokers),
            PreviewLine(Icons.Default.Refresh, Res.string.import_updated_brokers, preview.updatedBrokers),
        ),
    ).map { group -> group.filter { it.count > 0 } }.filter { it.isNotEmpty() }

/**
 * Read by TalkBack as one item, "New houses 3". Label and number share one baseline (they are different sizes,
 * and top-aligned their baselines differed by 2–3 sp on every line of a column meant to be scanned); the icon sits
 * 2 dp down, centred on the label's first line.
 */
@Composable
private fun Line(icon: ImageVector, label: String, count: Int, tint: Color) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).alignByBaseline())
        Spacer(Modifier.width(12.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.alignByBaseline(),
        )
    }
}
