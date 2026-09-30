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

package app.doorprints.shared.model

import app.doorprints.shared.export.ExportRows
import app.doorprints.shared.records.RecordRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One question asked about a house (docs/11 5.5, slice 3a): nested in the house as `answers`, right after `rooms` and
 * before `brokerId`, on the wire, in `data.json` and (as JSON text in `houses.answers`) in Room. The keys and their order
 * are the format's (`docs/schemas/backup-sample.json`, `BackupFieldsTest`); an absent value is never written as `null`.
 * The TypeScript twin is `HouseAnswer` in `web/src/app/core/models.ts`; the server's is `HouseAnswer` in its `house`
 * package.
 */
@Serializable
data class HouseAnswer(
    /** `[A-Za-z0-9._-]{1,64}` but not `.` or `..` ([RecordRules.isValidId]), unique within the house. */
    val id: String,
    /** The bank question it came from; absent for one asked of this house only, and it may name a deleted question. */
    val questionId: String? = null,
    /** The question as asked, 1..[HouseAnswers.MAX_TEXT]: a snapshot, so a copy reads even after the bank changes. */
    val text: String = "",
    /** 1..[HouseAnswers.MAX_ANSWER] characters; absent when empty. */
    val answer: String? = null,
    /** An [AnswerStatus] name, kept as text so a status from a newer app reads as OPEN instead of failing. */
    val status: String = AnswerStatus.OPEN.name,
    /** The order shown (0 upwards); readers order by [HouseAnswers.ordered]. */
    val sort: Int = 0,
) {
    /**
     * The status as every reader reads it: an unknown one is OPEN, a non-blank [answer] with OPEN is ANSWERED and
     * ANSWERED with no answer is OPEN.
     */
    val answerStatus: AnswerStatus
        get() {
            val s = AnswerStatus.fromWire(status)
            val has = !answer.isNullOrBlank()
            return when {
                has && s == AnswerStatus.OPEN -> AnswerStatus.ANSWERED
                !has && s == AnswerStatus.ANSWERED -> AnswerStatus.OPEN
                else -> s
            }
        }

    /** True when every value is in range: what a backup's check demands (a bad file is refused whole). */
    val isValid: Boolean
        get() = RecordRules.isValidId(id) && text.isNotBlank() && text.length <= HouseAnswers.MAX_TEXT &&
            (answer?.length ?: 0) <= HouseAnswers.MAX_ANSWER && AnswerStatus.entries.any { it.name == status } &&
            sort >= 0 && (questionId == null || RecordRules.isValidId(questionId))
}

/** Where a question stands at a house: still to ask, answered, or skipped on purpose. Stored and sent by name. */
enum class AnswerStatus {
    OPEN, ANSWERED, SKIPPED;

    companion object {
        fun fromWire(value: String?): AnswerStatus = entries.firstOrNull { it.name == value } ?: OPEN
    }
}

/**
 * The words the cost pre-fill writes (docs/11 5.21): Compose-style templates with `%1$s`/`%2$s`/`%1$d`, so the UI hands
 * over its translated strings (`answer_*`) and the answer is in the app's language; [EN] for tests and a caller
 * without strings. The text is then the person's own and is never re-translated.
 */
data class AnswerWords(
    /** "1 month". */
    val month: String = "%1\$d month",
    /** "2 months". */
    val months: String = "%1\$d months",
    /** A rupee amount and months together: "₹64,000, 2 months". */
    val amountAndMonths: String = "%1\$s, %2\$s",
    val maintenance: String = "%1\$s a month",
    val maintenanceIncluded: String = "%1\$s a month (included)",
    val maintenanceNotIncluded: String = "%1\$s a month (not included)",
    val lockInAndNotice: String = "Lock-in %1\$s, notice %2\$s",
    val lockIn: String = "Lock-in %1\$s",
    val notice: String = "Notice %1\$s",
) {
    internal fun fill(template: String, vararg args: String): String =
        PLACEHOLDER.replace(template) { args.getOrElse(it.groupValues[1].toInt() - 1) { "" } }

    internal fun monthsText(n: Int): String = fill(if (n == 1) month else months, n.toString())

    companion object {
        val EN = AnswerWords()
        private val PLACEHOLDER = Regex("""%(\d+)\$[ds]""")
    }
}

/** A house's list of answers: the cap, the reader's coercion, the order shown and *Add the usual questions*. */
@OptIn(ExperimentalUuidApi::class)
object HouseAnswers {
    const val MAX = 60
    const val MAX_TEXT = 300
    const val MAX_ANSWER = 2000

    /** The question ids whose answer the house's cost can pre-fill (docs/11 5.21). */
    const val DEPOSIT = "qd_deposit"
    const val MAINTENANCE = "qd_maintenance"
    const val BROKERAGE = "qd_brokerage"
    const val LOCK_IN = "qd_lockin"

