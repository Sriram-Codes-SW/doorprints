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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { routesFor } from '../../app.routes';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { ComparePage } from './compare-page';

/**
 * Compare in a school space (kinds PR B, S4b-BL-279; docs/03 §18.13, docs/06 TC-U-216): only the schools, the school’s
 * words, rows from the kind file (its extras; none of the house’s money, rooms or checklist), the best rating marked, and a
 * pick of a house refused with one sentence in four languages.
 */
const school = (id: string, over: Partial<HouseDto>): HouseDto => ({ ...newHouse(13, 80), id, kind: 'school', label: id.toUpperCase(), ...over });
const S1 = school('s1', { rating: 5, status: 'SHORTLISTED', extrasOther: { board: 'CBSE', medium: 'ENGLISH', grades: '1-12', commuteMinutes: 20, busService: true, fees: { amount: 96000, cadence: 'year' } } });
const S2 = school('s2', { rating: 3, extrasOther: { board: 'ICSE', busService: false } });
const H1: HouseDto = { ...newHouse(13.1, 80.1), id: 'h1', label: 'A house', price: 20000, priceType: 'RENT' };

async function open(lang: Lang = 'en', ids = 's1,s2,h1', all: HouseDto[] = [S1, S2, H1]) {
  TestBed.resetTestingModule();
  localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, 'house');
  TestBed.configureTestingModule({
    imports: [ComparePage],
    providers: [
      provideRouter(routesFor(true, ['house', 'school'])),
      { provide: LocalDataService, useValue: { settled: signal(0), scoring: () => of(DEFAULT_SCORING), houses: () => of(all), visitCounts: () => of(new Map()), places: () => of([]), brokers: () => of([]) } },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ ids }) } } },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  await TestBed.inject(Router).navigateByUrl('/k/school/compare');
  const fixture = TestBed.createComponent(ComparePage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}
const t = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('Compare in a school space (S4b-BL-279)', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('holds the schools only, in the school’s words, and links each to its own page', async () => {
    const host = await open();
    expect(t(host.querySelector('h1'))).toBe('Compare schools');
    expect(t(host.querySelector('#picker-hint'))).toBe('Choose 2 to 4 schools. Selected: 2.');
    expect([...host.querySelectorAll('.chip')].map(t)).toEqual(['★Shortlisted: S1', 'S2']);
    expect([...host.querySelectorAll('thead a')].map((a) => a.getAttribute('href'))).toEqual(['/k/school/s1', '/k/school/s2']);
    expect(t(host.querySelector('.footnote'))).toBe('The best value in each row is highlighted and marked ✓.');
  });

  it('has the kind file’s rows and none of the house’s', async () => {
    const host = await open();
    const rows = new Map([...host.querySelectorAll('tbody tr')].map((tr) => [t(tr.children[0]), [...tr.children].slice(1).map(t)] as const));
    expect([...rows.keys()]).toEqual(['Board', 'Medium of instruction', 'Grades offered', 'Commute (minutes)', 'School bus', 'Fees per year', 'Admission fee', 'Your rating', 'Visits', 'Street', 'Contact']);
    expect(rows.get('Board')).toEqual(['CBSE', 'ICSE']);
    expect(rows.get('Grades offered')).toEqual(['1-12', '–No value']);
    expect(rows.get('School bus')).toEqual(['Yes', 'No']);
    expect(rows.get('Fees per year')).toEqual(['₹96,000', '–No value']);
    expect(rows.get('Your rating')![0]).toContain('✓(best)');
  });

  // S4b-BL-292: the kind file says which way a number is better (`better`), so a school's lower commute and lower fees are marked.
  it('marks the lower commute and the lower fees as the best, and nothing where there is one value, a tie, or no hint', async () => {
    const s3 = school('s3', { rating: 4, extrasOther: { board: 'CBSE', commuteMinutes: 35, busService: true, fees: { amount: 72000, cadence: 'year' }, admissionFee: { amount: 5000, cadence: 'once' } } });
    const s4 = school('s4', { rating: 4, extrasOther: { board: 'CBSE', commuteMinutes: 20, fees: { amount: 72000, cadence: 'year' }, admissionFee: { amount: 5000, cadence: 'once' } } });
    const host = await open('en', 's1,s3,s4', [S1, s3, s4]);
    const best = (label: string) => {
      const tr = [...host.querySelectorAll('tbody tr')].find((r) => t(r.children[0]) === label)!;
      return [...tr.children].slice(1).map((td) => td.classList.contains('best'));
    };
    expect(best('Commute (minutes)')).toEqual([true, false, true]);
    expect(best('Fees per year')).toEqual([false, true, true]);
    expect(best('Admission fee')).toEqual([false, false, false]); // one value is not a comparison; two equal ones are a tie
    expect(best('Board')).toEqual([false, false, false]); // no hint: an enum has no better
    expect(best('School bus')).toEqual([false, false, false]);
    const one = await open('en', 's1,s2');
    const tr = [...one.querySelectorAll('tbody tr')].find((r) => t(r.children[0]) === 'Commute (minutes)')!;
    expect([...tr.children].slice(1).map((td) => td.classList.contains('best'))).toEqual([false, false]);
  });

  it('refuses a pick of a house with one sentence, in each language, and keeps the schools', async () => {
    expect(t((await open('en')).querySelector('p[role="status"]'))).toBe('Compare works on the list you are looking at. Picks from another list were left out.');
    for (const lang of ['hi', 'ta', 'te'] as const) expect(t((await open(lang)).querySelector('p[role="status"]')).length).toBeGreaterThan(20);
    expect((await open('en', 's1,s2')).querySelector('p[role="status"]:not(#picker-hint)')).toBeNull();
  });

  it('in Tamil the title is the school’s', async () => {
    expect(t((await open('ta')).querySelector('h1'))).toBe('பள்ளிகளை ஒப்பிடு');
  });
});
