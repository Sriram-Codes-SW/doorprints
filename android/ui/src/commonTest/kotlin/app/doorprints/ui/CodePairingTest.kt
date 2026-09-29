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

import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.PairPolledDto
import app.doorprints.shared.api.PairStartedDto
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Connecting by code (docs/03 §12.1, ADR-25): the loop that shows the code and waits for the owner. */
class CodePairingTest {
    private val started = PairStartedDto(userCode = "K7MQ-4XRD", pollToken = "tok", expiresIn = 600, interval = 3)
    private val clock = TestTimeSource()
    private val waits = mutableListOf<Long>()
    private val shown = mutableListOf<String>()

    private suspend fun run(
        start: suspend () -> PairStartedDto = { started },
        replies: MutableList<() -> PairPolledDto>,
    ): PairingEnd = pairByCode(
        "https://home.example.org",
        start = start,
        poll = { token ->
            assertEquals("tok", token)
            replies.removeAt(0)()
        },
        onCode = { shown += it.userCode },
        wait = { ms -> waits += ms; clock += ms.milliseconds },
        clock = clock,
    )

    @Test
    fun showsTheCodePollsAtTheIntervalAndReturnsTheKeyForThatServer() = runTest {
        val end = run(replies = mutableListOf({ PairPolledDto("pending") }, { PairPolledDto("approved", "dpk_x") }))
        assertEquals(PairingEnd.Approved("https://home.example.org", "dpk_x"), end)
        assertEquals(listOf("K7MQ-4XRD"), shown)
        assertEquals(listOf(3000L, 3000L), waits)
    }

    @Test
    fun deniedAndExpiredEndIt() = runTest {
        assertEquals(PairingEnd.Denied, run(replies = mutableListOf({ PairPolledDto("denied") })))
        assertEquals(PairingEnd.Expired, run(replies = mutableListOf({ PairPolledDto("expired") })))
    }

    @Test
    fun theCodesLifetimeEndsItEvenIfTheServerKeepsSayingPending() = runTest {
        val end = run(replies = MutableList(1000) { { PairPolledDto("pending") } })
        assertEquals(PairingEnd.Expired, end)
        // 600 s at 3 s: 199 polls, then the 200th wait passes the end.
        assertEquals(200, waits.size)
    }

    @Test
    fun twoNetworkFailuresInARowAreToleratedTheThirdEndsIt() = runTest {
        val flaky = run(
            replies = mutableListOf(
                { throw IOException("offline") },
                { throw IOException("offline") },
                { PairPolledDto("approved", "dpk_x") },
            ),
        )
        assertIs<PairingEnd.Approved>(flaky)
        val failed = run(replies = MutableList(3) { { throw IOException("offline") } })
        assertIs<PairingEnd.Failed>(failed)
    }

    @Test
    fun anOlderServerWithoutPairingIsNamedAndOtherStartFailuresAreReported() = runTest {
        val old = run(start = { throw ApiException(ApiException.Kind.NOT_FOUND, 404) }, replies = mutableListOf())
        assertEquals(PairingEnd.NoPairing, old)
        val limited = run(start = { throw ApiException(ApiException.Kind.RATE_LIMITED, 429) }, replies = mutableListOf())
        assertIs<PairingEnd.Failed>(limited)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun spellsTheCodeAndTellsATypedKeyFromADeviceKey() {
        assertEquals("K 7 M Q, 4 X R D", spellCode("K7MQ-4XRD"))
        assertTrue(isTypedKey("an-owner-key-from-the-settings-file"))
        assertEquals(false, isTypedKey("dpk_abc"))
        assertEquals(false, isTypedKey(""))
    }
}
