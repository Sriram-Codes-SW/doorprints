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

import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

// Darwin's CLOCK_MONOTONIC keeps counting while the device sleeps, like Android's elapsedRealtime (S4b-BL-40); the
// system uptime (NSProcessInfo.systemUptime, CLOCK_UPTIME_RAW) stops, so a screen come back to after a night's sleep
// would not refresh.
internal actual fun elapsedRealtimeMillis(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()) / 1_000_000u).toLong()
