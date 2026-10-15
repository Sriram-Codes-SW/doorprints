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
 * Sarvam AI's speech to text (ADR-37, voice PR 7; the twin of the website's `SarvamTranscriber`): `POST
 * api.sarvam.ai/speech-to-text` as `multipart/form-data` with the fields `file` and `language_code`, the person's **own**
 * Sarvam key in the header `api-subscription-key` (a key slot of its own, never the AI key). The call goes through
 * [postAiMultipart] (the time limit and no redirect of [postAiJson]); a refused call is [aiFailure]'s error. A clip over
 * 30 seconds ([AudioClip.SARVAM_MAX_DURATION_MS]) is refused before anything is sent. The answer's `transcript` is the
 * words and its `language_code` the language. Neither the audio, the words nor the key is logged or put in an error (T-I48).
 */
class SarvamTranscriber(
    private val http: HttpClient,
    private val apiKey: String,
    /** Null turns the limit off (tests, whose virtual clock would end it at once). */
    private val timeoutMs: Long? = 60_000,
) : Transcriber {
    override suspend fun transcribe(bytes: ByteArray, mime: String, langHint: String?, durationMs: Long?): Transcript {
        val type = AudioClip.check(bytes.size, mime, durationMs, AudioClip.SARVAM_MAX_DURATION_MS)
        val reply = postAiMultipart(http, URL, headers(apiKey), fields(type, langHint), bytes, timeoutMs)
        return transcriptOf(reply.status, reply.body, reply.retryAfter, langHint)
    }

    companion object {
        /** Where the audio goes: one fixed address, never typed or configured. */
        const val URL = "https://api.sarvam.ai/speech-to-text"

        /** The host named in the disclosure. */
        const val HOST = "api.sarvam.ai"

        /** The list price shown in the disclosure, INR a minute (30 INR an hour, docs/ai/voice-input.md §2). */
        const val PRICE_INR_PER_MINUTE = "0.50"

        private val json = Json { ignoreUnknownKeys = true }

        /** The key in Sarvam's own header (not a Bearer token). */
        internal fun headers(apiKey: String): Map<String, String> = mapOf("api-subscription-key" to apiKey.trim())

        /** `hi-IN`, `ta-IN` or `te-IN` for those languages, else `unknown` (Sarvam then detects the language). */
        internal fun languageCode(langHint: String?): String = when (val language = AudioClip.languageOf(langHint)) {
            "hi", "ta", "te" -> "$language-IN"
            else -> "unknown"
        }

        /** The form's fields in order (the `sarvamRequest` vectors); [mime] is already [AudioClip.mimeOf]'s. */
        internal fun fields(mime: String, langHint: String?): List<AiFormField> = listOf(
            AiFormField("file", fileName = "audio.${AudioClip.extensionOf(mime)}", contentType = mime),
            AiFormField("language_code", languageCode(langHint)),
        )

        /** What an answer means (the `sarvamContent` vectors): a 2xx JSON object with a string `transcript`. */
        internal fun transcriptOf(status: Int, body: String, retryAfter: String?, langHint: String?): Transcript {
            if (status !in 200..299) throw failure(status, retryAfter, body)
            val answer = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw noTranscript()
            val text = answer["transcript"] as? JsonPrimitive
            if (text == null || !text.isString) throw noTranscript()
            val reported = (answer["language_code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Transcript(text.content.trim(), transcriptLanguage(reported, langHint))
        }

        internal fun failure(status: Int, retryAfter: String?, body: String = ""): ApiException = aiFailure(status, retryAfter, body)
    }
}
