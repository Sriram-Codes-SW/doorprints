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
 * The model's answer with no way to carry data out (docs/03 §13.1, S4b-BL-178; the server's `AnswerText.clean`).
 * `![alt](url)` and `[text](url)` become `alt` and `text`; an http(s) address that does not appear in the context (the
 * records the model was given, after contact removal, not the question) becomes `[link removed]`, one that does is kept.
 * Applied to the answer of Ask and to the summary and reasons of Plan, after the model returns. A claim such as
 * "deleted" is not checked.
 *
 * Common code: no character-class intersections or `\s`, which Kotlin/Native's regex does not share with the JVM.
 */
object AnswerText {
    const val LINK_REMOVED = "[link removed]"

    // The text may hold one level of brackets (so "[see [house:<id>]](...)" keeps the citation). Every part is bounded
    // and the alternatives start with different characters, so a scan stays linear in the input.
    private val MD_LINK = Regex(
        "!?\\[((?:[^\\[\\]\\n]|\\[[^\\[\\]\\n]*\\]){0,500})\\]\\([ \\t\\r\\n]{0,3}[^() \\t\\r\\n]{0,2000}" +
            "(?:[ \\t\\r\\n]+\"[^\"\\n]{0,200}\")?[ \\t\\r\\n]{0,3}\\)",
    )

    // An address ends at the first character it cannot hold, so the text after it (Hindi, Tamil) is left alone.
    private val URL_TEXT = Regex("https?://[A-Za-z0-9\\-._~:/?#@!\$&*+,;=%]+", RegexOption.IGNORE_CASE)
    private const val URL_TAIL = ".,;:!?"

    /** The length of the address without the punctuation a sentence puts after it. */
    private fun keep(url: String): Int {
        var end = url.length
        while (end > 0 && URL_TAIL.indexOf(url[end - 1]) >= 0) end--
        return end
    }

    /**
     * [text] with links and images reduced to their text and any address not in [context] replaced by `[link removed]`.
     */
    fun clean(text: String, context: String): String {
        if (text.isEmpty()) return text
        val known = HashSet<String>()
        for (m in URL_TEXT.findAll(context)) known += m.value.substring(0, keep(m.value)).lowercase()
        val unlinked = MD_LINK.replace(text) { it.groupValues[1] }
        return URL_TEXT.replace(unlinked) { m ->
            val end = keep(m.value)
            if (m.value.substring(0, end).lowercase() in known) m.value else LINK_REMOVED + m.value.substring(end)
        }
    }
}
