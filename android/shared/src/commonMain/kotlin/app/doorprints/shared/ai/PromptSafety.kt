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

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Untrusted text (pasted listings, notes, questions) in prompts: the server's `PromptSafety`, ported (docs/03 §13.1).
 * The text goes in a block whose tag carries a random nonce (`<listing-3f9a1c>`), so it cannot close the block and
 * continue as instructions; look-alike tags and control characters are removed first. Defence in depth: the real
 * guarantees are the checks on the answers (citations, phone and URL in the pasted text, only known house ids).
 */
object PromptSafety {
    /** Six hex characters from a secure random source (`Uuid.random` is cryptographically random on every platform). */
    @OptIn(ExperimentalUuidApi::class)
    fun nonce(): String = Uuid.random().toHexString().take(6)

    /** Drops control characters except newline, tab and carriage return. */
    private fun dropControls(text: String) = text.filterNot { c ->
        (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') || c.code in 0x7f..0x9f
    }

    /** Removes control characters and any tag that could be mistaken for one of our delimiters. */
    fun neutralize(text: String?, tagName: String): String {
        if (text == null) return ""
        return Regex("</?\\s*${Regex.escape(tagName)}[^>]*>", RegexOption.IGNORE_CASE).replace(dropControls(text), "")
    }

    /** `<tag-nonce>\n…\n</tag-nonce>` with the content neutralized. */
    fun wrap(tagName: String, nonce: String, untrusted: String?): String {
        val tag = "$tagName-$nonce"
        return "<$tag>\n${neutralize(untrusted, tagName)}\n</$tag>"
    }
}
