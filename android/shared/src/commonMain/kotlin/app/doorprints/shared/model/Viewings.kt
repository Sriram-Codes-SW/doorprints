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

import app.doorprints.shared.records.RecordRules
import app.doorprints.shared.records.RecordType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.abs
import kotlin.random.Random

/**
 * A planned, done or cancelled viewing of a house (docs/11 5.8, slice 3b-1 of 5.30): a record of type `viewing` whose
 * id is [id], `v_` + 8 lowercase hex when the app makes it. The payload keys are, in this order, [houseId], [startsAt],
 * [durationMin], [kind], [status], [remindMin], [huntReminder] (only when true), [withWhom], [notes] and [visitId] (each
 * only when set). The house may be gone (a deleted house keeps its viewings, shown as "a house that is gone"). The
 * TypeScript twin is `Viewing` in `web/src/app/shared/viewing.ts`; the server stores the same keys in its `record` table.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Viewing(
    /** The record id, not part of the payload. */
    @Transient val id: String = "",
    /** The house's id; required, may name a house that is gone. */
    val houseId: String = "",
    /** Epoch ms of the start; required, > 0. */
    val startsAt: Long = 0L,
    /** [MIN_DURATION]..[MAX_DURATION] minutes. */
    val durationMin: Int = DEFAULT_DURATION,
    /** A [ViewingKind] name, kept as text so a kind from a newer app reads as FIRST. */
    val kind: String = ViewingKind.FIRST.name,
    /** A [ViewingStatus] name; *missed* is never stored ([Viewings.missed]). */
    val status: String = ViewingStatus.PLANNED.name,
    /** Minutes before [startsAt] a reminder comes, one of [REMIND_CHOICES]; 0 is none. Used by slice 3b-2. */
    val remindMin: Int = DEFAULT_REMIND,
    /** Offer Hunt mode before the viewing (slice 3c); written only when true, not shown on the form yet. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val huntReminder: Boolean = false,
    /** Who the person meets (0..[MAX_WITH_WHOM]): contact data, never in a reminder, a calendar file or the AI's text. */
    val withWhom: String? = null,
    /** 0..[MAX_NOTES] characters. */
    val notes: String? = null,
    /** The visit that made it DONE (*Mark viewing done*), when there is one. */
    val visitId: String? = null,
) {
    val viewingKind: ViewingKind get() = ViewingKind.fromWire(kind)
    val viewingStatus: ViewingStatus get() = ViewingStatus.fromWire(status)

    /** The end, epoch ms. */
    val endsAt: Long get() = startsAt + durationMin * 60_000L

    /**
     * What a reader keeps (the records table, a sync, another device), like [Question.coerced]: a duration out of range
     * is 30, an unknown kind FIRST, an unknown status PLANNED, a reminder not in the list 60, a blank text or visit id
     * none, an over-long text cut to its limit. Null for an untrusted row (an id outside `RecordRules.isValidId`, a
     * blank house id, a house or visit id over 64 characters, no positive start): it is skipped (and a copy leaves it
     * out), as the web's `viewingFromPayload` and the server do.
     */
    fun coerced(): Viewing? {
        if (!RecordRules.isValidId(id) || houseId.isBlank() || houseId.length > MAX_REF || startsAt <= 0) return null
        if ((visitId?.length ?: 0) > MAX_REF) return null
        return copy(
            durationMin = if (durationMin in MIN_DURATION..MAX_DURATION) durationMin else DEFAULT_DURATION,
            kind = viewingKind.name,
            status = viewingStatus.name,
            remindMin = if (remindMin in REMIND_CHOICES) remindMin else DEFAULT_REMIND,
            withWhom = withWhom?.trim()?.take(MAX_WITH_WHOM)?.ifEmpty { null },
            notes = notes?.trim()?.take(MAX_NOTES)?.ifEmpty { null },
            visitId = visitId?.trim()?.ifEmpty { null },
        )
    }

    /**
     * True when every value is in range: what a backup's check demands (a bad file is refused whole, and an unknown
     * kind or status in a FILE is refused, while a stored one is coerced on read).
     */
    val isValid: Boolean
        get() = RecordRules.isValidId(id) && houseId.isNotBlank() && houseId.length <= MAX_REF && startsAt > 0 &&
            (visitId?.length ?: 0) <= MAX_REF &&
            durationMin in MIN_DURATION..MAX_DURATION &&
            ViewingKind.entries.any { it.name == kind } && ViewingStatus.entries.any { it.name == status } &&
            remindMin in REMIND_CHOICES && (withWhom?.length ?: 0) <= MAX_WITH_WHOM && (notes?.length ?: 0) <= MAX_NOTES

    companion object {
        const val MIN_DURATION = 5
        const val MAX_DURATION = 480
        const val DEFAULT_DURATION = 30
        const val DEFAULT_REMIND = 60
        const val MAX_WITH_WHOM = 200
        const val MAX_NOTES = 2000

        /** The longest house or visit id a viewing may name (an id's own limit, as the server checks it). */
        const val MAX_REF = 64

        /** The reminder choices: Off, 15 min, 30 min, 1 hour, 2 hours, 1 day before. */
        val REMIND_CHOICES: List<Int> = listOf(0, 15, 30, 60, 120, 1440)

        /** At most this many live viewings: the record cap of the `records` table and the server. */
        const val MAX_VIEWINGS = RecordRules.MAX_ROWS_PER_TYPE
        const val ID_PREFIX = "v_"
        private val APP_ID = Regex("v_[0-9a-f]{8}")

        /** The history's order: [startsAt], then the id (the Repository's list and the `viewings.csv` table). */
        val ORDER: Comparator<Viewing> = compareBy<Viewing> { it.startsAt }.thenBy { it.id }

        fun isAppId(id: String): Boolean = APP_ID.matches(id)

        /** A fresh id, `v_` + 8 random lowercase hex digits, drawn again while [taken] says it is used. */
        fun newId(taken: (String) -> Boolean, random: Random = Random.Default): String {
            while (true) {
                val id = ID_PREFIX + (1..8).joinToString("") { random.nextInt(16).toString(16) }
                if (!taken(id)) return id
            }
        }
    }
}

