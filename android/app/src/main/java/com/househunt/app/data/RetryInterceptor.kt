package com.househunt.app.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/**
 * Retries idempotent calls (GET/PUT/DELETE/HEAD, and POSTs tagged [Idempotent]) on network errors and on
 * 408/429/502/503/504, with exponential backoff and full jitter: wait = random(0, min(cap, base * 2^(attempt-1))).
 * A server Retry-After (seconds) is honoured when it is short; a long one is returned to the caller so WorkManager
 * can retry later instead of blocking a worker thread.
 *
 * Must be added as an application interceptor (it calls proceed() more than once).
 */
class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 15_000,
    private val random: Random = Random.Default,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val retriable = request.method in IDEMPOTENT_METHODS || request.tag(Idempotent::class.java) != null
        var attempt = 1
        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (!retriable || attempt >= maxAttempts || chain.call().isCanceled()) throw e
                sleep(backoffMs(attempt))
                attempt++
                continue
            }
            if (!retriable || attempt >= maxAttempts || response.code !in RETRY_CODES) return response
            val wait = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: backoffMs(attempt)
            if (wait > maxDelayMs) return response
            response.close()
            sleep(wait)
            attempt++
        }
    }

    fun backoffMs(attempt: Int): Long {
        val exp = baseDelayMs shl (attempt - 1).coerceIn(0, 20)
        return random.nextLong(0, min(maxDelayMs, exp) + 1)
    }

    companion object {
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PUT", "DELETE")
        val RETRY_CODES = setOf(408, 429, 502, 503, 504)
    }
}
