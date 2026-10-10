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
import golden from '../../../../../docs/ai/evals/golden-set.json';
import variantsJson from '../../../../../docs/ai/evals/address-variants.json';
import { type GoldenLike, type VariantsFile, ANCHOR_RULES, addressRunHeader, applyAddressSet, canonicalJson, fingerprint, placeWords, selectAddressRun } from './address-variants';

/**
 * The address variants (S4b-BL-225) in the website's port: the same behaviour as the server eval's AddressVariantsTest, on the
 * same file, pinned by the same fingerprints. Needs no model and no key.
 */
const base = golden as unknown as GoldenLike & { cases: { id: string; type: string; region: string; input: Record<string, unknown>; expected: Record<string, unknown> }[] };
const variants = variantsJson as unknown as VariantsFile;
const SETS = ['known', 'known-alt', 'unknown-invented', 'landmark-pin', 'vernacular', 'vernacular-strict', 'messy', 'hostile'];

/** The cases a set leaves out, found by a separate calculation (a Python script reading the same file), not by this code. */
const NOT_APPLICABLE: Record<string, string[]> = {
  known: [],
  'known-alt': [],
  'unknown-invented': [],
  'landmark-pin': ['extract-04-injection', 'ask-03-why-rejected', 'ask-07-injection-reveal-prompt', 'plan-04-injection-notes'],
  vernacular: ['extract-01-whatsapp-rent', 'extract-04-injection', 'ask-03-why-rejected', 'ask-07-injection-reveal-prompt', 'plan-04-injection-notes'],
  'vernacular-strict': [
    'extract-01-whatsapp-rent', 'extract-04-injection', 'ask-03-why-rejected', 'ask-07-injection-reveal-prompt', 'plan-04-injection-notes',
    'extract-15-delhi-saket-sale-sq-yd', 'extract-19-pune-marathi-lakhs', 'extract-20-ahmedabad-vegetarian', 'extract-22-lucknow-lakh-rent',
    'extract-24-guwahati-katha-link', 'extract-25-chandigarh-marla-no-contact', 'extract-26-goa-studio', 'extract-27-dehradun-two-and-a-half',
    'extract-28-shimla-95-l', 'extract-29-kolkata-price-on-request', 'extract-32-hyderabad-villa-sale', 'extract-33-chennai-conflicting-rent',
    'ask-18-kolkata-lift', 'ask-19-pune-noisy', 'ask-21-mumbai-parking', 'ask-24-lucknow-property-tax-unknown', 'ask-30-hyderabad-pets',
    'plan-07-mumbai-afternoon', 'plan-08-pune-saturday', 'plan-09-chennai-and-guwahati', 'plan-10-dehradun-and-shimla',
  ],
  messy: [],
  hostile: [],
};

const MINI: GoldenLike = {
  fixtureHouses: [
    { id: 'aaaaaaaa-0000-4000-8000-000000000001', city: 'Pune', label: 'Kothrud 2BHK', address: 'Karve Road, Kothrud, Pune', street: 'Karve Road', locality: 'Kothrud', lat: 18.5, lon: 73.8, status: 'NEW', price: 30000, notes: 'Quiet lane.' },
    { id: 'aaaaaaaa-0000-4000-8000-000000000002', city: 'Pune', label: 'Baner Road 120 PG flat', address: 'Baner Road, Baner, Pune', street: 'Baner Road', locality: 'Baner', lat: 18.55, lon: 73.78, status: 'NEW', price: 40000, notes: '' },
  ],
  fixtureVisits: [],
  cases: [
    { id: 'ask-place', type: 'ask', input: { question: 'Is the Kothrud flat quiet?' }, expected: { mustContain: ['quiet'] } },
    { id: 'ask-capitals', type: 'ask', input: { question: 'Is the KOTHRUD flat quiet?' }, expected: {} },
    { id: 'ask-longer-word', type: 'ask', input: { question: 'Is the Kothrudian flat quiet?' }, expected: {} },
    { id: 'ask-no-place', type: 'ask', input: { question: 'Which flat is cheapest?' }, expected: {} },
    { id: 'ask-in-expected', type: 'ask', input: { question: 'Which flat is quiet?' }, expected: { mustContain: ['Kothrud'] } },
    { id: 'ask-in-note-only', type: 'ask', input: { question: 'Which flat is cheapest?' }, expected: { note: 'Kothrud is the answer.' } },
    { id: 'ask-rewritten', type: 'ask', input: { question: 'Is the Kothrud flat quiet?' }, expected: { mustContain: ['Kothrud'] } },
    { id: 'ask-other-house', type: 'ask', input: { question: 'Is the Baner flat big?' }, expected: {} },
    { id: 'ask-city', type: 'ask', input: { question: 'Which Pune flat is cheapest?' }, expected: {} },
    { id: 'ask-generic-word', type: 'ask', input: { question: 'Is there a road nearby?' }, expected: {} },
    { id: 'ask-number', type: 'ask', input: { question: 'Is house 120 big?' }, expected: {} },
    { id: 'ask-short-word', type: 'ask', input: { question: 'Is the PG big?' }, expected: {} },
    { id: 'extract-text', type: 'extract', input: { text: '2BHK in Kothrud, 30k' }, expected: { locality: 'Kothrud', price: 30000 } },
  ],
};
const ID1 = 'aaaaaaaa-0000-4000-8000-000000000001';
const ID2 = 'aaaaaaaa-0000-4000-8000-000000000002';

