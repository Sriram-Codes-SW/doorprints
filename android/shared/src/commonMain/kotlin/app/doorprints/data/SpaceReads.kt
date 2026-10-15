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
package app.doorprints.data

import app.doorprints.shared.model.KindTally
import app.doorprints.shared.model.SpaceView
import app.doorprints.shared.model.query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

// The reads of the houses by space (S4b-BL-303), apart from CommonRepository (at its size budget). Each waits for the once-only
// move of contacts into brokers (slice 1b), [beforeRead], before the first row, so no screen sees the houses before it.

/** The live candidates [space] holds, newest edit first: every row, the rows of some kinds, or all but some (`SpaceView.query`). */
internal fun HouseDao.spaceRows(space: SpaceView, beforeRead: suspend () -> Unit): Flow<List<HouseEntity>> = flow {
    beforeRead()
    val q = space.query()
    when {
        q.none -> emit(emptyList())
        !q.except -> emitAll(observeKinds(q.kinds))
        q.kinds.isEmpty() -> emitAll(observeAll())
        else -> emitAll(observeKindsExcept(q.kinds))
    }
}

/** One row per kind with a live candidate: how many and when the newest changed. */
internal fun HouseDao.tallies(beforeRead: suspend () -> Unit): Flow<List<KindTally>> = flow {
    beforeRead()
    emitAll(observeKindTallies())
}
