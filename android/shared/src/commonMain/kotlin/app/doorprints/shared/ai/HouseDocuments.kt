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

    private fun line(sb: StringBuilder, key: String, value: String?) {
        if (!value.isNullOrBlank()) sb.append(key).append(": ").append(value.trim()).append('\n')
    }
}