/** A file with the given sets, for the mini golden set. */
const mini = (sets: VariantsFile['sets']): VariantsFile => ({ version: 't', genericWords: ['road'], sets, fingerprints: {} });
const oneSet = (houses: VariantsFile['sets'][string]['houses'], cases: VariantsFile['sets'][string]['cases'] = {}): VariantsFile =>
  mini({ s: { description: 'd', anchorRule: 'none', houses, cases } });
const ran = (g: GoldenLike): string[] => g.cases.map((c) => c.id);

describe('the default and a set that changes nothing', () => {
  it('is the input object itself whether unset, blank or named', () => {
    for (const name of [undefined, null, '', '   ', 'default']) {
      const applied = applyAddressSet(base, variants, name);
      expect(applied.golden, String(name)).toBe(base);
      expect(applied).toMatchObject({ set: 'default', notApplicable: [] });
    }
  });

  it('known changes nothing and has the fingerprint of the default', () => {
    const applied = applyAddressSet(base, variants, 'known');
    expect(applied.golden).toBe(base);
    expect(fingerprint(applied.golden)).toBe(fingerprint(base));
    expect(fingerprint(applied.golden)).toBe(variants.fingerprints['known']);
  });

  it('refuses a set that is not in the file, naming the ones that are', () => {
    expect(() => applyAddressSet(base, variants, 'unknown-invented-typo')).toThrow(/unknown-invented-typo.*known-alt.*hostile/);
  });

  it('never changes the golden set it was given', () => {
    const before = canonicalJson(base);
    for (const name of SETS) applyAddressSet(base, variants, name);
    expect(canonicalJson(base)).toBe(before);
  });
});

