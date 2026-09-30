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
import {
  DEFAULT_DURATION_MIN,
  MAX_ID_LENGTH,
  filterViewings,
  groupViewings,
  isAppViewingId,
  markableViewing,
  newViewingId,
  nextViewingOf,
  sortViewings,
  suggestedVisitFor,
  viewingFromPayload,
  viewingMatches,
  viewingMissed,
  viewingToPayload,
  viewingsForCopy,
} from './viewing';
import type { Viewing } from './viewing';

const HOUR = 3_600_000;
const NOW = Date.parse('2026-09-30T12:00:00.000Z');
const v = (id: string, over: Partial<Viewing> = {}): Viewing => ({
  id,
  houseId: 'h-1',
  startsAt: NOW + HOUR,
  durationMin: 30,
  kind: 'FIRST',
  status: 'PLANNED',
  remindMin: 60,
  ...over,
});

describe('viewingFromPayload (coercion on read)', () => {
  const good = { houseId: 'h-1', startsAt: 1790501400000, durationMin: 45, kind: 'SECOND', status: 'DONE', remindMin: 30 };

  it('reads a full payload and gives the keys in the contract order', () => {
    const full = { ...good, huntReminder: true, withWhom: 'Ravi', notes: 'Tape', visitId: 'vis-1' };
    const viewing = viewingFromPayload('v_a1b2c3d4', full);
    expect(viewing).toEqual({ id: 'v_a1b2c3d4', ...full });
    expect(Object.keys(viewingToPayload(viewing!))).toEqual([
      'houseId', 'startsAt', 'durationMin', 'kind', 'status', 'remindMin', 'huntReminder', 'withWhom', 'notes', 'visitId',
    ]);
  });

  it('writes only the required keys for a plain viewing, and huntReminder only when true', () => {
    expect(Object.keys(viewingToPayload(v('v_00000001')))).toEqual(['houseId', 'startsAt', 'durationMin', 'kind', 'status', 'remindMin']);
    expect(Object.keys(viewingToPayload({ ...v('v_00000001'), huntReminder: true }))).toContain('huntReminder');
    expect(viewingFromPayload('v_00000001', { ...good, huntReminder: false })).not.toHaveProperty('huntReminder');
    expect(viewingFromPayload('v_00000001', { ...good, huntReminder: 'yes' })).not.toHaveProperty('huntReminder');
  });

  it('skips a row with a blank or missing house, a missing or non-positive start, or a bad id as untrusted', () => {
    expect(viewingFromPayload('v_00000001', { ...good, houseId: '  ' })).toBeNull();
    expect(viewingFromPayload('v_00000001', { ...good, houseId: undefined })).toBeNull();
    expect(viewingFromPayload('v_00000001', { ...good, houseId: 'x'.repeat(MAX_ID_LENGTH + 1) })).toBeNull();
    expect(viewingFromPayload('v_00000001', { ...good, startsAt: undefined })).toBeNull();
    expect(viewingFromPayload('v_00000001', { ...good, startsAt: 0 })).toBeNull();
    expect(viewingFromPayload('v_00000001', { ...good, startsAt: -5 })).toBeNull();
    expect(viewingFromPayload('v_00000001', { ...good, startsAt: '1790501400000' })).toBeNull();
    expect(viewingFromPayload('bad id', good)).toBeNull();
    expect(viewingFromPayload('..', good)).toBeNull();
    expect(viewingFromPayload('v_00000001', null)).toBeNull();
  });

  it('reads an unknown kind as FIRST, an unknown status as PLANNED, a bad duration as 30 and a bad reminder as 60', () => {
    expect(viewingFromPayload('v_00000001', { ...good, kind: 'THIRD', status: 'MISSED', durationMin: 4, remindMin: 7 })).toMatchObject({
      kind: 'FIRST', status: 'PLANNED', durationMin: DEFAULT_DURATION_MIN, remindMin: 60,
    });
    expect(viewingFromPayload('v_00000001', { ...good, durationMin: 481 })?.durationMin).toBe(30);
    expect(viewingFromPayload('v_00000001', { ...good, durationMin: 5 })?.durationMin).toBe(5);
    expect(viewingFromPayload('v_00000001', { ...good, durationMin: 480 })?.durationMin).toBe(480);
    expect(viewingFromPayload('v_00000001', { ...good, remindMin: 0 })?.remindMin).toBe(0);
    expect(viewingFromPayload('v_00000001', { ...good, remindMin: 1440 })?.remindMin).toBe(1440);
    expect(viewingFromPayload('v_00000001', { houseId: 'h', startsAt: 5 })).toMatchObject({ durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60 });
  });

  it('drops an over-long or blank withWhom, notes and visitId instead of the whole row', () => {
    const row = viewingFromPayload('v_00000001', { ...good, withWhom: 'x'.repeat(201), notes: 'n'.repeat(2001), visitId: 'v'.repeat(65) });
    expect(row).not.toBeNull();
    expect(row).not.toHaveProperty('withWhom');
    expect(row).not.toHaveProperty('notes');
    expect(row).not.toHaveProperty('visitId');
    expect(viewingFromPayload('v_00000001', { ...good, withWhom: 'x'.repeat(200), notes: ' ' })).toMatchObject({ withWhom: 'x'.repeat(200) });
    expect(viewingFromPayload('v_00000001', { ...good, notes: ' ' })).not.toHaveProperty('notes');
  });
});

