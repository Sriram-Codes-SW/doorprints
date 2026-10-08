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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.doorprints.shared.ai.ListingCut
import app.doorprints.shared.ai.OnDeviceAi
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The listing text box of *Fill in from listing text* (S4b-BL-182). It keeps everything that is pasted; Extract reads
 * only the first [OnDeviceAi.MAX_INPUT_CHARS] characters of it ([ListingCut]), and the line under the box says how many
 * at the end are left out. The line is in a live region that is there before it appears, so TalkBack reads it when a
 * long paste fills it, and the warning sign and the words carry it, not colour.
 */
@Composable
fun PasteListingText(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val leftOut = ListingCut.of(text).leftOut
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text, onText,
            label = { Text(stringResource(Res.string.house_paste_field)) },
            minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
        )
        LiveMessage {
            if (leftOut > 0) {
                WarnNote(
                    stringResource(
                        Res.string.house_paste_cut,
                        Formats.indianGrouping(OnDeviceAi.MAX_INPUT_CHARS.toString()),
                        Formats.indianGrouping(leftOut.toString()),
                    ),
                )
            }
        }
    }
}
