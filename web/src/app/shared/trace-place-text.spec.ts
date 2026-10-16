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
import { TranslationService } from '../i18n/translation.service';
import { dayKey, placeCheckSummary, placeCheckText, placeTextHelpers } from './trace-place-text';
import type { PlaceTextHelpers } from './trace-place-text';
import type { PlaceCheckResult, PlaceRow, PlaceStatus } from './trace-place-check';

const DAY = 86_400_000;
const T0 = Date.UTC(2026, 9, 7, 8, 0, 0); // Wed 7 Oct 2026, 08:00 UTC
const row = (walkIndex: number, distanceM: number, atMs: number, walked = true, saved = false): PlaceRow => ({ walkIndex, distanceM, atMs, walked, saved, segment: 0, t: 0 });
const result = (status: PlaceStatus, rows: PlaceRow[], fuzzy = false): PlaceCheckResult => ({
  status,
  fuzzy,
  nearestM: rows.length ? Math.min(...rows.map((r) => r.distanceM)) : null,
  rows,
});

/** The English strings of docs/11 5.27.13, enough to read the sentences. */
const EN: Record<string, string> = {
  'trace.here.walked': 'You walked within {distance} of {place} on {dates}.',
  'trace.here.close': 'No walk of yours passed within {tolerance} of {place}, but one came within {distance} on {dates}.',
  'trace.here.none': 'No walk of yours passed within {tolerance} of {place} in the last 30 days.',
  'trace.here.noneSaved': 'No walk of yours passed within {tolerance} of {place} in the last 30 days or in your saved walks.',
  'trace.here.onlyRecorded': 'This covers only the walks Doorprints recorded.',
  'trace.here.onlyRecordedWeb': 'On the website, only walks recorded while this page was open are included.',
  'trace.here.andMore': '{dates} and {n} more',
  'trace.here.row': '{date}, {distance} away',
  'trace.here.rowSaved': '{date}, {distance} away, saved walk',
  'trace.here.rowsMore': 'and {n} more walks',
  'trace.here.empty': 'There are no walks to compare yet (phone).',
  'trace.here.emptyWeb': 'There are no walks to compare yet (web).',
  'trace.here.imprecise': 'Location not precise enough. Try again outdoors.',
  'trace.here.fuzzy': 'Your location is only accurate to about {n} m, so this answer may be off.',
  'trace.here.invalid': 'This spot has no valid location.',
  'trace.here.placeHere': 'here',
  'trace.here.placeHouse': 'this house',
  'trace.here.placeSpot': 'this spot',
};
const service = new TranslationService();
const helpers = (svc: TranslationService = service): PlaceTextHelpers => ({
  t: (key, params) => (EN[key] ?? key).replace(/\{(\w+)\}/g, (_m, k: string) => String(params?.[k] ?? `{${k}}`)),
  list: (items) => svc.list(items),
  dateWithWeekday: (ms, tz) => svc.dateWithWeekday(ms, tz, T0),
  metres: (n) => svc.metres(n),
});
/** A day the way the app writes it (en-IN puts a comma after the weekday: *Wed, 7 Oct*). */
const d = (ms: number) => service.dateWithWeekday(ms, 'UTC', T0);
const text = (r: PlaceCheckResult, opts: Parameters<typeof placeCheckSummary>[1] = { hasSaved: false }, place: 'here' | 'house' | 'spot' = 'house', web = false) =>
  placeCheckText(placeCheckSummary(r, { timeZone: 'UTC', ...opts }), place, helpers(), web);

describe('dayKey', () => {
  it('is the calendar day in the given time zone', () => {
    const late = Date.UTC(2026, 9, 6, 23, 50, 0);
    expect(dayKey(late, 'UTC')).toBe('2026-10-06');
    expect(dayKey(late, 'Asia/Kolkata')).toBe('2026-10-07');
  });
});

