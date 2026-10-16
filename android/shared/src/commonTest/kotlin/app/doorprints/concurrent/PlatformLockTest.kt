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

package app.doorprints.concurrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformLockTest {
    @Test
    fun theBlockRunsAndItsValueComesBack() {
        assertEquals(42, PlatformLock().withLock { 42 })
    }

    @Test
    fun theLockIsReentrantBecauseAStoreCallsAnotherStoreMethodInsideItsOwn() {
        val lock = PlatformLock()
        assertEquals("inner", lock.withLock { lock.withLock { "inner" } })
    }

    @Test
    fun anExceptionReleasesTheLockSoTheNextCallIsNotStuck() {
        val lock = PlatformLock()
        assertFailsWith<IllegalStateException> { lock.withLock { throw IllegalStateException("boom") } }
        assertEquals(1, lock.withLock { 1 })
    }
}
