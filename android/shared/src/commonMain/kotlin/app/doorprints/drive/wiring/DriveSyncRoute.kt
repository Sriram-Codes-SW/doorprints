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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Carries the Drive sync backend into one run of the repository's sync loop (S4b-BL-70's seam, `syncBackendFor`). The
 * Drive pass ([DriveAssembly]'s `syncPass`) runs `repository.sync` inside [element]; the repository's chooser asks
 * [current] and so finds the backend of exactly that pass. A coroutine-context element (a thread-local on Android before
 * the iPhone's Drive; Kotlin/Native has none), so only the coroutine that set it, and the ones it starts, see it, across
 * every thread switch of the loop, and a server sync running at the same time sees nothing.
 */
class DriveSyncRoute {
    private class Carrier(val backend: SyncBackend, key: CoroutineContext.Key<Carrier>) : AbstractCoroutineContextElement(key)

    /** One key per route, as the thread-local was one per route: another route's pass is not this route's. */
    private val key = object : CoroutineContext.Key<Carrier> {}

    /**
     * A context element that makes [backend] the one [current] answers with, inside the coroutines that run with it.
     */
    fun element(backend: SyncBackend): CoroutineContext = Carrier(backend, key)

    /** The backend of the Drive pass this coroutine runs in, or null outside one. */
    suspend fun current(): SyncBackend? = coroutineContext[key]?.backend
}
