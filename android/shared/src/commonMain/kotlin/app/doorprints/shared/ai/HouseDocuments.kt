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

import app.doorprints.shared.model.AnswerStatus
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.RoomSizes
import app.doorprints.shared.model.Distances

/**
 * One house as labelled plain text for Ask: the server's `HouseDocuments.text` (docs/03 §13.1), with the same lines in
 * the same order. No contact line; every text field goes through [ContactRedactor] (label, checklist keys and notes
 * as free text, address, street and locality as places). Notes are capped at [NOTES_MAX] characters.
 */
object HouseDocuments {
    const val NOTES_MAX = 3000

    fun text(h: AiHouse): String {
        val r = ContactRedactor.forContact(h.contactName, h.contactPhone)
        val sb = StringBuilder()
        line(sb, "House", r.freeText(h.label))
        line(sb, "Address", r.place(h.address))
        line(sb, "Street", r.place(h.street))
        line(sb, "Locality", r.place(h.locality))
        if (h.price != null) {
            val type = when (h.priceType) {
                null -> ""
                "RENT" -> " per month (rent)"
                else -> " (sale)"
            }
            line(sb, "Price", "Rs ${h.price}$type")
        }
        if (h.bedrooms != null) line(sb, "Size", if (h.bedrooms == 0) "studio / 1RK" else "${h.bedrooms} BHK")
        // The house's own values (slice 1a), the same words as the server's; never "My offer" (docs/11 5.30 item 5).
        if (h.areaSqft != null) line(sb, "Carpet area", "${h.areaSqft} sq ft")
        // S4b-BL-87: the floor in the server's words, 0 the ground floor and a negative one a basement level.
        h.floor?.let { line(sb, "Floor", if (it == 0) "ground floor" else if (it < 0) "basement ${-it}" else "$it") }
        h.cost?.let { c ->
            line(sb, "Deposit", c.deposit?.let { "Rs $it" } ?: c.depositMonths?.let { months(it) })
            if (c.maintenance != null) {
                val included = when (c.maintenanceIncluded) {
                    true -> " (included in the rent)"
                    false -> " (not included)"
                    null -> ""
                }
                line(sb, "Maintenance", "Rs ${c.maintenance} per month$included")
            }
            line(sb, "Brokerage", c.brokerage?.let { "Rs $it" } ?: c.brokerageMonths?.let { months(it) })
            line(sb, "Lock-in", c.lockInMonths?.let { months(it) })
            line(sb, "Notice", c.noticeMonths?.let { months(it) })
            line(sb, "Available from", c.availableFrom)
            line(sb, "Agreed price", c.agreedPrice?.let { "Rs $it" })
        }
        line(sb, "Rooms", rooms(h.rooms, r))
        answerLines(sb, h.answers, r)
        viewingLines(sb, h.viewings, r)
        areaNoteLines(sb, h.areaNotes, r)
        distanceLines(sb, h.distances, r)
        moveInLines(sb, h.moveIn, r)
        line(sb, "Status", h.status)
        if (h.rating != null) line(sb, "My rating", "${h.rating}/5")
        if (h.checklist.isNotEmpty()) {
            line(sb, "Checklist", h.checklist.entries.sortedBy { it.key }.joinToString(", ") { "${r.freeText(it.key)} ${it.value}/5" })
        }
        // Deliberately no "Contact" line: the contact name and phone never go to the provider (F-30).
        line(sb, "Visits", visitSummary(h.visits))
        if (!h.notes.isNullOrBlank()) {
            val notes = h.notes.trim()
            line(sb, "Notes", r.freeText(if (notes.length > NOTES_MAX) notes.take(NOTES_MAX) + " …" else notes))
        }
        return sb.toString().trim()
    }

