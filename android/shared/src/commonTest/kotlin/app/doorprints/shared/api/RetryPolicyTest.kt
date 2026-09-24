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
