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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.doorprints.data.HouseEntity
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * What the last listing fill did to the house form (S4b-BL-238): the form before it, for *Undo fill*, and what it wrote,
 * for the "from the listing" marks. Kept with `remember`, not saved: a recreated screen keeps the filled fields and
 * loses only the undo and the marks (as the result line already did, CMP-3 review).
 */
class ListingFillState {
    var before by mutableStateOf<HouseEntity?>(null)
    var merge by mutableStateOf<ListingMerge?>(null)

    fun record(before: HouseEntity, merge: ListingMerge) {
        this.before = before
        this.merge = merge
    }

    fun clear() {
        before = null
        merge = null
    }
}

/**
 * The result line of a listing fill with *Undo fill* (puts the form back as it was before the fill; nothing is saved by
 * either) and, under it, the fields the fill wrote that the person has not edited since ([remainingMarks]). Everything is
 * in the live region that was already there for the result line, so TalkBack reads the undo's confirmation too.
 */
@Composable
fun PasteResult(
    message: String?,
    fill: ListingFillState,
    current: HouseEntity?,
    onDismiss: () -> Unit,
    onUndo: (HouseEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveMessage(modifier) {
        Column {
            message?.let { text ->
                ResultCard(
                    tone = ResultTone.NEUTRAL,
                    text = text,
                    onDismiss = onDismiss,
                    modifier = Modifier.padding(top = 8.dp),
                    actions = {
                        fill.before?.let { before ->
                            TextButton(onClick = { onUndo(before) }) { Text(stringResource(Res.string.house_paste_undo)) }
                        }
                    },
                )
            }
            val merge = fill.merge
            val marks = if (merge != null && current != null) remainingMarks(merge, current) else emptyList()
            if (marks.isNotEmpty()) {
                val names = marks.map { stringResource(it.nameRes) }
                Text(
                    stringResource(Res.string.house_paste_from_listing, names.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The price warning of a fill (S4b-BL-238), as one more line for "Please check": empty when the two readings agree. */
suspend fun priceWarning(text: String, draft: HouseDraftDto): List<String> {
    val (fromText, fromAi) = priceDisagreement(text, draft) ?: return emptyList()
    return listOf(getString(Res.string.house_paste_price_differs, Formats.rupees(fromText), Formats.rupees(fromAi)))
}
