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

package app.doorprints.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The shared cases are in [ParityVectorsTest] (`answerText`); these are the ones the vectors cannot hold (S4b-BL-178). */
class AnswerTextTest {
    private val a = "11111111-1111-4111-8111-111111111111"

    @Test
    fun anAddressInTheRecordsIsKeptAndOneOnlyInTheQuestionIsNot() {
        assertEquals(
            "x https://example.com/l/1 [link removed]",
            AnswerText.clean(
                "![x](https://evil.example/a.png) https://example.com/l/1 https://evil.example/log?d=",
                "House: A\nNotes: see https://example.com/l/1.",
            ),
        )
        assertEquals(AiPrompts.I_DONT_KNOW, AnswerText.clean(AiPrompts.I_DONT_KNOW, ""))
        assertEquals("", AnswerText.clean("", "x"))
    }

    @Test
    fun hostileLongInputIsScannedInLinearTime() {
        val texts = listOf("[", "![", "[a](", "](", "http://", "[x](http://").map { it.repeat(200_000) } +
            listOf("[".repeat(30_000) + "[x]".repeat(30_000), "[" + "a".repeat(200_000), "[a](" + "(".repeat(100_000), "http://" + "a".repeat(1_000_000))
        for (text in texts) {
            val took = measureTime { AnswerText.clean(text, text) }
            assertTrue(took < 5.seconds, "${text.take(12)} took $took")
        }
        assertEquals("x ".repeat(100_000) + "[link removed]", AnswerText.clean("x ".repeat(100_000) + "https://evil.example/y", ""))
    }

    @Test
    fun aPlanSummaryAndReasonsLoseLinksAndForeignAddressesButKeepOnesFromTheHouses() {
        val gate = PlanCandidate(a, "Gate https://example.com/g", "L", null, "NEW", null, null, null, null, 12.975, 77.59, 0)
        val plan = PlanChecks.assemble(
            AgentPlan(
                "Go ![x](https://evil.example/p.png) see https://evil.example/s",
                listOf(AgentStop(a, "Close, [photos](https://evil.example/r) and https://example.com/g, https://evil.example/q")),
            ),
            linkedMapOf(a to gate), emptyList(), 12.9716, 77.5946, 8,
        )
        assertEquals("Go x see [link removed]", plan.summary)
        assertEquals("Close, photos and https://example.com/g, [link removed]", plan.stops.single().reason)
    }
}
