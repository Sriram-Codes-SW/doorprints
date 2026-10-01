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
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The duplicate-flat warning (docs/11 5.25, S4b-BL-85): "Maybe the same flat as …", naming the other houses within
 * about 30 m with the same bedrooms and floor (`DuplicateFlat`). A warning, never a block: nothing stops a save. Always
 * composed as a polite live region, so TalkBack reads it when it appears; nothing is shown for no [names].
 */
@Composable
fun DuplicateFlatWarning(names: List<String>, modifier: Modifier = Modifier) {
    LiveMessage(modifier) {
        if (names.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Text(
                    stringResource(Res.string.duplicate_flat_warning, joinedList(names)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