    /**
     * The rooms (slice 1c), the same words as the server's `HouseDocuments.rooms` and the web's `houseText`:
     * `Master bedroom 13 ft 0 in x 12 ft 0 in (condition 4/5); Kitchen 9 ft 10 in x 8 ft 0 in`, in the order shown,
     * always feet and inches. Names (redacted like the label; a blank one is the type's English name), sizes and
     * condition only: a room's notes may hold a contact's name or number and never go to the provider.
     */
    internal fun rooms(rooms: List<HouseRoom>?, r: ContactRedactor.Redactor): String? {
        if (rooms.isNullOrEmpty()) return null
        return rooms.sortedWith(HouseRooms.ORDER).joinToString("; ") { room ->
            val name = room.name?.trim()?.takeIf { it.isNotEmpty() }?.let { r.freeText(it) }
                ?: room.type.lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { "Room" }
            buildString {
                append(name)
                if (room.lengthCm != null && room.widthCm != null) {
                    append(' ').append(RoomSizes.feetInchesText(room.lengthCm)).append(" x ").append(RoomSizes.feetInchesText(room.widthCm))
                }
                if (room.condition != null) append(" (condition ").append(room.condition).append("/5)")
            }
        }
    }

    /** At most this many `Asked:` lines and this many `Still to ask:` lines (slice 3a), as the server writes. */
    const val ANSWER_LINES_MAX = 20

    /**
     * The viewing answers (slice 3a), the same words as the server's `HouseDocuments.answerLines` and the web's
     * `houseText`: `Asked: <question> | Answer: <answer>` for each answered one, then `Still to ask: <question>` for each
     * open one (a skipped question is neither), at most [ANSWER_LINES_MAX] of each, in the order shown
     * ([HouseAnswers.ordered]). Question and answer are the person's own words and go through the redactor like notes:
     * an answer may well hold the owner's number.
     */
    internal fun answerLines(sb: StringBuilder, answers: List<HouseAnswer>?, r: ContactRedactor.Redactor) {
        val ordered = HouseAnswers.ordered(answers)
        ordered.filter { it.answerStatus == AnswerStatus.ANSWERED }.take(ANSWER_LINES_MAX).forEach { a ->
            line(sb, "Asked", r.freeText(a.text.trim()).orEmpty() + " | Answer: " + r.freeText(a.answer.orEmpty().trim()).orEmpty())
        }
        ordered.filter { it.answerStatus == AnswerStatus.OPEN }.take(ANSWER_LINES_MAX).forEach { a ->
            line(sb, "Still to ask", r.freeText(a.text.trim()))
        }
    }

    /** At most this many `Viewing:` lines per house (slice 3b-1), as the server writes. */
    const val VIEWING_LINES_MAX = 10

    /**
     * The viewings (slice 3b-1), the same words as the server's `HouseDocuments` and the web's `houseText`:
     * `Viewing: 2026-09-27 09:30 | SECOND | PLANNED`, plus ` | Notes: <notes>` when there are notes, the time in UTC,
     * PLANNED ones first then newest first (ties by id), at most [VIEWING_LINES_MAX]. The
     * notes go through the redactor and their white space collapses to single spaces, so a note cannot fake a line of
     * its own; `withWhom` is never here.
     */
    internal fun viewingLines(sb: StringBuilder, viewings: List<AiViewing>, r: ContactRedactor.Redactor) {
        viewings.sortedWith(compareBy<AiViewing> { it.status != "PLANNED" }.thenByDescending { it.startsAt }.thenBy { it.id })
            .take(VIEWING_LINES_MAX)
            .forEach { v ->
                val notes = v.notes?.let { r.freeText(it.trim()) }?.split(WHITE_SPACE)?.filter { it.isNotEmpty() }?.joinToString(" ")
                val value = utcDateTime(v.startsAt) + " | " + v.kind + " | " + v.status +
                    if (notes.isNullOrEmpty()) "" else " | Notes: $notes"
                line(sb, "Viewing", value)
            }
    }

    private val WHITE_SPACE = Regex("\\s+")

    /** At most this many `Area note:` and `Distance to` lines per house (slice 4a), as the server writes. */
    const val AREA_NOTE_LINES_MAX = 5
    const val DISTANCE_LINES_MAX = 10

