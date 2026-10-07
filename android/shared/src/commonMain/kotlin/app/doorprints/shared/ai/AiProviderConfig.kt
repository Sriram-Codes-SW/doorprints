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

/** Which AI the person's own device talks to (docs/03 §13.2, ADR-35). */
enum class AiKind(val wire: String) {
    GEMINI("gemini"),
    OPENAI_COMPATIBLE("openai-compatible"),

    /** Anthropic's Messages API ([AnthropicClient]): key and model. */
    ANTHROPIC("anthropic");

    companion object {
        /** A saved value, or [GEMINI] when there is none (a device from before ADR-35) or it is not known. */
        fun fromWire(value: String?): AiKind = entries.firstOrNull { it.wire == value } ?: GEMINI
    }
}

/**
 * The person's AI choice besides the key (which keeps its own encrypted slot): a plain setting, never a secret, and
 * never in a backup, a copy, a sync file or a share file (D-31, TC-U-170). [baseUrl] and [model] are empty for
 * [AiKind.GEMINI]. [toString] says neither the address nor a key (the address can name a computer in the house).
 */
data class AiProviderConfig(
    val kind: AiKind = AiKind.GEMINI,
    val baseUrl: String = "",
    val model: String = "",
) {
    override fun toString(): String =
        "AiProviderConfig(kind=${kind.wire}, model=${if (model.isEmpty()) "none" else "set"})"

    companion object {
        val GEMINI = AiProviderConfig()
    }
}

/** A starting point for the base URL (ADR-35); the model is typed by the person, never guessed. */
data class AiPreset(val id: String, val baseUrl: String, val keyOptional: Boolean) {
    companion object {
        val OPENAI = AiPreset("openai", "https://api.openai.com/v1", keyOptional = false)
        val OPENROUTER = AiPreset("openrouter", "https://openrouter.ai/api/v1", keyOptional = false)
        val GROQ = AiPreset("groq", "https://api.groq.com/openai/v1", keyOptional = false)
        val OLLAMA = AiPreset("ollama", "http://localhost:11434/v1", keyOptional = true)
        val LM_STUDIO = AiPreset("lmstudio", "http://localhost:1234/v1", keyOptional = true)
        val CUSTOM = AiPreset("custom", "", keyOptional = false)

        /** Anthropic's address, the one its kind uses (its own preset: the person picks it from the list, never types it). */
        val ANTHROPIC = AiPreset("anthropic", "https://api.anthropic.com", keyOptional = false)

        val ALL: List<AiPreset> = listOf(OPENAI, OPENROUTER, GROQ, OLLAMA, LM_STUDIO, CUSTOM)
    }
}
