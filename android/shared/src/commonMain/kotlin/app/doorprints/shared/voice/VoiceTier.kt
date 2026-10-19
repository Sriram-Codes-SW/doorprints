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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What the rule says. [CHECKING] is only ever transient: the probe has not answered yet. */
enum class VoiceVerdict { ALLOW, REFUSE_FREE, REFUSE_UNKNOWN, CHECKING }

/** Thrown, before any request, when the free-plan rule does not allow voice for this key (the words come from `voice_tier_*`). */
class VoiceTierRefused(val verdict: VoiceVerdict, val provider: String) : Exception("voice not allowed: $verdict")

/** What a probe (or the lack of one) found: not asked yet, under way, a free plan, a paid plan, or no signal. */
enum class TierProbe { NONE, RUNNING, FREE, PAID, UNKNOWN }

/** A kept verdict. Only definite evidence is ever kept: [VoiceVerdict.ALLOW] (paid) or [VoiceVerdict.REFUSE_FREE]. */
data class CachedTier(val verdict: VoiceVerdict, val ageMs: Long)

/** Everything the rule reads. [model] is the transcription model the request would name ("" when none). */
data class VoiceTierInput(
    val provider: String,
    val model: String,
    val probe: TierProbe,
    val cached: CachedTier?,
    /** The person confirmed, for this key, that the plan is paid. */
    val acknowledged: Boolean,
)

/**
 * Whether voice input may send the speaker's audio to the person's provider with the key they gave (owner decision
 * 2026-10-19; docs/03 ADR-37, docs/02 RR-33 and T-I46). A free plan may let the provider use what is said to improve its
 * products, so voice input refuses a free plan and lets a paid one through. The rule and the readings of the evidence that feed
 * it; no screen, no network. The twin of the website's `voice-tier.ts`; both run `docs/schemas/voice-tier-vectors.json`.
 *
 * Evidence per provider (read from the providers' own pages on 2026-10-19; docs/ai/voice-input.md section 8):
 *  - Gemini: a 429 whose `error.details[].violations[].quotaId` contains `FreeTier` (a paid project never returns one). The
 *    probe is one call to a model that has no free tier, so a free key is refused with that quota id.
 *  - OpenRouter: `GET /api/v1/key` answers `data.is_free_tier`; a model id ending `:free` is free whatever the key.
 *  - OpenAI: no free API tier, so paid by policy (the opt-in complimentary-token programme is disclosed, not detectable).
 *  - Groq and Sarvam: the tier cannot be read by any API, so unknown.
 *  - Ollama, LM Studio and a custom address: allowed (nothing leaves the device, or the address is the person's own choice).
 * Unknown refuses until the person says, once per key, that the plan is paid. A confirmed free plan has no such way out.
 */
object VoiceTier {
    /** A paid verdict is kept for 7 days; a free one for 24 hours (a person may upgrade). */
    const val ALLOW_TTL_MS: Long = 7L * 24 * 3_600_000
    const val REFUSE_FREE_TTL_MS: Long = 24L * 3_600_000

    /** The first 16 hex characters of the SHA-256 of a key name its kept verdict; never the key, never reversible to it. */
    const val FINGERPRINT_HEX = 16

    /** The model Gemini is probed with: documented as having no free tier (ai.google.dev pricing, read 2026-10-19). */
    const val GEMINI_PROBE_MODEL = "gemini-3.1-pro-preview"

    /** The fixed one-word prompt; no audio, no candidate data, no device data. */
    const val GEMINI_PROBE_PROMPT = "Hi"
    const val OPENROUTER_KEY_URL = "https://openrouter.ai/api/v1/key"

    private val OWN_PLACES = listOf("ollama", "lmstudio", "custom")
    private val PROBED = listOf("gemini", "openrouter")
    private val json = Json { ignoreUnknownKeys = true }

    /** The provider's name as the person knows it, for the words about its plan. */
    fun displayName(provider: String): String = when (provider.lowercase()) {
        "gemini" -> "Gemini"
        "openai" -> "OpenAI"
        "openrouter" -> "OpenRouter"
        "groq" -> "Groq"
        "sarvam" -> "Sarvam"
        else -> provider
    }

    /** True for a model id ending `:free` (OpenRouter's free variants), whatever the case. */
    fun isFreeModel(model: String): Boolean = model.trim().lowercase().endsWith(":free")

