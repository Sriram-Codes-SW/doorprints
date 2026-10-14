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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.doorprints.shared.export.ExportFormat
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The line on *Save a copy* that says what the copy holds while two or more kinds are on (kinds PR C, S4b-BL-289; docs/03
 * §18.13): a readable copy holds only the list on screen (*This copy holds only Schools: 3.*), the full backup holds every
 * list. [count] is what the chosen options select, null until it is known (then the short form).
 */
@Composable
fun SpaceScopeNote(space: SpaceUi, format: ExportFormat, count: Int?, modifier: Modifier = Modifier) {
    // A readable copy says nothing until its count is known (the card above says it is counting); the backup is always whole.
    if (format != ExportFormat.BACKUP && count == null) return
    val text = if (format == ExportFormat.BACKUP || count == null) {
        stringResource(Res.string.export_space_scope_backup)
    } else {
        stringResource(Res.string.export_space_scope, stringResource(space.words.many), count)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
