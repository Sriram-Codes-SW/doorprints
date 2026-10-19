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

package app.doorprints.shared.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TC-U-286 (docs/06): the free-plan rule for voice input (owner decision 2026-10-19, ADR-37, RR-33) on the shared vectors
 * `docs/schemas/voice-tier-vectors.json`, which the website's `voice-tier.spec.ts` runs too. A confirmed free signal never becomes
 * ALLOW, whatever else is true; the readings of the two probes; the key's fingerprint.
 */
class VoiceTierVectorsTest {
    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir) { "the repository root was not found" }, VECTORS).readText()).jsonObject
    }

    private fun cases(name: String) = root.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun probeOf(s: String) = TierProbe.valueOf(s.uppercase())

    @Test
    fun theConstantsOfTheVectorsAreTheConstantsOfTheCode() {
        val c = root.getValue("constants").jsonObject
        assertEquals(VoiceTier.ALLOW_TTL_MS, c.getValue("allowTtlMs").jsonPrimitive.long)
        assertEquals(VoiceTier.REFUSE_FREE_TTL_MS, c.getValue("refuseFreeTtlMs").jsonPrimitive.long)
        assertEquals(VoiceTier.FINGERPRINT_HEX, c.getValue("fingerprintHexLength").jsonPrimitive.int)
        assertEquals(VoiceTier.GEMINI_PROBE_MODEL, c.text("geminiProbeModel"))
        assertEquals(VoiceTier.GEMINI_PROBE_PROMPT, c.text("geminiProbePrompt"))
        assertEquals(c.getValue("geminiProbeMaxOutputTokens").jsonPrimitive.int, VoiceTier.geminiProbeBody()
            .getValue("generationConfig").jsonObject.getValue("maxOutputTokens").jsonPrimitive.int)
    }

    @Test
    fun theRuleAgreesWithEveryVector() {
        val rows = cases("rule")
        assertTrue(rows.size >= 30, "the vectors are the table both stacks read")
        for (c in rows) {
            val cached = (c["cached"] as? JsonObject)?.let { CachedTier(VoiceVerdict.valueOf(it.text("verdict")), it.getValue("ageMs").jsonPrimitive.long) }
            val input = VoiceTierInput(c.text("provider"), c.text("model"), probeOf(c.text("probe")), cached, c.getValue("acknowledged").jsonPrimitive.boolean)
            assertEquals(VoiceVerdict.valueOf(c.text("expect")), VoiceTier.decide(input), c.text("note"))
        }
    }

    @Test
    fun aFreeSignalIsNeverAllowForAnyProviderAcknowledgementOrCache() {
        val caches = listOf(null, CachedTier(VoiceVerdict.ALLOW, 0), CachedTier(VoiceVerdict.REFUSE_FREE, 0))
        for (provider in listOf("gemini", "openai", "openrouter", "groq", "sarvam", "somewhere-new")) {
            for (ack in listOf(false, true)) {
                for (cached in caches) {
                    assertEquals(VoiceVerdict.REFUSE_FREE, VoiceTier.decide(VoiceTierInput(provider, "", TierProbe.FREE, cached, ack)), provider)
                }
            }
            assertEquals(VoiceVerdict.REFUSE_FREE, VoiceTier.decide(VoiceTierInput(provider, "x/y:free", TierProbe.PAID, null, true)), provider)
            assertEquals(
                VoiceVerdict.REFUSE_FREE,
                VoiceTier.decide(VoiceTierInput(provider, "", TierProbe.NONE, CachedTier(VoiceVerdict.REFUSE_FREE, 5), true)), provider,
            )
        }
    }

    @Test
    fun theGeminiProbeIsReadByItsQuotaIds() {
        for (c in cases("geminiProbe")) {
            assertEquals(probeOf(c.text("expect")), VoiceTier.geminiProbeResult(c.getValue("status").jsonPrimitive.int, c.text("body")), c.text("note"))
        }
    }

    @Test
    fun openRouterKeyInformationIsReadByIsFreeTier() {
        for (c in cases("openRouterKeyInfo")) {
            assertEquals(probeOf(c.text("expect")), VoiceTier.openRouterKeyResult(c.getValue("status").jsonPrimitive.int, c.text("body")), c.text("note"))
        }
    }

    @Test
    fun theFingerprintIsTheFirstSixteenHexOfTheSha256() {
        for (c in cases("fingerprint")) {
            assertEquals(c.text("expect"), VoiceTier.fingerprint(c.text("input")), c.text("input"))
        }
        val a = VoiceTier.fingerprint("placeholder-key-aaaa")
        assertNotEquals(a, VoiceTier.fingerprint("placeholder-key-aaab"))
        assertEquals(16, a.length)
        assertFalse("placeholder" in a)
    }

    @Test
    fun theGeminiProbeSendsOneFixedWordAndOneTokenAndNothingElse() {
        val body = VoiceTier.geminiProbeBody().toString()
        assertEquals("""{"contents":[{"role":"user","parts":[{"text":"Hi"}]}],"generationConfig":{"maxOutputTokens":1,"temperature":0}}""", body)
        assertFalse(Regex("inlineData|audio|systemInstruction").containsMatchIn(body))
    }

    @Test
    fun onlyProvidersTheRuleCannotReadMayBeConfirmedByThePerson() {
        assertTrue(listOf("groq", "sarvam", "gemini", "openrouter").all(VoiceTier::canAcknowledge))
        assertFalse(listOf("openai", "ollama", "lmstudio", "custom").any(VoiceTier::canAcknowledge))
    }

    private companion object {
        const val VECTORS = "docs/schemas/voice-tier-vectors.json"
    }
}
