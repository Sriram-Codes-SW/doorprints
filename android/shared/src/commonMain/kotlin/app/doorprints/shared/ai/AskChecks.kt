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

import app.doorprints.shared.api.CitationDto
import kotlinx.serialization.Serializable

/** What the model returns for Ask (the server's `AskModels.ModelAnswer`). */
@Serializable
data class ModelAnswer(val answer: String? = null, val citedHouseIds: List<String>? = null)

/** A house as it was sent to the model for Ask: its id, redacted text and citation label. */
data class AskDocument(val id: String, val text: String, val label: String)

/**
 * The checks on an Ask answer: the server's `RagService.citations` and `AskPrompts.snippet`, ported (docs/03 §13.1).
 * A house is cited only where the answer names it inline as `[house:<id>]`, in order of first appearance, and only if
 * it was sent; the model's `citedHouseIds` count only for an answer with no inline marker at all. The refusal sentence
 * never has citations.
 */
object AskChecks {
    private val INLINE_MARKER = Regex("\\[house:([^\\[\\]\\n]{1,400})]", RegexOption.IGNORE_CASE)
    private val UUID_TEXT =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The citations to show for [answer]: the houses named inline that were really sent in [docs], in order of first
      * appearance, each with the line of its record closest to [question]. None for a blank answer or the refusal
      * sentence.
     */
    fun citations(answer: ModelAnswer, docs: List<AskDocument>, question: String): List<CitationDto> {
        val text = answer.answer ?: ""
        if (text.isBlank() || isRefusal(text)) return emptyList()
        val byId = LinkedHashMap<String, AskDocument>()
        docs.forEach { byId[it.id.lowercase()] = it }
        val ids = LinkedHashSet(inlineIds(text))
        if (ids.isEmpty()) answer.citedHouseIds?.forEach { id -> normalizeId(id).takeIf { it.isNotEmpty() }?.let(ids::add) }
        return ids.mapNotNull { id -> byId[id]?.let { CitationDto(id, it.label, snippet(it.text, question, 240)) } }
    }

    /** The exact refusal sentence, trimmed, with curly single quotes folded. */
    fun isRefusal(answer: String?): Boolean =
        answer != null && AiPrompts.I_DONT_KNOW == answer.trim().replace('’', '\'').replace('‘', '\'')

    /** The lower-case ids of the form `[house:<uuid>]` in [answer], in order and without repeats. */
    fun inlineIds(answer: String?): List<String> {
        if (answer == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (marker in INLINE_MARKER.findAll(answer)) {
            for (uuid in UUID_TEXT.findAll(marker.groupValues[1])) out += uuid.value.lowercase()
        }
        return out.toList()
    }

    private fun normalizeId(id: String?): String {
        if (id == null) return ""
        var s = id.trim().lowercase()
        if (s.startsWith("[house:")) s = s.substring(7)
        if (s.startsWith("house:")) s = s.substring(6)
        if (s.endsWith("]")) s = s.dropLast(1)
        return s
    }

    /** The line of the document sharing most words with the question (the first line on a tie). */
    fun snippet(docText: String?, question: String?, max: Int): String {
        if (docText.isNullOrBlank()) return ""
        val qWords = words(question)
        var best: String? = null
        var bestScore = -1
        for (line in docText.split("\n")) {
            if (line.isBlank()) continue
            val score = words(line).count { it in qWords }
            if (score > bestScore) {
                best = line
                bestScore = score
            }
        }
        val s = best?.trim() ?: ""
        return if (s.length <= max) s else s.take(max - 1) + "…"
    }

    /** The distinct lower-case words of more than two characters; the measure of how much two texts share. */
    internal fun words(s: String?): Set<String> =
        if (s == null) emptySet() else s.lowercase().split(NON_WORD).filter { it.length > 2 }.toSet()
}
