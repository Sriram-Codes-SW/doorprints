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
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LocalDataService } from '../../core/local-data.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { SyncService } from '../../data/sync.service';
import { TranslationService } from '../../i18n/translation.service';
import { scoringOf } from '../../shared/scoring';
import type { CriterionRow } from '../../shared/scoring';
import { MapPage } from './map-page';

/**
 * The house list of the map page under the person's criteria (slice 2, docs/11 5.4): "Best score" is the ranking
 * (a house that misses a must-have comes last, whatever its score) and such a house shows a "Must-have missed" chip.
 * The map itself is not made (jsdom has no WebGL, and another spec of this suite replaces the maplibre-gl package, so
 * a test of the list cannot rely on either): `ngAfterViewInit` is stubbed and the list is what these tests read.
 */
const SECURITY_MUST: CriterionRow = {
  key: 'security',
  updatedAt: null,
  criterion: { key: 'security', weight: 2, mustHave: true, minScore: 4, sort: 6 },
};

const house = (id: string, label: string, over: Partial<HouseDto>): HouseDto => ({
  ...newHouse(13, 80),
  id,
  label,
  createdAt: '2026-09-01T00:00:00.000Z',
  updatedAt: '2026-09-01T00:00:00.000Z',
  ...over,
});

// Great score but misses the must-have; a plain one; a cheaper one with the same score as the plain one.
const MISSED = house('m', 'Missed it', { rating: 5, checklist: { security: 2, water: 5 } });
const PLAIN = house('p', 'Plain', { rating: 3, checklist: { security: 5 }, price: 30000 });
const CHEAP = house('c', 'Cheap', { rating: 3, checklist: { security: 5 }, price: 20000 });

afterEach(() => {
  vi.restoreAllMocks();
  TestBed.resetTestingModule();
  localStorage.clear();
});

/** The fixture of the last `render`, for a test that changes something on the page and reads it again. */
let lastFixture: ReturnType<typeof TestBed.createComponent<MapPage>> | null = null;

async function render(query: Record<string, string>, extra: Record<string, unknown> = {}) {
  vi.spyOn(MapPage.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
  TestBed.configureTestingModule({
    imports: [MapPage],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          houses: () => of([MISSED, PLAIN, CHEAP]),
          brokers: () => of([]),
          areas: () => of([]),
          areaNotes: () => of([]),
          ...extra,
          scoring: () => of(scoringOf([SECURITY_MUST], [])),
          stats: () => of({ houses: 3, shortlisted: 0, rejected: 0, visits: 0, streets: 0 }),
        },
      },
      { provide: SyncService, useValue: { migration: signal('done'), enabled: signal(false), downloadToThisBrowser: vi.fn() } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
    ],
  });
  TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(MapPage);
  lastFixture = fixture;
  fixture.detectChanges();
  await fixture.whenStable();
  // The page reads its data in effects and promise chains; let them run before the list is read.
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const rows = (host: HTMLElement) => [...host.querySelectorAll('ul.list li')].map((li) => li.textContent?.replace(/\s+/g, ' ').trim() ?? '');

describe('MapPage: the house list under the ranking (slice 2)', () => {
  it('sorts "Best score" by the ranking: a house that misses a must-have last, then score, then the lower price', async () => {
    const host = await render({ sort: 'score' });
    const list = rows(host);
    expect(list).toHaveLength(3);
    expect(list[0]).toContain('Cheap');
    expect(list[1]).toContain('Plain');
    expect(list[2]).toContain('Missed it');
  });

  it('shows the chip "Must-have missed" on a house that misses a must-have, and on no other', async () => {
    const host = await render({ sort: 'score' });
    const list = rows(host);
    expect(list[2]).toContain('Must-have missed');
    expect(list[0]).not.toContain('Must-have missed');
    expect(list[1]).not.toContain('Must-have missed');
  });
});

