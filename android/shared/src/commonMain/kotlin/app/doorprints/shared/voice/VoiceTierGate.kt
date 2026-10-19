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

import app.doorprints.data.AiProviderChoice
import app.doorprints.data.SettingsStore
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.ai.GeminiTranscriber
import app.doorprints.shared.ai.TranscribeCapability
import app.doorprints.shared.ai.postAiJson
import app.doorprints.shared.api.IsoTime
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The key and provider one voice request would use; the key is held for the call and never kept or printed. */
class TierSubject(val provider: String, val model: String, private val key: String) {
    internal fun key(): String = key
    override fun toString(): String = "TierSubject($provider)"
}

/**
 * The free-plan rule wired into voice input on the phones (owner decision 2026-10-19, ADR-37, RR-33): reads what is kept for
 * the key ([SettingsStore.voiceTier]), asks the provider **once** when the rule is still waiting for evidence (Gemini and
 * OpenRouter only), and answers with [VoiceTier.decide]. The twin of the website's `VoiceInputService` tier code.
 *
 * The probe carries no audio, no house or candidate data and nothing about the device: Gemini gets one fixed one-word prompt asking
 * for one token, to a model with no free tier; OpenRouter gets a GET with no body. It runs only when a screen or a request asks
 * after the person turned *Voice input* on. Neither the key nor a reply is kept or logged: only the verdict, a time and the name of
 * the evidence, under the key's hash. A call that could not tell is asked again after a minute, never kept.
 */
