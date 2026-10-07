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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.doorprints.data.TourEnd

/**
 * What the app knows about whether the tour has been offered: not read yet, never seen, or ended here (done or skipped).
 * Not read yet offers nothing, so a person who has seen the tour never sees the offer flash before the setting loads.
 */
enum class TourMemory {
    LOADING, UNSEEN, SEEN;

    companion object {
        /**
         * [loaded]: the stored choice has been read; [stored]: how the tour ended before, if it did; [endedHere]: it ended in
         * this run, whose write may not have landed yet.
         */
        fun of(loaded: Boolean, stored: TourEnd?, endedHere: Boolean): TourMemory = when {
            endedHere -> SEEN
            !loaded -> LOADING
            stored != null -> SEEN
            else -> UNSEEN
        }
    }
}

/** The one-time offer: on the first visit to the tour's home tab, and never again once the tour has ended. */
object TourOffer {
    fun show(memory: TourMemory, active: Boolean, route: String?, home: String): Boolean =
        memory == TourMemory.UNSEEN && !active && route == home
}

/**
 * Which step of the tour is showing. Pure state (Compose snapshot state, no UI): [start] takes the steps this phone shows,
 * [next] and [back] move, and [next] past the last step or [skip] ends it and says how, so the caller can remember it
 * ([TourEnd]; the offer is not made again either way). The screens never see it; [TourHost] moves the app to
 * [TourStep.route] and highlights its target.
 */
class TourSession {
    private var steps: List<TourStep> by mutableStateOf(emptyList())
    private var index: Int? by mutableStateOf(null)

    val active: Boolean get() = index != null
    val step: TourStep? get() = index?.let(steps::getOrNull)

    /** 0-based; 0 when not running. */
    val position: Int get() = index ?: 0
    val size: Int get() = steps.size
    val isFirst: Boolean get() = position == 0
    val isLast: Boolean get() = position == steps.size - 1

    /** Starts at the first of [steps] (or does nothing for an empty list). */
    fun start(steps: List<TourStep>) {
        if (steps.isEmpty()) return
        this.steps = steps
        index = 0
    }

    /** The next step, or after the last one the end, [TourEnd.DONE]. */
    fun next(): TourEnd? {
        val i = index ?: return null
        if (i >= steps.size - 1) {
            index = null
            return TourEnd.DONE
        }
        index = i + 1
        return null
    }

    /** One step back; never before the first. */
    fun back() {
        val i = index ?: return
        if (i > 0) index = i - 1
    }

    /** "Not now" on the offer, or *Skip tour* and the system Back in the tour: ends it, however far it got. */
    fun skip(): TourEnd {
        index = null
        return TourEnd.SKIPPED
    }
}
