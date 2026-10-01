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

import type { HouseDto } from '../core/models';
import { costFilterMatches, activeCostFilters, NO_COST_FILTER, type CostFilter } from './cost-filter';

// The same houses and vectors, in the same order, as Kotlin's `CostFilterTest` (S4b-BL-84).
describe('costFilterMatches', () => {
  const house = (id: string, price: number | null, priceType: 'RENT' | 'SALE', areaSqft: number | null, cost: HouseDto['cost']) =>
    ({ id, price, priceType, areaSqft, cost }) as Pick<HouseDto, 'id' | 'price' | 'priceType' | 'areaSqft' | 'cost'>;
  const houses = [
    house('rent', 30_000, 'RENT', 1000, { deposit: 90_000, maintenance: 2_500 }),
    house('included', 25_000, 'RENT', 500, { depositMonths: 2, maintenance: 2_000, maintenanceIncluded: true, brokerage: 10_000 }),
    house('sale', 5_000_000, 'SALE', 1000, null),
    house('noArea', 20_000, 'RENT', null, null),
    house('noPrice', null, 'RENT', 800, null),
  ];
  const f = (part: Partial<CostFilter>): CostFilter => ({ ...NO_COST_FILTER, ...part });
  const vectors: [CostFilter, string][] = [
    [f({}), 'rent,included,sale,noArea,noPrice'],
    [f({ monthly: { min: 25_000 } }), 'rent,included'],
    [f({ monthly: { max: 25_000 } }), 'included,noArea'],
    [f({ monthly: { min: 25_000, max: 25_000 } }), 'included'],
    [f({ monthly: { min: 30_000, max: 25_000 } }), 'included'],
    [f({ moveIn: { max: 100_000 } }), 'included,noArea'],
    [f({ moveIn: { min: 0 } }), 'rent,included,noArea'],
    [f({ perSqFt: { min: 40 } }), 'included,sale'],
    [f({ perSqFt: { max: 30 } }), 'rent'],
    [f({ monthly: { max: 30_000 }, perSqFt: { max: 60 } }), 'included'],
  ];
  vectors.forEach(([filter, expected], i) => {
    it(`vector ${i}: ${JSON.stringify(filter)}`, () => {
      expect(houses.filter((h) => costFilterMatches(filter, h)).map((h) => h.id).join(',')).toBe(expected);
    });
  });
  it('counts the ranges set', () => {
    expect(vectors.map(([filter]) => activeCostFilters(filter))).toEqual([0, 1, 1, 1, 1, 1, 1, 1, 1, 2]);
  });
});
