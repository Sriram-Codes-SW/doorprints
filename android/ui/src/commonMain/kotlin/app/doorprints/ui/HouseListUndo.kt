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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.export.CopyRecord
import app.doorprints.export.CopyUndoOutcome
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The copy import the list shows (round 19): [record] for [runId], read from disk for [requested] (the list's
 * `importedRun` at the time). [runId] stays set after an undo deleted the record, so the row can still say what the
 * undo did.
 */
internal class ShownImport(val requested: String?, val runId: String?, val record: CopyRecord?)

/**
 * The house list's undo, in the same [ResultCard] as the Export and Import results (UX review, rounds 18 and 19; see
 * [HouseListScreen]). NEUTRAL "Imported from your backup at <time>: 40 copies." with "You can undo this until …" while
 * the undo is on offer and while it runs ("Removing the copies…"), SUCCESS with what the undo did, ERROR (warning sign,
 * error colours) when it failed. The card sits in a live region on a [Box] that is there before anything changes
 * (polite; assertive for a failure), so each change is announced. The button is one call site in the card's actions:
 * *Undo this import* ([onUndo], which asks first; disabled while [undoing] or when [canUndo] is false) and, once the
 * copies are gone, *Close* ([onHide]) in the same place, so TalkBack's focus stays in the row. Before an undo the card
 * also has its 48 dp close button ([onHide]), for a user who wants to keep the copies, and so does a failed undo's card
 * (round 20), for a user who does not want to retry. While [takeFocus] is set the button can take focus in touch mode
 * (`canFocus = true`), so the list can put TalkBack back on it after the confirm dialog ([focusRequester]).
 */
@Composable
internal fun ImportUndoRow(
    count: Int,
    importedAt: Long?,
    until: Long?,
    undoing: Boolean,
    outcome: CopyUndoOutcome?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onHide: () -> Unit,
    focusRequester: FocusRequester,
    takeFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    // Not while a retry runs: the last outcome is still the failure until the new one arrives, and the card then shows
    // "Removing the copies…" as NEUTRAL, with no close button.
    val failed = outcome?.failed == true && !undoing
    val done = outcome != null && !failed && !undoing
    val offered = outcome == null && !undoing
    val text = when {
        undoing -> stringResource(Res.string.import_undoing)
        failed -> stringResource(Res.string.import_undo_failed)
        outcome != null -> undoneSentence(outcome).orEmpty()
        else -> pluralStringResource(Res.plurals.houses_imported_row, count, count, importedAt?.dateText().orEmpty())
    }
    val tone = when {
        failed -> ResultTone.ERROR
        done -> ResultTone.SUCCESS
        else -> ResultTone.NEUTRAL
    }
    Box(
        modifier.fillMaxWidth().semantics {
            liveRegion = if (failed) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    ) {
        ResultCard(
            tone = tone,
            text = text,
            // Closable before an undo (to keep the copies) and after a failed one (round 20: a user who does not want
            // to retry can clear the red card); after a successful one the button itself is Close.
            onDismiss = if (offered || failed) onHide else null,
            actions = {
                if (until != null && offered) {
                    // Under the card's text: the 24 dp icon and its 12 dp gap.
                    Text(
                        stringResource(Res.string.import_undo_until, until.dateText()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp),
                    )
                }
                ResultActionsRow {
                    TextButton(
                        onClick = { if (done) onHide() else onUndo() },
                        enabled = done || (canUndo && !undoing),
                        // The target of the list's focusUndoButton (round 20). A button only takes focus in touch
                        // mode (which TalkBack is) while that is set: canFocus is otherwise left to the system, as for
                        // every other button, so keyboard and D-pad focus is unchanged.
                        modifier = Modifier.heightIn(min = 48.dp).focusRequester(focusRequester).then(
                            if (takeFocus) Modifier.focusProperties { canFocus = true } else Modifier,
                        ),
                    ) { ButtonLabel(stringResource(if (done) Res.string.common_close else Res.string.import_undo_copy)) }
                }
            },
        )
    }
}

/**
 * "Remove the 40 copies imported at <time>?" (UX review, round 19; UX-005). The undo writes synced tombstones and
 * deletes photo files, so the copies go from this phone, the server and every other device, and nothing brings them
 * back: from the lasting house-list row it is confirmed, like every other bulk or house delete in the app. *Remove
 * copies* is a [DangerButton] (error outline, round 21: the same look as Import's *Replace* and the house form's
 * *Delete*); *Keep them* is the dismiss action, and so are Back and a tap outside.
 */
@Composable
internal fun ConfirmUndoDialog(count: Int, importedAt: Long, onRemove: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = {
            Text(pluralStringResource(Res.plurals.houses_undo_confirm_title, count, count, importedAt.dateText()))
        },
        text = { Text(stringResource(Res.string.houses_undo_confirm_body)) },
        confirmButton = {
            // The app's one look for an irreversible choice (Design review, round 21): as Import's *Replace*.
            DangerButton(text = stringResource(Res.string.houses_undo_confirm_remove), onClick = onRemove)
        },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.houses_undo_confirm_keep))
            }
        },
    )
}
