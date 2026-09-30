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
import { STATUS_COLOR, newHouse } from '../../core/models';
import { HOUSE_PAINT, houseFeatures } from './house-markers';

/** The hollow marker of an approximate location (FR-068): the feature says so, and the paint reads it. */
describe('house markers', () => {
  it('marks a feature approx only for an APPROX house', () => {
    const precise = { ...newHouse(13, 80, 'MAP'), id: 'a' };
    const rough = { ...newHouse(13.1, 80.1, 'APPROX'), id: 'b' };
    const old = { ...newHouse(13.2, 80.2), id: 'c' };
    expect(houseFeatures([precise, rough, old]).map((f) => f.properties.approx)).toEqual([false, true, false]);
    expect(houseFeatures([rough])[0].geometry.coordinates).toEqual([80.1, 13.1]);
  });

  it('draws an approx house with no fill and its status colour as the stroke', () => {
    const approx = ['==', ['get', 'approx'], true];
    expect(HOUSE_PAINT['circle-opacity']).toEqual(['case', approx, 0, ['match', ['get', 'status'], 'REJECTED', 0.75, 1]]);
    expect(HOUSE_PAINT['circle-stroke-color']).toEqual(['case', approx, HOUSE_PAINT['circle-color'], '#ffffff']);
    expect(HOUSE_PAINT['circle-color']).toContain(STATUS_COLOR.SHORTLISTED);
  });
});
