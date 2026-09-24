package app.doorprints.shared.api

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Retry rules of the API client (was OkHttp's RetryInterceptor in :app until Sprint 3.5, same numbers):
 * idempotent calls (GET/HEAD/PUT/DELETE, plus POSTs the client marks idempotent, i.e. the photo upload with its
 * client-chosen id) are retried on network errors and on 408/429/502/503/504, with exponential backoff and full
 * jitter: wait = random(0, min(cap, base * 2^(attempt-1))). A server Retry-After (seconds) is honoured when it is
 * short; a longer one is returned to the caller so WorkManager can retry later instead of holding a worker.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 15_000,
    private val random: Random = Random.Default,
    /** Injected so tests do not wait; production suspends (does not block a thread, unlike Thread.sleep). */
    val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun backoffMs(attempt: Int): Long {
        val exp = baseDelayMs shl (attempt - 1).coerceIn(0, 20)
        return random.nextLong(0, min(maxDelayMs, exp) + 1)
    }

    fun isRetriable(method: String, markedIdempotent: Boolean): Boolean =
        method in IDEMPOTENT_METHODS || markedIdempotent

    companion object {
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PUT", "DELETE")
        val RETRY_CODES = setOf(408, 429, 502, 503, 504)
    }
}
