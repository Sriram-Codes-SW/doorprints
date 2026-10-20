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

// The list's filter by a number or an amount (docs/03 §18.28): the range rows of the synthetic kind `zz_all` held to the `ranges`
// part of docs/schemas/kinds/kind-filter-vectors.json (the phones' `KindFilterVectorsTest` reads the same part), the address they write
// and read, and the two fields a row draws. The chip rows of the enums and switches are extra-filter.spec.ts's, unchanged.
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/kinds/kind-filter-vectors.json';
import { ExtraFilters } from '../pages/map/extra-filters';
import { listQueryParams, DEFAULT_LIST_QUERY, parseListQuery } from '../pages/map/map-list';
import { TranslationService } from '../i18n/translation.service';
import { audit } from './testing/a11y';
import { forKind } from './testing/kind-flows';
import { extraFilterMatches, extraFiltersOn, filterChoices, filterableExtras, parseRange, rangeText, readExtraFilter, type ExtraFilter } from './extra-filter';

interface Ranges {
  kind: string;
  rows: { id: string; type: string; choices: string[] }[];
  candidates: Record<string, Record<string, unknown>>;
  matches: { filter: ExtraFilter; keeps: string[] }[];
  parse: { text: string; min: number | null; max: number | null; valid?: boolean }[];
  ends: { min: string; max: string; text: string | null }[];
  address: { query: string; zz_all: ExtraFilter }[];
}
const R = (vectorsJson as unknown as { ranges: Ranges }).ranges;
const getter = (query: string) => {
  const p = new URLSearchParams(query);
  return (name: string) => p.get(name);
};

describe('the filter by a number or an amount (docs/03 §18.28)', () => {
  let flow: ReturnType<typeof forKind>;
  beforeEach(() => void (flow = forKind('zz_all')));
  afterEach(() => flow.release());
  const fields = () => flow.kind().fields;
  const keeps = (filter: ExtraFilter) => Object.keys(R.candidates).filter((id) => extraFilterMatches(filter, fields(), { extrasOther: R.candidates[id] as never }));

  it('offers a row for each extra the kind file names, a range for a number and an amount', () => {
    expect(R.kind).toBe('zz_all');
    expect(filterableExtras(fields()).map((f) => ({ id: f.id, type: f.type, choices: filterChoices(f) }))).toEqual(R.rows);
  });

  for (const [i, c] of R.matches.entries()) {
    it(`keeps what the vectors keep: case ${i + 1}, ${JSON.stringify(c.filter)}`, () => {
      expect(keeps(c.filter)).toEqual(c.keeps);
    });
  }

  for (const p of R.parse) {
    it(`reads the range text ${JSON.stringify(p.text)} as the vectors do`, () => {
      expect(parseRange(p.text)).toEqual(p.valid === false ? null : { min: p.min, max: p.max });
    });
  }

  for (const e of R.ends) {
    it(`makes ${JSON.stringify(e.text)} of the ends ${JSON.stringify([e.min, e.max])}`, () => {
      expect(rangeText(e.min, e.max)).toBe(e.text);
    });
  }

  for (const [i, a] of R.address.entries()) {
    it(`reads the address: case ${i + 1}, ${a.query || 'empty'}`, () => {
      expect(readExtraFilter(getter(a.query), fields())).toEqual(a.zz_all);
      expect(parseListQuery(getter(a.query), fields()).extras).toEqual(a.zz_all);
    });
  }

  it('writes a range as x.<id> and counts it as one row', () => {
    expect(listQueryParams({ ...DEFAULT_LIST_QUERY, extras: { seats: '10:40', fees: null } })).toMatchObject({ 'x.seats': '10:40', 'x.fees': null });
    expect(extraFiltersOn({ seats: '10:40', fees: null, board: 'CBSE' })).toBe(2);
  });

  it('compares an amount as stored, whatever its cadence, and a plain number is no amount', () => {
    const f = fields();
    expect(extraFilterMatches({ fees: '1000:' }, f, { extrasOther: { fees: { amount: 5000, cadence: 'month' } } as never })).toBe(true);
    expect(extraFilterMatches({ fees: '1000:' }, f, { extrasOther: { fees: 5000 } as never })).toBe(false);
    expect(extraFilterMatches({ seats: '1:' }, f, { extrasOther: { seats: { amount: 5, cadence: 'year' } } as never })).toBe(false);
  });
});