/** Slice 4a: the list search also reads the text of the area notes that reach a house (the shared test case of docs/06). */
describe('MapPage: searching the text of area notes (slice 4a)', () => {
  const inArea = house('a', 'By the park', { street: '5th Cross' });
  const onStreet = house('s', 'On the road', { street: 'MG Road', lat: 14, lon: 81 });
  const elsewhere = house('e', 'Far away', { street: 'Other Road', lat: 15, lon: 82 });
  const area = { id: 'a_00000001', name: 'Park', lat: 13, lon: 80, radiusM: 500, enabled: true };
  const notes = [
    { id: 'n_00000001', updatedAt: '2026-09-02T00:00:00.000Z', note: { id: 'n_00000001', areaId: 'a_00000001', text: 'Water tanker every morning' } },
    { id: 'n_00000002', updatedAt: '2026-09-03T00:00:00.000Z', note: { id: 'n_00000002', street: 'mg road ', text: 'Bus depot on the corner' } },
  ];
  const stub = { houses: () => of([inArea, onStreet, elsewhere]), areas: () => of([area]), areaNotes: () => of(notes) };

  it('finds the house an area note reaches, and the house a street note reaches, and no other', async () => {
    expect(rows(await render({ q: 'tanker' }, stub))).toHaveLength(1);
    TestBed.resetTestingModule();
    const byArea = rows(await render({ q: 'tanker' }, stub));
    expect(byArea[0]).toContain('By the park');
    TestBed.resetTestingModule();
    const byStreet = rows(await render({ q: 'bus depot' }, stub));
    expect(byStreet).toHaveLength(1);
    expect(byStreet[0]).toContain('On the road');
  });

  it('finds nothing by those words when there are no notes', async () => {
    expect(rows(await render({ q: 'tanker' }, { houses: stub.houses }))).toHaveLength(0);
  });
});

/** S4b-BL-84: the filters over the cost numbers, on top of the status and the search, read from and kept in the URL. */
describe('MapPage: the cost filters', () => {
  it('keeps only the houses whose monthly cost is in the range, and says how many ranges are set', async () => {
    const host = await render({ monthlyMax: '25000' });
    const list = rows(host);
    expect(list).toHaveLength(1);
    expect(list[0]).toContain('Cheap');
    expect(host.querySelector('.cost-filters summary')?.textContent?.trim()).toBe('Filter by cost (1)');
    expect(host.querySelector<HTMLInputElement>('#cost-monthly-max')!.value).toBe('25000');
  });

  it('narrows the list as an end is typed, names every field after its range, and clears', async () => {
    const host = await render({});
    expect(rows(host)).toHaveLength(3);
    expect(host.querySelector('label[for="cost-monthly-min"]')?.textContent?.trim()).toBe('Monthly cost from (₹)');
    expect(host.querySelector('label[for="cost-perSqFt-max"]')?.textContent?.trim()).toBe('Cost per sq ft up to (₹)');
    const min = host.querySelector<HTMLInputElement>('#cost-monthly-min')!;
    min.value = '25000';
    min.dispatchEvent(new Event('change'));
    lastFixture!.detectChanges();
    // Only Plain (₹30,000 a month): Cheap is below, and Missed it has no price, so no monthly cost.
    expect(rows(host)).toHaveLength(1);
    expect(rows(host)[0]).toContain('Plain');
    const clear = [...host.querySelectorAll<HTMLButtonElement>('.cost-filters button')].find((b) => b.textContent?.trim() === 'Clear cost filters')!;
    clear.click();
    lastFixture!.detectChanges();
    expect(rows(host)).toHaveLength(3);
    expect(clear.disabled).toBe(true);
  });
});

describe('MapPage: the panel shown when the map has no tiles', () => {
  type Panel = { mapAvailable: { set(v: boolean): void }; mapWorkerFailed: { set(v: boolean): void } };
  const retryButton = (host: HTMLElement) => [...host.querySelectorAll('.map-offline button')].find((b) => b.textContent?.trim() === 'Try again');

  it('offers Try again while offline: the map can try again when the connection is back', async () => {
    const host = await render({});
    (lastFixture!.componentInstance as unknown as Panel).mapAvailable.set(false);
    lastFixture!.detectChanges();
    expect(host.querySelector('.map-offline')?.textContent).toContain('The map needs an internet connection');
    expect(retryButton(host)).toBeTruthy();
  });

  it('says the map helper failed and offers no Try again, which could not bring it back', async () => {
    const host = await render({});
    const panel = lastFixture!.componentInstance as unknown as Panel;
    panel.mapAvailable.set(false);
    panel.mapWorkerFailed.set(true);
    lastFixture!.detectChanges();
    expect(host.querySelector('.map-offline')?.textContent).toContain('The map could not start its helper');
    expect(retryButton(host)).toBeUndefined();
  });
});
