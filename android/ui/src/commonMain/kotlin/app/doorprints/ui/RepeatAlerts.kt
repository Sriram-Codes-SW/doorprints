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

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The repeated-path alert as the Map hears it (docs/11 5.27.5): the platform's Hunt service posts the notification, and
 * calls [signal] beside it, so with the app in front the Map also shows the same sentence as a snackbar. Nothing is
 * carried (no place, distance or count), nothing is stored, and nothing is heard while the Map is not on screen.
 */
object RepeatAlerts {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val events: SharedFlow<Unit> get() = flow

    /** Tells the Map that a repeated-path alert was just posted; dropped when the Map is not listening. */
    fun signal() {
        flow.tryEmit(Unit)
    }
}
