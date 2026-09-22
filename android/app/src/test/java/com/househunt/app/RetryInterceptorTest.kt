package com.househunt.app

import com.househunt.app.data.Idempotent
import com.househunt.app.data.RetryInterceptor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/** OSI L4: retries with exponential backoff + full jitter, only for idempotent requests. No network needed. */
class RetryInterceptorTest {

    private val sleeps = mutableListOf<Long>()
    private val retry = RetryInterceptor(maxAttempts = 3, random = Random(42), sleep = { sleeps += it })

    /** Plays back canned outcomes: an Int is an HTTP status, an IOException is thrown. */
    private fun client(vararg outcomes: Any): Pair<OkHttpClient, () -> Int> {
        var calls = 0
        val fake = Interceptor { chain ->
            val outcome = outcomes[minOf(calls, outcomes.size - 1)]
            calls++
            if (outcome is IOException) throw outcome
            val code = outcome as Int
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }
        return OkHttpClient.Builder().addInterceptor(retry).addInterceptor(fake).build() to { calls }
    }

    private val get = Request.Builder().url("https://example.invalid/api/stats").build()

    @Test
    fun retriesServerErrorsThenSucceeds() {
        val (http, calls) = client(503, 502, 200)
        http.newCall(get).execute().use { assertEquals(200, it.code) }
        assertEquals(3, calls())
        assertEquals(2, sleeps.size)
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        val (http, calls) = client(503)
        http.newCall(get).execute().use { assertEquals(503, it.code) }
        assertEquals(3, calls())
    }

    @Test
    fun retriesNetworkErrorsForIdempotentCalls() {
        val (http, calls) = client(IOException("reset"), 200)
        http.newCall(get).execute().use { assertEquals(200, it.code) }
        assertEquals(2, calls())
    }

    @Test
    fun doesNotRetryPlainPostsButDoesRetryTaggedOnes() {
        val body = "{}".toRequestBody("application/json".toMediaType())
        val (http, calls) = client(IOException("reset"), 200)
        try {
            http.newCall(Request.Builder().url("https://example.invalid/api/ai/ask").post(body).build()).execute()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(1, calls())

        val (http2, calls2) = client(IOException("reset"), 200)
        val upload = Request.Builder().url("https://example.invalid/api/houses/x/photos").post(body)
            .tag(Idempotent::class.java, Idempotent).build()
        http2.newCall(upload).execute().use { assertEquals(200, it.code) }
        assertEquals(2, calls2())
    }

    @Test
    fun doesNotRetryClientErrors() {
        val (http, calls) = client(401)
        http.newCall(get).execute().use { assertEquals(401, it.code) }
        assertEquals(1, calls())
    }

    @Test
    fun backoffIsJitteredAndCapped() {
        for (attempt in 1..10) {
            val wait = retry.backoffMs(attempt)
            assertTrue(wait in 0..15_000)
            assertTrue(wait <= 1_000L shl (attempt - 1))
        }
    }
}