describe('placeCheckSummary', () => {
  it('lists the distinct days of the walked rows newest first, at most three, then counts the rest', () => {
    const rows = [row(4, 10, T0), row(3, 12, T0 - 2 * DAY), row(2, 8, T0 - 3 * DAY), row(1, 9, T0 - 5 * DAY), row(0, 7, T0 - 6 * DAY)];
    const s = placeCheckSummary(result('WALKED', rows), { hasSaved: false, timeZone: 'UTC' });
    expect(s.days).toEqual([T0, T0 - 2 * DAY, T0 - 3 * DAY]);
    expect(s.moreDays).toBe(2);
  });

  it('counts two walks on one day as one day, and takes the largest distance among the listed days, rounded up, at least 1 m', () => {
    const rows = [row(2, 3.2, T0 + 3600_000), row(1, 22.1, T0), row(0, 0, T0 - DAY)];
    const s = placeCheckSummary(result('WALKED', rows), { hasSaved: false, timeZone: 'UTC' });
    expect(s.days).toHaveLength(2);
    expect(s.distanceM).toBe(23); // ceil(22.1), the largest of the listed days, not the smallest (3.2)
    expect(placeCheckSummary(result('WALKED', [row(0, 0, T0)]), { hasSaved: false, timeZone: 'UTC' }).distanceM).toBe(1);
  });

  it('leaves a day beyond the third out of the largest distance', () => {
    const rows = [row(3, 5, T0), row(2, 6, T0 - DAY), row(1, 7, T0 - 2 * DAY), row(0, 24.9, T0 - 3 * DAY)];
    expect(placeCheckSummary(result('WALKED', rows), { hasSaved: false, timeZone: 'UTC' }).distanceM).toBe(7);
  });

  it('shows at most five rows and counts the rest, one per walk, newest first', () => {
    const rows = Array.from({ length: 7 }, (_, i) => row(7 - i, 10, T0 - i * DAY));
    const s = placeCheckSummary(result('WALKED', rows), { hasSaved: false, timeZone: 'UTC' });
    expect(s.rows).toHaveLength(5);
    expect(s.rowsMore).toBe(2);
    expect(s.rows[0].atMs).toBe(T0);
  });

  it('uses only the walked rows for the headline of WALKED, not the close ones beside them', () => {
    const rows = [row(1, 41, T0, false), row(0, 12, T0 - DAY, true)];
    const s = placeCheckSummary(result('WALKED', rows), { hasSaved: false, timeZone: 'UTC' });
    expect(s.days).toEqual([T0 - DAY]);
    expect(s.distanceM).toBe(12);
    expect(s.rows).toHaveLength(1);
  });

  it('uses the close rows for CLOSE and no rows for NONE', () => {
    expect(placeCheckSummary(result('CLOSE', [row(0, 41, T0, false)]), { hasSaved: false, timeZone: 'UTC' }).rows).toHaveLength(1);
    expect(placeCheckSummary(result('NONE', []), { hasSaved: false, timeZone: 'UTC' }).rows).toEqual([]);
  });

  it('shows the fuzzy line only with WALKED, CLOSE or NONE, with the accuracy rounded', () => {
    const s = (status: PlaceStatus, acc: number) => placeCheckSummary(result(status, [], true), { hasSaved: false, timeZone: 'UTC', fixAccuracyM: acc });
    expect(s('WALKED', 30.4).fuzzyM).toBe(30);
    expect(s('CLOSE', 45.6).fuzzyM).toBe(46);
    expect(s('NONE', 40).fuzzyM).toBe(40);
    for (const status of ['EMPTY', 'IMPRECISE', 'INVALID_PLACE'] as const) expect(s(status, 40).fuzzyM, status).toBeNull();
    expect(placeCheckSummary(result('WALKED', [row(0, 1, T0)]), { hasSaved: false, timeZone: 'UTC' }).fuzzyM).toBeNull();
  });
});

