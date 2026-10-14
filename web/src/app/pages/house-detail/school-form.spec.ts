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

// The hidden schools kind on the candidate page (kinds PR 5, S4b-BL-209; docs/03 §18.9). With the developer flag on, a
// school is drawn from its kind file: `school-form.golden.json` pins every control of its Details and Fees cards (element,
// id, attributes, rows, label in en, hi, ta and te), their element tree and the page's section headings; it was written
// once from this page and read by eye, and a change to it is a visible change of the form. Since kinds PR 8 (S4b-BL-212)
// a new candidate is of the default kind (Settings > Kinds) while the flag or the switch is on, and a house otherwise; the page
// has no kind picker; a stored school row is drawn as a school whatever the switch says, its extras kept.
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService, type HouseDraft } from '../../core/ai.service';
import { DEV_KINDS_KEY } from '../../core/dev-kinds';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from '../../core/kind-setting';

/** Settings > Kinds with the school alone shown, so it is the default kind (S4b-BL-212). */
function schoolDefault(experimental = true): void {
  if (experimental) localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, 'school');
}
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { controls, headings, split, structure, text } from '../../shared/testing/form-lines';
import { HouseDetailPage } from './house-detail-page';
import golden from './school-form.golden.json';

/** A saved school (school-vectors.json's, as the page reads it) in Guwahati. */
const SCHOOL: HouseDto = {
  id: '55555555-5555-4555-8555-000000000209',
  kind: 'school',
  label: 'Pine Hill School',
  address: 'GS Road, Dispur',
  street: 'GS Road',
  locality: 'Dispur',
  lat: 26.1433,
  lon: 91.7898,
  status: 'SHORTLISTED',
  rating: 4,
  priceType: null,
  checklist: {},
  extrasOther: {
    admissionFee: { amount: 25000, cadence: 'once' }, board: 'CBSE', busService: true, commuteMinutes: 25,
    fees: { amount: 96000, cadence: 'year' }, grades: 'Nursery to 12', medium: 'ENGLISH',
  },
  deleted: false,
  createdAt: '2026-09-20T10:00:00.000Z',
  updatedAt: '2026-09-20T10:00:00.000Z',
  syncVersion: 0,
};

const saveHouse = vi.fn((h: HouseDto) => of(h));
/** The listing extract (kinds PR 7): answers the school's draft of kind-ai-vectors.json's first listing. */
const extractListing = vi.fn((_text: string, _kind?: string) => of(LISTING_DRAFT));
const LISTING_DRAFT: HouseDraft = {
  label: 'Pine Hill School', address: 'GS Road, Dispur, Guwahati', street: 'GS Road', locality: 'Dispur', price: null,
  priceType: null, bedrooms: null, areaSqft: null, contactName: 'Admissions office', contactPhone: '+91 361 223 4567',
  listingUrl: null, notes: null, amenities: [], warnings: [],
  extras: { board: 'CBSE', medium: 'REGIONAL', busService: true, fees: { amount: 96000, cadence: 'year' } },
};

async function open(house: HouseDto | null, lang: Lang, flag: boolean): Promise<{ fixture: ComponentFixture<HouseDetailPage>; host: HTMLElement }> {
  if (flag) localStorage.setItem(DEV_KINDS_KEY, 'school');
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      {
        provide: LocalDataService,
        useValue: {
          houses: () => of([]), brokers: () => of([]), scoring: () => of(DEFAULT_SCORING), questions: () => of([]),
          house: () => of(house), visits: () => of([]), viewingsOf: () => of([]), areas: () => of([]),
          areaNotes: () => of([]), places: () => of([]), settled: signal(0), photos: () => of([]), conditionPhotos: () => of([]), saveHouse,
        },
      },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap(house ? { id: house.id } : {}), queryParamMap: convertToParamMap(house ? {} : { lat: '26.1433', lon: '91.7898' }) } },
      },
      { provide: GeocodeService, useValue: { reverse: () => of({}), search: () => of(null) } },
      { provide: AiService, useValue: { enabled: signal(true), usesOwnKey: signal(false), ownHost: signal(''), extractListing } },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(HouseDetailPage);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<HouseDetailPage>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

const draft = (fixture: ComponentFixture<HouseDetailPage>): HouseDto => (fixture.componentInstance as unknown as { draft: () => HouseDto }).draft();
const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
interface Golden { controls: string[]; structure: string[]; labels: Record<string, string[]>; headings: Record<string, string[]> }
const GOLDEN = golden as unknown as Golden;

afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  saveHouse.mockClear();
  extractListing.mockClear();
});

