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

import { floorSearchText, floorWords, parseFloor } from './house-floor';

// S4b-BL-87: the same words as Kotlin's `HouseSearch.floorText` and `ExportStrings.floor`, the same reading as `floorOf`.
describe('house floor', () => {
  it('words a floor: the ground floor, a basement level, else the number', () => {
    const t = (key: string, params?: Record<string, string | number>) => `${key}${params ? JSON.stringify(params) : ''}`;
    expect([0, -2, 7].map((f) => floorWords(t, f))).toEqual(['house.floorGround', 'house.floorBasement{"n":2}', '7']);
  });
  it('is found by the same English words as on Android', () => {
    expect([0, -2, 12].map(floorSearchText)).toEqual(['ground floor 0', 'basement 2', 'floor 12']);
  });
  it('reads -5..200 with an optional minus, else nothing', () => {
    expect(['0', ' 3 ', '-2', '200', '-5'].map(parseFloor)).toEqual([0, 3, -2, 200, -5]);
    for (const bad of ['', '-', '201', '-6', '1.5', '2-', '--1', '1000']) expect(parseFloor(bad)).toBeNull();
  });
});
