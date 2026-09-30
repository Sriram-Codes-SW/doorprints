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
import { HouseDto, PriceType, newHouse } from '../../core/models';
import { comparePrice, listQueryParams, listReturnParams, parseListQuery, searchText, brokerSearchText } from './map-list';

function house(label: string, price: number | null, priceType: PriceType | null): HouseDto {
  return { ...newHouse(12.9, 77.6), label, price, priceType };
}

describe('the house list in the URL', () => {
  const read = (params: Record<string, string>) => parseListQuery((name) => params[name] ?? null);

  it('reads search, status and sort back from the query parameters', () => {
    expect(read({ q: 'park', status: 'SHORTLISTED', sort: 'price' })).toEqual({
      q: 'park',
      status: 'SHORTLISTED',
      sort: 'price',
    });
  });

  it('falls back to the defaults for missing or unknown values', () => {
    expect(read({})).toEqual({ q: '', status: 'ALL', sort: 'recent' });
    expect(read({ status: 'shortlisted', sort: 'cheapest' })).toEqual({ q: '', status: 'ALL', sort: 'recent' });
  });

  it('gives the way back to the list only the values that differ from the defaults, with no empty ones', () => {
    expect(listReturnParams({ q: '', status: 'ALL', sort: 'recent' })).toEqual({});
    expect(listReturnParams({ q: ' park ', status: 'SHORTLISTED', sort: 'recent' })).toEqual({
      q: 'park',
      status: 'SHORTLISTED',
    });
    expect(listReturnParams({ q: '', status: 'ALL', sort: 'price' })).toEqual({ sort: 'price' });
  });

  it('leaves the defaults out of the URL, so a plain list keeps a plain address', () => {
    expect(listQueryParams({ q: '  ', status: 'ALL', sort: 'recent' })).toEqual({ q: null, status: null, sort: null });
    expect(listQueryParams({ q: ' 2BHK ', status: 'NEW', sort: 'score' })).toEqual({
      q: '2BHK',
      status: 'NEW',
      sort: 'score',
    });
  });
});

describe('"Lowest price"', () => {
  it('orders rents, then sales, then untyped prices, each cheapest first, and houses without a price last', () => {
    const list = [
      house('sale 75L', 7_500_000, 'SALE'),
      house('no price', null, 'RENT'),
      house('rent 25k', 25_000, 'RENT'),
      house('untyped', 40_000, null),
      house('sale 50L', 5_000_000, 'SALE'),
      house('rent 18k', 18_000, 'RENT'),
    ];
    expect([...list].sort(comparePrice).map((h) => h.label)).toEqual([
      'rent 18k',
      'rent 25k',
      'sale 50L',
      'sale 75L',
      'untyped',
      'no price',
    ]);
  });
});

/**
 * The house list's search rule, the same cases as Android's `HouseSearchTest` (`:shared` commonTest), kept in step by
 * hand: a case added here is added there (docs/06 TC-U-93). The page filters with `searchText(h).includes(q)`, q
 * lower-cased and trimmed (map-page.ts).
 */
describe('searchText', () => {
  const green: HouseDto = {
    ...newHouse(12.9, 77.6),
    label: 'Green View 2BHK',
    address: '12, 5th Cross',
    street: '5th Cross',
    locality: 'Indiranagar',
    notes: 'Water 24x7, near the metro',
    contactName: 'Ravi Kumar',
  };
  const lake: HouseDto = { ...newHouse(12.9, 77.6), label: 'Lake Road flat', locality: 'हिन्दी नगर' };
  // Slice 1b: the words of the broker a house is linked to (its name, agency and fee terms) are matched too.
  const greenBroker = brokerSearchText({ name: 'Ravi Kumar', agency: 'Adyar Homes', feeTerms: "15 days' rent, once" });
  const matching = (query: string): string[] => {
    const q = query.trim().toLowerCase();
    return [
      ['green', green, greenBroker],
      ['lake', lake, ''],
    ]
      .filter(([, h, words]) => !q || searchText(h as HouseDto, words as string).includes(q))
      .map(([name]) => name as string);
  };

  it('a blank query matches every house', () => expect(matching('  ')).toEqual(['green', 'lake']));
  it('the label matches ignoring case', () => expect(matching('green view')).toEqual(['green']));
  it('the address, street and locality match', () => {
    expect(matching('5th cross')).toEqual(['green']);
    expect(matching('INDIRANAGAR')).toEqual(['green']);
  });
  it('the notes match', () => expect(matching('metro')).toEqual(['green']));
  it("a query matches a room's note", () => {
    const withRooms: HouseDto = {
      ...green,
      rooms: [
        { id: 'r1', type: 'BEDROOM', name: 'Master', lengthCm: 300, widthCm: 300, condition: 4, notes: 'Damp wall', sort: 0 },
      ],
    };
    const q = 'damp'.trim().toLowerCase();
    expect(searchText(withRooms, greenBroker).toLowerCase().includes(q)).toBe(true);
    expect(searchText(withRooms, greenBroker).toLowerCase().includes('master')).toBe(true);
  });
  it("a query matches an answer's text and an answer", () => {
    const withAnswers: HouseDto = {
      ...green,
      answers: [
        { id: 'a1', text: 'Is the terrace open to tenants?', status: 'OPEN', sort: 0 },
        { id: 'a2', questionId: 'qd_water', text: 'Water supply hours?', answer: 'Borewell, twice a day', status: 'ANSWERED', sort: 1 },
      ],
    };
    const finds = (query: string) => searchText(withAnswers, greenBroker).includes(query);
    expect(finds('terrace open')).toBe(true);
    expect(finds('borewell')).toBe(true);
    expect(finds('helipad')).toBe(false);
    expect(searchText(green, greenBroker).includes('terrace')).toBe(false);
  });
  it('the contact name matches', () => expect(matching('ravi')).toEqual(['green']));
  it("a query matches the broker's agency", () => expect(matching('adyar homes')).toEqual(['green']));
  it("the broker's fee terms match", () => expect(matching('15 days')).toEqual(['green']));
  it('Indic text matches', () => expect(matching('हिन्दी')).toEqual(['lake']));
  it('a query found nowhere matches nothing', () => expect(matching('penthouse')).toEqual([]));
  it('the query is trimmed', () => expect(matching(' lake ')).toEqual(['lake']));
  it('empty values are left out', () => expect(searchText(lake)).toBe('lake road flat हिन्दी नगर'));
  it('a house with no broker words is searched as before', () => expect(searchText(lake, '')).toBe(searchText(lake)));
});