/** The `viewing` record type of the `records` table (slice 3b-1). */
val ViewingType: RecordType<Viewing> = RecordType("viewing", Viewing.serializer())

/** First look, a second viewing, or a follow-up. Stored and sent by name. */
enum class ViewingKind {
    FIRST, SECOND, FOLLOW_UP;

    companion object {
        fun fromWire(value: String?): ViewingKind = entries.firstOrNull { it.name == value } ?: FIRST
    }
}

/** What became of a viewing. *Missed?* is derived, never stored ([Viewings.missed]). Stored and sent by name. */
enum class ViewingStatus {
    PLANNED, DONE, CANCELLED;

    companion object {
        fun fromWire(value: String?): ViewingStatus = entries.firstOrNull { it.name == value } ?: PLANNED
    }
}

/** The groups of the Viewings screen's timeline, in the order shown. */
enum class ViewingGroup { UPCOMING, MISSED, DONE, CANCELLED }

/**
 * The pure rules of viewings (docs/11 5.8, design of 2026-09-30), one per stack: the web's `viewingMissed`,
 * `suggestedVisitFor` and friends in `viewing.ts` say the same.
 */
object Viewings {
    /** A PLANNED viewing that ended more than this long ago shows as *Missed?*. */
    const val MISSED_AFTER_MS = 2 * 60 * 60_000L

    /** A visit this close to a viewing's start (before or after) is offered as the one that made it DONE. */
    const val VISIT_WINDOW_MS = 2 * 60 * 60_000L

    /** True when [viewing] is PLANNED and ended more than two hours before [nowMs]; never written, so devices never fight. */
    fun missed(viewing: Viewing, nowMs: Long): Boolean =
        viewing.viewingStatus == ViewingStatus.PLANNED && viewing.endsAt + MISSED_AFTER_MS < nowMs

