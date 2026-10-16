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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.shared.trace.RepeatLook
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.trace_legend_once
import app.doorprints.ui.res.trace_legend_repeated
import app.doorprints.ui.res.trace_legend_title
import org.jetbrains.compose.resources.stringResource

/** The trace's colours as the map draws them (the tiles stay light in both themes, so these do not follow the theme). */
internal val TraceBaseColor = Color(0xFF8E24AA)
internal val TraceRepeatColor = Color(0xFFE65100)

/**
 * The Map's trace legend (docs/11 5.27.4): *Your paths* with a sample of each line, solid *Walked once* and dashed in
 * the second colour *Walked more than once*, drawn with the same widths and dash as the layers ([repeatWidthStops],
 * [TRACK_REPEAT_DASH]; the sample is at street zoom, 5 and 9 px). With *Off* the repeats are not marked, so only the
 * first line shows. The samples are decoration: the words say what each is, and the dash is the second cue beside
 * the colour (WCAG 1.4.1).
 */
@Composable
fun TraceLegend(look: RepeatLook, modifier: Modifier = Modifier) {
    val title = stringResource(Res.string.trace_legend_title)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        modifier = modifier.semantics {
            contentDescription = title
            heading()
            isTraversalGroup = true
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TraceSample(repeated = false, look = look)
                Text(stringResource(Res.string.trace_legend_once), style = MaterialTheme.typography.labelMedium)
            }
            if (look != RepeatLook.OFF) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TraceSample(repeated = true, look = look)
                    Text(stringResource(Res.string.trace_legend_repeated), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** One sample line: the base's purple, or the overlay's orange with its dash and its look's width. */
@Composable
private fun TraceSample(repeated: Boolean, look: RepeatLook) {
    Canvas(Modifier.size(width = 60.dp, height = 12.dp)) {
        val y = size.height / 2
        if (!repeated) {
            drawLine(TraceBaseColor.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width, y), strokeWidth = TRACK_WIDTHS[1].second.dp.toPx(), cap = StrokeCap.Round)
        } else {
            // The purple line under the dashes, then the dashes: three widths of dash and two of gap.
            val width = repeatWidthStops(look)[1].second.dp.toPx()
            drawLine(TraceBaseColor.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width, y), strokeWidth = TRACK_WIDTHS[1].second.dp.toPx(), cap = StrokeCap.Round)
            val dash = floatArrayOf((TRACK_REPEAT_DASH[0] * width).toFloat(), (TRACK_REPEAT_DASH[1] * width).toFloat())
            drawLine(
                TraceRepeatColor.copy(alpha = TRACK_REPEAT_OPACITY.toFloat()), Offset(0f, y), Offset(size.width, y),
                strokeWidth = width, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(dash),
            )
        }
    }
}
