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

// The list's filter by a kind's typed extra (S4b-BL-265): the rows a kind offers and which candidates a set of rows keeps,
// held to docs/schemas/kinds/kind-filter-vectors.json (the phones' twin reads the same file), the address it writes and reads,
// and the chip rows it draws. The house's list, its address keys and its saved state are as they were (the safety test).
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/kinds/kind-filter-vectors.json';
import { HOUSE_FIELDS } from '../core/kind-fields';
import { SCHOOL_FIELDS } from '../core/school-kind';
import { ExtraFilters } from '../pages/map/extra-filters';
import { DEFAULT_LIST_QUERY, listQueryParams, listReturnParams, parseListQuery } from '../pages/map/map-list';
import { TranslationService } from '../i18n/translation.service';
import { audit } from './testing/a11y';
import { extraFilterMatches, extraFiltersOn, filterChoices, filterableExtras, readExtraFilter, type ExtraFilter } from './extra-filter';

interface Vectors {
  format: string;
  rows: Record<string, { id: string; type: string; choices: string[] }[]>;
  candidates: Record<string, Record<string, unknown>>;
  matches: { filter: ExtraFilter; keeps: string[] }[];
  houseKeeps: { filter: ExtraFilter; keeps: string[] };
  address: { query: string; school: ExtraFilter; house: ExtraFilter }[];
}
const V = vectorsJson as unknown as Vectors;
const ids = Object.keys(V.candidates);
const keeps = (fields: typeof SCHOOL_FIELDS, filter: ExtraFilter) =>
  ids.filter((id) => extraFilterMatches(filter, fields, { extrasOther: V.candidates[id] as never }));
const getter = (query: string) => {
  const p = new URLSearchParams(query);
  return (name: string) => p.get(name);
};

describe('the filter by a kind’s typed extra (S4b-BL-265)', () => {
  it('the school offers its enums and its switch as rows, the house none', () => {
    expect(V.format).toBe('doorprints-kind-filter-vectors/1');
    expect(filterableExtras(SCHOOL_FIELDS).map((f) => ({ id: f.id, type: f.type, choices: filterChoices(f) }))).toEqual(V.rows['school']);
    expect(filterableExtras(HOUSE_FIELDS)).toEqual(V.rows['house']);
  });

  for (const [i, c] of V.matches.entries()) {
    it(`keeps what the vectors keep: case ${i + 1}, ${JSON.stringify(c.filter)}`, () => {
      expect(keeps(SCHOOL_FIELDS, c.filter)).toEqual(c.keeps);
    });
  }

  it('a house list has no rows, so no filter keeps any of its candidates out', () => {
    expect(keeps(HOUSE_FIELDS, V.houseKeeps.filter)).toEqual(V.houseKeeps.keeps);
  });

  it('counts the rows that are set', () => {
    expect(extraFiltersOn({ board: 'CBSE', medium: null, busService: 'no' })).toBe(2);
    expect(extraFiltersOn({})).toBe(0);
  });

  for (const [i, a] of V.address.entries()) {
    it(`reads the address: case ${i + 1}, ${a.query || 'empty'}`, () => {
      expect(readExtraFilter(getter(a.query), SCHOOL_FIELDS)).toEqual(a.school);
      expect(readExtraFilter(getter(a.query), HOUSE_FIELDS)).toEqual(a.house);
      expect(parseListQuery(getter(a.query), SCHOOL_FIELDS).extras).toEqual(a.school);
    });
  }

  it('writes a row as x.<id>, null once it is back on Any, and the house’s address has no new key', () => {
    const base = { ...DEFAULT_LIST_QUERY };
    expect(listQueryParams({ ...base, extras: { board: 'CBSE', medium: null, busService: 'no' } })).toMatchObject({ 'x.board': 'CBSE', 'x.medium': null, 'x.busService': 'no' });
    expect(Object.keys(listQueryParams(base))).toEqual(['q', 'status', 'sort', 'monthlyMin', 'monthlyMax', 'moveInMin', 'moveInMax', 'sqftMin', 'sqftMax']);
    expect(listReturnParams({ ...base, extras: { board: 'ICSE', medium: null } })).toEqual({ 'x.board': 'ICSE' });
    expect(listReturnParams({ ...base, q: 'x' })).toEqual({ q: 'x' });
    expect(parseListQuery(getter('status=NEW'))).toEqual({ ...base, status: 'NEW', cost: { monthly: {}, moveIn: {}, perSqFt: {} } });
  });
});

describe('the chip rows (S4b-BL-265)', () => {
  async function draw(fields: typeof SCHOOL_FIELDS, value: ExtraFilter, lang: 'en' | 'hi' | 'ta' | 'te' = 'en') {
    TestBed.resetTestingModule();
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(ExtraFilters);
    fixture.componentRef.setInput('fields', fields);
    fixture.componentRef.setInput('value', value);
    const emitted: ExtraFilter[] = [];
    fixture.componentInstance.changed.subscribe((e) => emitted.push(e));
    fixture.detectChanges();
    await fixture.whenStable();
    return { host: fixture.nativeElement as HTMLElement, emitted, fixture };
  }
  const chips = (host: HTMLElement) =>
    [...host.querySelectorAll('[role="group"]')].map((g) => [g.getAttribute('aria-label'), [...g.querySelectorAll('button')].map((b) => `${b.textContent!.trim()}${b.getAttribute('aria-pressed') === 'true' ? '*' : ''}`)]);

  it('draws a row per enum and switch of a school, Any pressed until a choice is set', async () => {
    const { host } = await draw(SCHOOL_FIELDS, { board: 'CBSE', busService: 'no' });
    expect(chips(host)).toEqual([
      ['Board', ['Any', 'CBSE*', 'ICSE', 'State board', 'IB', 'Cambridge (IGCSE)', 'Other']],
      ['Medium of instruction', ['Any*', 'English', 'Hindi', 'State language', 'Other']],
      ['School bus', ['Any', 'Yes', 'No*']],
    ]);
    expect(audit(host)).toEqual([]);
  });

  it('draws nothing for a house', async () => {
    const { host } = await draw(HOUSE_FIELDS, {});
    expect(host.querySelectorAll('button').length).toBe(0);
  });

  it('a click emits the whole map: the clicked row changed, every other row as it was, Any as null', async () => {
    const { host, emitted } = await draw(SCHOOL_FIELDS, { board: 'CBSE' });
    [...host.querySelectorAll<HTMLButtonElement>('[role="group"]')[2].querySelectorAll('button')].find((b) => b.textContent!.trim() === 'Yes')!.click();
    [...host.querySelectorAll<HTMLButtonElement>('[role="group"]')[0].querySelectorAll('button')][0].click();
    expect(emitted).toEqual([{ board: 'CBSE', medium: null, busService: 'yes' }, { board: null, medium: null, busService: null }]);
  });

  for (const lang of ['hi', 'ta', 'te'] as const) {
    it(`has words in ${lang}`, async () => {
      const { host } = await draw(SCHOOL_FIELDS, {}, lang);
      for (const [label, buttons] of chips(host) as [string, string[]][]) {
        expect(label).toBeTruthy();
        expect(buttons.every((b) => b.length > 0 && !/^[a-z]+\.[a-z]/i.test(b))).toBe(true);
      }
    });
  }
});
