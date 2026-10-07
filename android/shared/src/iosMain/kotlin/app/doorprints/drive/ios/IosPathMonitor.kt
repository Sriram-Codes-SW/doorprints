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

package app.doorprints.drive.ios

import app.doorprints.drive.photo.IosNetworkState
import app.doorprints.drive.photo.IosPathSnapshot
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create
import app.doorprints.concurrent.PlatformLock
import kotlin.concurrent.Volatile

/**
 * The network as the Drive photo rules need it (docs/15 §11): one `NWPathMonitor` (the C API of the Network framework,
 * `nw_path_monitor_*`) for the process, whose latest path becomes the [IosPathSnapshot] [IosNetworkState] asks for:
 * satisfied (a usable route), expensive (mobile data or a hotspot) and constrained (Low Data Mode). Started once, on
 * first use; no snapshot yet reads as offline, which is the safe answer for the first moments. Compiled here, not run
 * (MT-86 on a phone: Wi-Fi, mobile data, Low Data Mode, aeroplane mode).
 */
@OptIn(ExperimentalForeignApi::class)
object IosPathMonitor {
    @Volatile
    private var latest: IosPathSnapshot? = null
    private val lock = PlatformLock()
    private var started = false

    /** The latest path, starting the monitor the first time. */
    fun snapshot(): IosPathSnapshot? {
        ensureStarted()
        return latest
    }

    /** The photo rules' network state over this monitor. */
    fun state(): IosNetworkState = IosNetworkState(::snapshot)

    private fun ensureStarted() = lock.withLock {
        if (!started) {
            started = true
            start()
        }
    }

    private fun start() {
        val monitor = nw_path_monitor_create()
        val queue = dispatch_queue_create("app.doorprints.drive.network", null)
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path ->
            latest = if (path == null) {
                null
            } else {
                IosPathSnapshot(
                    satisfied = nw_path_get_status(path) == nw_path_status_satisfied,
                    expensive = nw_path_is_expensive(path),
                    constrained = nw_path_is_constrained(path),
                )
            }
        }
        nw_path_monitor_start(monitor)
    }
}