describe('the whitelist', () => {
  it('changes only address, street, locality, label and notes of a house, in every set', () => {
    for (const name of SETS) {
      const applied = applyAddressSet(base, variants, name).golden;
      expect(applied.fixtureHouses.length, name).toBe(base.fixtureHouses.length);
      applied.fixtureHouses.forEach((now, i) => {
        const was = base.fixtureHouses[i];
        expect(Object.keys(now), `${name} house ${i}`).toEqual(Object.keys(was));
        for (const key of Object.keys(was)) {
          if (['address', 'street', 'locality', 'label', 'notes'].includes(key)) continue;
          expect(canonicalJson(now[key]), `${name} ${key} of house ${i}`).toBe(canonicalJson(was[key]));
        }
      });
      expect(canonicalJson(applied.fixtureVisits), `${name} visits`).toBe(canonicalJson(base.fixtureVisits));
    }
  });

  it('changes only the named parts of a case, and appends guards to the right list', () => {
    const changed = new Set<string>();
    for (const name of SETS) {
      const guards = variants.sets[name].guards ?? [];
      for (const c of applyAddressSet(base, variants, name).golden.cases) {
        const was = base.cases.find((x) => x.id === c.id)!;
        expect(Object.keys(c)).toEqual(Object.keys(was));
        for (const key of ['id', 'type', 'category', 'region']) expect((c as Record<string, unknown>)[key]).toEqual((was as Record<string, unknown>)[key]);
        expect(Object.keys(c.input)).toEqual(Object.keys(was.input));
        for (const key of Object.keys(was.input)) {
          if (key !== 'text' && key !== 'question') expect(c.input[key], `${name} ${c.id} input.${key}`).toEqual(was.input[key]);
        }
        const guardKey = c.type === 'ask' ? 'mustNotContain' : c.type === 'plan' ? 'summaryMustNotContain' : 'notesMustNotContain';
        for (const key of Object.keys(was.expected)) {
          if (key === 'mustContain' || key === 'locality' || (guards.length > 0 && key === guardKey)) continue;
          expect(canonicalJson(c.expected[key]), `${name} ${c.id} expected.${key}`).toBe(canonicalJson(was.expected[key]));
        }
        for (const key of Object.keys(c.expected)) {
          expect(key in was.expected || (key === guardKey && guards.length > 0), `${name} ${c.id} new key ${key}`).toBe(true);
        }
        for (const g of guards) expect(c.expected[guardKey] as string[], `${name} ${c.id} guard`).toContain(g);
        if (canonicalJson(c) !== canonicalJson(was)) changed.add(name);
      }
    }
    expect([...changed].sort()).toEqual(['hostile', 'known-alt', 'landmark-pin', 'unknown-invented']);
  });

  it.each(['id', 'city', 'region', 'lat', 'lon', 'status', 'price', 'priceType', 'bedrooms', 'rating', 'checklist', 'contactName', 'contactPhone', 'listingUrl', 'notes', 'updatedAt', 'deleted'])(
    'refuses a house row that names %s',
    (key) => {
      const v = oneSet({ [ID1]: { [key]: 'x' } as never });
      expect(() => applyAddressSet(MINI, v, 's')).toThrow(`'${key}'`);
    },
  );

  it('refuses a house row with a value that is not a string, and a house that is not in the golden set', () => {
    expect(() => applyAddressSet(MINI, oneSet({ [ID1]: { address: 12 } as never }), 's')).toThrow(/string/);
    expect(() => applyAddressSet(MINI, oneSet({ 'aaaaaaaa-0000-4000-8000-0000000000ff': { address: 'x' } }), 's')).toThrow(/not a fixture house/);
  });

  it.each([
    [{ id: 'x' }, "may not change 'id'"],
    [{ type: 'ask' }, "may not change 'type'"],
    [{ input: { filters: {} } }, 'input.filters'],
    [{ input: { startLat: 1 } }, 'input.startLat'],
    [{ input: { text: 'x' } }, 'may change only input.question of the ask case'],
    [{ expected: { price: 1 } }, 'expected.price'],
    [{ expected: { expectedHouseIds: [] } }, 'expected.expectedHouseIds'],
    [{ expected: { answerEquals: 'x' } }, 'expected.answerEquals'],
    [{ expectedDelete: ['mustContain'] }, 'only expected.locality'],
  ])('refuses a case row %j', (row, message) => {
    expect(() => applyAddressSet(MINI, oneSet({}, { 'ask-place': row as never }), 's')).toThrow(message);
  });

  it('refuses a question on an extraction case, and a case that is not in the golden set', () => {
    expect(() => applyAddressSet(MINI, oneSet({}, { 'extract-text': { input: { question: 'x' } } }), 's')).toThrow('only input.text of the extract case');
    expect(() => applyAddressSet(MINI, oneSet({}, { 'no-such-case': { input: { question: 'x' } } }), 's')).toThrow('not in the golden set');
  });

  it('writes notes as the original, a space and the append, and the append alone when there are none', () => {
    const v = oneSet({ [ID1]: { notesAppend: 'City: Pune.' }, [ID2]: { notesAppend: 'City: Pune.' } });
    const houses = applyAddressSet(MINI, v, 's').golden.fixtureHouses;
    expect(houses[0]['notes']).toBe('Quiet lane. City: Pune.');
    expect(houses[1]['notes']).toBe('City: Pune.');
    expect(MINI.fixtureHouses[0]['notes']).toBe('Quiet lane.');
    const mumbai = applyAddressSet(base, variants, 'vernacular').golden.fixtureHouses[7];
    expect(String(mumbai['notes'])).toBe(`${String(base.fixtureHouses[7]['notes'])} City: Mumbai.`);
  });
});

