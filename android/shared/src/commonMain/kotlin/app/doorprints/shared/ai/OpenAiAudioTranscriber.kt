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

import app.doorprints.shared.api.ApiException
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Speech to text through the `openai-compatible` kind (ADR-37): `POST {baseUrl}/audio/transcriptions` as
 * `multipart/form-data` with the fields `file`, `model`, an optional `prompt` (key terms, for the later setting) and an
 * optional `language` (the hint's language part), which OpenAI, Groq and OpenRouter document for speech to text
 * ([TranscribeCapability] says which presets may use it). [baseUrl] is already checked with [BaseUrlValidator]; the key
 * goes only in an `Authorization: Bearer` header, and only when there is one. The call goes through [postAiMultipart]
 * (the time limit and no redirect of [postAiJson]); a refused call is [aiFailure]'s error. The answer's `text` is the
 * transcript; its language is the hint's, because the default `json` answer names none. Neither the audio nor the
 * transcript is logged or put in an error.
 *
 * [model] is a transcription model (`whisper-1`, `gpt-4o-mini-transcribe`, Groq's `whisper-large-v3-turbo`), which is
 * usually not the chat model of the provider configuration: the later setting chooses it.
 */
class OpenAiAudioTranscriber(
    private val http: HttpClient,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    private val prompt: String? = null,
    /** Null turns the limit off (tests, whose virtual clock would end it at once). */
    private val timeoutMs: Long? = 60_000,
) : Transcriber {
    override suspend fun transcribe(bytes: ByteArray, mime: String, langHint: String?, durationMs: Long?): Transcript {
        val type = AudioClip.check(bytes.size, mime, durationMs)
        val reply = postAiMultipart(
            http, url(baseUrl), headers(apiKey), fields(type, model, prompt, langHint), bytes, timeoutMs,
        )
        return transcriptOf(reply.status, reply.body, reply.retryAfter, langHint)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun url(baseUrl: String) = "$baseUrl/audio/transcriptions"

        /** The key in a Bearer header, or no header at all for a server that needs none. */
        internal fun headers(apiKey: String): Map<String, String> =
            if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")

        /** The form's fields in order (the `transcribeRequest` vectors); [mime] is already [AudioClip.mimeOf]'s. */
        internal fun fields(mime: String, model: String, prompt: String?, langHint: String?): List<AiFormField> = buildList {
            add(AiFormField("file", fileName = "audio.${AudioClip.extensionOf(mime)}", contentType = mime))
            add(AiFormField("model", model))
            prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { add(AiFormField("prompt", it)) }
            AudioClip.languageOf(langHint)?.let { add(AiFormField("language", it)) }
        }

        /** What an answer means (the `transcribeContent` vectors, provider `openai`): a 2xx JSON object with a string `text`. */
        internal fun transcriptOf(status: Int, body: String, retryAfter: String?, langHint: String?): Transcript {
            if (status !in 200..299) throw failure(status, retryAfter)
            val answer = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw noTranscript()
            val text = answer["text"] as? JsonPrimitive
            if (text == null || !text.isString) throw noTranscript()
            return Transcript(text.content.trim(), transcriptLanguage(null, langHint))
        }

        internal fun failure(status: Int, retryAfter: String?): ApiException = aiFailure(status, retryAfter)
    }
}
