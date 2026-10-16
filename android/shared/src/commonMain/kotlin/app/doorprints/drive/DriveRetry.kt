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

package app.doorprints.drive

import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

/**
 * The access token for Drive, from whoever holds the grant (S4b-BL-117: the website's GIS token client, the phones'
 * PKCE flow). The Drive layer never stores, logs or shows it.
 */
interface TokenProvider {
    /** A current access token; throws when there is none (not connected, the grant revoked). */
    suspend fun accessToken(): String

    /**
     * Drive refused [token] (401). The provider drops it, so the next [accessToken] gets a fresh one or throws. A
     * [DriveClient] asks once more after this and then fails with [DriveException.Kind.UNAUTHORIZED].
     */
    suspend fun onRejected(token: String) {}
}

/**
 * The retry rule of docs/15 §5.2, the same on both stacks (web: `DriveRetry`; drive-vectors.json `backoff`): at most
 * [maxAttempts] tries for a [DriveException.isRetryable] failure; before try n+1 the wait is Drive's `Retry-After` when
 * it gave one (and at most [maxRetryAfterMs]; longer, and the error goes back to the caller so the scheduler retries
 * later instead of holding a worker), else exponential backoff with full jitter:
 * `floor(random() * (min(capMs, baseMs * 2^(n-1)) + 1))` with `random()` in [0, 1).
 */
class DriveRetry(
    val maxAttempts: Int = 5,
    val baseMs: Long = 1_000,
    val capMs: Long = 32_000,
    val maxRetryAfterMs: Long = 60_000,
    /** In [0, 1); injected so the vectors and tests are exact. */
    private val random: () -> Double = { Random.Default.nextDouble() },
    /** Injected so tests do not wait (the fake Drive moves its clock instead). */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The jittered backoff before try [attempt] + 1 (attempt counts the tries made, from 1). */
    fun backoffMs(attempt: Int): Long {
        val ceiling = min(capMs, baseMs shl (attempt - 1).coerceIn(0, 30))
        return floor(random() * (ceiling + 1)).toLong().coerceIn(0, ceiling)
    }

    /** How long to wait after try [attempt] failed with [error], or null to give up and throw it. */
    fun waitFor(attempt: Int, error: DriveException): Long? {
        if (!error.isRetryable || attempt >= maxAttempts) return null
        val asked = error.retryAfterMs ?: return backoffMs(attempt)
        return if (asked > maxRetryAfterMs) null else asked
    }

    /** Waits as [waitFor] says, or throws [error]. */
    suspend fun pause(attempt: Int, error: DriveException) {
        sleep(waitFor(attempt, error) ?: throw error)
    }

    /** Waits the jittered backoff after try [attempt] (for a wait that has no Drive error, such as a late checksum). */
    suspend fun backoff(attempt: Int) {
        sleep(backoffMs(attempt))
    }

    /** Runs [block] until it succeeds, a failure is not retryable, or the tries are used up. */
    suspend fun <T> run(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: DriveException) {
                pause(attempt, e)
                attempt++
            }
        }
    }
}

/**
 * One request with a token from [tokens]: on a 401 the token is reported ([TokenProvider.onRejected]) and the request
 * made once more with the next one; a second 401 is thrown. Both clients use it, so they agree. Public (not `internal`) so
 * the fake Drive, which `:app`'s tests compile from `:shared`'s commonTest, runs this rule and not a copy of it.
 */
suspend fun <T> authorized(tokens: TokenProvider, block: suspend (token: String) -> T): T {
    val first = tokens.accessToken()
    return try {
        block(first)
    } catch (e: DriveException) {
        if (e.kind != DriveException.Kind.UNAUTHORIZED) throw e
        tokens.onRejected(first)
        block(tokens.accessToken())
    }
}
