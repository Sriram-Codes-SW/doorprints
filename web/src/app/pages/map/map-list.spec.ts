import { describe, expect, it } from 'vitest';
import { HouseDto, PriceType, newHouse } from '../../core/models';
import { comparePrice, listQueryParams, listReturnParams, parseListQuery } from './map-list';

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
