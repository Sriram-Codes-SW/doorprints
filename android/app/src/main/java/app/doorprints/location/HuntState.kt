package app.doorprints.location

import app.doorprints.data.HouseEntity
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
        /**
         * Why Hunt mode stopped by itself, shown on the Map until closed or Hunt mode is turned on again (UX review,
         * whole-app audit); null after the user stopped it.
         */
        val stopReason: StopReason? = null,
    )

    /** Why [HuntService] stopped without being asked to. */
    enum class StopReason {
        /** The battery fell to [HuntService.LOW_BATTERY_PERCENT] and the phone was not charging. */
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
