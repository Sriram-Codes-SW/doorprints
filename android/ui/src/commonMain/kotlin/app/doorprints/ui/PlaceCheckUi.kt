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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.map_save_here
import app.doorprints.ui.res.trace_here_button
import app.doorprints.ui.res.trace_here_menu_here
import app.doorprints.ui.res.trace_here_menu_spot
import app.doorprints.ui.res.trace_here_pick_confirm
import app.doorprints.ui.res.trace_here_pick_hint
import app.doorprints.ui.res.trace_here_press_menu
import org.jetbrains.compose.resources.stringResource

/** Two footprints, built from a path: the core icon set has none. */
internal val FootprintsIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Footprints", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M7,2c-1.5,0 -2.4,1.9 -2.4,4s0.9,3.4 2.4,3.4S9.4,8.1 9.4,6 8.5,2 7,2z" +
                    "M6.9,11c-1.1,0 -1.9,0.8 -1.9,1.8 0,1.4 0.7,2.2 1.1,3.3 0.3,0.7 0.4,1.4 1.4,1.4s1.1,-0.7 1.4,-1.4c0.4,-1.1 1.1,-1.9 1.1,-3.3 0,-1 -0.8,-1.8 -1.9,-1.8z" +
                    "M17,6c-1.5,0 -2.4,1.9 -2.4,4s0.9,3.4 2.4,3.4 2.4,-1.3 2.4,-3.4S18.5,6 17,6z" +
                    "M16.9,15c-1.1,0 -1.9,0.8 -1.9,1.8 0,1.4 0.7,2.2 1.1,3.3 0.3,0.7 0.4,1.4 1.4,1.4s1.1,-0.7 1.4,-1.4c0.4,-1.1 1.1,-1.9 1.1,-3.3 0,-1 -0.8,-1.8 -1.9,-1.8z",
            ),
            fill = SolidColor(Color.Black),
        ).build()
}

/**
 * The Map's *Have I been here?* button (docs/11 5.27.13): a 48 dp floating button beside the location button, always shown
 * (whether or not the trace is on or Hunt mode runs); it opens a two-row menu, *Where I am now* and *A spot on the map*.
 */
@Composable
fun CheckButton(open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, onHere: () -> Unit, onSpot: () -> Unit) {
    val label = stringResource(Res.string.trace_here_button)
    SmallFloatingActionButton(
        onClick = onOpen,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        Icon(FootprintsIcon, contentDescription = null)
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.trace_here_menu_here)) }, onClick = onHere)
            DropdownMenuItem(text = { Text(stringResource(Res.string.trace_here_menu_spot)) }, onClick = onSpot)
        }
    }
}

/** The long press's small popup (docs/11 5.27.13): *Save house here* and *Did I walk here?*, two rows. */
@Composable
fun LongPressMenu(onSave: () -> Unit, onCheck: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(Modifier.padding(vertical = 8.dp)) {
                TextButton(onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.map_save_here))
                }
                TextButton(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.trace_here_press_menu))
                }
            }
        }
    }
}

/**
 * The accessible way to pick a spot (docs/11 5.27.13, A11Y-B02: no long press needed): a cross at the centre of the map, the
 * hint and *Check this spot* (instead of *Place here*) with *Cancel*. The cross is a form, not a colour.
 */
@Composable
fun SpotPickerCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.trace_here_pick_hint), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.trace_here_pick_confirm)) }
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.common_cancel)) }
            }
        }
    }
}

/** The cross over the map's centre while a spot is being picked: decoration (the card says what to do). */
@Composable
fun SpotCross(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val stroke = 3.dp.toPx()
        drawLine(Color(0xFF1F1F1F), Offset(0f, c.y), Offset(size.width, c.y), strokeWidth = stroke)
        drawLine(Color(0xFF1F1F1F), Offset(c.x, 0f), Offset(c.x, size.height), strokeWidth = stroke)
        drawCircle(Color.White, radius = 4.dp.toPx(), center = c)
    }
}
