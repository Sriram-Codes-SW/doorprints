package com.househunt.app.location

import com.househunt.app.data.HouseEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live state shared between the background HuntService and the UI. */
object HuntState {
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }
}
