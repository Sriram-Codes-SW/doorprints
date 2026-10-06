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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** A location reading with its reported accuracy in metres (docs/11 5.27.13). Used for one answer and dropped. */
data class PlaceFix(val lat: Double, val lon: Double, val accuracyM: Double)

/**
 * The best fix over up to [maxWaitMs] (docs/11 5.27.13, *Here*): the first reading of [goodAccuracyM] or better ends the
 * wait; readings worse than that are kept only as the best so far; at the end of the wait the best so far decides (so a
 * worse-than-gate answer is the caller's `IMPRECISE`), and no reading at all gives null. A reading with a negative or
 * non-finite accuracy is no reading. Nothing is stored or logged.
 */
suspend fun bestOf(fixes: Flow<PlaceFix>, maxWaitMs: Long, goodAccuracyM: Double): PlaceFix? {
    var best: PlaceFix? = null
    withTimeoutOrNull(maxWaitMs) {
        fixes.firstOrNull { fix ->
            if (!fix.accuracyM.isFinite() || fix.accuracyM < 0) return@firstOrNull false
            val current = best
            if (current == null || fix.accuracyM < current.accuracyM) best = fix
            fix.accuracyM <= goodAccuracyM
        }
    }
    return best
}