    /** The rule (the `rule` vectors). A free signal is never turned into ALLOW; acknowledging only opens an unknown plan. */
    fun decide(i: VoiceTierInput): VoiceVerdict {
        val provider = i.provider.lowercase()
        if (provider in OWN_PLACES) return VoiceVerdict.ALLOW
        if (isFreeModel(i.model)) return VoiceVerdict.REFUSE_FREE
        val fresh = i.cached?.takeIf { it.ageMs < (if (it.verdict == VoiceVerdict.ALLOW) ALLOW_TTL_MS else REFUSE_FREE_TTL_MS) }
        if (i.probe == TierProbe.FREE || fresh?.verdict == VoiceVerdict.REFUSE_FREE) return VoiceVerdict.REFUSE_FREE
        if (i.probe == TierProbe.PAID || fresh?.verdict == VoiceVerdict.ALLOW) return VoiceVerdict.ALLOW
        if (i.probe == TierProbe.RUNNING) return VoiceVerdict.CHECKING
        if (provider == "openai") return VoiceVerdict.ALLOW
        if (i.probe == TierProbe.NONE && provider in PROBED) return VoiceVerdict.CHECKING
        return if (i.acknowledged) VoiceVerdict.ALLOW else VoiceVerdict.REFUSE_UNKNOWN
    }

    /** True when the rule needs a call to read this provider's evidence. */
    fun needsProbe(provider: String): Boolean = provider.lowercase() in PROBED

    /** True when an unknown plan can be confirmed by the person (every provider the rule cannot read). */
    fun canAcknowledge(provider: String): Boolean = provider.lowercase().let { it !in OWN_PLACES && it != "openai" }

    /** The body of the one Gemini probe call: a fixed one-word prompt, one output token, nothing else. */
    fun geminiProbeBody(): JsonObject = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", GEMINI_PROBE_PROMPT) }) })
            })
        })
        put("generationConfig", buildJsonObject {
            put("maxOutputTokens", 1)
            put("temperature", 0)
        })
    }

    /**
     * What Gemini's answer to the probe says (the `geminiProbe` vectors): an answer from a model that has no free tier is a paid
     * project; a 429 naming a `FreeTier` quota id is a free one; a 429 naming only other quotas is a paid one that is being rate
     * limited; anything else (a refused key, a missing model, no network) says nothing.
     */
    fun geminiProbeResult(status: Int, body: String): TierProbe {
        if (status in 200..299) return TierProbe.PAID
        if (status != 429) return TierProbe.UNKNOWN
        val ids = quotaIds(body)
        if (ids.isEmpty()) return TierProbe.UNKNOWN
        return if (ids.any { "FreeTier" in it }) TierProbe.FREE else TierProbe.PAID
    }

    /** What OpenRouter's key information says (the `openRouterKeyInfo` vectors): `data.is_free_tier` as a real boolean, else nothing. */
    fun openRouterKeyResult(status: Int, body: String): TierProbe {
        if (status !in 200..299) return TierProbe.UNKNOWN
        val flag = (runCatching { json.parseToJsonElement(body).jsonObject["data"] }.getOrNull() as? JsonObject)?.get("is_free_tier")
        val value = (flag as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return when (value) {
            true -> TierProbe.FREE
            false -> TierProbe.PAID
            null -> TierProbe.UNKNOWN
        }
    }

    private fun quotaIds(body: String): List<String> {
        val details = (runCatching { json.parseToJsonElement(body).jsonObject["error"] }.getOrNull() as? JsonObject)?.get("details") as? JsonArray
            ?: return emptyList()
        return details.flatMap { d ->
            ((d as? JsonObject)?.get("violations") as? JsonArray).orEmpty().mapNotNull { v ->
                ((v as? JsonObject)?.get("quotaId") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }
        }
    }

    /**
     * What names a key's kept verdict and acknowledgement: the first 16 hex characters of its SHA-256 (64 of 256 bits). The key
     * cannot be recovered from it, and the key itself is never kept (the `fingerprint` vectors).
     */
    fun fingerprint(key: String): String = Sha256.hex(key.encodeToByteArray()).take(FINGERPRINT_HEX)
}

/** SHA-256 in plain Kotlin, because common code has no digest of its own; pinned by the vectors and the standard test strings. */
internal object Sha256 {
    private val K: IntArray = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    ).map { it.toInt() }.toIntArray()

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    fun hex(data: ByteArray): String {
        val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        val bits = data.size.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        val h = longArrayOf(0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL, 0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L).map { it.toInt() }.toIntArray()
        val w = IntArray(64)
        for (off in padded.indices step 64) {
            for (t in 0 until 16) {
                val b = off + t * 4
                w[t] = ((padded[b].toInt() and 0xff) shl 24) or ((padded[b + 1].toInt() and 0xff) shl 16) or
                    ((padded[b + 2].toInt() and 0xff) shl 8) or (padded[b + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
                val t2 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return h.joinToString("") { (it.toLong() and 0xffffffffL).toString(16).padStart(8, '0') }
    }
}
