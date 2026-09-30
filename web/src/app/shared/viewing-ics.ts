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

import type { Viewing } from './viewing';

/**
 * The calendar file of one viewing (docs/11 5.8, RFC 5545): one VEVENT, UTC times, CRLF after every line, text escaped
 * and lines folded at 75 octets. Pure and deterministic (the stamp is a parameter), so the output for the sample
 * viewing equals `docs/schemas/viewing-sample.ics` byte for byte (vector V6). The twin of Kotlin `ViewingIcs.build`.
 * The notes go in DESCRIPTION; `withWhom` never does (it is contact data).
 */

const MAX_LINE_OCTETS = 75;
const encoder = new TextEncoder();

/** `20260927T093000Z`: the UTC instant as iCalendar writes it. */
export function icsTime(epochMs: number): string {
  return new Date(epochMs).toISOString().replace(/[-:]/g, '').slice(0, 15) + 'Z';
}

/** Escapes a TEXT value: `\`, `;`, `,` and line breaks. */
export function icsEscape(text: string): string {
  return text.replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r\n|\r|\n/g, '\\n');
}

/**
 * Folds one content line: no line is longer than 75 octets (UTF-8), the break never splits a character, and a
 * continuation line starts with one space (so it holds 74 more octets). Returns the lines without their CRLF.
 */
export function icsFold(line: string): string[] {
  const out: string[] = [];
  let current = '';
  let used = 0;
  for (const ch of line) {
    const octets = encoder.encode(ch).length;
    if (used + octets > MAX_LINE_OCTETS) {
      out.push(current);
      current = ' ';
      used = 1;
    }
    current += ch;
    used += octets;
  }
  out.push(current);
  return out;
}

/**
 * The text of the `.ics` file. `dtstampMs` is the viewing's `updatedAt`, so the same viewing always gives the same
 * bytes; `viewingWord` is the translated "Viewing" of the SUMMARY.
 */
export function viewingIcs(
  viewing: Viewing,
  houseLabel: string,
  houseAddress: string | null | undefined,
  viewingWord: string,
  dtstampMs: number,
): string {
  const summary = `${viewingWord}: ${houseLabel}`;
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Doorprints//Viewing//EN',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    'BEGIN:VEVENT',
    `UID:${viewing.id}@doorprints`,
    `DTSTAMP:${icsTime(dtstampMs)}`,
    `DTSTART:${icsTime(viewing.startsAt)}`,
    `DTEND:${icsTime(viewing.startsAt + viewing.durationMin * 60_000)}`,
    `SUMMARY:${icsEscape(summary)}`,
  ];
  if (houseAddress && houseAddress.trim() !== '') lines.push(`LOCATION:${icsEscape(houseAddress)}`);
  if (viewing.notes) lines.push(`DESCRIPTION:${icsEscape(viewing.notes)}`);
  if (viewing.remindMin > 0) {
    lines.push('BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:${icsEscape(summary)}`, `TRIGGER:-PT${viewing.remindMin}M`, 'END:VALARM');
  }
  lines.push('END:VEVENT', 'END:VCALENDAR');
  return lines.flatMap(icsFold).map((l) => l + '\r\n').join('');
}
