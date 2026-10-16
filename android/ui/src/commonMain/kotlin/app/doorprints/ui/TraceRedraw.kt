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

import app.doorprints.shared.trace.TraceWalk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/** At most one redraw of the walks this often while a walk is recorded (docs/11 5.27.3 *Cost and limits*, docs/03 §7.2a). */
const val TRACE_REDRAW_MS = 5_000L

/**
 * The first value at once, then at most one value per [periodMs]: the newest one that came meanwhile (so the last change is
 * never lost, only delayed). The Map's walks flow goes through it: a kept point arrives about every 15 s, a brisk walk
 * more often, and each redraw reads every walk and runs the detection.
 */
fun <T> Flow<T>.throttleRedraw(periodMs: Long = TRACE_REDRAW_MS): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMs)
}

/**
 * The last [TraceDrawing] and the key of the walks it was built from: the walks' sources, ids (first time) and point counts.
 * An unchanged set is not detected again (a kept point changes a count, a sync or a house edit changes nothing).
 */
class TraceDrawingCache(private val build: (List<TraceWalk>) -> TraceDrawing = TraceDrawing::of) {
    private var key: String? = null
    private var drawing: TraceDrawing = TraceDrawing.EMPTY

    /** The drawing for [walks]: built again only when their key changed, otherwise the last one. */
    fun of(walks: List<TraceWalk>): TraceDrawing {
        val k = keyOf(walks)
        if (k != key) {
            drawing = build(walks)
            key = k
        }
        return drawing
    }

    companion object {
        fun keyOf(walks: List<TraceWalk>): String =
            walks.joinToString(";") { w -> "${w.source}:${w.points.firstOrNull()?.atMs}:${w.points.firstOrNull()?.walkId}:${w.points.size}" }
    }
}
