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
import type { HouseStatus } from '../core/models';
import { HouseStatusRules, choose, closeTargets } from './house-status';

const h = (id: string, status: HouseStatus) => ({ id, status });
const statuses = (list: readonly { status: HouseStatus }[]) => list.map((x) => x.status);

/** The status rules of slice 5 (docs/11 5.24): the same vectors M1..M3 as Kotlin `HouseStatusRulesTest`. */
describe('HouseStatusRules', () => {
  it('m1_choosing_taken_returns_the_previous_taken_house_to_shortlisted', () => {
    const before = [h('a', 'TAKEN'), h('b', 'SHORTLISTED'), h('c', 'NEW')];
    const after = choose(before, 'b', 'TAKEN');
    expect(statuses(after)).toEqual(['SHORTLISTED', 'TAKEN', 'NEW']);
    // The input is not changed.
    expect(statuses(before)).toEqual(['TAKEN', 'SHORTLISTED', 'NEW']);
  });

  it('m1_choosing_another_status_only_changes_that_house', () => {
    expect(statuses(choose([h('a', 'TAKEN'), h('b', 'NEW')], 'b', 'REJECTED'))).toEqual(['TAKEN', 'REJECTED']);
    expect(statuses(choose([h('a', 'TAKEN'), h('b', 'NEW')], 'a', 'SHORTLISTED'))).toEqual(['SHORTLISTED', 'NEW']);
    expect(statuses(choose([h('a', 'NEW')], 'missing', 'TAKEN'))).toEqual(['NEW']);
  });

  it('m2_close_targets_leave_out_the_taken_rejected_and_not_chosen_houses', () => {
    const houses = [h('t', 'TAKEN'), h('n', 'NEW'), h('s', 'SHORTLISTED'), h('r', 'REJECTED'), h('x', 'NOT_CHOSEN')];
    expect(closeTargets(houses, 't')).toEqual(['n', 's']);
    expect(closeTargets([h('t', 'TAKEN')], 't')).toEqual([]);
  });

  it('m3_after_any_sequence_of_choices_at_most_one_house_is_taken', () => {
    const all: HouseStatus[] = ['NEW', 'SHORTLISTED', 'REJECTED', 'TAKEN', 'NOT_CHOSEN'];
    let houses = [h('a', 'NEW'), h('b', 'SHORTLISTED'), h('c', 'TAKEN'), h('d', 'NEW')];
    // A fixed pseudo-random walk over houses and statuses: the invariant holds after every step.
    let seed = 7;
    for (let step = 0; step < 300; step++) {
      seed = (seed * 1103515245 + 12345) % 2147483648;
      const id = houses[seed % houses.length].id;
      const status = all[Math.floor(seed / 7) % all.length];
      houses = choose(houses, id, status);
      expect(houses.filter((x) => x.status === 'TAKEN').length).toBeLessThanOrEqual(1);
    }
  });

  it('exposes the rules as one object, the twin of Kotlin HouseStatusRules', () => {
    expect(HouseStatusRules.choose).toBe(choose);
    expect(HouseStatusRules.closeTargets).toBe(closeTargets);
  });
});
