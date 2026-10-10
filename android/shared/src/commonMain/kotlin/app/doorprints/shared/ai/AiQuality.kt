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

/**
 * The *AI speed and cost* setting (S4b-BL-198 step 2, docs/ai/ai-design.md 13.2): how long Gemini may think before it
 * answers. Thinking is about three quarters of the output cost, so less of it is cheaper and faster, and in the golden-set
 * runs the answers were as good. The website has the same three choices (`web/src/app/core/ai/ai-quality.ts`), and the
 * `geminiRequest` vectors pin the request each one makes. Only the own-key Gemini client reads it; for every other service
 * it is kept but ignored.
 */
enum class AiQuality(val wire: String) {
    /** The model's own default: nothing is sent, as before this setting existed. */
    QUALITY("quality"),

    /** Gemini's `thinkingLevel` `MEDIUM`. */
    BALANCED("balanced"),

    /** Gemini's `thinkingLevel` `LOW`. */
    ECONOMY("economy");

    /** `generationConfig.thinkingConfig.thinkingLevel` for this choice, or null for [QUALITY], which sends no `thinkingConfig`. */
    val thinkingLevel: String?
        get() = when (this) {
            QUALITY -> null
            BALANCED -> "MEDIUM"
            ECONOMY -> "LOW"
        }

    companion object {
        /** What nothing saved, or anything unknown, reads as: today's behaviour. */
        val DEFAULT = QUALITY

        /** A stored text as a choice: exactly one of the three names, else [DEFAULT]. */
        fun fromWire(value: String?): AiQuality = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}
