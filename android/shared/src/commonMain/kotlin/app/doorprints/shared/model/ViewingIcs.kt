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

import kotlin.time.Instant

/**
 * One viewing as an iCalendar file (RFC 5545; docs/11 5.8, design of 2026-09-30): what the website downloads and the
 * iPhone shares, so the phone's own calendar reminds reliably. The TypeScript twin is `viewingIcs` in `viewing.ts`;
 * both write `docs/schemas/viewing-sample.ics` byte for byte for the sample's viewing `v_a1b2c3d4` (vector V6).
 *
 * Deterministic: `DTSTAMP` is the record's `updatedAt`, not the clock. Times are UTC. Every line ends with CRLF; a line
 * over 75 octets of UTF-8 is folded before the octet limit without splitting a character, and the continuation starts
 * with one space. `withWhom` is never written (contact data).
 */
object ViewingIcs {
    private const val CRLF = "\r\n"
    private const val FIRST_LINE_OCTETS = 75

    /**
      * The iCalendar text for [viewing] at [houseLabel]. [houseAddress] becomes the location when present;
      * [viewingWord] is the
      * title's first word in the person's language; [dtstampMs] is written as the stamp so the output does not depend
      * on the clock.
     */
    fun build(
        viewing: Viewing,
        houseLabel: String,
        houseAddress: String?,
        viewingWord: String = "Viewing",
        dtstampMs: Long,
    ): String {
        val summary = "$viewingWord: $houseLabel"
        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//Doorprints//Viewing//EN")
            add("CALSCALE:GREGORIAN")
            add("METHOD:PUBLISH")
            add("BEGIN:VEVENT")
            add("UID:${viewing.id}@doorprints")
            add("DTSTAMP:${utc(dtstampMs)}")
            add("DTSTART:${utc(viewing.startsAt)}")
            add("DTEND:${utc(viewing.endsAt)}")
            add("SUMMARY:${escape(summary)}")
            if (!houseAddress.isNullOrEmpty()) add("LOCATION:${escape(houseAddress)}")
            if (!viewing.notes.isNullOrEmpty()) add("DESCRIPTION:${escape(viewing.notes)}")
            if (viewing.remindMin > 0) {
                add("BEGIN:VALARM")
                add("ACTION:DISPLAY")
                add("DESCRIPTION:${escape(summary)}")
                add("TRIGGER:-PT${viewing.remindMin}M")
                add("END:VALARM")
            }
            add("END:VEVENT")
            add("END:VCALENDAR")
        }
        return lines.joinToString("") { fold(it) + CRLF }
    }

    /** `20260927T093000Z`: the UTC date and time to the second. */
    fun utc(epochMs: Long): String {
        val iso = Instant.fromEpochSeconds(epochMs.floorDiv(1000L)).toString() // 2026-09-27T09:30:00Z
        return iso.substring(0, 19).replace("-", "").replace(":", "") + "Z"
    }

    /** A TEXT value: `\`, `;`, `,` and a newline escaped as RFC 5545 3.3.11 says (a CR is dropped). */
    fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    /**
     * [line] folded at 75 octets (the first line) and 1 + 74 (each continuation), cut only between characters: a
     * multi-byte character (or a surrogate pair) that would cross the limit goes to the next line whole.
     */
    fun fold(line: String): String {
        if (line.encodeToByteArray().size <= FIRST_LINE_OCTETS) return line
        val out = StringBuilder()
        var used = 0
        var limit = FIRST_LINE_OCTETS
        var i = 0
        while (i < line.length) {
            val cp = line[i]
            val width = if (cp.isHighSurrogate() && i + 1 < line.length) 2 else 1
            val chunk = line.substring(i, i + width)
            val octets = chunk.encodeToByteArray().size
            if (used + octets > limit) {
                out.append(CRLF).append(' ')
                used = 1
                limit = FIRST_LINE_OCTETS
            }
            out.append(chunk)
            used += octets
            i += width
        }
        return out.toString()
    }
}
