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

// Experimental off (owner decision 2026-10-15, docs/13 part J1): the production build lists the school (compiled in), and
// the person has not turned on Experimental: more than houses. Everything the person can reach is then the house's alone:
// no chooser, no spaces routes, no kind rows in Settings, and search and the AI read exactly what the house build reads.
// The unit tests run in Angular's development mode, where every built-in kind is available: the same availability the
// production build's DOORPRINTS_KINDS = house,school gives, so these cases hold for it (release-kinds.spec.ts pins the define).
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes, routesFor } from './app.routes';
import { aiKindOf } from './core/ai/kind-ai';
import { houseAiOutputs } from './core/ai/house-ai-outputs';
import { HOUSE_AI_SHA256 } from './core/ai/golden/house-ai-pins.golden';
import { HOUSE_FIELDS } from './core/kind-fields';
import { availableKinds, defaultKind, enabledKinds, experimentalKindsOn, kindsOn, offeredKinds, spacesOn, unreleasedKinds } from './core/kind-setting';
import { LocalDataService } from './core/local-data.service';
import type { HouseDto } from './core/models';
import { aiShownIn } from './core/space-scope';
import { sha256Hex } from './export/sha256';
import { utf8 } from './export/zip';
import { FIXTURE_HOUSES } from './export/golden/fixture';
import { TranslationService } from './i18n/translation.service';
import { KindsSettings } from './pages/data/kinds-settings';
import { searchText } from './pages/map/map-list';

const school = { id: 's1', kind: 'school', label: 'Pine Hill School', lat: 26, lon: 91, status: 'NEW', deleted: false, updatedAt: '2026-10-01T00:00:00.000Z' } as unknown as HouseDto;

describe('experimental off: a build that lists the school is the house app until the switch is turned on', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('lists the school, offers the house alone, and has no spaces', () => {
    expect(availableKinds()).toEqual(['house', 'school']);
    expect(unreleasedKinds()).toEqual(['school']);
    expect(experimentalKindsOn()).toBe(false);
    expect([offeredKinds(), enabledKinds()]).toEqual([['house'], ['house']]);
    expect([defaultKind(), spacesOn(), kindsOn()]).toEqual(['house', false, false]);
  });

  it('has no chooser and no spaces routes: the routes are the house app’s own', () => {
    const got = routesFor(spacesOn());
    expect(got).toBe(routes);
    const paths = got.map((r) => r.path ?? '');
    expect(paths.filter((p) => p.startsWith('k/'))).toEqual([]);
    expect(got.some((r) => r.title === 'title.chooser')).toBe(false);
  });

  it('shows Settings > What you track with the switch off and no kind rows', async () => {
    TestBed.configureTestingModule({
      imports: [KindsSettings],
      providers: [provideRouter(routes), { provide: LocalDataService, useValue: { houses: () => of([school]) } }],
    });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(KindsSettings);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector<HTMLInputElement>('#kinds-experimental')?.checked).toBe(false);
    expect(host.querySelector('#kind-rows')).toBeNull();
    expect(host.querySelector('[id^="kind-on-"]')).toBeNull();
  });

  it('searches a house as the house build does (no word of a kind, whatever the switch)', () => {
    for (const h of FIXTURE_HOUSES) expect(searchText(h)).toBe(searchText(h, '', [], undefined, undefined, HOUSE_FIELDS));
    expect(searchText({ ...school, extrasOther: { board: 'CBSE' } } as HouseDto).includes('cbse')).toBe(false);
  });

  it('shows the AI of the house alone, and reads every house exactly as the house build did (the golden pins)', () => {
    expect(aiKindOf('school', kindsOn())).toBeNull();
    for (const on of [false, true]) {
      for (const own of [false, true]) {
        expect(aiShownIn(defaultKind(), availableKinds(), on, own, ['school'])).toBe(aiShownIn('house', ['house'], on, own, []));
      }
    }
    const got: Record<string, string> = {};
    for (const [name, text] of Object.entries(houseAiOutputs())) got[name] = sha256Hex(utf8(text));
    expect(got).toEqual(HOUSE_AI_SHA256);
  });
});
