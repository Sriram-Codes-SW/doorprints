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

/** One metre in degrees on the equator (and of latitude anywhere), the figure of the vector file's conventions. */
internal const val M = 0.000008993216

/** A point [northM] metres north and [eastM] metres east of (0, 0) on the equator. */
internal fun pt(northM: Double, eastM: Double, atMs: Long, walkId: Long = 0, resumed: Boolean = false) =
    TracePoint(northM * M, eastM * M, atMs, walkId, resumed)

/** A straight walk north from [fromM] to [toM] every [stepM] metres, [eastM] east of the axis, a point each [stepMs]. */
internal fun street(fromM: Double, toM: Double, stepM: Double, eastM: Double, startMs: Long, stepMs: Long = 20_000): List<TracePoint> {
    val out = ArrayList<TracePoint>()
    var n = fromM
    var t = startMs
    val dir = if (toM >= fromM) 1 else -1
    while (if (dir > 0) n <= toM + 1e-9 else n >= toM - 1e-9) {
        out.add(pt(n, eastM, t)); n += dir * stepM; t += stepMs
    }
    return out
}

/** A small deterministic generator (the same numbers on every platform). */
internal class Lcg(private var s: Long) {
    fun next(): Double {
        s = (s * 6364136223846793005L + 1442695040888963407L)
        return ((s ushr 11) and 0x1fffffffffffffL).toDouble() / 9007199254740992.0
    }
}
