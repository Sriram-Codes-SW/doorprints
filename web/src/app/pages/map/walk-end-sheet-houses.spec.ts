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
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { NEAREST_HOUSE_M, NEAR_HOUSES_M, houseChoices } from './walk-end-sheet-houses';

const DEG = 1 / 111_194.9266;
const house = (id: string, northM: number, over: Partial<HouseDto> = {}): HouseDto => ({ ...newHouse(northM * DEG, 0, 'MAP'), id, label: id, ...over });
/** A walk 0..200 m north along lon 0. */
const walk = Array.from({ length: 11 }, (_, i) => ({ lat: i * 20 * DEG, lon: 0 }));

describe('which houses the picker offers (docs/11 5.27.6)', () => {
  it('uses 30 m for the nearest house and 150 m for the houses near any point of the walk', () => {
    expect(NEAREST_HOUSE_M).toBe(30);
    expect(NEAR_HOUSES_M).toBe(150);
  });

  it('preselects the house nearest to where the walk stopped when it is within 30 m', () => {
    const c = houseChoices(walk, [house('far', 100), house('end', 215)]);
    expect(c.nearest?.house.id).toBe('end');
    expect(Math.round(c.nearest!.distanceM)).toBe(15);
  });

  it('offers no nearest house when the closest to the stop is farther than 30 m', () => {
    const c = houseChoices(walk, [house('a', 240)]);
    expect(c.nearest).toBeNull();
    expect(c.near.map((x) => x.house.id)).toEqual(['a']);
  });

  it('never offers a house whose location is only approximate as the nearest (it is an area, not a door)', () => {
    const c = houseChoices(walk, [house('approx', 205, { locationSource: 'APPROX' }), house('exact', 220)]);
    expect(c.nearest?.house.id).toBe('exact');
  });

  it('lists the houses within 150 m of any point by their smallest distance, nearest first, without the nearest again', () => {
    const c = houseChoices(walk, [house('end', 205), house('mid', 100), house('side', 100, { lon: 100 * DEG }), house('away', 600)]);
    expect(c.nearest?.house.id).toBe('end');
    expect(c.near.map((x) => x.house.id)).toEqual(['mid', 'side']);
    expect(Math.round(c.near[1].distanceM)).toBe(100);
  });

  it('includes a house just inside 150 m from the walk and leaves out one just beyond it', () => {
    const edge = houseChoices(walk, [house('edge', 100, { lon: 149.9 * DEG }), house('out', 100, { lon: 150.1 * DEG })]);
    expect(edge.near.map((x) => x.house.id)).toEqual(['edge']);
  });

  it('measures the distance to the nearest point of the walk, not to its end', () => {
    const c = houseChoices(walk, [house('mid', 100, { lon: 40 * DEG })]);
    expect(Math.round(c.near[0].distanceM)).toBe(40);
  });

  it('has no choices for a walk with no points or for no houses', () => {
    expect(houseChoices([], [house('a', 0)])).toEqual({ nearest: null, near: [] });
    expect(houseChoices(walk, [])).toEqual({ nearest: null, near: [] });
  });

  it('skips a house with no valid location', () => {
    const c = houseChoices(walk, [house('bad', 0, { lat: NaN })]);
    expect(c.near).toEqual([]);
    expect(c.nearest).toBeNull();
  });
});