describe('newViewingId', () => {
  it('is v_ and 8 lowercase hex characters', () => {
    for (let i = 0; i < 20; i++) expect(isAppViewingId(newViewingId())).toBe(true);
    expect(isAppViewingId('v_ABCDEF01')).toBe(false);
    expect(isAppViewingId('v_123')).toBe(false);
  });
});

describe('viewingMissed (derived, never stored)', () => {
  const ended = (minutesAgo: number, over: Partial<Viewing> = {}) => v('v_00000001', { startsAt: NOW - minutesAgo * 60_000 - 30 * 60_000, ...over });

  it('is a PLANNED viewing that ended more than two hours ago', () => {
    expect(viewingMissed(ended(121), NOW)).toBe(true);
    expect(viewingMissed(ended(120), NOW)).toBe(false);
    expect(viewingMissed(ended(60), NOW)).toBe(false);
    expect(viewingMissed(v('v_00000001', { startsAt: NOW + HOUR }), NOW)).toBe(false);
  });

  it('counts from the end (start plus duration), not the start', () => {
    const long = v('v_00000001', { startsAt: NOW - 3 * HOUR, durationMin: 120 });
    expect(viewingMissed(long, NOW)).toBe(false);
    expect(viewingMissed({ ...long, durationMin: 30 }, NOW)).toBe(true);
  });

  it('is never true for a DONE or CANCELLED viewing', () => {
    expect(viewingMissed(ended(500, { status: 'DONE' }), NOW)).toBe(false);
    expect(viewingMissed(ended(500, { status: 'CANCELLED' }), NOW)).toBe(false);
  });
});

describe('suggestedVisitFor', () => {
  const at = (ms: number) => new Date(ms).toISOString();
  const viewing = v('v_00000001', { startsAt: NOW });
  const visit = (id: string, offsetMs: number, houseId = 'h-1', deleted = false) => ({ id, houseId, arrivedAt: at(NOW + offsetMs), deleted });

  it('finds a visit within two hours on both sides of the start', () => {
    expect(suggestedVisitFor(viewing, [visit('a', -2 * HOUR)])?.id).toBe('a');
    expect(suggestedVisitFor(viewing, [visit('a', 2 * HOUR)])?.id).toBe('a');
    expect(suggestedVisitFor(viewing, [visit('a', -2 * HOUR - 1)])).toBeNull();
    expect(suggestedVisitFor(viewing, [visit('a', 2 * HOUR + 1)])).toBeNull();
  });

  it('picks the closest one', () => {
    expect(suggestedVisitFor(viewing, [visit('far', HOUR), visit('near', -10 * 60_000), visit('mid', -30 * 60_000)])?.id).toBe('near');
  });

  it('breaks an equal distance by the earlier arrival, then the id', () => {
    expect(suggestedVisitFor(viewing, [visit('after', HOUR), visit('before', -HOUR)])?.id).toBe('before');
    expect(suggestedVisitFor(viewing, [visit('b', HOUR), visit('a', HOUR)])?.id).toBe('a');
  });

  it('ignores a visit of another house, a deleted one and one with no readable time', () => {
    expect(suggestedVisitFor(viewing, [visit('other', 0, 'h-2')])).toBeNull();
    expect(suggestedVisitFor(viewing, [visit('gone', 0, 'h-1', true)])).toBeNull();
    expect(suggestedVisitFor(viewing, [{ id: 'x', houseId: 'h-1', arrivedAt: 'never' }])).toBeNull();
    expect(suggestedVisitFor(viewing, [])).toBeNull();
  });

  it('offers Mark viewing done for the latest PLANNED viewing that has a visit near it', () => {
    const early = v('v_00000001', { startsAt: NOW - 24 * HOUR });
    const late = v('v_00000002', { startsAt: NOW });
    const done = v('v_00000003', { startsAt: NOW, status: 'DONE' });
    const visits = [visit('vis-late', 0), { id: 'vis-early', houseId: 'h-1', arrivedAt: at(NOW - 24 * HOUR) }];
    expect(markableViewing([early, late, done], visits)?.viewing.id).toBe('v_00000002');
    expect(markableViewing([early, late], [visits[1]])?.viewing.id).toBe('v_00000001');
    expect(markableViewing([done], visits)).toBeNull();
    expect(markableViewing([early, late], [])).toBeNull();
  });
});

