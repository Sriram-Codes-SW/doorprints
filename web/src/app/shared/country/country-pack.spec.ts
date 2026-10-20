import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import vectors from '../../../../../docs/schemas/country-pack-vectors.json';
import { askPrompt, extractionPrompt, price as readAmount, redactGeneric } from '../../core/ai/ai-core';
import { placeOf, searchParams } from '../../core/place-search';
import { keyTermsFor } from '../../core/voice/voice-input.service';
import { groupAmount, rupees } from '../../export/deterministic';
import { phoneKey } from '../broker';
import { parseListingText } from '../listing-text';
import { COUNTRY_VIEW, isCountryCenter } from '../map-center';
import { ZZ_PACK } from '../testing/zz-pack';
import { COUNTRY } from './country.token';
import { country, derived, promptMoney, useCountryPack } from './country-pack';
import type { CountryPack } from './country-pack';
import { INDIA_PACK } from './india-pack';

/**
 * TC-U-289 and TC-U-290 (docs/06): the country pack's characterization vectors (WP-C1) and the synthetic second pack (WP-C2).
 * The vectors are `docs/schemas/country-pack-vectors.json`, read by the Android core's `CountryPackVectorsTest` too. Section
 * `in` is what the code answered before the pack existed; section `zz` is the same call sites under a pack that shares
 * nothing with India's, which only works when every call site reads the pack.
 */

interface Section {
  group: [string, string][];
  money: { amount: number; text: string }[];
  amounts: { text: string; value: number | null }[];
  phoneKey: { phone: string; key: string | null }[];
  listing: { text: string; price: number | null; priceType: string | null; phone: string | null }[];
  redact: { text: string; out: string }[];
  geocoder: {
    countryCode: string;
    queryName: string;
    box: { south: number; west: number; north: number; east: number };
    defaultView: { lat: number; lon: number; zoom: number };
    params: { place: string; language: string; params: Record<string, string> | null }[];
  };
  prompt: { name: string; word: string; currencyName: string; areaUnit: string; houseKeyTerms: string; moneyLine: { amount: number; text: string } };
  locale: { fallback: string; speech: [string, string][] };
  xlsxFormat: { web: string };
}

const SECTIONS: [string, CountryPack, Section][] = [
  ['in', INDIA_PACK, vectors.in as unknown as Section],
  ['zz', ZZ_PACK, vectors.zz as unknown as Section],
];

let restore: (() => void) | null = null;
afterEach(() => {
  restore?.();
  restore = null;
});

describe.each(SECTIONS)('country pack %s', (id, pack, v) => {
  const tc = id === 'in' ? 'TC-U-289' : 'TC-U-290';
  const use = () => {
    restore = useCountryPack(pack);
  };

  it(`${tc}: groups digits, writes money and reads the amount words as the vectors say`, () => {
    use();
    expect(country().id).toBe(id);
    for (const [digits, grouped] of v.group) expect(pack.money.group(digits), digits).toBe(grouped);
    for (const m of v.money) expect(rupees(m.amount), String(m.amount)).toBe(m.text);
    expect(groupAmount(-1234567)).toBe(`-${pack.money.group('1234567')}`);
    for (const a of v.amounts) expect(readAmount(a.text, []), a.text).toBe(a.value);
    expect(pack.money.xlsxFormat).toBe(v.xlsxFormat.web);
  });

  it(`${tc}: compares phone numbers, reads them out of a listing and redacts them as the vectors say`, () => {
    use();
    for (const p of v.phoneKey) expect(phoneKey(p.phone), p.phone).toBe(p.key);
    for (const l of v.listing) {
      const draft = parseListingText(l.text);
      expect([draft.price, draft.priceType, draft.contactPhone], l.text).toEqual([l.price, l.priceType, l.phone]);
    }
    for (const r of v.redact) expect(redactGeneric(r.text), r.text).toBe(r.out);
  });

  it(`${tc}: asks the geocoder for the pack\'s country and keeps only the pack\'s own points`, () => {
    use();
    expect(pack.geo.countryCode).toBe(v.geocoder.countryCode);
    expect(pack.geo.queryName).toBe(v.geocoder.queryName);
    expect(pack.geo.box).toEqual(v.geocoder.box);
    for (const p of v.geocoder.params) expect(searchParams(p.place, p.language), p.place).toEqual(p.params);
    expect(COUNTRY_VIEW).toMatchObject(v.geocoder.defaultView);
    expect(isCountryCenter(v.geocoder.defaultView.lat, v.geocoder.defaultView.lon)).toBe(true);
  });

  it(`${tc}: names the currency, the area unit and the key terms in the AI words`, () => {
    use();
    expect(promptMoney(v.prompt.moneyLine.amount)).toBe(v.prompt.moneyLine.text);
    expect(keyTermsFor('house')).toBe(v.prompt.houseKeyTerms);
    expect(extractionPrompt('a listing', 'n1').system).toContain(`app in ${v.prompt.name}.`);
    expect(askPrompt('a question', [], 'n1').system).toContain(`(prices in ${v.prompt.word},`);
    expect(pack.money.name).toBe(v.prompt.currencyName);
    expect(pack.area.unit).toBe(v.prompt.areaUnit);
    expect(pack.locale.fallback).toBe(v.locale.fallback);
    expect(Object.entries(pack.locale.speech)).toEqual(v.locale.speech);
  });

  it(`${tc}: the single COUNTRY provider gives the active pack`, () => {
    use();
    expect(TestBed.inject(COUNTRY)).toBe(pack);
  });
});

describe('the pack is what the call sites read (WP-C2)', () => {
  it('TC-U-290: India answers differently from Zedland on the same input, and the swap is undone', () => {
    expect(rupees(1234567)).toBe('₹12,34,567');
    restore = useCountryPack(ZZ_PACK);
    expect(rupees(1234567)).toBe('$1,234,567');
    restore();
    restore = null;
    expect(rupees(1234567)).toBe('₹12,34,567');
  });

  it('TC-U-290: a found place outside the pack\'s border is refused, one inside it is kept', () => {
    const found = [{ lat: '3', lon: '4', display_name: 'Foo Town' }];
    // India's outline: a point near the equator in the Gulf of Guinea is not India; under Zedland's own test it is inside.
    expect(placeOf(found)).toBeNull();
    restore = useCountryPack(ZZ_PACK);
    expect(placeOf(found)).toEqual({ lat: 3, lon: 4, label: 'Foo Town' });
    expect(placeOf([{ lat: '8', lon: '4' }])).toBeNull();
  });

  it('TC-U-290: the derived patterns are built once per pack and from that pack only', () => {
    expect(derived(INDIA_PACK)).toBe(derived(INDIA_PACK));
    expect(derived(ZZ_PACK).listingPrice.test('Rs 25,000')).toBe(false);
    expect(derived(INDIA_PACK).listingPrice.test('Rs 25,000')).toBe(true);
    expect(derived(ZZ_PACK).listingPrice.test('Zd 25,000')).toBe(true);
  });
});