    /**
     * The area notes that reach the house (slice 4a), the same words as the server's `HouseDocuments.areaNoteLines` and
     * the web's `houseText`: `Area note: <text>`, newest first (ties by id), at most [AREA_NOTE_LINES_MAX], the text
     * through the redactor with its white space collapsed, so a note cannot fake a line of its own.
     */
    internal fun areaNoteLines(sb: StringBuilder, notes: List<AiAreaNote>, r: ContactRedactor.Redactor) {
        notes.sortedWith(compareByDescending<AiAreaNote> { it.updatedAt }.thenBy { it.id }).take(AREA_NOTE_LINES_MAX)
            .forEach { n -> line(sb, "Area note", oneLine(r.freeText(n.text.trim()))) }
    }

    /**
     * The distances to my places (slice 4a): `Distance to <place>: <km> km`, nearest first (ties by name), at most
     * [DISTANCE_LINES_MAX], the km with one decimal, half up ([Distances.km]); the name through the redactor, never the
     * coordinates.
     */
    internal fun distanceLines(sb: StringBuilder, distances: List<AiDistance>, r: ContactRedactor.Redactor) {
        distances.sortedWith(compareBy<AiDistance> { it.meters }.thenBy { it.name }).take(DISTANCE_LINES_MAX).forEach { d ->
            val name = oneLine(r.freeText(d.name.trim()))
            if (!name.isNullOrEmpty()) sb.append("Distance to ").append(name).append(": ").append(Distances.km(d.meters)).append(" km\n")
        }
    }

    /**
     * Moving in (slice 5), after the distances, the same words as the server's and the web's `houseText`:
     * `Moving in: <done> of <total> done` when it has items, and `Moving in notes: <notes>` through the redactor on one
     * line (its white space collapsed). The items' own texts are not sent.
     */
    internal fun moveInLines(sb: StringBuilder, moveIn: MoveIn?, r: ContactRedactor.Redactor) {
        val m = moveIn ?: return
        val (done, total) = m.progress
        if (total > 0) line(sb, "Moving in", "$done of $total done")
        line(sb, "Moving in notes", oneLine(m.notes?.trim()?.let { r.freeText(it) }))
    }

    private fun oneLine(text: String?): String? = text?.split(WHITE_SPACE)?.filter { it.isNotEmpty() }?.joinToString(" ")

    /** `yyyy-MM-dd HH:mm` in UTC, as the server's formatter writes a viewing's start. */
    internal fun utcDateTime(epochMs: Long): String {
        val minutes = epochMs.floorDiv(60_000L).mod(24 * 60L)
        return utcDate(epochMs) + " " + (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')
    }

    /** The label as Ask's citations show it: free text, redacted. */
    fun label(h: AiHouse): String = ContactRedactor.forContact(h.contactName, h.contactPhone).freeText(h.label) ?: ""

    fun visitSummary(visits: List<AiVisit>): String {
        if (visits.isEmpty()) return "not visited yet"
        val last = visits.maxOf { it.arrivedAt }
        val totalMinutes = visits.filter { it.leftAt != null && it.leftAt > it.arrivedAt }
            .sumOf { (it.leftAt!! - it.arrivedAt) / 60_000 }
        val day = utcDate(last)
        return "${visits.size}${if (visits.size == 1) " visit" else " visits"}, last on $day" +
            if (totalMinutes > 0) ", $totalMinutes min in total" else ""
    }

    /** `yyyy-MM-dd` in UTC for epoch milliseconds (days-to-civil, H. Hinnant), as the server's `DAY` formatter. */
    internal fun utcDate(epochMs: Long): String {
        val z = epochMs.floorDiv(86_400_000L) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    private fun months(n: Int): String = if (n == 1) "1 month" else "$n months"

    private fun line(sb: StringBuilder, key: String, value: String?) {
        if (!value.isNullOrBlank()) sb.append(key).append(": ").append(value.trim()).append('\n')
    }
}