describe('the timeline', () => {
  const upcomingLate = v('v_00000001', { startsAt: NOW + 5 * HOUR });
  const upcomingSoon = v('v_00000002', { startsAt: NOW + HOUR });
  const missedOld = v('v_00000003', { startsAt: NOW - 30 * HOUR });
  const missedNew = v('v_00000004', { startsAt: NOW - 10 * HOUR });
  const justEnded = v('v_00000005', { startsAt: NOW - HOUR });
  const doneOld = v('v_00000006', { startsAt: NOW - 50 * HOUR, status: 'DONE' });
  const doneNew = v('v_00000007', { startsAt: NOW - 5 * HOUR, status: 'DONE' });
  const cancelled = v('v_00000008', { startsAt: NOW + 2 * HOUR, status: 'CANCELLED' });
  const all = [upcomingLate, doneOld, missedNew, cancelled, upcomingSoon, missedOld, doneNew, justEnded];

  it('groups Upcoming soonest first, then Missed, Done and Cancelled newest first', () => {
    const g = groupViewings(all, NOW);
    expect(g.upcoming.map((x) => x.id)).toEqual(['v_00000005', 'v_00000002', 'v_00000001']);
    expect(g.missed.map((x) => x.id)).toEqual(['v_00000004', 'v_00000003']);
    expect(g.done.map((x) => x.id)).toEqual(['v_00000007', 'v_00000006']);
    expect(g.cancelled.map((x) => x.id)).toEqual(['v_00000008']);
  });

  it('orders by start then id', () => {
    expect(sortViewings([v('v_0000000b'), v('v_0000000a'), v('v_00000001', { startsAt: 5 })]).map((x) => x.id)).toEqual(['v_00000001', 'v_0000000a', 'v_0000000b']);
  });

  it('finds the next PLANNED viewing of a house at or after now', () => {
    expect(nextViewingOf(all, 'h-1', NOW)?.id).toBe('v_00000002');
    expect(nextViewingOf(all, 'h-2', NOW)).toBeNull();
    expect(nextViewingOf([v('v_00000001', { startsAt: NOW })], 'h-1', NOW)?.id).toBe('v_00000001');
    expect(nextViewingOf([v('v_00000001', { startsAt: NOW - 1 })], 'h-1', NOW)).toBeNull();
    expect(nextViewingOf([cancelled, doneNew], 'h-1', NOW - 100 * HOUR)).toBeNull();
  });

  it('filters by date range, kind and status', () => {
    const kinds = [v('v_00000001', { kind: 'SECOND' }), v('v_00000002', { kind: 'FOLLOW_UP', status: 'DONE' }), v('v_00000003')];
    expect(filterViewings(kinds, { kind: 'SECOND' }).map((x) => x.id)).toEqual(['v_00000001']);
    expect(filterViewings(kinds, { status: 'DONE' }).map((x) => x.id)).toEqual(['v_00000002']);
    expect(filterViewings(kinds, { kind: '', status: '' })).toHaveLength(3);
    expect(filterViewings(all, { from: NOW, to: NOW + 3 * HOUR }).map((x) => x.id).sort()).toEqual(['v_00000002', 'v_00000008']);
    expect(filterViewings(all, { from: NOW + 6 * HOUR })).toEqual([]);
  });

  it('lists a copy\'s viewings upcoming PLANNED first, then the rest newest first', () => {
    expect(viewingsForCopy(all, NOW).map((x) => x.id)).toEqual([
      'v_00000002', 'v_00000001', 'v_00000008', 'v_00000005', 'v_00000007', 'v_00000004', 'v_00000003', 'v_00000006',
    ]);
  });
});

describe('the Viewings screen search', () => {
  const viewing = v('v_00000001', { withWhom: 'Meena Iyer', notes: 'Check the TERRACE door' });
  const house = { label: 'Green View 2BHK', street: 'MG Road', locality: 'Adyar' };

  it('matches the house label, street, locality, with whom and notes, ignoring case', () => {
    for (const q of ['green view', 'mg road', 'ADYAR', 'meena', 'terrace door']) expect(viewingMatches(viewing, house, q), q).toBe(true);
    expect(viewingMatches(viewing, house, 'velachery')).toBe(false);
    expect(viewingMatches(viewing, house, '  ')).toBe(true);
  });

  it('still matches with whom and notes when the house is gone, and matches nothing of the house then', () => {
    expect(viewingMatches(viewing, null, 'meena')).toBe(true);
    expect(viewingMatches(viewing, null, 'adyar')).toBe(false);
  });
});
