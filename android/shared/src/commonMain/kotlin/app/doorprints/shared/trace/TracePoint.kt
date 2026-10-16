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

package app.doorprints.shared.trace

/**
 * One kept fix of a walk (docs/11 5.27.2). [walkId] is the `atMs` of the walk's first kept point, and **0 is no id**
 * (rows from before the walk ids, and any point whose id is not known): the id rule of the splitter applies only when
 * both points carry a non-zero id. [resumed] is the website's first point after a page hidden for more than five
 * minutes: no segment joins it to the point before it (always false on the phones).
 */
data class TracePoint(
    val lat: Double,
    val lon: Double,
    val atMs: Long,
    val walkId: Long = 0,
    val resumed: Boolean = false,
)

/** Where a walk came from: the 30-day trace or a saved walk (the place check lists a saved walk as such). */
enum class WalkSource { TRACE, SAVED }

/** A walk, already split (docs/11 5.27.3 step 1), for the place check. */
class TraceWalk(val points: List<TracePoint>, val source: WalkSource = WalkSource.TRACE)

/** A stretch of a walk as arc lengths in metres from the walk's first point. */
data class Stretch(val fromM: Double, val toM: Double)

/** One walk's result of [RepeatDetector.detect]. */
data class WalkRepeats(val repeated: List<Stretch>, val shown: List<Stretch>)