describe('placeCheckText', () => {
  it('writes the walked headline with the place, the largest distance and the days', () => {
    const t = text(result('WALKED', [row(1, 12.2, T0), row(0, 22.1, T0 - 2 * DAY)]), { hasSaved: false }, 'house');
    expect(t.headline).toBe(`You walked within 23 m of this house on ${d(T0)} and ${d(T0 - 2 * DAY)}.`);
  });

  it('writes the close headline with the tolerance as a distance (a 20 m constant would change no string)', () => {
    const t = text(result('CLOSE', [row(0, 41.2, T0, false)]), { hasSaved: false }, 'spot');
    expect(t.headline).toBe(`No walk of yours passed within 25 m of this spot, but one came within 42 m on ${d(T0)}.`);
    expect(t.notes).toEqual(['This covers only the walks Doorprints recorded.']);
  });

  it('writes the none headline, with the saved-walks variant when a saved walk exists', () => {
    expect(text(result('NONE', []), { hasSaved: false }, 'here').headline).toBe('No walk of yours passed within 25 m of here in the last 30 days.');
    expect(text(result('NONE', []), { hasSaved: true }, 'here').headline).toBe('No walk of yours passed within 25 m of here in the last 30 days or in your saved walks.');
  });

  it('adds "and n more" after three days', () => {
    const rows = [row(4, 10, T0), row(3, 10, T0 - DAY), row(2, 10, T0 - 2 * DAY), row(1, 10, T0 - 3 * DAY), row(0, 10, T0 - 4 * DAY)];
    expect(text(result('WALKED', rows)).headline).toBe(`You walked within 10 m of this house on ${d(T0)}, ${d(T0 - DAY)} and ${d(T0 - 2 * DAY)} and 2 more.`);
  });

  it('writes the rows with the saved-walk tag and the "and n more walks" line', () => {
    const rows = Array.from({ length: 6 }, (_, i) => row(6 - i, 10 + i, T0 - i * DAY, true, i === 1));
    const t = text(result('WALKED', rows));
    expect(t.rows).toHaveLength(5);
    expect(t.rows[0]).toBe(`${d(T0)}, 10 m away`);
    expect(t.rows[1]).toBe(`${d(T0 - DAY)}, 11 m away, saved walk`);
    expect(t.rowsMore).toBe('and 1 more walks');
  });

  it('answers the other states in their own sentences, the website one for empty and its extra line for a negative answer', () => {
    expect(text(result('EMPTY', [])).headline).toBe('There are no walks to compare yet (phone).');
    expect(text(result('EMPTY', []), { hasSaved: false }, 'house', true).headline).toBe('There are no walks to compare yet (web).');
    expect(text(result('IMPRECISE', [])).headline).toBe('Location not precise enough. Try again outdoors.');
    expect(text(result('INVALID_PLACE', [])).headline).toBe('This spot has no valid location.');
    expect(text(result('NONE', []), { hasSaved: false }, 'house', true).notes).toEqual([
      'This covers only the walks Doorprints recorded.',
      'On the website, only walks recorded while this page was open are included.',
    ]);
    expect(text(result('WALKED', [row(0, 3, T0)]), { hasSaved: false }, 'house', true).notes).toEqual([]);
  });

  it('adds the fuzzy line for a loose fix', () => {
    const t = text(result('WALKED', [row(0, 3, T0)], true), { hasSaved: false, fixAccuracyM: 33.2 }, 'here');
    expect(t.notes).toEqual(['Your location is only accurate to about 33 m, so this answer may be off.']);
  });

  it('writes the dates in the language of the app: Hindi, Tamil and Telugu, through Intl', () => {
    for (const lang of ['hi', 'ta', 'te'] as const) {
      const svc = new TranslationService();
      svc.setLang(lang);
      const t = placeCheckText(placeCheckSummary(result('WALKED', [row(0, 10, T0)]), { hasSaved: false, timeZone: 'UTC' }), 'house', helpers(svc), false);
      expect(t.rows[0], lang).toContain(svc.dateWithWeekday(T0, 'UTC', T0));
      expect(t.rows[0], lang).toContain(svc.metres(10));
      expect(placeTextHelpers(svc).metres(10)).toBe(svc.metres(10));
    }
  });
});
