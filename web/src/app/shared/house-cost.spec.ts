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
import { costSummary } from './house-cost';

/**
 * The seven vectors of the slice 1a contract, in this order; Kotlin `CostSummaryTest` in android/shared keeps the
 * same list, so a phone and a browser work out the same numbers for the same house.
 */
describe('costSummary', () => {
  it('1. rent with a deposit, maintenance, one month brokerage and an agreed price', () => {
    const s = costSummary({
      price: 32000,
      priceType: 'RENT',
      areaSqft: 1150,
      cost: { deposit: 64000, maintenance: 2500, maintenanceIncluded: false, brokerageMonths: 1, agreedPrice: 31000 },
    });
    expect(s).toEqual({ monthlyCost: 34500, moveIn: 128000, perSqFt: 27.0, effectivePrice: 31000 });
  });

  it('2. rent with no cost and no area', () => {
    expect(costSummary({ price: 32000, priceType: 'RENT', areaSqft: null, cost: null })).toEqual({
      monthlyCost: 32000,
      moveIn: 32000,
      perSqFt: null,
      effectivePrice: 32000,
    });
  });

  it('3. maintenance included in the rent, deposit in months', () => {
    const s = costSummary({
      price: 32000,
      priceType: 'RENT',
      cost: { maintenance: 2500, maintenanceIncluded: true, depositMonths: 2 },
    });
    expect(s.monthlyCost).toBe(32000);
    expect(s.moveIn).toBe(96000);
  });

  it('4. a sale: no monthly cost, no move-in, but a price per sq ft from the agreed price', () => {
    const s = costSummary({ price: 1250000, priceType: 'SALE', areaSqft: 1450, cost: { brokerage: 25000, agreedPrice: 1200000 } });
    expect(s).toEqual({ monthlyCost: null, moveIn: null, perSqFt: 827.6, effectivePrice: 1200000 });
  });

  it('5. no price: nothing computes', () => {
    expect(costSummary({ price: null, priceType: 'RENT', cost: { deposit: 64000 } })).toEqual({
      monthlyCost: null,
      moveIn: null,
      perSqFt: null,
      effectivePrice: null,
    });
  });

  it('6. area 0 never divides', () => {
    expect(costSummary({ price: 32000, priceType: 'RENT', areaSqft: 0 }).perSqFt).toBeNull();
  });

  it('7. rupees win over months for the deposit', () => {
    expect(costSummary({ price: 32000, priceType: 'RENT', cost: { deposit: 64000, depositMonths: 3 } }).moveIn).toBe(96000);
  });
});
