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

import type { HouseCost, HouseDto } from '../core/models';

/**
 * What a house costs, worked out from its price, its carpet area and its cost fields (docs/11 5.21). The
 * TypeScript twin of Kotlin `CostSummary.of` in android/shared: the same rules, the same seven test vectors in the
 * same order (`house-cost.spec.ts`, `CostSummaryTest`), like `searchText`/`HouseSearch`. Each result is null when
 * it cannot be computed.
 */
export interface CostSummary {
  /** RENT only: the rent plus the maintenance unless the rent includes it. */
  monthlyCost: number | null;
  /** RENT only: the deposit, the brokerage and the first month's rent. */
  moveIn: number | null;
  /** The agreed price (else the asked price) per square foot of carpet area, one decimal; RENT and SALE alike. */
  perSqFt: number | null;
  /** The agreed price when there is one, else the asked price. Ranking keeps using the asked price. */
  effectivePrice: number | null;
}

/** The house values the cost rules read. */
export type CostInput = Pick<HouseDto, 'price' | 'priceType' | 'areaSqft' | 'cost'>;

/**
 * Works out the monthly cost, the cost to move in and the cost per square foot of a house. For a rent, the monthly cost
 * is the rent plus the maintenance unless the rent includes it, and the move-in cost is deposit, brokerage and one
 * month's rent; an amount in rupees wins over the same item given in months. Nothing is guessed: what cannot be
 * computed is null.
 */
export function costSummary(house: CostInput): CostSummary {
  const price = number(house.price);
  const cost: HouseCost = house.cost ?? {};
  const agreed = number(cost.agreedPrice);
  const effectivePrice = agreed ?? price;
  const area = number(house.areaSqft);
  const perSqFt = effectivePrice !== null && area !== null && area > 0 ? Math.round((effectivePrice / area) * 10) / 10 : null;
  if (price === null || house.priceType !== 'RENT') return { monthlyCost: null, moveIn: null, perSqFt, effectivePrice };
  const maintenance = cost.maintenanceIncluded === true ? 0 : (number(cost.maintenance) ?? 0);
  // Rupees win over months when both are given; both absent means nothing to pay.
  const depositRupees = number(cost.deposit) ?? (number(cost.depositMonths) ?? 0) * price;
  const brokerageRupees = number(cost.brokerage) ?? (number(cost.brokerageMonths) ?? 0) * price;
  return {
    monthlyCost: price + maintenance,
    moveIn: depositRupees + brokerageRupees + price,
    perSqFt,
    effectivePrice,
  };
}

/** The value if it is a finite number, else null. */
function number(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