class VoiceTierGate(
    private val settings: SettingsStore,
    private val http: HttpClient,
    private val now: () -> Long = { IsoTime.nowMillis() },
    /** Null turns the limit off (tests, whose virtual clock would end it at once). */
    private val timeoutMs: Long? = 15_000,
) {
    private val lock = Mutex()
    private val probes = HashMap<String, Pair<TierProbe, Long>>()
    private val lastFingerprint = HashMap<String, String>()

    /** What the audio call would use for [choice], or null when no key is saved for it. */
    suspend fun subjectFor(choice: VoiceChoice): TierSubject? {
        if (choice.provider == VoiceProvider.SARVAM) {
            val key = settings.sarvamKey()
            return if (key.isBlank()) null else TierSubject("sarvam", "", key)
        }
        val s = settings.current()
        if (s.aiProvider != AiProviderChoice.DEVICE || s.geminiKey.isBlank()) return null
        val provider = TranscribeCapability.presetOf(s.aiProviderConfig)
        val model = if (s.aiProviderConfig.kind == AiKind.GEMINI) GeminiClient.MODEL else VoiceInput.modelOf(provider).orEmpty()
        return TierSubject(provider, model, s.geminiKey)
    }

    /**
     * The rule applied to what is known now. With [probe] true, when the rule is still waiting for evidence the provider is asked
     * once (never twice at once for a key); with false the answer may be [VoiceVerdict.CHECKING]. The second check before a
     * request calls this with true.
     */
    suspend fun verdict(subject: TierSubject, probe: Boolean = true): VoiceVerdict = lock.withLock {
        val fp = VoiceTier.keyHash(subject.key())
        forgetReplaced(subject.provider, fp)
        var v = decideNow(subject, fp)
        val known = probes[fp]
        val retry = known != null && known.first == TierProbe.UNKNOWN && now() - known.second > Limits.REPROBE_AFTER_MS
        if (probe && VoiceTier.needsProbe(subject.provider) && (v == VoiceVerdict.CHECKING || retry)) {
            val result = ask(subject)
            if (result == TierProbe.FREE) settings.voiceTier.saveEntry(fp, entry(VoiceVerdict.REFUSE_FREE, subject.provider, "probe"))
            if (result == TierProbe.PAID) settings.voiceTier.saveEntry(fp, entry(VoiceVerdict.ALLOW, subject.provider, "probe"))
            probes[fp] = result to now()
            v = decideNow(subject, fp)
        }
        v
    }

    /** Whether the person confirmed, for this key and provider, that the plan is paid. */
    suspend fun isAcknowledged(subject: TierSubject): Boolean =
        settings.voiceTier.ack(VoiceTier.keyHash(subject.key())) == subject.provider

    /** Records or withdraws "my plan is paid" for this key (asked again for a new key: the name is the key's hash). */
    suspend fun acknowledge(subject: TierSubject, on: Boolean) {
        settings.voiceTier.saveAck(VoiceTier.keyHash(subject.key()), if (on) subject.provider else null)
    }

    /** The confirmation is offered: the rule cannot read this provider's plan (or its probe could not tell) and it is not a free plan. */
    suspend fun canConfirmPaid(subject: TierSubject, verdict: VoiceVerdict): Boolean {
        if (!VoiceTier.canAcknowledge(subject.provider) || verdict == VoiceVerdict.REFUSE_FREE || verdict == VoiceVerdict.CHECKING) return false
        return !VoiceTier.needsProbe(subject.provider) || probes[VoiceTier.keyHash(subject.key())]?.first == TierProbe.UNKNOWN
    }

    /**
     * A later ordinary Gemini request answered 429 with a free-tier quota id: this key is on a free plan whatever was kept, so the
     * kept verdict becomes REFUSE_FREE at once. Reads the status and the quota id only.
     */
    suspend fun noteGeminiAnswer(key: String, status: Int, body: String) = noteGeminiAnswer(settings, key, status, body, now())

    private suspend fun decideNow(subject: TierSubject, fp: String): VoiceVerdict {
        val cached = parse(settings.voiceTier.entry(fp), subject.provider)
        return VoiceTier.decide(
            VoiceTierInput(
                provider = subject.provider,
                model = subject.model,
                // Only a probe that could not tell is remembered here; a definite answer lives in the kept entry, with its lifetime.
                probe = probes[fp]?.first?.takeIf { it == TierProbe.UNKNOWN } ?: TierProbe.NONE,
                cached = cached,
                acknowledged = settings.voiceTier.ack(fp) == subject.provider,
            ),
        )
    }

    private suspend fun forgetReplaced(provider: String, fp: String) {
        val before = lastFingerprint.put(provider, fp)
        if (before != null && before != fp) {
            settings.voiceTier.forget(before)
            probes.remove(before)
        }
    }

    private fun entry(verdict: VoiceVerdict, provider: String, evidence: String) = "${verdict.name}|${now()}|$provider:$evidence"

    private fun parse(raw: String?, provider: String): CachedTier? {
        val parts = raw?.split('|') ?: return null
        if (parts.size != 3 || !parts[2].startsWith("$provider:")) return null
        val verdict = VoiceVerdict.entries.firstOrNull { it.name == parts[0] }?.takeIf { it == VoiceVerdict.ALLOW || it == VoiceVerdict.REFUSE_FREE } ?: return null
        val at = parts[1].toLongOrNull()?.takeIf { it <= now() } ?: return null
        return CachedTier(verdict, now() - at)
    }

    /** The one call. A failure to reach the provider says nothing ([TierProbe.UNKNOWN]). */
    private suspend fun ask(subject: TierSubject): TierProbe = try {
        if (timeoutMs == null) read(subject) else withTimeoutOrNull(timeoutMs) { read(subject) } ?: TierProbe.UNKNOWN
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TierProbe.UNKNOWN
    }

    private suspend fun read(subject: TierSubject): TierProbe = when (subject.provider) {
        "gemini" -> {
            val reply = postAiJson(
                http, GeminiTranscriber.url(VoiceTier.GEMINI_PROBE_MODEL), mapOf("x-goog-api-key" to subject.key()),
                VoiceTier.geminiProbeBody(), null,
            )
            VoiceTier.geminiProbeResult(reply.status, reply.body)
        }
        "openrouter" -> http.prepareGet(VoiceTier.OPENROUTER_KEY_URL) { header("Authorization", "Bearer ${subject.key()}") }
            .execute { VoiceTier.openRouterKeyResult(it.status.value, it.bodyAsText()) }
        else -> TierProbe.UNKNOWN
    }

    companion object {
        /** The ordinary-request hook without a gate (the AI store has the settings but no HTTP client for a probe). */
        suspend fun noteGeminiAnswer(settings: SettingsStore, key: String, status: Int, body: String, at: Long = IsoTime.nowMillis()) {
            if (key.isBlank() || status != 429 || VoiceTier.geminiProbeResult(status, body) != TierProbe.FREE) return
            settings.voiceTier.saveEntry(VoiceTier.keyHash(key), "${VoiceVerdict.REFUSE_FREE.name}|$at|gemini:ordinary-429")
        }
    }

    private object Limits {
        /** A probe that could not tell is asked again after this long, when something asks. */
        const val REPROBE_AFTER_MS = 60_000L
    }
}