    /**
     * What a reader keeps (a file, the server, another device, the form's save), like the web's `cleanAnswers`: an
     * answer with a bad id or an id already seen, or a blank or over-long question, is dropped; a blank or over-long
     * answer is none; the status is [HouseAnswer.answerStatus]; a bad question id is none; a negative sort is 0; then
     * the answers by sort and id and the first [MAX]. Null for none: an empty list is never stored or written.
     */
    fun coerced(answers: List<HouseAnswer>?): List<HouseAnswer>? {
        if (answers.isNullOrEmpty()) return null
        val seen = HashSet<String>()
        return answers.asSequence()
            .filter { RecordRules.isValidId(it.id) && text(it.text, MAX_TEXT) != null && seen.add(it.id) }
            .map { a ->
                val answer = text(a.answer, MAX_ANSWER)
                HouseAnswer(
                    id = a.id,
                    questionId = a.questionId?.takeIf(RecordRules::isValidId),
                    text = a.text,
                    answer = answer,
                    status = a.copy(answer = answer).answerStatus.name,
                    sort = a.sort.coerceAtLeast(0),
                )
            }
            .sortedWith(compareBy<HouseAnswer> { it.sort }.thenBy { it.id })
            .take(MAX)
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    /** The order shown everywhere (the form, the copies, AI): OPEN first, then by sort, then by id. */
    val ORDER: Comparator<HouseAnswer> =
        compareBy<HouseAnswer> { it.answerStatus != AnswerStatus.OPEN }.thenBy { it.sort }.thenBy { it.id }

    fun ordered(answers: List<HouseAnswer>?): List<HouseAnswer> = answers.orEmpty().sortedWith(ORDER)

    /** The next answer's sort: one past the largest, 0 for the first. */
    fun nextSort(answers: List<HouseAnswer>?): Int = (answers.orEmpty().maxOfOrNull { it.sort } ?: -1) + 1

    /**
     * The usual questions for a house of [priceType]: the bank's questions that are not archived, [Question.defaultOn],
     * apply to the house ([Question.appliesToHouse]) and are not on it yet (by [HouseAnswer.questionId]), in the bank's
     * order ([Question.ORDER]). *Add the usual questions* is disabled ("Already added") when this is empty.
     */
    fun usual(priceType: String?, bank: List<Question>, existing: List<HouseAnswer>?): List<Question> {
        val asked = existing.orEmpty().mapNotNullTo(HashSet()) { it.questionId }
        return bank.filter { !it.archived && it.defaultOn && it.appliesToHouse(priceType) && it.id !in asked }
            .sortedWith(Question.ORDER)
    }

    /**
     * *Add the usual questions* (pure; the web's `addUsual` passes the same vectors Q1..Q6): [existing] and then each of
     * [usual] as an OPEN answer with the question's text and the next sort, until the house has [MAX]. A deposit,
     * maintenance, brokerage or lock-in question whose value the house's [cost] has is pre-filled in [words] and
     * ANSWERED (docs/11 5.21). [newId] names each new answer (a UUID in the app).
     */
    fun addUsual(
        priceType: String?,
        cost: HouseCost?,
        bank: List<Question>,
        existing: List<HouseAnswer>?,
        words: AnswerWords = AnswerWords.EN,
        newId: () -> String = { Uuid.random().toString() },
    ): List<HouseAnswer> {
        val out = existing.orEmpty().toMutableList()
        var sort = nextSort(out)
        for (q in usual(priceType, bank, existing)) {
            if (out.size >= MAX) break
            val prefill = prefill(q.id, cost, words)
            out += HouseAnswer(
                id = newId(), questionId = q.id, text = q.text, answer = prefill,
                status = if (prefill != null) AnswerStatus.ANSWERED.name else AnswerStatus.OPEN.name, sort = sort++,
            )
        }
        return out
    }

    /** One ad-hoc or picked question as a new OPEN answer at the end ([addUsual]'s shape, for *Add a question*). */
    fun ask(text: String, questionId: String?, existing: List<HouseAnswer>?, newId: () -> String = { Uuid.random().toString() }): HouseAnswer =
        HouseAnswer(id = newId(), questionId = questionId, text = text.trim().take(MAX_TEXT), sort = nextSort(existing))

    /**
     * The answer the cost gives for question [questionId], in [words], or null: deposit in rupees or months (both when
     * both), maintenance a month with whether the rent includes it, brokerage in rupees or months, lock-in and notice.
     */
    fun prefill(questionId: String, cost: HouseCost?, words: AnswerWords = AnswerWords.EN): String? {
        val c = cost ?: return null
        fun money(rupees: Long?, months: Int?): String? = when {
            rupees != null && months != null -> words.fill(words.amountAndMonths, ExportRows.rupees(rupees), words.monthsText(months))
            rupees != null -> ExportRows.rupees(rupees)
            months != null -> words.monthsText(months)
            else -> null
        }
        return when (questionId) {
            DEPOSIT -> money(c.deposit, c.depositMonths)
            BROKERAGE -> money(c.brokerage, c.brokerageMonths)
            MAINTENANCE -> c.maintenance?.let { m ->
                val template = when (c.maintenanceIncluded) {
                    true -> words.maintenanceIncluded
                    false -> words.maintenanceNotIncluded
                    null -> words.maintenance
                }
                words.fill(template, ExportRows.rupees(m))
            }
            LOCK_IN -> {
                val lock = c.lockInMonths?.let(words::monthsText)
                val notice = c.noticeMonths?.let(words::monthsText)
                when {
                    lock != null && notice != null -> words.fill(words.lockInAndNotice, lock, notice)
                    lock != null -> words.fill(words.lockIn, lock)
                    notice != null -> words.fill(words.notice, notice)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun text(value: String?, max: Int): String? = value?.takeIf { it.isNotBlank() && it.length <= max }

    private val listSerializer = ListSerializer(HouseAnswer.serializer())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /** The answers as compact JSON text in the format's key order, or null for none: `houses.answers` and the form's draft. */
    fun encode(answers: List<HouseAnswer>?): String? =
        answers?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(listSerializer, it) }

    /** [encode]'s text back; null for none or for text that does not decode (never written here). */
    fun decode(text: String?): List<HouseAnswer>? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
}
