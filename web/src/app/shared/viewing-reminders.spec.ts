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
import type { Viewing } from './viewing';
import { dueReminders, upcomingReminders } from './viewing-reminders';

const MIN = 60_000;
const TEN = new Date(2026, 9, 1, 10, 0).getTime();
const v = (id: string, over: Partial<Viewing> = {}): Viewing => ({
  id, houseId: 'h1', startsAt: TEN, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60, ...over,
});

describe('upcomingReminders', () => {
  it('R1 60 min before 10:00 is 09:00', () => {
    const [r] = upcomingReminders([v('v_00000001')], TEN - 2 * 60 * MIN);
    expect(r.fireAt).toBe(new Date(2026, 9, 1, 9, 0).getTime());
  });

  it('R2 remindMin 0 gives none', () => {
    expect(upcomingReminders([v('v_00000001', { remindMin: 0 })], 0)).toEqual([]);
  });

  it('R3 DONE or CANCELLED gives none', () => {
    expect(upcomingReminders([v('v_00000001', { status: 'DONE' }), v('v_00000002', { status: 'CANCELLED' })], 0)).toEqual([]);
  });

  it('R4 a fireAt at or before now gives none', () => {
    const fireAt = TEN - 60 * MIN;
    expect(upcomingReminders([v('v_00000001')], fireAt)).toEqual([]);
    expect(upcomingReminders([v('v_00000001')], fireAt + 1)).toEqual([]);
    expect(upcomingReminders([v('v_00000001')], fireAt - 1)).toHaveLength(1);
  });

  it('R5 70 viewings give the earliest 60', () => {
    const all = Array.from({ length: 70 }, (_, i) => v('v_' + (i + 1).toString(16).padStart(8, '0'), { startsAt: TEN + (70 - i) * MIN }));
    const got = upcomingReminders(all, 0);
    expect(got).toHaveLength(60);
    expect(got[0].viewing.id).toBe('v_00000046');
    expect(got.map((r) => r.fireAt)).toEqual([...got.map((r) => r.fireAt)].sort((a, b) => a - b));
    expect(upcomingReminders(all, 0, 5)).toHaveLength(5);
  });

  it('R6 ties break by id', () => {
    const got = upcomingReminders([v('v_0000000b'), v('v_0000000a'), v('v_0000000c', { startsAt: TEN - MIN })], 0);
    expect(got.map((r) => r.viewing.id)).toEqual(['v_0000000c', 'v_0000000a', 'v_0000000b']);
  });
});

describe('dueReminders', () => {
  it('takes the reminders in (after, now] only', () => {
    const fireAt = TEN - 60 * MIN;
    const list = [v('v_00000001')];
    expect(dueReminders(list, fireAt - 1, fireAt)).toHaveLength(1);
    expect(dueReminders(list, fireAt, fireAt + MIN)).toEqual([]);
    expect(dueReminders(list, fireAt - 2, fireAt - 1)).toEqual([]);
  });
});
