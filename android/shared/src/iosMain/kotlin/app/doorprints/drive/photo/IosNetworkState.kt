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
