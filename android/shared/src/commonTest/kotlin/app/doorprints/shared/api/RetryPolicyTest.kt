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

package app.doorprints.shared.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {

    private val policy = RetryPolicy(random = Random(42))

    @Test
    fun backoffIsJitteredAndCapped() {
        for (attempt in 1..10) {
            val wait = policy.backoffMs(attempt)
            assertTrue(wait in 0..15_000)
            assertTrue(wait <= 1_000L shl (attempt - 1))
        }
        // Absurd attempt numbers must not overflow the shift.
        assertTrue(policy.backoffMs(1_000) in 0..15_000)
    }

    @Test
    fun onlyIdempotentOrMarkedCallsRetry() {
        for (m in listOf("GET", "HEAD", "PUT", "DELETE")) assertTrue(policy.isRetriable(m, markedIdempotent = false))
        assertFalse(policy.isRetriable("POST", markedIdempotent = false))
        assertTrue(policy.isRetriable("POST", markedIdempotent = true))
        assertEquals(setOf(408, 429, 502, 503, 504), RetryPolicy.RETRY_CODES)
    }
}
