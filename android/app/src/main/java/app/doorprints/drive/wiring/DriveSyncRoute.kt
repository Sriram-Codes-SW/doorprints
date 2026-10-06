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

package app.doorprints.drive.wiring

import app.doorprints.data.SyncBackend
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement

/**
 * Carries the Drive sync backend into one run of the repository's sync loop (S4b-BL-70's seam, `syncBackendFor`). The
 * Drive pass ([DriveAssembly]'s `syncPass`) runs `repository.sync` inside [element]; the repository's chooser asks
 * [current] and so finds the backend of exactly that pass. A thread-local context element, so only the coroutine that
 * set it sees it, across every thread switch of the loop, and a server sync running at the same time sees nothing.
 */
class DriveSyncRoute {
    private val holder = ThreadLocal<SyncBackend?>()

    fun element(backend: SyncBackend): CoroutineContext = holder.asContextElement(backend)

    fun current(): SyncBackend? = holder.get()
}
