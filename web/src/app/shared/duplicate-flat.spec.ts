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

import { duplicateFlats, flatBedrooms, isSameFlat, type FlatFacts } from './duplicate-flat';

// The same vectors, in the same order, as Kotlin's `DuplicateFlatTest` (S4b-BL-85). House `a` is at 13.0, 80.0, placed
// on the map, 2 BHK on the third floor.
describe('duplicate flat', () => {
  const a: FlatFacts = { id: 'a', lat: 13.0, lon: 80.0, locationSource: 'MAP', bedrooms: 2, floor: 3 };
  const bedrooms = (n: number) => Array.from({ length: n }, () => ({ type: 'BEDROOM' }));
  const vectors: [FlatFacts, boolean][] = [
    [{ id: 'b', lat: 13.00009, lon: 80.0, locationSource: 'GPS', bedrooms: 2, floor: 3 }, true],
    [{ id: 'c', lat: 13.0, lon: 80.00025, bedrooms: 2, floor: 3 }, true],
    [{ id: 'd', lat: 13.0, lon: 80.00028, locationSource: 'MAP', bedrooms: 2, floor: 3 }, false],
    [{ id: 'e', lat: 13.00036, lon: 80.0, locationSource: 'MAP', bedrooms: 2, floor: 3 }, false],
    [{ id: 'f', lat: 13.0, lon: 80.0, locationSource: 'MAP', bedrooms: 2, floor: 4 }, false],
    [{ id: 'g', lat: 13.0, lon: 80.0, locationSource: 'MAP', bedrooms: 3, floor: 3 }, false],
    [{ id: 'h', lat: 13.0, lon: 80.0, locationSource: 'MAP', bedrooms: 2 }, false],
    [{ id: 'i', lat: 13.0, lon: 80.0, locationSource: 'MAP', floor: 3 }, false],
    [{ id: 'j', lat: 13.0, lon: 80.0, locationSource: 'APPROX', bedrooms: 2, floor: 3 }, false],
    [{ id: 'k', lat: 0, lon: 0, bedrooms: 2, floor: 3 }, false],
    [{ id: 'l', lat: 13.0, lon: 80.0, locationSource: 'MAP', rooms: [...bedrooms(2), { type: 'HALL' }], floor: 3 }, true],
    [{ id: 'm', lat: 13.0, lon: 80.0, locationSource: 'MAP', bedrooms: 2, rooms: bedrooms(1), floor: 3 }, false],
    [{ id: 'n', lat: 13.0, lon: 80.0, locationSource: 'MAP', bedrooms: 2, rooms: [{ type: 'KITCHEN' }], floor: 3 }, true],
    [{ id: 'a', lat: 13.0, lon: 80.0, locationSource: 'MAP', bedrooms: 2, floor: 3 }, false],
  ];
  for (const [other, same] of vectors) {
    it(`${other.id} is ${same ? '' : 'not '}the same flat, both ways round`, () => {
      expect(isSameFlat(a, other)).toBe(same);
      expect(isSameFlat(other, a)).toBe(same);
    });
  }
  it('lists the matches in the order given; the ground floor is a floor like the others', () => {
    expect(duplicateFlats(a, vectors.map(([o]) => o))).toEqual(['b', 'c', 'l', 'n']);
    const ground: FlatFacts = { id: 'g0', lat: 13.0, lon: 80.0, locationSource: 'GPS', bedrooms: 1, floor: 0 };
    expect(duplicateFlats(ground, [{ ...ground, id: 'g1' }, { ...ground, id: 'g2', floor: -1 }])).toEqual(['g1']);
  });
  it('takes the bedrooms from the rooms when there are any', () => {
    expect([flatBedrooms(2, null), flatBedrooms(1, bedrooms(3)), flatBedrooms(2, [{ type: 'KITCHEN' }]), flatBedrooms(null, [])]).toEqual([2, 3, 2, null]);
  });
});
