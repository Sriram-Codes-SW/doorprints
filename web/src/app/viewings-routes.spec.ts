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

import { TestBed } from '@angular/core/testing';
import { Router, TitleStrategy, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { firstValueFrom } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { routes } from './app.routes';
import { LocalDataService } from './core/local-data.service';
import type { HouseDto } from './core/models';
import { I18nTitleStrategy } from './i18n/i18n-title.strategy';
import { TranslationService } from './i18n/translation.service';
import type { Viewing } from './shared/viewing';

/**
 * The website's `/viewings` routes (slice 3b-1; tested since S4b-BL-103): the real route table, title strategy and
 * pages over this browser's (in-memory) store. `/viewings` is the history, `/viewings/new` the form for a new viewing
 * (never read as a viewing called "new"), `/viewings/:id` the stored viewing, and an unknown id says it is not here.
 * A row of the history opens its viewing; each route sets its translated page title.
 */
const HOUSE: HouseDto = {
  id: 'h1', label: 'Green View 2BHK', lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0,
};
const VIEWING: Viewing = {
  id: 'v_a1b2c3d4', houseId: 'h1', startsAt: Date.now() + 5 * 3_600_000, durationMin: 45, kind: 'SECOND',
  status: 'PLANNED', remindMin: 30, withWhom: 'Meena Iyer',
};

afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function start(lang: 'en' | 'ta' = 'en', seed = true) {
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), { provide: TitleStrategy, useClass: I18nTitleStrategy }],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  if (seed) {
    const api = TestBed.inject(LocalDataService);
    await firstValueFrom(api.saveHouse(HOUSE));
    await firstValueFrom(api.saveViewing(VIEWING));
  }
  return RouterTestingHarness.create();
}

/** Lets the page's untracked loading promises run until `ready` holds (or fails after a bounded number of turns). */
async function until(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let i = 0; i < 50 && !ready(); i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    harness.detectChanges();
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

const page = (harness: RouterTestingHarness) => harness.routeNativeElement as HTMLElement;
const heading = (harness: RouterTestingHarness) => page(harness).querySelector('h1')?.textContent?.trim();

describe('website routes: /viewings', () => {
  it('/viewings shows the history, with its title, and lists the stored viewing', async () => {
    const harness = await start();
    await harness.navigateByUrl('/viewings');
    expect(page(harness).tagName).toBe('APP-VIEWINGS-PAGE');
    await until(harness, () => page(harness).querySelector('a.row-link') !== null);
    expect(heading(harness)).toBe('Viewings');
    expect(document.title).toBe('Viewings · Doorprints');
    expect(page(harness).querySelector('a.row-link')?.getAttribute('href')).toBe('/viewings/v_a1b2c3d4');
  });

  it('a row of the history opens its viewing at /viewings/:id', async () => {
    const harness = await start();
    await harness.navigateByUrl('/viewings');
    await until(harness, () => page(harness).querySelector('a.row-link') !== null);
    (page(harness).querySelector('a.row-link') as HTMLAnchorElement).click();
    await until(harness, () => page(harness).tagName === 'APP-VIEWING-PAGE');
    expect(TestBed.inject(Router).url).toBe('/viewings/v_a1b2c3d4');
    await until(harness, () => heading(harness) === 'Viewing');
  });

  it('/viewings/new is the form for a new viewing, not a viewing called "new"', async () => {
    const harness = await start();
    await harness.navigateByUrl('/viewings/new');
    expect(page(harness).tagName).toBe('APP-VIEWING-PAGE');
    await until(harness, () => page(harness).querySelector('#viewing-house') !== null);
    expect(heading(harness)).toBe('Plan a viewing');
    expect(page(harness).textContent).not.toContain('This viewing is not in this browser.');
    expect(document.title).toBe('Viewing · Doorprints');
  });

  it('/viewings/new?houseId= starts the form on that house', async () => {
    const harness = await start();
    await harness.navigateByUrl('/viewings/new?houseId=h1&kind=SECOND');
    await until(harness, () => (page(harness).querySelector('#viewing-house') as HTMLSelectElement | null)?.value === 'h1');
    expect((page(harness).querySelector('#viewing-kind') as HTMLSelectElement).value).toBe('SECOND');
  });

  it('/viewings/:id opens the stored viewing with its fields', async () => {
    const harness = await start();
    await harness.navigateByUrl('/viewings/v_a1b2c3d4');
    expect(page(harness).tagName).toBe('APP-VIEWING-PAGE');
    await until(harness, () => (page(harness).querySelector('#viewing-whom') as HTMLInputElement | null)?.value === 'Meena Iyer');
    expect(heading(harness)).toBe('Viewing');
    expect((page(harness).querySelector('#viewing-house') as HTMLSelectElement).value).toBe('h1');
    expect((page(harness).querySelector('#viewing-duration') as HTMLSelectElement).selectedOptions[0]?.textContent).toContain('45');
  });

  it('/viewings/:id of a viewing that is not in this browser says so', async () => {
    const harness = await start('en', false);
    await harness.navigateByUrl('/viewings/v_0f0f0f0f');
    await until(harness, () => page(harness).textContent?.includes('This viewing is not in this browser.') === true);
    expect(page(harness).querySelector('#viewing-house')).toBeNull();
  });

  it('sets the page titles in the chosen language (Tamil)', async () => {
    const harness = await start('ta', false);
    await harness.navigateByUrl('/viewings');
    expect(document.title).toBe('வீடு பார்வையிடல் · Doorprints');
    await harness.navigateByUrl('/viewings/new');
    expect(document.title).toBe('வீடு பார்வையிடல் · Doorprints');
    expect(page(harness).tagName).toBe('APP-VIEWING-PAGE');
  });
});