describe('the variant-safe rule', () => {
  it('runs a case that is rewritten or holds no changed place word, and lists the rest', () => {
    const v = oneSet(
      { [ID1]: { address: 'Plot 9, Lakeside Colony, Pune', locality: 'Lakeside', label: 'Lakeside 2BHK' } },
      { 'ask-rewritten': { input: { question: 'Is the Lakeside flat quiet?' }, expected: { mustContain: ['Lakeside'] } } },
    );
    const applied = applyAddressSet(MINI, v, 's');
    expect(applied.notApplicable).toEqual(['ask-place', 'ask-capitals', 'ask-in-expected', 'extract-text']);
    expect(ran(applied.golden)).toEqual(['ask-longer-word', 'ask-no-place', 'ask-in-note-only', 'ask-rewritten', 'ask-other-house', 'ask-city', 'ask-generic-word', 'ask-number', 'ask-short-word']);
    const rewritten = applied.golden.cases.find((c) => c.id === 'ask-rewritten')!;
    expect(rewritten.input['question']).toBe('Is the Lakeside flat quiet?');
    expect(rewritten.expected['mustContain']).toEqual(['Lakeside']);
  });

  it('does not count a word the new text still holds, a generic word, or one without a letter', () => {
    const v = oneSet({ [ID2]: { address: 'Pashan Link, Pune', street: 'Pashan Link', locality: 'Pashan', label: 'Pashan flat' } });
    const applied = applyAddressSet(MINI, v, 's');
    expect(applied.notApplicable).toEqual(['ask-other-house']);
    for (const id of ['ask-generic-word', 'ask-number', 'ask-short-word', 'ask-city', 'ask-place']) expect(ran(applied.golden)).toContain(id);
  });

  it('counts the city only when nothing of the house names it any more', () => {
    expect(applyAddressSet(MINI, oneSet({ [ID1]: { address: 'behind the red temple' } }), 's').notApplicable).toEqual(['ask-city']);
    expect(applyAddressSet(MINI, oneSet({ [ID1]: { address: 'behind the red temple', notesAppend: 'City: Pune.' } }), 's').notApplicable).toEqual([]);
  });

  it('leaves out, in each real set, the cases an independent calculation found', () => {
    for (const name of SETS) {
      const applied = applyAddressSet(base, variants, name);
      expect(applied.notApplicable, `not applicable under ${name}`).toEqual(NOT_APPLICABLE[name]);
      expect(applied.golden.cases.length, `cases run under ${name}`).toBe(base.cases.length - NOT_APPLICABLE[name].length);
      expect(ran(applied.golden)).toEqual(base.cases.map((c) => c.id).filter((id) => !NOT_APPLICABLE[name].includes(id)));
    }
  });

  it('reads words as lower-cased runs of letters and digits', () => {
    expect(placeWords('HSR Layout, 2nd-Stage; Kothrud/Pune')).toEqual(['hsr', 'layout', '2nd', 'stage', 'kothrud', 'pune']);
    expect(placeWords(null)).toEqual([]);
  });
});

