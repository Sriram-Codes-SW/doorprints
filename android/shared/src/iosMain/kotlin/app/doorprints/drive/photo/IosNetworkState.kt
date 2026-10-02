package app.doorprints.drive.photo

/** What an `NWPath` says (the host app's `NWPathMonitor` handler keeps the latest one). */
data class IosPathSnapshot(val satisfied: Boolean, val expensive: Boolean, val constrained: Boolean)

/**
 * [NetworkState] on iOS (S4b-BL-128, docs/15 §11), over a snapshot source so the Kotlin side needs no Network-framework
 * binding: the app's `NWPathMonitor` updates the latest [IosPathSnapshot] (`status == .satisfied`, `isExpensive` for mobile
 * data or a hotspot, `isConstrained` for Low Data Mode). No snapshot yet is offline. Compile-only here: it needs a real
 * device (S4b-BL-128 notes).
 */
class IosNetworkState(private val latest: () -> IosPathSnapshot?) : NetworkState {
    override fun current(): NetworkConditions {
        val path = latest() ?: return NetworkConditions.OFFLINE
        return PhotoNetworkPolicy.fromApple(path.satisfied, path.expensive, path.constrained)
    }
}