describe('the two fields of a range row (docs/03 §18.28)', () => {
  let flow: ReturnType<typeof forKind>;
  beforeEach(() => {
    TestBed.resetTestingModule();
    flow = forKind('zz_all');
  });
  afterEach(() => flow.release());

  async function draw(value: ExtraFilter, lang: 'en' | 'hi' | 'ta' | 'te' = 'en') {
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(ExtraFilters);
    fixture.componentRef.setInput('fields', flow.kind().fields);
    fixture.componentRef.setInput('value', value);
    const emitted: ExtraFilter[] = [];
    fixture.componentInstance.changed.subscribe((e) => emitted.push(e));
    fixture.detectChanges();
    await fixture.whenStable();
    return { host: fixture.nativeElement as HTMLElement, emitted, fixture };
  }
  const field = (host: HTMLElement, id: string) => host.querySelector<HTMLInputElement>(`#${id}`)!;
  const type = (el: HTMLInputElement, text: string) => {
    el.value = text;
    el.dispatchEvent(new Event('change'));
  };

  it('draws a group for each range, two labelled number fields with the set ends, and chips for the choices', async () => {
    const { host } = await draw({ seats: '10:40', board: 'CBSE' });
    const groups = [...host.querySelectorAll('[role="group"]')].map((g) => g.getAttribute('aria-label'));
    expect(groups).toHaveLength(4);
    const seats = host.querySelectorAll('[role="group"]')[2];
    expect([...seats.querySelectorAll('label')].map((l) => l.textContent!.trim())).toEqual([`${groups[2]}: at least`, `${groups[2]}: at most`]);
    expect([...seats.querySelectorAll('input')].map((i) => [i.type, i.value])).toEqual([['number', '10'], ['number', '40']]);
    expect(host.querySelectorAll('button.chip').length).toBeGreaterThan(0);
    expect(audit(host)).toEqual([]);
  });

  it('names the fields in the four languages', async () => {
    const words = { en: ['at least', 'at most'], hi: ['कम से कम', 'अधिकतम'], ta: ['குறைந்தபட்சம்', 'அதிகபட்சம்'], te: ['కనీసం', 'గరిష్ఠం'] } as const;
    for (const lang of ['en', 'hi', 'ta', 'te'] as const) {
      const { host } = await draw({}, lang);
      const labels = [...host.querySelectorAll('.range')[0].querySelectorAll('label')].map((l) => l.textContent!.trim());
      expect(labels[0].endsWith(words[lang][0]) && labels[1].endsWith(words[lang][1]), `${lang}: ${labels}`).toBe(true);
      TestBed.resetTestingModule();
    }
  });

  it('a change emits the whole map: the typed range, the ends swapped when the minimum is above the maximum, blank as Any', async () => {
    const { host, emitted } = await draw({ board: 'CBSE' });
    type(field(host, 'xf-min-seats'), '5');
    type(field(host, 'xf-max-seats'), '2');
    type(field(host, 'xf-min-fees'), '1000');
    expect(emitted).toEqual([
      { board: 'CBSE', includesMeals: null, seats: '5:', fees: null },
      { board: 'CBSE', includesMeals: null, seats: '2:5', fees: null },
      { board: 'CBSE', includesMeals: null, seats: null, fees: '1000:' },
    ]);
  });

  it('both fields blank is a row left on Any', async () => {
    const { host, emitted } = await draw({ seats: '3:' });
    type(field(host, 'xf-min-seats'), '');
    expect(emitted).toEqual([{ board: null, includesMeals: null, seats: null, fees: null }]);
  });
});
