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

import app.doorprints.data.Repository
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.PairPolledDto
import app.doorprints.shared.api.PairStartedDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How connecting by code ended (docs/03 §12.1, ADR-25). */
sealed interface PairingEnd {
    /** The owner approved: the device key for [serverUrl], handed over once. */
    data class Approved(val serverUrl: String, val deviceKey: String) : PairingEnd
    /** The owner refused the request. */
    data object Denied : PairingEnd
    /** The code ran out: the server said so, or the wait here ended first. */
    data object Expired : PairingEnd
    /** The server is older than pairing (404 or 405 on `/api/pair/start`). */
    data object NoPairing : PairingEnd
    /** The request failed; [error] says why (see `SyncOutcome.fromError`). */
    data class Failed(val error: Throwable) : PairingEnd
}

/** [pairByCode] with the [Repository]'s calls. */
suspend fun pairByCode(
    repo: Repository,
    serverUrl: String,
    deviceName: String,
    onCode: (PairStartedDto) -> Unit,
): PairingEnd = pairByCode(
    serverUrl,
    start = { repo.startPairing(serverUrl, deviceName) },
    poll = { token -> repo.pollPairing(serverUrl, token) },
    onCode = onCode,
)

/**
 * Connects by code: [start] asks the server for a code, [onCode] shows it, and [poll] asks at the server's interval
 * until the owner approves or denies it on the owner page, or it expires. Two network failures in a row are
 * tolerated; the third ends it. Cancelling the calling coroutine (*Cancel*, leaving the screen) stops it; the code then
 * expires on the server by itself. [wait] is the pause between polls and [clock] measures the code's lifetime (a
 * test's virtual time).
 */
suspend fun pairByCode(
    serverUrl: String,
    start: suspend () -> PairStartedDto,
    poll: suspend (pollToken: String) -> PairPolledDto,
    onCode: (PairStartedDto) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
    clock: TimeSource = TimeSource.Monotonic,
): PairingEnd {
    val started = try {
        start()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        return if (e.code == 404 || e.code == 405) PairingEnd.NoPairing else PairingEnd.Failed(e)
    } catch (e: Exception) {
        return PairingEnd.Failed(e)
    }
    onCode(started)
    val deadline = clock.markNow() + started.expiresIn.seconds
    var failures = 0
    while (true) {
        wait(started.interval.coerceAtLeast(1) * 1000L)
        if (deadline.hasPassedNow()) return PairingEnd.Expired
        val polled = try {
            poll(started.pollToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (++failures >= 3) return PairingEnd.Failed(e)
            continue
        }
        failures = 0
        when (polled.status) {
            "approved" -> polled.deviceKey?.let { return PairingEnd.Approved(serverUrl, it) }
            "denied" -> return PairingEnd.Denied
            "expired" -> return PairingEnd.Expired
        }
    }
}

/** The code letter by letter for a screen reader ("K 7 M Q, 4 X R D"), so it is not read as a word. */
fun spellCode(code: String): String = code.split('-').joinToString(", ") { part -> part.toList().joinToString(" ") }

/** A key typed in the older way (the owner key), not a device key from pairing. */
fun isTypedKey(key: String): Boolean = key.isNotBlank() && !key.startsWith("dpk_")
