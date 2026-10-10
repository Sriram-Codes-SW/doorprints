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
import { priceDisagreement } from './listing-price-check';

/** The same texts as the phones' `HouseFormRulesTest` (S4b-BL-238): the two stacks warn on the same pastes. */
describe('the regex-versus-model price check', () => {
  it('warns when the text says one rent and the model read another, with both figures', () => {
    expect(priceDisagreement('3BHK Satellite, Ahmedabad. Rent Rs 45,000, maintenance 2,500 extra.', { price: 2500 })).toEqual({ text: 45000, ai: 2500 });
    expect(priceDisagreement('2BHK Kothrud, rent ₹32,000, deposit 1 lakh.', { price: 100000 })).toEqual({ text: 32000, ai: 100000 });
  });

  it('is quiet when they agree, when either side has no price, or when the text is blank', () => {
    expect(priceDisagreement('2BHK Adyar, rent Rs 28,000, advance 10 months.', { price: 28000 })).toBeNull();
    expect(priceDisagreement('Ballygunge 2BHK for sale, price on request.', { price: 11000000 })).toBeNull();
    expect(priceDisagreement('2BHK Adyar, rent Rs 28,000.', { price: null })).toBeNull();
    expect(priceDisagreement('   ', { price: 28000 })).toBeNull();
  });
});
