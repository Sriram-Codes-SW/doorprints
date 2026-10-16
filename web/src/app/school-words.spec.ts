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

// The house's words left on a school's pages (kinds PR 16, S4b-BL-256, S4b-BL-259, S4b-BL-277; docs/03 §18.9): the page, its
// walk check, the tab titles, the list rows, Your data, the Areas and Places pages and the dictionaries' school words, in
// en, hi, ta and te, with a school space on screen. Each page is rendered and read; the house's own pages are asserted
// to be what they were. The expected words are written here, not recomputed with the code under test.
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import type { Type } from '@angular/core';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from './core/ai.service';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from './core/kind-setting';
import { LocalDataError } from './core/local-error';
import { LocalDataService } from './core/local-data.service';
import { newHouse } from './core/models';
import type { HouseDto } from './core/models';
import { toolWord, toolWordPairs } from './core/space-tool-words';
import { spaceWords } from './core/space-words';
import { LocalStore } from './data/local-store.service';
import { SyncService } from './data/sync.service';
import { DICTIONARIES } from './i18n/all-dictionaries';
import type { TKey } from './i18n/en';
import type { Lang } from './i18n/languages';
import { TranslationService } from './i18n/translation.service';
import { routesFor } from './app.routes';
import { AreasPage } from './pages/areas/areas-page';
import { DataPage } from './pages/data/data-page';
import { HouseDetailPage } from './pages/house-detail/house-detail-page';
import { MapPage } from './pages/map/map-page';
import { PlacesPage } from './pages/places/places-page';
import { DEFAULT_SCORING } from './shared/scoring';

const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
/** What a house is called in each language; a school's page must carry none of it (the phone-first words, not the places). */
const HOUSE_WORD: Record<Lang, RegExp> = {
  en: /house|BHK|\bflat\b/i,
  hi: /मकान|घर/,
  ta: /வீடு|வீட்/,
  te: /ఇల్లు|ఇళ్ళు|ఇళ్లు|ఇంటి|ఇంట(?!ర్)/,
};

const SCHOOL: HouseDto = {
  ...newHouse(26.1433, 91.7898), id: 's1', kind: 'school', label: 'Pine Hill School', status: 'SHORTLISTED', rating: 4, priceType: null,
  street: 'GS Road', locality: 'Dispur', createdAt: '2026-09-20T10:00:00.000Z', updatedAt: '2026-09-20T10:00:00.000Z',
  extrasOther: { board: 'CBSE', fees: { amount: 96000, cadence: 'year' } },
};
const SCHOOL2: HouseDto = { ...SCHOOL, id: 's2', label: 'Oak Valley', checklist: { safety: 5 } }; // 5 on one item and 4 stars: 4.5
const HOUSE: HouseDto = {
  ...newHouse(26.15, 91.8), id: 'h1', label: 'Green View', status: 'SHORTLISTED', price: 32000, priceType: 'RENT', rating: 4,
  createdAt: '2026-09-21T10:00:00.000Z', updatedAt: '2026-09-21T10:00:00.000Z',
};

function api(houses: HouseDto[], over: Record<string, unknown> = {}) {
  const known: Record<string, unknown> = {
    settled: signal(0), revision: signal(0), stats: () => of({ houses: houses.length, shortlisted: houses.length, rejected: 0, visits: 0, streets: 1 }),
    houses: () => of(houses), house: (id: string) => of(houses.find((h) => h.id === id) ?? null), scoring: () => of(DEFAULT_SCORING),
    visitCounts: () => of(new Map()), photos: () => of([]), conditionPhotos: () => of([]), ...over,
  };
  return new Proxy(known, { get: (t, k: string) => (k in t ? t[k] : () => of([])) });
}
const ai = {
  enabled: signal(true), enabledFor: () => true, optedIn: signal(true), serverEnabled: signal(true), usesOwnKey: signal(true),
  ownHost: signal('api.example'), offReason: signal(null), refresh: () => undefined,
};

interface Mounted { fixture: ComponentFixture<unknown>; host: HTMLElement }