    /** The timeline group of [viewing] at [nowMs]. */
    fun groupOf(viewing: Viewing, nowMs: Long): ViewingGroup = when (viewing.viewingStatus) {
        ViewingStatus.DONE -> ViewingGroup.DONE
        ViewingStatus.CANCELLED -> ViewingGroup.CANCELLED
        ViewingStatus.PLANNED -> if (missed(viewing, nowMs)) ViewingGroup.MISSED else ViewingGroup.UPCOMING
    }

    /**
     * The timeline: Upcoming (PLANNED, not missed) soonest first, then Missed?, Done and Cancelled newest first; ties by
     * id. Empty groups are left out.
     */
    fun timeline(viewings: List<Viewing>, nowMs: Long): List<Pair<ViewingGroup, List<Viewing>>> {
        val grouped = viewings.groupBy { groupOf(it, nowMs) }
        return ViewingGroup.entries.mapNotNull { g ->
            val list = grouped[g] ?: return@mapNotNull null
            g to if (g == ViewingGroup.UPCOMING) list.sortedWith(Viewing.ORDER) else list.sortedWith(NEWEST_FIRST)
        }
    }

    /** Newest start first, then the id. */
    val NEWEST_FIRST: Comparator<Viewing> = compareByDescending<Viewing> { it.startsAt }.thenBy { it.id }

    /** The earliest PLANNED viewing of [houseId] that starts at or after [nowMs], or null. */
    fun nextOf(viewings: List<Viewing>, houseId: String, nowMs: Long): Viewing? =
        viewings.filter { it.houseId == houseId && it.viewingStatus == ViewingStatus.PLANNED && it.startsAt >= nowMs }
            .minWithOrNull(Viewing.ORDER)

    /**
     * The visit at [viewing]'s house whose arrival is within two hours of its start, before or after, the closest one
     * (ties by the earlier arrival, then id); null when none. [visits] are (id, houseId, arrivedAt).
     */
    fun suggestedVisitFor(viewing: Viewing, visits: List<VisitRef>): VisitRef? =
        visits.filter { it.houseId == viewing.houseId && abs(it.arrivedAt - viewing.startsAt) <= VISIT_WINDOW_MS }
            .minWithOrNull(compareBy<VisitRef> { abs(it.arrivedAt - viewing.startsAt) }.thenBy { it.arrivedAt }.thenBy { it.id })

    /** A visit as [suggestedVisitFor] needs it. */
    data class VisitRef(val id: String, val houseId: String?, val arrivedAt: Long)

    /**
     * The Viewings screen's search: true when [query] (trimmed, case-insensitive) is empty or is part of the house's
     * label, street or locality, or the viewing's `withWhom` or notes, as the house search matches.
     */
    fun matches(viewing: Viewing, houseLabel: String?, street: String?, locality: String?, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOf(houseLabel, street, locality, viewing.withWhom, viewing.notes)
            .any { it != null && it.contains(q, ignoreCase = true) }
    }

    /**
     * The viewings of one house as the copies and the AI list them: PLANNED ones first, then the rest, newest first
     * inside each (ties by id).
     */
    fun plannedFirst(viewings: List<Viewing>): List<Viewing> =
        viewings.sortedWith(compareBy<Viewing> { it.viewingStatus != ViewingStatus.PLANNED }.then(NEWEST_FIRST))

    /**
     * The re-check list of *Book a second viewing?*: how many questions of the house are still open, and the keys of
     * the active criteria it scored 2 or less, in the scoring's order.
     */
    fun recheck(answers: List<HouseAnswer>?, checklist: Map<String, Int>, scoring: Scoring): Recheck = Recheck(
        openQuestions = answers.orEmpty().count { it.answerStatus == AnswerStatus.OPEN },
        lowCriteria = scoring.criteria.filter { !it.archived && (checklist[it.key] ?: 0) in 1..2 }.map { it.key },
    )

    data class Recheck(val openQuestions: Int, val lowCriteria: List<String>) {
        val isEmpty: Boolean get() = openQuestions == 0 && lowCriteria.isEmpty()
    }
}
