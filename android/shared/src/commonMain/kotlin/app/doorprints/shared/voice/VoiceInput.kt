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

import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.GeminiTranscriber
import app.doorprints.shared.ai.OpenAiAudioTranscriber
import app.doorprints.shared.ai.SarvamTranscriber
import app.doorprints.shared.ai.TranscribeCapability
import app.doorprints.shared.ai.Transcriber
import app.doorprints.shared.country.Country
import io.ktor.client.HttpClient

/**
 * The *Voice input* setting on this phone: the switch (off by default, a plain preference of this phone, never exported,
 * synced or backed up) and the provider choice it works with (a plain setting, no key).
 */
data class VoiceChoice(
    val enabled: Boolean = false,
    val config: AiProviderConfig = AiProviderConfig.GEMINI,
    /** Which service writes out the words (voice PR 7): the person's AI provider, or Sarvam with its own key. */
    val provider: VoiceProvider = VoiceProvider.OWN_AI,
    /** A Sarvam key is saved (the key itself is read only at the moment of a call). */
    val sarvamKeySaved: Boolean = false,
)

/** Which service writes out the words: `own` (the default, absent in the settings) or `sarvam` (a key slot of its own). */
enum class VoiceProvider(val wire: String) {
    OWN_AI("own"), SARVAM("sarvam");

    companion object {
        fun fromWire(wire: String?): VoiceProvider = entries.firstOrNull { it.wire == wire } ?: OWN_AI
    }
}

/**
 * The rules of voice input that do not need a screen or a microphone (docs/03 ADR-37, docs/ai/voice-input.md): who gets a
 * microphone button, which transcription model each service is called with, the list price shown before the switch, and
 * the key terms the model should spell right. The twin of the website's `VoiceInputService` and `transcriberFor`.
 */
object VoiceInput {
    /**
     * The transcription model of each speech-capable preset (OpenRouter lists `openai/gpt-4o-mini-transcribe` in its
     * `output_modalities=transcription` catalogue, read 2026-10-15); Gemini's model is the chat adapter's.
     */
    private val MODELS: Map<String, String> = mapOf(
        "openai" to "gpt-4o-mini-transcribe",
        "openrouter" to "openai/gpt-4o-mini-transcribe",
        "groq" to "whisper-large-v3-turbo",
    )

    /**
     * The list price shown in the disclosure, INR a minute, read from the vendors' pages on 2026-10-10 at 88 INR to the
     * dollar (docs/ai/voice-input.md §2): Gemini at its higher audio price, OpenAI's and OpenRouter's
     * `gpt-4o-mini-transcribe`, Groq's turbo.
     */
    private val PRICE_INR_PER_MINUTE: Map<String, String> = mapOf("gemini" to "0.17", "openai" to "0.26", "openrouter" to "0.26", "groq" to "0.06")

    /** The transcription model of a speech-capable preset (not Gemini, whose model is the chat adapter's), or null. */
    fun modelOf(preset: String): String? = MODELS[preset]

    /** The list price of a minute for [config], or null where none is known. */
    fun priceInr(config: AiProviderConfig): String? = PRICE_INR_PER_MINUTE[TranscribeCapability.presetOf(config)]

    /** Words the model should spell right, by kind of space; a house hunt's are the default. Sent as the OpenAI form's `prompt`. */
    fun keyTerms(kind: String): String = if (kind == "school") {
        "CBSE, ICSE, fees, classes, admission, school bus"
    } else {
        Country.pack.voiceHouseKeyTerms
    }

    /**
     * Whether the person's own AI is in use and takes audio: what the switch can do anything for. [ownKey] is
     * `Repository.aiOwnKey` (the own AI answers, with a key). Anthropic, Ollama, LM Studio and a custom address never
     * qualify, and the connected server's AI is not [ownKey]: no proxy and no server route (ADR-37).
     */
    fun providerOk(ownKey: Boolean, config: AiProviderConfig): Boolean = ownKey && TranscribeCapability.canTranscribe(config)

    /**
     * The microphone is offered: the switch is on, *AI features* are on ([aiEnabled]), the own AI takes audio and this phone
     * can record ([recordable]). (The switch is read first: with it off nothing else counts.)
     */
    fun available(choice: VoiceChoice, aiEnabled: Boolean, ownKey: Boolean, recordable: Boolean): Boolean =
        choice.enabled && aiEnabled && usable(choice, ownKey) && recordable

    /** The chosen service can write out words now: Sarvam has its own key, or the own AI takes audio ([providerOk]). */
    fun usable(choice: VoiceChoice, ownKey: Boolean): Boolean =
        if (choice.provider == VoiceProvider.SARVAM) choice.sarvamKeySaved else providerOk(ownKey, choice.config)

    /** The host the audio goes to, for the disclosure: Sarvam's, else the AI provider's ([aiHost], which the screen computes). */
    fun host(choice: VoiceChoice, aiHost: String): String = if (choice.provider == VoiceProvider.SARVAM) SarvamTranscriber.HOST else aiHost

    /** The list price of a minute, INR: Sarvam's, else [priceInr] of the AI provider. */
    fun price(choice: VoiceChoice): String? =
        if (choice.provider == VoiceProvider.SARVAM) SarvamTranscriber.PRICE_INR_PER_MINUTE else priceInr(choice.config)

    /** The hard stop of a recording, milliseconds: Sarvam's 30 s, else a minute. */
    fun maxDurationMs(choice: VoiceChoice): Long =
        if (choice.provider == VoiceProvider.SARVAM) AudioLimits.SARVAM_MAX_DURATION_MS else AudioLimits.MAX_DURATION_MS

    /** The spoken language as a BCP 47 tag from the app's language (`hi-IN`); the provider may hear another one. */
    fun langHint(appLanguage: String?): String {
        val locale = Country.pack.locale
        return locale.speech[appLanguage?.lowercase()?.substringBefore('-')] ?: locale.fallback
    }

    /** The transcriber for the saved choice and the person's own [key], or null where the provider cannot take audio. */
    fun transcriberFor(http: HttpClient, config: AiProviderConfig, key: String, kind: String): Transcriber? = when {
        config.kind == AiKind.GEMINI -> GeminiTranscriber(http, key)
        config.kind != AiKind.OPENAI_COMPATIBLE -> null
        else -> MODELS[TranscribeCapability.presetOf(config)]?.let { model ->
            OpenAiAudioTranscriber(http, config.baseUrl, model, key, keyTerms(kind))
        }
    }
}