/** Mounts a page with a school space on screen (Settings > What you track: houses and schools, the school the default). */
async function mount(
  comp: Type<unknown>, lang: Lang,
  opts: { houses?: HouseDto[]; params?: Record<string, string>; query?: Record<string, string>; real?: boolean; dflt?: string; over?: Record<string, unknown> } = {},
): Promise<Mounted> {
  localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, opts.dflt ?? 'school');
  const params = opts.params ?? {};
  const query = opts.query ?? {};
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [comp],
    providers: [
      provideRouter([]),
      ...(opts.real
        ? []
        : [
            { provide: LocalDataService, useValue: api(opts.houses ?? [SCHOOL, SCHOOL2], opts.over) },
            { provide: SyncService, useValue: { migration: signal('done'), enabled: signal(false), lastError: signal(null), lastErrorForced: signal(false), downloadToThisBrowser: vi.fn() } },
            { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
            { provide: AiService, useValue: ai },
            {
              provide: ActivatedRoute,
              useValue: {
                snapshot: { paramMap: convertToParamMap(params), queryParamMap: convertToParamMap(query), params, queryParams: query },
                paramMap: of(convertToParamMap(params)), queryParamMap: of(convertToParamMap(query)), params: of(params), queryParams: of(query),
              },
            },
          ]),
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(comp) as ComponentFixture<unknown>;
  for (let i = 0; i < 4; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

/** Every visible word and the text attributes of an element, one string each. */
function words(root: HTMLElement): string[] {
  const out: string[] = [];
  const walk = (n: Node) => {
    if (n.nodeType === 3) {
      const t = (n.textContent ?? '').replace(/\s+/g, ' ').trim();
      if (t) out.push(t);
    }
    if (n instanceof HTMLElement) {
      for (const a of ['placeholder', 'aria-label', 'title', 'alt']) {
        const v = n.getAttribute(a);
        if (v) out.push(v);
      }
      n.childNodes.forEach(walk);
    }
  };
  walk(root);
  return out;
}
const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('the school’s words in the dictionaries (docs/03 §18.9)', () => {
  const pairs = toolWordPairs('school');
  const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

  it('every house word a school rewords is a key of the house, and each school key is in en, hi, ta and te with the same placeholders', () => {
    expect(pairs.length).toBeGreaterThan(60);
    // Compare's, Ask's and the Data card's counts carry their own parameters by design (`many`): the rewordings are the rest.
    for (const [house, school] of pairs.filter(([h]) => !h.startsWith('data.count') && !h.startsWith('data.preview'))) {
      for (const lang of LANGS) {
        const d = DICTIONARIES[lang] as Record<string, string>;
        expect(typeof d[house], `${lang} ${house}`).toBe('string');
        expect(typeof d[school], `${lang} ${school}`).toBe('string');
        expect(placeholders(d[school]), `${lang} ${school}`).toEqual(placeholders(d[house]));
      }
    }
  });

  it('the school’s words that replace a house word say school, not house, in every language; a house’s are what they were', () => {
    // *Your office or family home* (places.empty) names the person's own home, not a candidate: the one word left on purpose.
    const rewording = pairs.filter(([house]) => !/^(compare|ask|plan|data)\./.test(house) && house !== 'places.empty');
    for (const [house, school] of rewording) {
      for (const lang of LANGS) {
        const d = DICTIONARIES[lang] as Record<string, string>;
        // *Home* in a family home is the person's own, not a candidate: the stems are the candidate's (house, flat, BHK).
        expect(d[school].replace(/\{\w+\}/g, '').match(HOUSE_WORD[lang]), `${lang} ${school}`).toBeNull();
        expect(d[school], `${lang} ${school} differs from ${house}`).not.toBe(d[house]);
      }
      expect(toolWord('house', house), house).toBe(house);
      expect(toolWord(undefined, house), house).toBe(house);
      expect(toolWord('school', house), house).toBe(school);
    }
  });
});

describe('the voice words name no kind (kinds PR 31)', () => {
  it('no voice.* text says house in any language: the hints, the rationale and the statuses hold for every kind', () => {
    for (const lang of LANGS) {
      const d = DICTIONARIES[lang] as Record<string, string>;
      const keys = Object.keys(d).filter((k) => k.startsWith('voice.'));
      expect(keys.length, lang).toBeGreaterThan(30);
      for (const k of keys) expect(d[k].replace(/\{\w+\}/g, '').match(HOUSE_WORD[lang]), `${lang} ${k}`).toBeNull();
    }
  });
});

describe('the space words that name the areas a candidate is in (S4b-BL-256)', () => {
  it('a school’s are the school’s keys, a house’s the keys they were', () => {
    expect([spaceWords('school').areaInReach, spaceWords('school').areaNotesNone]).toEqual(['school.areaNotesInReach', 'school.areaNotesNone']);
    expect([spaceWords('house').areaInReach, spaceWords('house').areaNotesNone]).toEqual(['areaNotes.inReach', 'areaNotes.none']);
  });
});

describe('the tab titles of a school’s pages (S4b-BL-259, S4b-BL-277)', () => {
  const routes = routesFor(true, ['house', 'school']);
  const titleOf = (path: string, lang: Lang) => {
    const r = routes.find((x) => x.path === path)!;
    return (DICTIONARIES[lang] as Record<string, string>)[r.title as string];
  };

  it('/k/school/new, /k/school/<id> and /k/school are New school, School details and Schools in every language', () => {
    expect(titleOf('k/school/new', 'en')).toBe('New school · Doorprints');
    expect(titleOf('k/school/:id', 'en')).toBe('School details · Doorprints');
    expect(titleOf('k/school', 'en')).toBe('Schools · Doorprints');
    for (const lang of LANGS) {
      for (const path of ['k/school/new', 'k/school/:id', 'k/school']) expect(titleOf(path, lang).match(HOUSE_WORD[lang]), `${lang} ${path}`).toBeNull();
    }
  });

  it('the house’s tab titles are the keys they were', () => {
    expect(titleOf('k/house/new', 'en')).toBe('New house · Doorprints');
    expect(titleOf('k/house/:id', 'en')).toBe('House details · Doorprints');
    expect(routes.find((x) => x.path === 'k/house')!.title).toBe('title.map');
    expect(routes.find((x) => x.path === 'houses/:id')!.title).toBe('title.house');
  });
});

describe('a school’s page carries no house word (S4b-BL-259)', () => {
  for (const lang of LANGS) {
    it(`a saved school and a new school, in ${lang}: no BHK, house or flat in the words or the attributes`, async () => {
      for (const m of [
        await mount(HouseDetailPage, lang, { params: { id: 's1' } }),
        await mount(HouseDetailPage, lang, { query: { lat: '26.1', lon: '91.7' } }),
      ]) {
        const bad = words(m.host).filter((w) => HOUSE_WORD[lang].test(w));
        expect(bad, lang).toEqual([]);
      }
    });
  }

  it('the name’s placeholder, the map’s label, the walk check and the empty lines are the school’s (en)', async () => {
    const m = await mount(HouseDetailPage, 'en', { params: { id: 's1' } });
    expect(m.host.querySelector<HTMLInputElement>('#house-name')!.placeholder).toBe('e.g. St. Mary’s School');
    expect(text(m.host.querySelector('#house-check-heading'))).toBe('Did I walk past this school?');
    expect(words(m.host)).toContain('Map of the school location');
    expect(text(m.host.querySelector('#visits-heading')?.closest('section'))).toContain('Visits');
  });

  it('a new school says Save the school first, asks for a school name, and offers to fill the school from a listing', async () => {
    const m = await mount(HouseDetailPage, 'en', { query: { lat: '26.1', lon: '91.7' } });
    expect(m.host.textContent).toContain('Save the school first to record visits.');
    expect(m.host.textContent).toContain('Save the school first to add photos.');
    expect(m.host.textContent).toContain('then create the school.');
    expect(m.host.textContent).toContain('together with the matching school notes.');
    m.host.querySelector<HTMLButtonElement>('.toolbar button.btn-primary')!.click();
    for (let i = 0; i < 3; i++) {
      m.fixture.detectChanges();
      await m.fixture.whenStable();
    }
    expect(text(m.host.querySelector('#name-error'))).toBe('Please give the school a name.');
    expect(m.host.textContent).toContain('Please give the school a name.');
  });

  it('a house keeps its words: the placeholder, the question, the empty lines and the hint', async () => {
    const m = await mount(HouseDetailPage, 'en', { houses: [HOUSE], params: { id: 'h1' }, dflt: 'house' });
    expect(m.host.querySelector<HTMLInputElement>('#house-name')!.placeholder).toBe('e.g. 2BHK near the park');
    expect(text(m.host.querySelector('#house-check-heading'))).toBe('Did I walk past this house?');
    expect(words(m.host)).toContain('Map of the house location');
    const n = await mount(HouseDetailPage, 'en', { houses: [HOUSE], query: { lat: '26.1', lon: '91.7' }, dflt: 'house' });
    expect(n.host.textContent).toContain('Save the house first to record visits.');
    expect(n.host.textContent).toContain('then create the house.');
  });

  it('a school that is not there says School not found, in the space’s words; a house says House not found', async () => {
    const notThere = { house: () => throwError(() => new LocalDataError('error.notFoundLocal')) };
    const gone = await mount(HouseDetailPage, 'en', { houses: [], params: { id: 'nope' }, over: notThere });
    expect(text(gone.host.querySelector('.empty-state p'))).toBe('School not found.');
    const missing = await mount(HouseDetailPage, 'en', { houses: [], params: { id: 'nope' }, dflt: 'house', over: notThere });
    expect(text(missing.host.querySelector('.empty-state p'))).toBe('House not found.');
  });
});

describe('a school space’s list and Your data (S4b-BL-277)', () => {
  const stubMap = () => vi.spyOn(MapPage.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
  const rowsOf = (host: HTMLElement) => [...host.querySelectorAll('ul.list li')].map((li) => text(li));

  // The school has its own checklist since kinds PR 27 (docs/03 §18.21), so its row shows a score as the house's does (the rating
  // here, as nothing is checked yet); it still has no price and no BHK.
  it('a school’s row is its name, status, score and place: no price, no BHK (en, hi, ta, te)', async () => {
    stubMap();
    for (const lang of LANGS) {
      const m = await mount(MapPage, lang);
      const rows = rowsOf(m.host);
      expect(rows, lang).toHaveLength(2);
      const score = (DICTIONARIES[lang] as Record<string, string>)['common.score'];
      for (const row of rows) {
        expect(row.includes(score), `${lang} ${row}`).toBe(true);
        expect(row, lang).not.toMatch(/₹|—/);
      }
    }
    const en = rowsOf((await mount(MapPage, 'en')).host);
    expect(en).toEqual(['Pine Hill School★ Shortlisted ★Score 4.0 GS Road, Dispur', 'Oak Valley★ Shortlisted ★Score 4.5 GS Road, Dispur']);
  });

  // S4b-BL-265: the chip rows over a kind's typed extras. The rules are kind-filter-vectors.json's (shared/extra-filter.spec.ts).
  it('the extras chips narrow the school list, read the address, and a house list has none', async () => {
    stubMap();
    const three: HouseDto[] = [
      { ...SCHOOL, extrasOther: { board: 'CBSE', busService: true } },
      { ...SCHOOL2, extrasOther: { board: 'ICSE' } },
      { ...SCHOOL, id: 's3', label: 'Birch Lane', extrasOther: { board: 'CBSE', busService: false } },
    ];
    const names = (host: HTMLElement) => rowsOf(host).map((r) => r.split('★')[0].trim());
    const chip = (host: HTMLElement, group: string, label: string) =>
      [...host.querySelectorAll(`[role="group"][aria-label="${group}"] button`)].find((b) => b.textContent!.trim() === label) as HTMLButtonElement;
    const m = await mount(MapPage, 'en', { houses: three, query: { 'x.board': 'CBSE' } });
    expect(names(m.host)).toEqual(['Pine Hill School', 'Birch Lane']);
    expect(chip(m.host, 'Board', 'CBSE').getAttribute('aria-pressed')).toBe('true');
    chip(m.host, 'School bus', 'Yes').click();
    m.fixture.detectChanges();
    expect(names(m.host)).toEqual(['Pine Hill School']);
    chip(m.host, 'School bus', 'No').click();
    m.fixture.detectChanges();
    chip(m.host, 'Board', 'Any').click();
    m.fixture.detectChanges();
    expect(names(m.host)).toEqual(['Birch Lane']);
    const h = await mount(MapPage, 'en', { houses: [HOUSE], dflt: 'house', query: { 'x.board': 'CBSE' } });
    expect(h.host.querySelectorAll('[role="group"][aria-label="Board"]').length).toBe(0);
    expect(names(h.host)).toEqual(['Green View']);
  });

  it('a house row keeps its price and score', async () => {
    stubMap();
    const m = await mount(MapPage, 'en', { houses: [HOUSE], dflt: 'house' });
    expect(rowsOf(m.host)[0]).toMatch(/₹32,000/);
    expect(rowsOf(m.host)[0]).toMatch(/Score/);
  });

  it('the map’s zoom-to-fit says every school in the list', async () => {
    stubMap();
    const m = await mount(MapPage, 'en');
    expect(words(m.host)).toContain('Zoom the map to show every school in the list');
    const h = await mount(MapPage, 'en', { houses: [HOUSE], dflt: 'house' });
    expect(words(h.host)).toContain('Zoom the map to show every house in the list');
  });

  it('Your data without criteria while a school space is the last one, with the questions and viewings cards in the school’s words (kinds PR 29); all three in the house space', async () => {
    for (const lang of LANGS) {
      const m = await mount(DataPage, lang, { real: true });
      expect(m.host.querySelector('#criteria-heading'), lang).toBeNull();
      expect(m.host.querySelector('#questions-heading'), lang).not.toBeNull();
      expect(m.host.querySelector('#viewings-heading'), lang).not.toBeNull();
      expect(m.host.querySelector('#areas-heading'), lang).not.toBeNull();
      expect(m.host.querySelector('#brokers-heading'), lang).not.toBeNull();
    }
    const sc = await mount(DataPage, 'en', { real: true });
    expect(words(sc.host)).toContain('Plan a school visit or open day, and see the ones coming up and the ones you have done.');
    expect(words(sc.host)).toContain('The questions you like to ask on a school visit.');
    const h = await mount(DataPage, 'en', { real: true, dflt: 'house' });
    for (const id of ['criteria', 'questions', 'viewings']) expect(h.host.querySelector(`#${id}-heading`), id).not.toBeNull();
    // One kind enabled (the default, and every production build): the house, with the cards it always had.
    localStorage.clear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [DataPage], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(DataPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    for (const id of ['criteria', 'questions', 'viewings']) {
      expect((fixture.nativeElement as HTMLElement).querySelector(`#${id}-heading`), `one kind: ${id}`).not.toBeNull();
    }
  });

  it('the Areas and Places pages and their Your data cards say school in a school space, house in the house space', async () => {
    const a = await mount(AreasPage, 'en');
    expect(text(a.host.querySelector('.muted'))).toContain('every school inside its circle');
    expect(a.host.textContent).toContain('Add one from a school.');
    const p = await mount(PlacesPage, 'en');
    expect(p.host.textContent).toContain('Each school shows how far it is from them.');
    expect(p.host.textContent).toContain('how far each school is.');
    const d = await mount(DataPage, 'en', { real: true });
    expect(d.host.textContent).toContain('with notes that show on the schools inside them');
    expect(d.host.textContent).toContain('Schools show their distance from them.');
    const h = await mount(AreasPage, 'en', { dflt: 'house' });
    expect(h.host.textContent).toContain('every house inside its circle');
    const hp = await mount(PlacesPage, 'en', { dflt: 'house' });
    expect(hp.host.textContent).toContain('Each house shows how far it is from them.');
  });
});
