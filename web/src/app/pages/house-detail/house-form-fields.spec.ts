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

// The safety test of kinds PR 4 (S4b-BL-208, docs/03 §18.8), written on the hand-written form before the renderer
// replaced its cost, floor and BHK fields: every control of the Details and Cost cards in page order (its element, id,
// input attributes, where it sits in the rows and its label in en, hi, ta and te), every section heading of the page,
// for a rent, a sale, a price with no type and a house that is Taken. `house-form.golden.json` was taken from main at
// 7fbde93c and is never rewritten by a test: a change to it is a visible change of the form and needs its own review.
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { AiService } from '../../core/ai.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { searchText } from '../map/map-list';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { HouseDetailPage } from './house-detail-page';
import golden from './house-form.golden.json';
import fieldVectors from '../../../../../docs/schemas/kinds/kind-field-vectors.json';
import { controls, headings, split, structure, text } from '../../shared/testing/form-lines';

const SAVED: HouseDto = {
  id: '5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10',
  label: 'Lake view flat',
  lat: 26.1445,
  lon: 91.7362,
  status: 'NEW',
  priceType: 'RENT',
  checklist: {},
  deleted: false,
  createdAt: '2026-09-20T10:00:00.000Z',
  updatedAt: '2026-09-20T10:00:00.000Z',
  syncVersion: 0,
};

/** The four scenarios of the golden file: what changes which fields and headings show. */
const SCENARIOS: Record<string, Partial<HouseDto>> = {
  rent: { priceType: 'RENT' },
  sale: { priceType: 'SALE' },
  untyped: { priceType: null },
  taken: { priceType: 'RENT', status: 'TAKEN' },
};

async function open(house: HouseDto, lang: Lang): Promise<{ fixture: ComponentFixture<HouseDetailPage>; host: HTMLElement }> {
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          houses: () => of([]), brokers: () => of([]), scoring: () => of(DEFAULT_SCORING), questions: () => of([]),
          house: () => of(house), visits: () => of([]), viewingsOf: () => of([]), areas: () => of([]),
          areaNotes: () => of([]), places: () => of([]), settled: signal(0), photos: () => of([]), conditionPhotos: () => of([]), saveHouse: () => of(house),
        },
      },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: house.id }), queryParamMap: convertToParamMap({}) } } },
      { provide: GeocodeService, useValue: { reverse: () => of({}), search: () => of(null) } },
      { provide: AiService, useValue: { enabled: signal(false), usesOwnKey: signal(false), ownHost: signal(''), extractListing: () => of() } },
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

const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
interface Golden {
  scenarios: Record<string, { controls: string[]; structure: string[] }>;
  labels: Record<string, string[]>;
  headings: Record<string, string[]>;
}
const GOLDEN = golden as unknown as Golden;

afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

describe('the house form as it is rendered (the safety test of S4b-BL-208)', () => {
  for (const lang of LANGS) {
    for (const [name, change] of Object.entries(SCENARIOS)) {
      it(`${name} in ${lang}: the same controls, attributes, rows and labels, and the same section headings`, async () => {
        const { host } = await open({ ...SAVED, ...change }, lang);
        const want = GOLDEN.scenarios[name];
        const lines = controls(host);
        if (lang === 'en') {
          expect(lines).toEqual(want.controls);
          expect(structure(host)).toEqual(want.structure);
        }
        expect(lines.map((l) => split(l)[0])).toEqual(want.controls.map((l) => split(l)[0]));
        expect(lines.map((l) => split(l)[1])).toEqual(GOLDEN.labels[`${name}.${lang}`]);
        expect(headings(host)).toEqual(GOLDEN.headings[`${name}.${lang}`]);
      });
    }
  }
});

/** Types into a control as a person does: the value, then the event its form control listens to. */
async function type(fixture: ComponentFixture<HouseDetailPage>, id: string, value: string, event = 'input'): Promise<void> {
  const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(`#${id}`);
  expect(el, id).not.toBeNull();
  el!.value = value;
  el!.dispatchEvent(new Event(event));
  fixture.detectChanges();
  await fixture.whenStable();
}