describe('every applied set is still a golden set', () => {
  const ZONES = ['north', 'south', 'east', 'west', 'north-east', 'hills', 'coast'];
  // Every key a case may use, by type (as ai-eval.spec.ts): a key the port does not read would be skipped without a word.
  const KEYS: Record<string, string[]> = {
    extract: ['price', 'priceType', 'bedrooms', 'locality', 'contactName', 'contactPhone', 'contactPhoneDigits', 'listingUrl', 'listingUrlNot', 'amenitiesInclude', 'notesMention', 'notesMustNotContain', 'draftMustNotContain', 'note'],
    ask: ['expectedHouseIds', 'allowedCitations', 'mustContain', 'mustNotContain', 'mustNotCite', 'grounded', 'answerEquals', 'citations', 'note'],
    plan: ['stopsSubsetOf', 'stopsMustNotInclude', 'stopsMustInclude', 'minStops', 'maxStops', 'fallback', 'stops', 'summaryMustNotContain', 'note'],
  };

  it.each(SETS)('%s: keys the port checks, a region on every case, ids once, listings within the cap, links as the sanitiser keeps them', (name) => {
    const applied = applyAddressSet(base, variants, name).golden as typeof base;
    expect(new Set(applied.cases.map((c) => c.id)).size).toBe(applied.cases.length);
    for (const c of applied.cases) {
      expect(Object.keys(c.expected).filter((k) => !KEYS[c.type].includes(k)), c.id).toEqual([]);
      expect(ZONES.includes(c.region) || c.region === 'cross-region', c.id).toBe(true);
      if (c.type !== 'extract') continue;
      const text = String(c.input['text']);
      expect([...text].length, c.id).toBeLessThanOrEqual(8000);
      const not = c.expected['listingUrlNot'];
      if (typeof not === 'string' && text.includes(not)) {
        const want = c.expected['listingUrl'];
        expect(typeof want === 'string' && text.includes(want), `${c.id}: a pasted link is "not expected" with no other pasted link expected`).toBe(true);
      }
      // The expected values are readable from the text; a null one is really absent (no link).
      const locality = c.expected['locality'];
      if (typeof locality === 'string' && c.region !== 'south') expect(text.toLowerCase(), `${c.id} locality`).toContain(locality.toLowerCase());
      if (c.region !== 'south' && c.expected['listingUrl'] === null) expect(text.toLowerCase(), `${c.id} link`).not.toContain('http');
    }
  });

  it.each(SETS)('%s: keeps its city anchor, the house limits and the ids in order', (name) => {
    const set = variants.sets[name];
    expect(ANCHOR_RULES).toContain(set.anchorRule);
    const applied = applyAddressSet(base, variants, name).golden;
    expect(applied.fixtureHouses.map((h) => h['id'])).toEqual(base.fixtureHouses.map((h) => h['id']));
    for (const h of applied.fixtureHouses) {
      const city = String(h['city']);
      if (city !== 'Bengaluru' && set.anchorRule !== 'none') {
        const field = set.anchorRule === 'city-word-in-notes' ? 'notes' : 'address';
        expect(String(h[field]), `${field} of ${String(h['label'])}`).toContain(city);
      }
      // HouseDto: label, street, locality at most 200 characters, address 500, notes 20,000.
      expect(String(h['label']).length).toBeLessThanOrEqual(200);
      expect(String(h['street']).length).toBeLessThanOrEqual(200);
      expect(String(h['locality']).length).toBeLessThanOrEqual(200);
      expect(String(h['address']).length).toBeLessThanOrEqual(500);
      expect(String(h['notes']).length).toBeLessThanOrEqual(20000);
    }
  });

  it('messy has an empty address and a 400-character one; hostile ends every address with its sentence and guards every case', () => {
    const messy = applyAddressSet(base, variants, 'messy').golden.fixtureHouses.map((h) => String(h['address']));
    expect(messy[3]).toBe('');
    expect(messy[4].length).toBe(400);
    expect(messy.filter((a) => a === '')).toHaveLength(1);
    const hostile = applyAddressSet(base, variants, 'hostile').golden;
    expect(hostile.cases).toHaveLength(75);
    const guards = variants.sets['hostile'].guards!;
    expect(guards.length).toBeGreaterThan(0);
    for (const c of hostile.cases) {
      const key = c.type === 'ask' ? 'mustNotContain' : c.type === 'plan' ? 'summaryMustNotContain' : 'notesMustNotContain';
      const list = c.expected[key] as string[];
      expect(list, c.id).toEqual(expect.arrayContaining(guards));
      expect(new Set(list).size, `${c.id} guards once`).toBe(list.length);
    }
    for (const h of hostile.fixtureHouses) expect(String(h['address'])).toContain('. ');
  });
});

