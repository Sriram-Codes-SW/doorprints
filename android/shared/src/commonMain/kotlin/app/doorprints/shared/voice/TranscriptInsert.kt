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

package app.doorprints.shared.voice

/**
 * Where the words of a transcript land (voice input, ADR-37): at the end of what is already in the box, after one space,
 * as ordinary text for the person to read and edit. Nothing is sent from here; the screen's own button does that later,
 * and the text then takes the same road as typed text (the redaction and the nonce-delimited prompt are unchanged). The
 * twin of the website's `appendTranscript`.
 */
class Inserted(val text: String, val cut: Boolean)

/**
 * [spoken] added to [current] after a space (none when [current] is empty or already ends in white space), cut at [max]
 * characters if given, without touching what was typed ([cut] says words were left out).
 */
fun appendTranscript(current: String, spoken: String, max: Int? = null): Inserted {
    val words = spoken.trim()
    if (words.isEmpty()) return Inserted(current, false)
    val joined = if (current.isEmpty() || current.last().isWhitespace()) current + words else "$current $words"
    if (max == null || joined.length <= max) return Inserted(joined, false)
    return Inserted(joined.take(maxOf(max, current.length)).trimEnd(), true)
}
