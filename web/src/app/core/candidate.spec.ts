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
import houseKind from '../../../../docs/schemas/kinds/house.json';
import { HOUSE_EXTRAS, MAX_OTHER_EXTRAS, candidateOf, cleanOtherExtras, houseOf, moneyOf } from './candidate';
import type { CandidateDto } from './candidate';
import { newHouse } from './models';
import type { HouseDto } from './models';
import { tryHouseFromDto } from '../data/records';

/**
 * The candidate shape of the format reset (docs/03 ADR-36 §18.4 doors 3, 5 and 7; S4b-BL-206) against the house
 * kind file and the server's rules (`Money.of`, `CandidateExtras.of`): the expected candidates are written out from
 * the kind file by hand, not computed with the code under test. docs/06 TC-U-200.
 */

/** Chennai, a full rent: every value the house has today. */
const ADYAR: HouseDto = {
  id: '11111111-1111-4111-8111-111111111111', label: 'Green View 2BHK', address: '12, MG Road', street: 'MG Road', locality: 'Adyar',
  lat: 13.006, lon: 80.2574, status: 'TAKEN', price: 32000, priceType: 'RENT', bedrooms: 2, rating: 4, contactName: 'Ravi Kumar',
  contactPhone: '+91 98400 11111', listingUrl: 'https://example.com/listing/1', notes: 'Ask about water', areaSqft: 1150,
  locationSource: 'GPS',
  cost: { deposit: 64000, maintenance: 2500, maintenanceIncluded: false, brokerageMonths: 1, lockInMonths: 11, noticeMonths: 2, availableFrom: '2026-10-15', myOffer: 30000, agreedPrice: 31000 },
  rooms: null, answers: null, moveIn: null, floor: 3, brokerId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', checklist: { water: 5 },
  createdAt: '2026-09-01T10:00:00.000Z', updatedAt: '2026-09-02T10:00:00.000Z', deleted: false, syncVersion: 4,
};

/** The same house as a candidate, by the kind file: money a month, the deposit once, maintenance a month, offers as the rent. */
const ADYAR_CANDIDATE = {
  id: '11111111-1111-4111-8111-111111111111', kind: 'house', label: 'Green View 2BHK', address: '12, MG Road', street: 'MG Road', locality: 'Adyar',
  lat: 13.006, lon: 80.2574, status: 'TAKEN', money: { amount: 32000, cadence: 'month' }, rating: 4, contactName: 'Ravi Kumar',
  contactPhone: '+91 98400 11111', listingUrl: 'https://example.com/listing/1', notes: 'Ask about water', locationSource: 'GPS',
  extras: {
    bedrooms: 2, areaSqft: 1150, floor: 3, deposit: { amount: 64000, cadence: 'once' }, maintenance: { amount: 2500, cadence: 'month' },
    maintenanceIncluded: false, brokerageMonths: 1, lockInMonths: 11, noticeMonths: 2, availableFrom: '2026-10-15',
    myOffer: { amount: 30000, cadence: 'month' }, agreedPrice: { amount: 31000, cadence: 'month' },
  },
  brokerId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', checklist: { water: 5 },
  createdAt: '2026-09-01T10:00:00.000Z', updatedAt: '2026-09-02T10:00:00.000Z', deleted: false, syncVersion: 4,
};

