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

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.AppSettings
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.settings_path_trace
import app.doorprints.ui.res.settings_path_trace_clear
import app.doorprints.ui.res.settings_path_trace_cleared
import app.doorprints.ui.res.settings_path_trace_hint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The path trace in Settings' Hunt mode section (docs/11 5.27, S4b-FR-2): *Trace my path on the map*, off by default,
 * with the hint that says what is kept and where (this phone only, 30 days), and *Clear the path* while there is one.
 * Turning the switch off keeps the points until they age out or are cleared, so a person who turns it off for a day
 * does not lose the week; the map draws the line only from what is kept. Nothing here is a permission: the trace only
 * records while hunt mode runs, which has its own.
 */
@Composable
fun PathTraceSection(settings: AppSettings) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    val track by repo.trackPoints.collectAsStateWithLifecycle(initialValue = emptyList())
    var cleared by remember { mutableStateOf(false) }
    SwitchRow(
        text = stringResource(Res.string.settings_path_trace),
        hint = stringResource(Res.string.settings_path_trace_hint),
        checked = settings.pathTrace,
        horizontalPadding = 0.dp,
        onChange = { on -> scope.launch { repo.settings.savePathTrace(on) } },
    )
    if (track.isNotEmpty()) {
        OutlinedButton(
            onClick = { scope.launch { repo.clearTrack(); cleared = true } },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(Res.string.settings_path_trace_clear)) }
    } else if (cleared) {
        // Confirmation in place of the button, announced once (WCAG 4.1.3); gone when the section is left.
        Text(
            stringResource(Res.string.settings_path_trace_cleared),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
