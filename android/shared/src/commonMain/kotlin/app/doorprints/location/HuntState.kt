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

package app.doorprints.location

import app.doorprints.data.HouseEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live state shared between [HuntEngine] (written by it, run by the platform's adapter: Android's `HuntService`) and
 * the UI (the Map's Hunt card). Common since CMP-7 (was in `:app`'s `location/HuntState.kt`), with its package kept,
 * so the common Map reads it.
 */
object HuntState {
    /** Fixes worse than this (metres) are shown but never trigger alerts or visits; the Map calls such a fix weak. */
    const val MAX_ACCURACY_M = 50f

    data class State(
        val active: Boolean = false,
        val lat: Double? = null,
        val lon: Double? = null,
        val accuracyM: Float? = null,
        val street: String? = null,
        val streetHouses: Int = 0,
        val streetVisits: Int = 0,
        val nearestHouse: HouseEntity? = null,
        val nearestDistanceM: Double? = null,
        val staying: Boolean = false,
        val startedAt: Long? = null,
        /** When the last GPS fix arrived; the UI shows "waiting for GPS" when it is old (signal lost indoors). */
        val lastFixAt: Long? = null,
        /**
         * Why Hunt mode stopped by itself, shown on the Map until closed or Hunt mode is turned on again (UX review,
         * whole-app audit); null after the user stopped it.
         */
        val stopReason: StopReason? = null,
    )

    /** Why Hunt mode stopped without being asked to ([HuntEngine], the adapter). */
    enum class StopReason {
        /** The battery fell to `HuntEngine.LOW_BATTERY_PERCENT` and the phone was not charging. */
        LOW_BATTERY,

        /** The location permission was missing or revoked. */
        NO_PERMISSION,

        /** Android refused to run it as a foreground service (started while the app was in the background). */
        NOT_ALLOWED,
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }
}