describe('the candidate of a house (kinds step 3)', () => {
  it('moves price and priceType to money and the values and the cost to extras, keys in the format order', () => {
    const candidate = candidateOf(ADYAR);
    expect(candidate).toEqual(ADYAR_CANDIDATE);
    expect(JSON.stringify(candidate)).toBe(JSON.stringify(ADYAR_CANDIDATE));
  });

  it('reads the candidate back as the same house', () => {
    expect(tryHouseFromDto(houseOf(ADYAR_CANDIDATE as CandidateDto))).toEqual({ ...ADYAR, dirty: false });
  });

  it('a sale in Guwahati: money once, brokerage once, the agreed price as often as the money (once)', () => {
    const sale: HouseDto = { ...newHouse(26.1445, 91.7362), id: 'g1', price: 4500000, priceType: 'SALE', cost: { brokerage: 45000, agreedPrice: 4400000 } };
    const c = candidateOf(sale);
    expect(c.money).toEqual({ amount: 4500000, cadence: 'once' });
    expect(c.extras).toEqual({ brokerage: { amount: 45000, cadence: 'once' }, agreedPrice: { amount: 4400000, cadence: 'once' } });
  });

  it('keeps the cadence of a house marked as a rent before its rent is known, and writes no extras for none', () => {
    const blank = { ...newHouse(28.6139, 77.209), id: 'd1' };
    const c = candidateOf(blank);
    expect(c.money).toEqual({ cadence: 'month' });
    expect('extras' in c).toBe(false);
    expect(houseOf(c).priceType).toBe('RENT');
    expect(houseOf(c).price).toBeNull();
  });

  it('reads a price without a type as monthly, and writes no money when neither is known (the server\'s Money.of)', () => {
    expect(moneyOf(18000, null)).toEqual({ amount: 18000, cadence: 'month' });
    expect(moneyOf(null, null)).toBeNull();
    expect(moneyOf(null, 'SALE')).toEqual({ cadence: 'once' });
    // An offer follows the money's cadence, or a month when the house has none.
    expect(candidateOf({ ...ADYAR, price: null, priceType: null, cost: { myOffer: 9000 } }).extras).toEqual({ bedrooms: 2, areaSqft: 1150, floor: 3, myOffer: { amount: 9000, cadence: 'month' } });
  });

  it('reads a known extra of the wrong type as unknown (absent)', () => {
    const c = {
      ...ADYAR_CANDIDATE,
      money: { amount: 7, cadence: 'week' },
      extras: { bedrooms: 'two', maintenance: 2500, maintenanceIncluded: 'no', availableFrom: 20261015, myOffer: { cadence: 'month' } },
    } as unknown as CandidateDto;
    const h = houseOf(c);
    expect(h.price).toBe(7);
    expect(h.priceType).toBeNull(); // a week is not a house's cadence
    expect([h.bedrooms, h.cost]).toEqual([null, null]);
    expect(h.extrasOther).toBeUndefined();
  });

  it('keeps the amount of a money extra stored with another cadence, so the next write does not lose it', () => {
    const c = { ...ADYAR_CANDIDATE, extras: { deposit: { amount: 5000, cadence: 'month' }, brokerage: { amount: 900, cadence: 'year' }, maintenance: { amount: 2500, cadence: 'once' } } } as unknown as CandidateDto;
    const h = houseOf(c);
    expect(h.cost).toEqual({ deposit: 5000, maintenance: 2500, brokerage: 900 });
    // Written back with the field's own cadence: the house model has no place for another one.
    expect(candidateOf(h).extras).toEqual({ deposit: { amount: 5000, cadence: 'once' }, maintenance: { amount: 2500, cadence: 'month' }, brokerage: { amount: 900, cadence: 'once' } });
  });

  it('drops a money extra that is not a money object with an amount (a number, a text, no amount)', () => {
    const h = houseOf({ ...ADYAR_CANDIDATE, extras: { deposit: 5000, maintenance: '2500', brokerage: { cadence: 'once' }, agreedPrice: { amount: -1, cadence: 'month' } } } as unknown as CandidateDto);
    expect(h.cost).toBeNull();
  });

  it('keeps an extra a newer app wrote, after the known ones in id order, and only of an extras type', () => {
    const c = {
      ...ADYAR_CANDIDATE,
      extras: {
        ...ADYAR_CANDIDATE.extras,
        zPetsAllowed: true,
        fromNewerApp: 4,
        schoolFee: { amount: 90000, cadence: 'year' },
        gateCode: 'A-12',
        Bad: 1,
        nested: { a: 1 },
        badMoney: { amount: 1.5, cadence: 'year' },
        nothing: null,
      },
    } as unknown as CandidateDto;
    const h = houseOf(c);
    expect(h.extrasOther).toEqual({ fromNewerApp: 4, gateCode: 'A-12', schoolFee: { amount: 90000, cadence: 'year' }, zPetsAllowed: true });
    const back = candidateOf(tryHouseFromDto(h)!);
    expect(Object.keys(back.extras!)).toEqual([...Object.keys(ADYAR_CANDIDATE.extras), 'fromNewerApp', 'gateCode', 'schoolFee', 'zPetsAllowed']);
  });

  it('keeps at most 30 unknown extras, the first 30 by id', () => {
    const many = Object.fromEntries(Array.from({ length: 40 }, (_, i) => [`x${String(i).padStart(2, '0')}`, i]));
    const kept = Object.keys(cleanOtherExtras(many)!);
    expect(kept).toHaveLength(MAX_OTHER_EXTRAS);
    expect(kept.at(-1)).toBe('x29');
  });

  it('drops an unknown extra whose id is not a field id', () => {
    expect(cleanOtherExtras({ Bad: 1, 'from-newer': 2, '9lives': 3, ok: 4 })).toEqual({ ok: 4 });
  });

  it('drops an unknown text extra over 2,000 characters and keeps one of exactly 2,000', () => {
    expect(cleanOtherExtras({ long: 'a'.repeat(2001), ok: 'a'.repeat(2000) })).toEqual({ ok: 'a'.repeat(2000) });
  });

  it('never keeps a known id as an unknown extra', () => {
    expect(cleanOtherExtras({ bedrooms: 3, deposit: { amount: 1, cadence: 'once' } })).toBeNull();
  });

  it('keeps a kind other than house as it came, and writes house for a house', () => {
    const school = houseOf({ ...ADYAR_CANDIDATE, kind: 'school', money: { amount: 90000, cadence: 'year' } } as unknown as CandidateDto);
    expect(school.kind).toBe('school');
    expect(candidateOf(tryHouseFromDto(school)!).kind).toBe('school');
    expect(candidateOf(ADYAR).kind).toBe('house');
    expect(houseOf({ ...ADYAR_CANDIDATE, kind: 'Bad Kind' } as unknown as CandidateDto).kind).toBeUndefined();
  });

  it('has the house kind file\'s extras, in its order, with each money extra\'s cadence', () => {
    const extras = (houseKind as { extras: { id: string; type: string; cadences?: string[]; cadenceFrom?: string }[] }).extras;
    expect([...HOUSE_EXTRAS]).toEqual(extras.map((e) => e.id));
    // Every money extra set to 1 on a rent: the fixed ones carry their only cadence, the others the money's.
    const cost = Object.fromEntries(extras.filter((e) => e.type === 'money').map((e) => [e.id, 1]));
    const c = candidateOf({ ...ADYAR, cost });
    for (const e of extras.filter((x) => x.type === 'money')) {
      expect((c.extras![e.id] as { cadence: string }).cadence, e.id).toBe(e.cadenceFrom === 'money' ? 'month' : e.cadences![0]);
    }
  });
});
