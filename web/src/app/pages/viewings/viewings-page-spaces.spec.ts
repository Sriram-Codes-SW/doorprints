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
import type { HouseDto } from '../../core/models';
import { SpaceService } from '../../core/space.service';
import { toolWord } from '../../core/space-tool-words';
import type { TKey } from '../../i18n/en';
import { TranslationService } from '../../i18n/translation.service';
import type { Viewing } from '../../shared/viewing';
import { ViewingsPage } from './viewings-page';

// Kinds PR 29 (docs/03 §18.23): the list of viewings is the space's: a school's visits in the school space, the house's
// viewings in the house space, a viewing whose candidate is gone in the default space; the page speaks the kind's words.
const NOW = Date.now();
const HOUR = 3_600_000;
const house = (id: string, label: string, kind?: string): HouseDto => ({
  id, label, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...(kind ? { kind } : {}),
});
const viewing = (id: string, houseId: string): Viewing => ({
  id, houseId, startsAt: NOW + HOUR, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60,
});
const HOUSES = [house('h1', 'Green View 2BHK'), house('s1', 'Pine Hill School', 'school')];
const VIEWINGS = [viewing('v_00000001', 'h1'), viewing('v_00000002', 's1'), viewing('v_00000003', 'gone')];

afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function render(kind: string, multi: boolean, lang: 'en' | 'hi' = 'en') {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [ViewingsPage],
    providers: [
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({}), queryParamMap: convertToParamMap({}) } } },
      {
        provide: SpaceService,
        useValue: { kind: signal(kind), multi: signal(multi), dflt: signal('house'), available: ['house', 'school'], kindWord: (k: TKey) => toolWord(kind, k) },
      },
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0), viewings: () => of(VIEWINGS), houses: () => of(HOUSES), viewingsRemind: () => of(true),
          setViewingsRemind: vi.fn(() => of(undefined)), markViewingDone: vi.fn(), saveViewing: vi.fn(),
        },
      },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(ViewingsPage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const rows = (host: HTMLElement) => [...host.querySelectorAll('li')].map((li) => li.textContent ?? '');

describe('ViewingsPage in a space', () => {
  it('lists a school’s visits in the school space, in its words, and not the house’s viewing', async () => {
    const host = await render('school', true);
    expect(host.querySelector('h1')?.textContent).toBe('School visits');
    expect(host.textContent).toContain('When you plan to visit a school or go to an open day');
    expect([...host.querySelectorAll('a.btn')].map((a) => a.textContent?.trim())).toContain('Plan a visit');
    const text = rows(host).join('|');
    expect(text).toContain('Pine Hill School');
    expect(text).not.toContain('Green View');
  });

  it('lists the house’s viewings, and one whose candidate is gone, in the house space', async () => {
    const host = await render('house', true);
    expect(host.querySelector('h1')?.textContent).toBe('Viewings');
    const text = rows(host).join('|');
    expect(text).toContain('Green View 2BHK');
    expect(text).toContain('A house that is gone');
    expect(text).not.toContain('Pine Hill');
  });

  it('lists everything when there is one space only, as the page always did', async () => {
    const host = await render('house', false);
    const text = rows(host).join('|');
    expect(['Green View 2BHK', 'Pine Hill School', 'A house that is gone'].every((x) => text.includes(x))).toBe(true);
  });

  it('names the school’s empty list in the school’s words, in Hindi too', async () => {
    TestBed.resetTestingModule();
    const host = await render('school', true, 'hi');
    expect(host.querySelector('h1')?.textContent).toBe('स्कूल का दौरा');
  });
});
