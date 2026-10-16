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

package app.doorprints.drive.photo

/*
 * When photos may use the network (S4b-BL-128, docs/15 §11): pure logic, no I/O. Web twin: `photo-network-policy.ts`, the
 * same names; `docs/schemas/photo-policy-vectors.json` pins the table on both stacks.
 *
 * Text and the backups are not photos: they go on any network. Only photo bytes (upload and download) ask this.
 * Battery and charging are NOT conditions (owner, 2026-10-02: a person adding photos on the road wants them in Drive).
 */

/** What the network is, as far as the platform can tell. Web usually cannot: [UNKNOWN] is treated as metered. */
enum class Metering { UNMETERED, METERED, UNKNOWN }

/**
 * The network right now. [roaming]: Android `!NET_CAPABILITY_NOT_ROAMING`; [dataSaver]: Android Data Saver, iPhone Low
 * Data Mode (`isConstrained`), the website's `saveData`.
 */
data class NetworkConditions(
    val online: Boolean,
    val metering: Metering,
    val roaming: Boolean = false,
    val dataSaver: Boolean = false,
) {
    companion object {
        val OFFLINE = NetworkConditions(online = false, metering = Metering.UNKNOWN)
    }
}

/** Where the policy gets the network from: Android `ConnectivityManager`, iOS `NWPathMonitor`, the website's `navigator.connection`. */
fun interface NetworkState {
    /** The network as it is at this moment. */
    fun current(): NetworkConditions
}

/**
 * The person's setting *Upload photos on mobile data* (docs/15 §11), off by default. The app's existing
 * `photosOnWifiOnly` (default true) is its inverse ([fromWifiOnly]).
 */
data class PhotoSettings(val uploadOnMobileData: Boolean = false) {
    companion object {
        fun fromWifiOnly(photosOnWifiOnly: Boolean): PhotoSettings = PhotoSettings(uploadOnMobileData = !photosOnWifiOnly)
    }
}

/** The one-off *Upload photos now over mobile data*: valid for [PhotoNetworkPolicy.ONE_OFF_TTL_MS] from [grantedAt], then gone. */
data class OneOffGrant(val grantedAt: Long) {
    val expiresAt: Long get() = grantedAt + PhotoNetworkPolicy.ONE_OFF_TTL_MS

    /** A clock that moved back before [grantedAt] makes it inactive (fail closed). */
    fun isActive(now: Long): Boolean = now >= grantedAt && now < expiresAt
}

/** Why the policy said what it said (the typed outcome behind the status line). */
enum class PhotoAllowReason {
    OFFLINE, UNMETERED, MOBILE_DATA_SETTING, ONE_OFF, WAITING_METERED, WAITING_ROAMING, WAITING_DATA_SAVER,
}

/** What the status line says about photos: *Waiting for Wi-Fi*, *Uploading*, *Paused (offline)*, *Done*. */
enum class PhotoNetworkStatus { WAITING_FOR_WIFI, UPLOADING, PAUSED_OFFLINE, DONE }

/** Whether photo bytes may move now ([allowed]) and why. */
data class PhotoNetworkDecision(val allowed: Boolean, val reason: PhotoAllowReason)

/**
 * The rule for when photo bytes may use the network; a pure function of the network, the setting and the one-off grant.
 */
object PhotoNetworkPolicy {
    /** How long a one-off grant lasts (30 minutes). */
    const val ONE_OFF_TTL_MS: Long = 30L * 60_000

    /**
     * May photo bytes move now? In order: no network, no; an active one-off grant, yes (it also overrides roaming and Data
     * Saver: the person asked, in the app); an unmetered network, yes; a metered or unknown network only with the
     * setting on and neither roaming nor Data Saver/Low Data Mode.
     */
    fun decide(c: NetworkConditions, s: PhotoSettings, grant: OneOffGrant?, now: Long): PhotoNetworkDecision = when {
        !c.online -> PhotoNetworkDecision(false, PhotoAllowReason.OFFLINE)
        grant != null && grant.isActive(now) -> PhotoNetworkDecision(true, PhotoAllowReason.ONE_OFF)
        c.metering == Metering.UNMETERED -> PhotoNetworkDecision(true, PhotoAllowReason.UNMETERED)
        !s.uploadOnMobileData -> PhotoNetworkDecision(false, PhotoAllowReason.WAITING_METERED)
        c.roaming -> PhotoNetworkDecision(false, PhotoAllowReason.WAITING_ROAMING)
        c.dataSaver -> PhotoNetworkDecision(false, PhotoAllowReason.WAITING_DATA_SAVER)
        else -> PhotoNetworkDecision(true, PhotoAllowReason.MOBILE_DATA_SETTING)
    }

    /** The status for [pending] photos waiting to move (uploads and downloads): nothing pending is *Done* whatever the network. */
    fun status(d: PhotoNetworkDecision, pending: Int): PhotoNetworkStatus = when {
        pending <= 0 -> PhotoNetworkStatus.DONE
        d.reason == PhotoAllowReason.OFFLINE -> PhotoNetworkStatus.PAUSED_OFFLINE
        d.allowed -> PhotoNetworkStatus.UPLOADING
        else -> PhotoNetworkStatus.WAITING_FOR_WIFI
    }

    /** Android: `NET_CAPABILITY_INTERNET`, `NET_CAPABILITY_NOT_METERED`, `NET_CAPABILITY_NOT_ROAMING`, Data Saver enabled. */
    fun fromAndroid(hasInternet: Boolean, notMetered: Boolean, notRoaming: Boolean, dataSaverEnabled: Boolean): NetworkConditions =
        NetworkConditions(hasInternet, if (notMetered) Metering.UNMETERED else Metering.METERED, roaming = !notRoaming, dataSaver = dataSaverEnabled)

    /** iPhone: `NWPath.status == .satisfied`, `isExpensive` (mobile data, a hotspot), `isConstrained` (Low Data Mode). */
    fun fromApple(satisfied: Boolean, expensive: Boolean, constrained: Boolean): NetworkConditions =
        NetworkConditions(satisfied, if (expensive) Metering.METERED else Metering.UNMETERED, dataSaver = constrained)
}

/**
 * The gate the sync loop's `photosAllowed` comes from: the network, the setting and the one-off grant, read at the moment
 * of the call. A grant lives in memory (it is a one-off: it is gone with the process, which is safe).
 */
class PhotoUploadGate(
    private val network: NetworkState,
    private val settings: () -> PhotoSettings,
    private val clock: () -> Long,
) {
    var grant: OneOffGrant? = null
        private set

    /** *Upload photos now over mobile data*: valid for [PhotoNetworkPolicy.ONE_OFF_TTL_MS]. */
    fun grantOneOff(): OneOffGrant = OneOffGrant(clock()).also { grant = it }

    /** Drops the one-off grant. */
    fun clearGrant() {
        grant = null
    }

    /** The policy's answer for this moment; an expired grant is dropped first. */
    fun decision(): PhotoNetworkDecision {
        val now = clock()
        if (grant?.isActive(now) == false) grant = null
        return PhotoNetworkPolicy.decide(network.current(), settings(), grant, now)
    }

    /** The `photosAllowed` of `Repository.sync`. */
    fun photosAllowed(): Boolean = decision().allowed

    /** The status line for [pending] photos waiting to move. */
    fun status(pending: Int): PhotoNetworkStatus = PhotoNetworkPolicy.status(decision(), pending)
}
