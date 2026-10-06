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
 * The alert's test, one point at a time (docs/11 5.27.3, "The alert's test"; the contract of the vector file's `alert`
 * cases). State: `blocked` and the time of the last alert. [others] are every other walk stored, saved walks included,
 * **except a walk whose last point is less than [TraceConstants.WALK_GAP_MS] before the live walk's first point**
 * (after *Finish walk* at a house, walking back is a different walk, but not one to warn about); the exclusion is for
 * the alert only.
 *
 * Feed it the live walk's kept points in time order with [onPoint]. It keeps the live walk's samples and each sample's
 * near flag, so a point costs only its own new samples (the answer equals the full definition: a sample's near flag
 * never depends on a later one). Pure: no clock (the time of a point is its `atMs`), no I/O.
 */
class RepeatAlert(others: List<List<TracePoint>>) {
    private var others: List<List<TracePoint>>? = others
    private var index: SegmentIndex? = null
    private val builder = SampleBuilder()
    private var near = BooleanArray(64)
    private var count = 0
    private var blocked = false
    private var lastAlertAt: Long? = null

    /**
     * The next kept point of the live walk. Returns the length in metres of the run the alert rings for, or null when
     * it does not ring at this point. A point that is not finite is ignored.
     */
    fun onPoint(p: TracePoint): Double? {
        if (!TraceGeo.isValid(p)) return null
        if (index == null) {
            val live = others!!.filter { it.size >= 2 && p.atMs - it.last().atMs >= TraceConstants.WALK_GAP_MS }
            index = SegmentIndex(live)
            others = null
        }
        val idx = index!!
        builder.add(p.lat, p.lon, p.resumed)
        val s = builder.samples
        if (s.size > near.size) near = near.copyOf(maxOf(s.size, near.size * 2))
        for (i in builder.lastAddedFrom until s.size) {
            near[i] = idx.anyWithin(s.lat[i], s.lon[i], TraceConstants.TOLERANCE_M, -1)
        }
        count++
        val last = s.size - 1
        if (!near[last]) {
            // Unblock only when bridging could no longer join this sample to the run behind it.
            var n = last - 1
            while (n >= 0 && !near[n]) n--
            if (n < 0 || s.part[n] != s.part[last] || s.arc[last] - s.arc[n] > TraceConstants.BRIDGE_M) blocked = false
            return null
        }
        if (count == 1) return null // index 0 never alerts
        // The trailing run: near samples (after bridging, within one part) ending at the last sample.
        var start = last
        while (true) {
            val j = start - 1
            if (j < 0 || s.part[j] != s.part[start]) break
            if (near[j]) { start = j; continue }
            var k = j
            while (k >= 0 && !near[k]) k--
            if (k < 0 || s.part[k] != s.part[start] || s.arc[start] - s.arc[k] > TraceConstants.BRIDGE_M) break
            start = k
        }
        val run = s.arc[last] - s.arc[start]
        val ring = run >= TraceConstants.ALERT_MIN_RUN_M && !blocked &&
            (lastAlertAt == null || p.atMs - lastAlertAt!! >= TraceConstants.ALERT_COOLDOWN_MS)
        if (!ring) return null
        blocked = true
        lastAlertAt = p.atMs
        return run
    }
}
