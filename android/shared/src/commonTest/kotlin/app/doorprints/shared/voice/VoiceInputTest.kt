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
import app.doorprints.shared.ai.AiPreset
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.GeminiTranscriber
import app.doorprints.shared.ai.OpenAiAudioTranscriber
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-U-252 (docs/06): who gets a microphone and what each service is called with (voice PR 5, S4b-BL-222); the twin of the
 * website's `voice-input.service.spec.ts`: off by default; the own key only; Gemini, OpenAI, Groq and OpenRouter only (not
 * Anthropic, Ollama, LM Studio, a custom address or the server's AI); the model, the key terms, the list price and the
 * language hint per service, and that the key goes only to its own host, in a header. Canned answers; no provider is called.
 */
class VoiceInputTest {
    private fun openAi(preset: AiPreset) = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, preset.baseUrl, "any-chat-model")
    private val anthropic = AiProviderConfig(AiKind.ANTHROPIC, AiPreset.ANTHROPIC.baseUrl, "claude")
    private val custom = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "https://llm.example.com/v1", "m")

    private fun on(config: AiProviderConfig) = VoiceChoice(true, config)

    @Test
    fun offByDefault() {
        assertFalse(VoiceChoice().enabled)
        assertFalse(VoiceInput.available(VoiceChoice(false, AiProviderConfig.GEMINI), aiEnabled = true, ownKey = true, recordable = true))
    }

    @Test
    fun geminiOpenAiGroqAndOpenRouterWithTheOwnKeyGetAMicrophone() {
        for (config in listOf(AiProviderConfig.GEMINI, openAi(AiPreset.OPENAI), openAi(AiPreset.GROQ), openAi(AiPreset.OPENROUTER))) {
            assertTrue(VoiceInput.available(on(config), aiEnabled = true, ownKey = true, recordable = true), "$config")
        }
    }

    @Test
    fun anthropicOllamaLmStudioACustomAddressAndTheServersAiGetNone() {
        for (config in listOf(anthropic, openAi(AiPreset.OLLAMA), openAi(AiPreset.LM_STUDIO), custom)) {
            assertFalse(VoiceInput.available(on(config), aiEnabled = true, ownKey = true, recordable = true), "$config")
            assertFalse(VoiceInput.providerOk(true, config))
        }
        // The connected server answers AI: no own key, so no microphone whatever the saved provider says.
        assertFalse(VoiceInput.available(on(AiProviderConfig.GEMINI), aiEnabled = true, ownKey = false, recordable = true))
    }

    @Test
    fun aiFeaturesOffOrNoRecorderMeansNoMicrophone() {
        assertFalse(VoiceInput.available(on(AiProviderConfig.GEMINI), aiEnabled = false, ownKey = true, recordable = true))
        assertFalse(VoiceInput.available(on(AiProviderConfig.GEMINI), aiEnabled = true, ownKey = true, recordable = false))
    }

    @Test
    fun theListPricePerMinuteIsTheWebsites() {
        assertEquals("0.17", VoiceInput.priceInr(AiProviderConfig.GEMINI))
        assertEquals("0.26", VoiceInput.priceInr(openAi(AiPreset.OPENAI)))
        assertEquals("0.26", VoiceInput.priceInr(openAi(AiPreset.OPENROUTER)))
        assertEquals("0.06", VoiceInput.priceInr(openAi(AiPreset.GROQ)))
        assertNull(VoiceInput.priceInr(custom))
    }

    @Test
    fun theLanguageHintComesFromTheAppsLanguage() {
        assertEquals("hi-IN", VoiceInput.langHint("hi"))
        assertEquals("ta-IN", VoiceInput.langHint("ta"))
        assertEquals("te-IN", VoiceInput.langHint("te"))
        assertEquals("en-IN", VoiceInput.langHint("en"))
        assertEquals("en-IN", VoiceInput.langHint(null))
    }

    @Test
    fun keyTermsFollowTheSpace() {
        assertTrue(VoiceInput.keyTerms("house").contains("2 BHK"))
        assertTrue(VoiceInput.keyTerms("school").contains("CBSE"))
    }

    @Test
    fun theTranscriberIsTheRightAdapterOrNone() {
        val http = HttpClient(MockEngine { respond("{}") })
        assertTrue(VoiceInput.transcriberFor(http, AiProviderConfig.GEMINI, "k", "house") is GeminiTranscriber)
        assertTrue(VoiceInput.transcriberFor(http, openAi(AiPreset.OPENAI), "k", "house") is OpenAiAudioTranscriber)
        assertNull(VoiceInput.transcriberFor(http, anthropic, "k", "house"))
        assertNull(VoiceInput.transcriberFor(http, openAi(AiPreset.OLLAMA), "", "house"))
        assertNull(VoiceInput.transcriberFor(http, custom, "k", "house"))
    }

    @Test
    fun eachPresetIsCalledWithItsModelAndTheKeyGoesOnlyToItsHostInAHeader() = runTest {
        // Real time: the adapter's 60 s limit would otherwise be jumped over by the test's virtual clock.
        withContext(Dispatchers.Default) { eachPreset() }
    }

    private suspend fun eachPreset() {
        val cases = listOf(
            Triple(AiPreset.OPENAI, "api.openai.com", "gpt-4o-mini-transcribe"),
            Triple(AiPreset.GROQ, "api.groq.com", "whisper-large-v3-turbo"),
            Triple(AiPreset.OPENROUTER, "openrouter.ai", "openai/gpt-4o-mini-transcribe"),
        )
        for ((preset, host, model) in cases) {
            val seen = mutableListOf<HttpRequestData>()
            val bodies = mutableListOf<String>()
            val http = HttpClient(MockEngine { request ->
                seen += request
                bodies += request.body.toByteArray().decodeToString()
                respond("""{"text":"two bhk"}""", HttpStatusCode.OK, Headers.build { append(HttpHeaders.ContentType, "application/json") })
            })
            val t = assertNotNull(VoiceInput.transcriberFor(http, openAi(preset), "sk-test-key", "house"))
            val words = t.transcribe(ByteArray(2_000) { 1 }, "audio/wav", "hi-IN", 1_000)
            assertEquals("two bhk", words.text)
            val request = seen.single()
            assertEquals(host, request.url.host, "$preset")
            assertEquals("Bearer sk-test-key", request.headers[HttpHeaders.Authorization])
            assertFalse(request.url.toString().contains("sk-test-key"))
            assertFalse(bodies.single().contains("sk-test-key"))
            assertTrue(bodies.single().contains(model), "$preset sends $model")
            assertTrue(bodies.single().contains("2 BHK"), "key terms go as the prompt")
        }
    }
}