async function click(fixture: ComponentFixture<HouseDetailPage>, id: string): Promise<void> {
  (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(`#${id}`)!.click();
  fixture.detectChanges();
  await fixture.whenStable();
}

describe('typing into the house form (the safety test of S4b-BL-208)', () => {
  it('puts each typed value where the house keeps it, the floor with its Basement sign', async () => {
    const { fixture } = await open({ ...SAVED }, 'en');
    const typed: [string, string, (d: HouseDto) => unknown, unknown][] = [
      ['house-bhk', '3', (d) => d.bedrooms, 3],
      ['house-area', '1150', (d) => d.areaSqft, 1150],
      ['house-floor', '2', (d) => d.floor, 2],
      ['cost-deposit', '64000', (d) => d.cost?.deposit, 64000],
      ['cost-deposit-months', '4', (d) => d.cost?.depositMonths, 4],
      ['cost-maintenance', '2500', (d) => d.cost?.maintenance, 2500],
      ['cost-brokerage', '18000', (d) => d.cost?.brokerage, 18000],
      ['cost-brokerage-months', '1', (d) => d.cost?.brokerageMonths, 1],
      ['cost-lock-in', '11', (d) => d.cost?.lockInMonths, 11],
      ['cost-notice', '2', (d) => d.cost?.noticeMonths, 2],
      ['cost-offer', '17000', (d) => d.cost?.myOffer, 17000],
      ['cost-agreed', '17500', (d) => d.cost?.agreedPrice, 17500],
    ];
    const draft = () => (fixture.componentInstance as unknown as { draft: () => HouseDto }).draft();
    for (const [id, value, read, want] of typed) {
      await type(fixture, id, value);
      expect(read(draft()), id).toBe(want);
    }
    await type(fixture, 'cost-available', '2026-11-01');
    expect(draft().cost?.availableFrom).toBe('2026-11-01');
    await click(fixture, 'cost-maintenance-included');
    expect(draft().cost?.maintenanceIncluded).toBe(true);
    await click(fixture, 'house-floor-basement');
    expect(draft().floor).toBe(-2);
    expect((fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#house-floor')!.value).toBe('2');
    await type(fixture, 'house-floor', '-3');
    expect(draft().floor).toBe(-3);
    // The line under the cost fields computes from what was typed, once there is a rent.
    await type(fixture, 'house-price', '17000');
    expect((fixture.nativeElement as HTMLElement).querySelector('.cost-line')?.textContent).toContain('Monthly cost');
  });

  it('a value typed into any field search reads is found by the list search, the floor through the renderer', async () => {
    const { fixture } = await open({ ...SAVED, label: '' }, 'en');
    // Places across the zones of the golden set (docs/14 §7, not one city).
    for (const [id, value] of [['house-name', 'zqlabel Shillong'], ['house-address', 'zqaddress Kochi'], ['house-street', 'zqstreet MG Road'],
      ['house-locality', 'zqlocality Salt Lake'], ['house-notes', 'zqnotes near the ghat'], ['house-contact', 'zqcontactName Ruchi']]) {
      await type(fixture, id, value);
    }
    await type(fixture, 'house-floor', '4');
    const d = (fixture.componentInstance as unknown as { draft: () => HouseDto }).draft();
    const text = searchText(d).toLowerCase();
    for (const token of ['zqlabel', 'zqaddress', 'zqstreet', 'zqlocality', 'zqnotes', 'zqcontactname', 'floor 4']) expect(text, token).toContain(token);
  });
});

interface FieldVector {
  id: string;
  section: string;
  web: { id: string; label: string; suffix: string | null; input: Record<string, string>; switch?: { id: string; label: string } };
}
interface RowVector { stack: string; section: string; cadence: string | null; rows: string[][] }
const KIND = (fieldVectors as unknown as { kinds: { fields: FieldVector[]; rows: RowVector[] }[] }).kinds[0];
const PRICE_TYPE: Record<string, HouseDto['priceType']> = { month: 'RENT', once: 'SALE' };

describe('docs/schemas/kinds/kind-field-vectors.json describes this form (the website half)', () => {
  it('each extra: its element, input attributes and label in the four languages', async () => {
    for (const lang of LANGS) {
      const { host } = await open({ ...SAVED }, lang);
      const t = TestBed.inject(TranslationService);
      for (const f of KIND.fields) {
        const el = host.querySelector(`#${f.web.id}`);
        expect(el, f.id).not.toBeNull();
        for (const [name, value] of Object.entries(f.web.input)) expect(el!.getAttribute(name), `${f.id} ${name}`).toBe(value);
        const own = ['type', 'min', 'max', 'step', 'inputmode', 'role'].filter((a) => el!.hasAttribute(a));
        expect(own.sort(), f.id).toEqual(Object.keys(f.web.input).sort());
        expect(text(host.querySelector(`label[for="${f.web.id}"]`)), `${lang} ${f.id}`).toBe(t.t(f.web.label as never) + (f.web.suffix ?? ''));
        expect(el!.closest('section')!.getAttribute('aria-labelledby'), f.id).toBe(`${f.section}-heading`);
        if (f.web.switch) {
          expect(text(host.querySelector(`label[for="${f.web.switch.id}"]`))).toBe(t.t(f.web.switch.label as never));
        }
      }
      TestBed.resetTestingModule();
    }
  });

  for (const v of KIND.rows.filter((r) => r.stack === 'web')) {
    it(`the ${v.section} extras for ${v.cadence ?? 'no'} cadence: in these rows, in this order`, async () => {
      const { host } = await open({ ...SAVED, priceType: v.cadence ? PRICE_TYPE[v.cadence] : null }, 'en');
      const ids = KIND.fields.filter((f) => f.section === v.section);
      const shown = ids.filter((f) => host.querySelector(`#${f.web.id}`));
      // Side by side: the fields of a row share one `.row`; a row of one is not in one, or alone in its `.row` of extras.
      const rows: string[][] = [];
      let last: Element | null = null;
      for (const f of shown) {
        const field = host.querySelector(`#${f.web.id}`)!.closest('.field')!;
        const row = field.parentElement!.classList.contains('row') ? field.parentElement : null;
        if (row && row === last) rows[rows.length - 1].push(f.id);
        else rows.push([f.id]);
        last = row;
      }
      expect(rows).toEqual(v.rows);
      // Page order is the kind file's order.
      const all = [...host.querySelectorAll('input, select, textarea')].map((e) => e.id);
      expect(shown.map((f) => all.indexOf(f.web.id))).toEqual([...shown.map((f) => all.indexOf(f.web.id))].sort((a, b) => a - b));
    });
  }
});