describe('choosing a set for a run (S4b-BL-226)', () => {
  it('is nothing at all for the default: unset, blank or named', () => {
    for (const name of [undefined, null, '', '  ', 'default']) expect(selectAddressRun(base, variants, name), String(name)).toBeNull();
  });

  it('carries the applied golden set, what was left out and the header line, with no error', () => {
    const run = selectAddressRun(base, variants, 'landmark-pin')!;
    expect(run.set).toBe('landmark-pin');
    expect(run.error).toBeNull();
    expect(run.golden.cases).toHaveLength(71);
    expect(run.notApplicable).toEqual(NOT_APPLICABLE['landmark-pin']);
    expect(run.total).toBe(75);
    // The first twelve characters of the fingerprint the file records, found by a separate calculation.
    expect(addressRunHeader(run)).toBe('landmark-pin (address-variants v0.1, fingerprint 345ca24bcf32)');
    expect(base.cases).toHaveLength(75);
  });

  it('is a run of its own for known, with nothing left out', () => {
    const run = selectAddressRun(base, variants, 'known')!;
    expect(run.notApplicable).toEqual([]);
    expect(run.golden.cases).toHaveLength(75);
    expect(run.error).toBeNull();
  });

  it('names both fingerprints when the applied set is not the one the file records', () => {
    const tampered = { ...variants, fingerprints: { ...variants.fingerprints, 'unknown-invented': '0'.repeat(64) } };
    const run = selectAddressRun(base, tampered, 'unknown-invented')!;
    expect(run.error).toContain('unknown-invented');
    expect(run.error).toContain('361631beb5ed');
    expect(run.error).toContain('0'.repeat(64));
  });

  it('refuses a set that is not in the file', () => {
    expect(() => selectAddressRun(base, variants, 'nope')).toThrow(/nope/);
  });
});

describe('fingerprints and the file', () => {
  it('computes, for every set, the fingerprint the file records (the server eval test computes the same)', () => {
    expect(Object.keys(variants.fingerprints)).toEqual(SETS);
    const seen = new Set<string>();
    for (const name of SETS) {
      const fp = fingerprint(applyAddressSet(base, variants, name).golden);
      expect(fp, name).toMatch(/^[0-9a-f]{64}$/);
      expect(fp, name).toBe(variants.fingerprints[name]);
      expect(seen.has(fp), `${name} differs from every other set`).toBe(false);
      seen.add(fp);
    }
  });

  it('writes canonical JSON with sorted keys, no white space, whole numbers without a fraction and JSON.stringify strings', () => {
    const value = { b: [1, 2.5, 3.0, 'x'], a: { z: true, y: 'q"\\\n\t\u0001éह' }, n: null, big: 12500000, lat: 12.9731 };
    expect(canonicalJson(value)).toBe('{"a":{"y":"q\\"\\\\\\n\\t\\u0001éह","z":true},"b":[1,2.5,3,"x"],"big":12500000,"lat":12.9731,"n":null}');
  });

  it('is a well-formed file whose sets cover every fixture house, name real cases and add no real phone number', () => {
    expect(variants.version).toBe('0.1');
    const changes = (variantsJson as unknown as { changes: { version: string }[] }).changes;
    expect(changes[changes.length - 1].version).toBe(variants.version);
    expect(Object.keys(variants.sets)).toEqual(SETS);
    expect(variants.genericWords.length).toBeGreaterThan(0);
    for (const w of variants.genericWords) expect(w).toBe(w.toLowerCase());
    const houseIds = base.fixtureHouses.map((h) => String(h['id']));
    const caseIds = base.cases.map((c) => c.id);
    for (const name of SETS) {
      const set = variants.sets[name];
      expect(set.description.trim(), name).not.toBe('');
      if (name === 'known') expect(Object.keys(set.houses)).toEqual([]);
      else expect(Object.keys(set.houses).map((id) => id.toLowerCase()), `${name} covers every fixture house in order`).toEqual(houseIds);
      for (const id of Object.keys(set.cases)) expect(caseIds, `${name} rewrites a real case`).toContain(id);
    }
    // Any ten-digit number the file adds looks made up (a tail of 12345, 00000 or 55555); the golden set's own are its to vouch for.
    const raw = JSON.stringify(variantsJson).replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g, '');
    const text = raw.slice(0, raw.indexOf('"fingerprints"'));
    const goldenText = JSON.stringify(golden);
    for (const m of text.matchAll(/(?:\+91[ -]?)?[6-9]\d{4}[ -]?\d{5}/g)) {
      if (goldenText.includes(m[0])) continue;
      expect(m[0].replace('+91', '').trim(), m[0]).toMatch(/^[6-9]\d{4}[ -]?(?:12345|00000|55555)$/);
    }
  });
});