describe('a school on the candidate page, with the developer flag on (school-form.golden.json)', () => {
  for (const lang of LANGS) {
    it(`in ${lang}: the golden file's controls, attributes, rows and labels, and its section headings`, async () => {
      const { host } = await open(SCHOOL, lang, true);
      const lines = controls(host);
      if (lang === 'en') {
        expect(lines).toEqual(GOLDEN.controls);
        expect(structure(host)).toEqual(GOLDEN.structure);
      }
      expect(lines.map((l) => split(l)[0])).toEqual(GOLDEN.controls.map((l) => split(l)[0]));
      expect(lines.map((l) => split(l)[1])).toEqual(GOLDEN.labels[lang]);
      expect(headings(host)).toEqual(GOLDEN.headings[lang]);
    });
  }

  it('shows the stored values in their fields, and hides what the kind hides', async () => {
    const { host } = await open(SCHOOL, 'en', true);
    const value = (id: string) => host.querySelector<HTMLInputElement>(`#${id}`)!;
    const chosen = (id: string) => text(host.querySelector<HTMLSelectElement>(`#${id}`)!.selectedOptions[0]);
    expect([chosen('extra-board'), chosen('extra-medium')]).toEqual(['CBSE', 'English']);
    expect([value('extra-grades').value, value('extra-commuteMinutes').value, value('extra-fees').value]).toEqual(['Nursery to 12', '25', '96000']);
    expect((value('extra-busService') as HTMLInputElement).checked).toBe(true);
    for (const gone of ['#house-price', '#house-price-type', '#house-broker', '#rooms-heading', '#questions-heading', '#checklist-heading', '.score']) {
      expect(host.querySelector(gone), gone).toBeNull();
    }
    expect(host.querySelector('app-house-viewings-card')).toBeNull();
    expect(host.querySelector('[name="dev-kind"]')).toBeNull();
    expect(text(host.querySelector('.danger-zone .btn-danger'))).toBe('Delete school');
  });

  it('with School the default kind (Settings > Kinds, S4b-BL-212) a new candidate is a school, and Save stores a school with its extras', async () => {
    schoolDefault();
    const { fixture, host } = await open(null, 'en', true);
    // The page has no kind picker any more (the developer's of S4b-BL-209 is gone): the space or the default decides.
    expect(host.querySelector('[name="dev-kind"]')).toBeNull();
    expect(text(host.querySelector('#house-title'))).toBe('New school');
    expect(host.querySelector('#house-price')).toBeNull();
    // Since kinds PR 7 (S4b-BL-211) the listing extract fills a school too.
    expect(host.querySelector('app-house-listing-card')).not.toBeNull();
    const type = async (id: string, v: string, event = 'input') => {
      const el = host.querySelector<HTMLInputElement>(`#${id}`)!;
      el.value = v;
      el.dispatchEvent(new Event(event));
      await settle(fixture);
    };
    await type('house-name', 'Sunbeam School');
    const board = host.querySelector<HTMLSelectElement>('#extra-board')!;
    board.selectedIndex = [...board.options].findIndex((o) => o.textContent?.trim() === 'State board');
    board.dispatchEvent(new Event('change'));
    await settle(fixture);
    await type('extra-fees', '72000');
    await type('extra-grades', 'Class 1 to 8');
    expect(draft(fixture).kind).toBe('school');
    expect(draft(fixture).priceType).toBeNull();
    expect(draft(fixture).extrasOther).toEqual({ board: 'STATE', fees: { amount: 72000, cadence: 'year' }, grades: 'Class 1 to 8' });
    host.querySelector<HTMLButtonElement>('.toolbar .btn-primary')!.click();
    await settle(fixture);
    expect(saveHouse).toHaveBeenCalledTimes(1);
    const saved = saveHouse.mock.calls[0][0];
    expect([saved.kind, saved.label, saved.price ?? null, saved.priceType]).toEqual(['school', 'Sunbeam School', null, null]);
    expect(saved.extrasOther).toEqual({ board: 'STATE', fees: { amount: 72000, cadence: 'year' }, grades: 'Class 1 to 8' });
  });

  it('with two kinds shown and House the default, a new candidate is a house with a price type', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
    const { fixture, host } = await open(null, 'en', true);
    expect([text(host.querySelector('#house-title')), draft(fixture).kind ?? null, draft(fixture).priceType]).toEqual(['New house', null, 'RENT']);
    expect(host.querySelector('#house-price')).not.toBeNull();
  });

  it('Settings’ switch alone, with no developer flag, makes a new candidate of the default kind', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    schoolDefault();
    const { fixture, host } = await open(null, 'en', false);
    expect([text(host.querySelector('#house-title')), draft(fixture).kind, draft(fixture).priceType]).toEqual(['New school', 'school', null]);
  });
});

