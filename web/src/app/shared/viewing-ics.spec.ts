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

import { describe, expect, it } from 'vitest';
import sampleIcs from '../../../../docs/schemas/viewing-sample.ics' with { loader: 'text' };
import type { Viewing } from './viewing';
import { icsEscape, icsFold, icsTime, viewingIcs } from './viewing-ics';

/** The second viewing of `docs/schemas/backup-sample.json`; its `updatedAt` is the stamp. */
const SAMPLE: Viewing = {
  id: 'v_a1b2c3d4',
  houseId: '11111111-1111-4111-8111-111111111111',
  startsAt: 1790501400000,
  durationMin: 45,
  kind: 'SECOND',
  status: 'PLANNED',
  remindMin: 30,
  huntReminder: true,
  notes: 'Ask for the water bill. Bring a tape; check the terrace, please.',
};
const STAMP = 1790072130000;
const octets = (line: string) => new TextEncoder().encode(line).length;
const linesOf = (text: string) => text.split('\r\n');

describe('viewingIcs', () => {
  it('V6: equals docs/schemas/viewing-sample.ics byte for byte', () => {
    const text = viewingIcs(SAMPLE, 'Green View 2BHK', '12, MG Road', 'Viewing', STAMP);
    expect(text).toBe(sampleIcs);
    expect(new TextEncoder().encode(text)).toEqual(new TextEncoder().encode(sampleIcs));
    expect(text.endsWith('END:VCALENDAR\r\n')).toBe(true);
  });

  it('ends every line with CRLF and never uses a bare newline', () => {
    const text = viewingIcs(SAMPLE, 'Green View 2BHK', '12, MG Road', 'Viewing', STAMP);
    expect(text.replace(/\r\n/g, '')).not.toMatch(/[\r\n]/);
  });

  it('has no DESCRIPTION, LOCATION or VALARM lines for a viewing without notes, address and reminder', () => {
    const text = viewingIcs({ ...SAMPLE, notes: undefined, remindMin: 0 }, 'Green View 2BHK', '', 'Viewing', STAMP);
    expect(text).not.toContain('DESCRIPTION');
    expect(text).not.toContain('LOCATION');
    expect(text).not.toContain('VALARM');
    expect(text).not.toContain('TRIGGER');
    expect(viewingIcs({ ...SAMPLE, notes: undefined }, 'x', null, 'Viewing', STAMP)).not.toContain('LOCATION');
    expect(viewingIcs({ ...SAMPLE, notes: undefined }, 'x', undefined, 'Viewing', STAMP)).toContain('BEGIN:VALARM');
  });

  it('puts the reminder minutes in the trigger and the end at start plus duration, both in UTC', () => {
    const text = viewingIcs({ ...SAMPLE, remindMin: 1440, durationMin: 90 }, 'H', null, 'Viewing', STAMP);
    expect(text).toContain('TRIGGER:-PT1440M\r\n');
    expect(text).toContain('DTSTART:20260927T093000Z\r\n');
    expect(text).toContain('DTEND:20260927T110000Z\r\n');
    expect(text).toContain('DTSTAMP:20260922T101530Z\r\n');
    expect(icsTime(0)).toBe('19700101T000000Z');
  });

  it('never carries the person met', () => {
    const text = viewingIcs({ ...SAMPLE, withWhom: 'Ravi Kumar' }, 'Green View 2BHK', '12, MG Road', 'Viewing', STAMP);
    expect(text).not.toContain('Ravi');
    expect(text).toBe(sampleIcs);
  });

  it('folds a multi-byte label at 75 octets without splitting a character', () => {
    const label = 'வீடு'.repeat(20);
    const text = viewingIcs({ ...SAMPLE, notes: undefined }, label, null, 'Viewing', STAMP);
    const lines = linesOf(text).slice(0, -1);
    expect(lines.every((l) => octets(l) <= 75)).toBe(true);
    expect(lines.some((l) => l.startsWith(' '))).toBe(true);
    // Unfolding (drop each CRLF + one space) gives the summary back whole, every character intact.
    const unfolded = text.replace(/\r\n /g, '');
    expect(unfolded).toContain(`SUMMARY:Viewing: ${label}\r\n`);
    expect(unfolded).not.toContain('�');
  });

  it('folds a long ASCII line at exactly 75 octets and continues with one space and 74 more', () => {
    const [first, second] = icsFold('X:' + 'a'.repeat(100));
    expect(octets(first)).toBe(75);
    expect(second.startsWith(' ')).toBe(true);
    expect(octets(second)).toBe(1 + (102 - 75));
    expect(icsFold('X:' + 'a'.repeat(73))).toHaveLength(1);
    expect(icsFold('X:' + 'a'.repeat(74))).toHaveLength(2);
    const long = icsFold('X:' + 'b'.repeat(200));
    expect(long.slice(1, -1).every((l) => octets(l) === 75)).toBe(true);
  });

  it('escapes backslash, semicolon, comma and line breaks in text values', () => {
    expect(icsEscape('a\\b;c,d\ne\r\nf\rg')).toBe('a\\\\b\\;c\\,d\\ne\\nf\\ng');
    const text = viewingIcs({ ...SAMPLE, notes: 'One; two, three\\four\nfive' }, 'A, B; C', '1, Main; Rd', 'Viewing', STAMP);
    expect(text).toContain('SUMMARY:Viewing: A\\, B\\; C\r\n');
    expect(text).toContain('LOCATION:1\\, Main\\; Rd\r\n');
    expect(text).toContain('DESCRIPTION:One\\; two\\, three\\\\four\\nfive\r\n');
  });

  it('uses the translated word for the summary and the alarm text', () => {
    const text = viewingIcs(SAMPLE, 'H', null, 'Sandarshan', STAMP);
    expect(text).toContain('SUMMARY:Sandarshan: H\r\n');
    expect(text).toContain('DESCRIPTION:Sandarshan: H\r\nTRIGGER');
    expect(text).toContain('UID:v_a1b2c3d4@doorprints\r\n');
  });
});
