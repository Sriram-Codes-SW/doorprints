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

import kotlinx.serialization.json.JsonObject

/**
 * Any AI that answers a prompt with JSON (docs/03 §13.2, ADR-35): Gemini ([GeminiClient]) or an OpenAI-compatible
 * endpoint ([OpenAiCompatClient]). [OnDeviceAi] and its prompts, checks and schemas do not know which one answers.
 */
interface JsonChatModel {
    /** The model's JSON answer as text, for [schema] (a Gemini-dialect object schema); the caller parses it. */
    suspend fun generateJson(system: String, user: String, schema: JsonObject, temperature: Double): String

    /** One real call that proves the key, the address and the model all work; throws an ApiException when not. */
    suspend fun ping()
}