describe('Fill in from listing text for a school (kinds PR 7, S4b-BL-211)', () => {
  it('sends the school\'s kind, fills the empty extras, keeps a typed one and names it, and Undo fill puts it back', async () => {
    schoolDefault();
    const { fixture, host } = await open(null, 'en', true);
    const board = host.querySelector<HTMLSelectElement>('#extra-board')!;
    board.selectedIndex = [...board.options].findIndex((o) => o.textContent?.trim() === 'State board');
    board.dispatchEvent(new Event('change'));
    await settle(fixture);
    const box = host.querySelector<HTMLTextAreaElement>('#listing-text')!;
    box.value = 'Pine Hill School, GS Road, Dispur. CBSE. Bus. Fees 96,000 a year.';
    box.dispatchEvent(new Event('input'));
    await settle(fixture);
    host.querySelector<HTMLButtonElement>('#listing-fill-submit')!.click();
    await settle(fixture);
    expect(extractListing).toHaveBeenCalledWith('Pine Hill School, GS Road, Dispur. CBSE. Bus. Fees 96,000 a year.', 'school');
    expect(draft(fixture).extrasOther).toEqual({ board: 'STATE', medium: 'REGIONAL', busService: true, fees: { amount: 96000, cadence: 'year' } });
    expect([draft(fixture).label, draft(fixture).price ?? null, draft(fixture).priceType]).toEqual(['Pine Hill School', null, null]);
    expect(text(host.querySelector('.listing-fill'))).toContain('Kept your Board; the listing says “CBSE”.');
    expect(host.querySelector<HTMLSelectElement>('#extra-medium')!.selectedOptions[0].textContent?.trim()).toBe('State language');
    host.querySelector<HTMLButtonElement>('.listing-undo')!.click();
    await settle(fixture);
    expect(draft(fixture).extrasOther).toEqual({ board: 'STATE' });
  });

  it('marks the extras the fill wrote as from the listing, not a kept one, and a mark goes when that extra is edited (S4b-BL-272)', async () => {
    schoolDefault();
    const { fixture, host } = await open(null, 'en', true);
    const board = host.querySelector<HTMLSelectElement>('#extra-board')!;
    board.selectedIndex = [...board.options].findIndex((o) => o.textContent?.trim() === 'State board');
    board.dispatchEvent(new Event('change'));
    await settle(fixture);
    const box = host.querySelector<HTMLTextAreaElement>('#listing-text')!;
    box.value = 'Pine Hill School, GS Road, Dispur. CBSE. Bus. Fees 96,000 a year.';
    box.dispatchEvent(new Event('input'));
    await settle(fixture);
    host.querySelector<HTMLButtonElement>('#listing-fill-submit')!.click();
    await settle(fixture);
    const chips = () => [...host.querySelectorAll('.listing-marks .chip')].map((c) => c.textContent?.trim());
    expect(chips()).toEqual(['School name', 'Address', 'Street', 'Locality', 'Contact name', 'Contact phone', 'Medium of instruction', 'School bus', 'Fees per year']);
    const medium = host.querySelector<HTMLSelectElement>('#extra-medium')!;
    medium.selectedIndex = [...medium.options].findIndex((o) => o.textContent?.trim() === 'English');
    medium.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);
    expect(chips()).toEqual(['School name', 'Address', 'Street', 'Locality', 'Contact name', 'Contact phone', 'School bus', 'Fees per year']);
    host.querySelector<HTMLButtonElement>('.listing-undo')!.click();
    await settle(fixture);
    expect(host.querySelector('.listing-marks')).toBeNull();
  });

  it('a house\'s fill sends the text alone, as before', async () => {
    const { fixture, host } = await open(null, 'en', true);
    const box = host.querySelector<HTMLTextAreaElement>('#listing-text')!;
    box.value = '2BHK in Dispur';
    box.dispatchEvent(new Event('input'));
    await settle(fixture);
    host.querySelector<HTMLButtonElement>('#listing-fill-submit')!.click();
    await settle(fixture);
    expect(extractListing.mock.calls[0]).toEqual(['2BHK in Dispur']);
  });
});

describe('with the developer flag and the switch off, a house hunter’s page is as it was (S4b-BL-209, S4b-BL-212)', () => {
  it('a new candidate is a house, with no kind picker, even with School stored as the default kind', async () => {
    schoolDefault(false);
    const { host, fixture } = await open(null, 'en', false);
    expect(host.querySelector('[name="dev-kind"]')).toBeNull();
    expect(text(host.querySelector('#house-title'))).toBe('New house');
    expect(host.querySelector('#house-price')).not.toBeNull();
    expect(draft(fixture).kind ?? null).toBeNull();
  });

  it('a stored school row (synced from a device with the switch on) is still drawn as a school, and its extras are kept through a save', async () => {
    const { fixture, host } = await open(SCHOOL, 'en', false);
    for (const id of ['#house-price', '#house-broker', '#rooms-heading', '#checklist-heading']) expect(host.querySelector(id), id).toBeNull();
    expect(host.querySelector('#extra-board')).not.toBeNull();
    expect(text(host.querySelector('label[for="house-name"]'))).toContain('School name');
    expect(text(host.querySelector('#cost-heading'))).toBe('Fees');
    host.querySelector<HTMLButtonElement>('.toolbar .btn-primary')!.click();
    await settle(fixture);
    expect(saveHouse).toHaveBeenCalledTimes(1);
    expect([saveHouse.mock.calls[0][0].kind, saveHouse.mock.calls[0][0].extrasOther]).toEqual(['school', SCHOOL.extrasOther]);
  });
});
