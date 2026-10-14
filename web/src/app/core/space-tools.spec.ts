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
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../app';
import { DataPage } from '../pages/data/data-page';
import { SharePage } from '../pages/share/share-page';
import { TranslationService } from '../i18n/translation.service';
import { OnDeviceAiService } from './ai/on-device-ai.service';
import { LocalStore } from '../data/local-store.service';
import { AiService } from './ai.service';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from './kind-setting';
import { newHouse } from './models';
import type { HouseDto } from './models';

/**
 * The tools in a school space (kinds PR B, S4b-BL-279; docs/03 §18.13, docs/06 TC-U-216): the shell’s navigation, the
 * assistant reading one space, the Data page’s words and the share target’s choice of what the listing is.
 */
@Component({ template: '' })
class Blank {}

function twoKinds(dflt = 'house'): void {
  localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, dflt);
}
const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  TestBed.resetTestingModule();
});

describe('the shell’s navigation in a school space', () => {
  async function nav(url: string, shown: (k: string) => boolean): Promise<string[]> {
    twoKinds();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: 'k/:kind', component: Blank }, { path: 'compare', component: Blank }, { path: '**', component: Blank }]),
        { provide: AiService, useValue: { enabled: signal(true), enabledFor: shown, serverEnabled: signal(true), usesOwnKey: signal(true), refresh: () => undefined, offReason: signal(null) } },
      ],
    });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl(url);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('nav.nav a')].map((a) => `${a.getAttribute('href')} ${text(a)}`);
  }

  it('shows Compare, Ask and Plan on the space’s own addresses when the assistant reads it', async () => {
    expect(await nav('/k/school', () => true)).toEqual(['/k/school Map', '/k/school/compare Compare', '/k/school/ask Ask', '/k/school/plan Plan', '/data Your data', '/connect Connect']);
  });

  it('shows Compare alone when the assistant does not read the space (the server’s, or a build without it)', async () => {
    expect(await nav('/k/school', (k) => k === 'house')).toEqual(['/k/school Map', '/k/school/compare Compare', '/data Your data', '/connect Connect']);
  });

  it('the house space keeps /compare, /ask and /plan', async () => {
    expect(await nav('/k/house', () => true)).toEqual(['/k/house Map', '/compare Compare', '/ask Ask', '/plan Plan', '/data Your data', '/connect Connect']);
  });
});

describe('the assistant reads one space (on this device)', () => {
  const school: HouseDto = { ...newHouse(13, 80), id: 's1', kind: 'school', label: 'Pine Hill School', locality: 'Adyar', updatedAt: '2026-10-01T00:00:00.000Z', extrasOther: { board: 'CBSE' } };
  const house: HouseDto = { ...newHouse(13.1, 80.1), id: 'h1', label: 'Green View', locality: 'Adyar', updatedAt: '2026-10-02T00:00:00.000Z', price: 20000, priceType: 'RENT' };

  async function ask(kind?: string): Promise<string> {
    TestBed.resetTestingModule();
    twoKinds();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: LocalStore,
          useValue: {
            allVisits: () => Promise.resolve([]),
            allHouses: () => Promise.resolve([school, house]),
            viewings: { all: () => Promise.resolve([]) },
            areas: { all: () => Promise.resolve([]) },
            places: { all: () => Promise.resolve([]) },
            areaNotes: { rows: () => Promise.resolve([]) },
          },
        },
      ],
    });
    const prompts: string[] = [];
    const model = { generateJson: vi.fn((_s: string, user: string) => { prompts.push(user); return Promise.resolve('{"answer":"ok"}'); }), ping: () => Promise.resolve() };
    await TestBed.inject(OnDeviceAiService).ask(model, 'Which is in Adyar?', undefined, kind);
    return prompts.join('\n');
  }

  it('a school space’s Ask shows the model the schools only', async () => {
    const p = await ask('school');
    expect(p).toContain('Pine Hill School');
    expect(p).not.toContain('Green View');
  });

  it('the house space’s Ask shows the houses only, and no space shows both, as before', async () => {
    const h = await ask('house');
    expect(h).toContain('Green View');
    expect(h).not.toContain('Pine Hill School');
    const all = await ask();
    expect(all).toContain('Green View');
    expect(all).toContain('Pine Hill School');
  });
});

describe('the Data page in a school space', () => {
  async function open(url: string): Promise<HTMLElement> {
    twoKinds();
    TestBed.configureTestingModule({ imports: [DataPage], providers: [provideRouter([{ path: '**', component: Blank }])] });
    await TestBed.inject(TranslationService).setLang('en');
    await TestBed.inject(Router).navigateByUrl(url);
    const fixture = TestBed.createComponent(DataPage);
    for (let i = 0; i < 6; i++) {
      fixture.detectChanges();
      await fixture.whenStable();
      await new Promise((r) => setTimeout(r, 0));
    }
    return fixture.nativeElement as HTMLElement;
  }

  it('says there is no school to save, and links to the school map', async () => {
    const host = await open('/k/school');
    const empty = host.querySelector('.empty-state')!;
    expect(text(empty.querySelector('p'))).toBe('There is nothing to save yet. Add a school first.');
    expect(empty.querySelector('a')!.getAttribute('href')).toBe('/k/school?add=1');
  });

  it('keeps the house’s words in the house space', async () => {
    const host = await open('/k/house');
    expect(text(host.querySelector('.empty-state p'))).toBe('There is nothing to save yet. Add a house first.');
  });
});

describe('the share target in a build with two kinds', () => {
  async function open() {
    twoKinds('school');
    TestBed.configureTestingModule({ imports: [SharePage], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang('en');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(SharePage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, navigate, host: fixture.nativeElement as HTMLElement };
  }

  it('offers the kinds, the default chosen, and the words and the map follow the choice', async () => {
    const { fixture, navigate, host } = await open();
    const select = host.querySelector<HTMLSelectElement>('#share-kind')!;
    expect([...select.options].map((o) => text(o))).toEqual(['Houses', 'Schools']);
    expect(select.value).toBe('school');
    expect(text(host.querySelector('.actions .btn-primary'))).toBe('Add a school from this');
    const page = fixture.componentInstance as unknown as { text: string; kind: string; createHouse(): void };
    page.text = 'Pine Hill, CBSE';
    page.createHouse();
    expect(navigate).toHaveBeenLastCalledWith(['/k/school'], { state: { shared: 'Pine Hill, CBSE', pickLocation: true }, replaceUrl: true });
    select.value = 'house';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(text(host.querySelector('.actions .btn-primary'))).toBe('Add a house from this');
    page.createHouse();
    expect(navigate).toHaveBeenLastCalledWith(['/k/house'], { state: { shared: 'Pine Hill, CBSE', pickLocation: true }, replaceUrl: true });
  });
});
