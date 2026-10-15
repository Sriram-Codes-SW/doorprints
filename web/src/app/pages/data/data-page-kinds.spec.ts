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
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY, defaultKind, enabledKinds } from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { DataPage } from './data-page';
import { KindsSettings } from './kinds-settings';

const school = { id: 's1', kind: 'school', label: 'Pine Hill School', lat: 26, lon: 91, status: 'NEW', deleted: false, updatedAt: '2026-10-01T00:00:00.000Z' } as unknown as HouseDto;
const house = { id: 'h1', label: 'Green View', lat: 26, lon: 91, status: 'NEW', deleted: false, updatedAt: '2026-10-02T00:00:00.000Z' } as unknown as HouseDto;

/**
 * Settings > Kinds (kinds PR 8, S4b-BL-212; docs/03 §18.12) in a build that ships the school (a development build): the
 * switch *Experimental: other kinds*, off by default; on, one row per offered kind with *Show*, *Default* and its count;
 * the last shown kind cannot be turned off; the router gets the spaces at once. A production build has no card at all
 * (`a11y-sweep`, the layout gate and the live UI test hold it). docs/06 TC-U-214.
 */
describe('Your data: What you track (Settings > Kinds)', () => {
  async function open(lang: Lang = 'en') {
    TestBed.configureTestingModule({
      imports: [KindsSettings],
      providers: [provideRouter(routes), { provide: LocalDataService, useValue: { houses: () => of([house, school]) } }],
    });
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(KindsSettings);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }
  const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const sw = (host: HTMLElement) => host.querySelector<HTMLInputElement>('#kinds-experimental')!;
  const show = (host: HTMLElement, id: string) => host.querySelector<HTMLInputElement>(`#kind-on-${id}`);
  const dflt = (host: HTMLElement, id: string) => host.querySelector<HTMLInputElement>(`#kind-default-${id}`);
  const click = async (f: { detectChanges(): void }, el: HTMLInputElement | null) => { el!.click(); f.detectChanges(); };

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('is off by default: the heading, the switch with its label and hint, and no rows', async () => {
    const { host } = await open();
    expect(text(host.querySelector('#kinds-heading'))).toBe('What you track');
    expect(sw(host).checked).toBe(false);
    expect(text(host.querySelector('label[for="kinds-experimental"]'))).toBe('Experimental: more than houses');
    expect(sw(host).getAttribute('aria-describedby')).toBe('kinds-experimental-hint');
    expect(text(host.querySelector('#kinds-experimental-hint'))).toContain('Off by default');
    expect(host.querySelector('#kind-rows')).toBeNull();
  });

  it('on: a row per kind with its count, House shown and the default; School shown makes two spaces and the router gets them', async () => {
    const { fixture, host } = await open();
    await click(fixture, sw(host));
    expect(localStorage.getItem(EXPERIMENTAL_KINDS_KEY)).toBe('1');
    expect([...host.querySelectorAll('#kind-rows .name')].map((n) => text(n))).toEqual(['Houses Saved: 1', 'Schools Saved: 1']);
    expect([show(host, 'house')!.checked, show(host, 'house')!.disabled, show(host, 'school')!.checked]).toEqual([true, true, false]);
    expect([dflt(host, 'house')!.checked, dflt(host, 'school')!.disabled]).toEqual([true, true]);
    expect(text(host.querySelector('label[for="kind-on-school"]'))).toBe('Show Schools');
    expect(dflt(host, 'school')!.getAttribute('aria-label')).toBe('Make Schools the default');
    await click(fixture, show(host, 'school'));
    expect([enabledKinds(), show(host, 'house')!.disabled, dflt(host, 'school')!.disabled]).toEqual([['house', 'school'], false, false]);
    expect(TestBed.inject(Router).config.some((r) => r.path === 'k/school')).toBe(true);
    await click(fixture, dflt(host, 'school'));
    expect([defaultKind(), localStorage.getItem(DEFAULT_KIND_KEY)]).toEqual(['school', 'school']);
    // Turning the default off picks the next shown kind; the last shown stays on.
    await click(fixture, show(host, 'school'));
    expect([enabledKinds(), defaultKind(), show(host, 'house')!.disabled]).toEqual([['house'], 'house', true]);
    expect(TestBed.inject(Router).config).toEqual(routes);
    expect(localStorage.getItem(ENABLED_KINDS_KEY)).toBeNull();
  });

  it('switched off again, the rows go and the school is no longer offered; its stored choice is kept for later', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
    const { fixture, host } = await open();
    expect(show(host, 'school')!.checked).toBe(true);
    await click(fixture, sw(host));
    expect([host.querySelector('#kind-rows'), enabledKinds(), localStorage.getItem(ENABLED_KINDS_KEY)]).toEqual([null, ['house'], '["house","school"]']);
  });

  it('the school the default and the house off: the house row stays, off, with its kept entries counted; turning it on again keeps the school the default (docs/03 §18.18)', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    localStorage.setItem(ENABLED_KINDS_KEY, '["school"]');
    localStorage.setItem(DEFAULT_KIND_KEY, 'school');
    const { fixture, host } = await open();
    expect([...host.querySelectorAll('#kind-rows .name')].map((n) => text(n))).toEqual(['Houses Saved: 1', 'Schools Saved: 1']);
    expect([show(host, 'house')!.checked, show(host, 'school')!.checked, show(host, 'school')!.disabled]).toEqual([false, true, true]);
    expect([dflt(host, 'school')!.checked, dflt(host, 'house')!.disabled]).toEqual([true, true]);
    await click(fixture, show(host, 'house'));
    expect([enabledKinds(), defaultKind()]).toEqual([['house', 'school'], 'school']);
    expect(TestBed.inject(Router).config.some((r) => r.path === 'k/house')).toBe(true);
  });

  it('sits on Your data before Remove all data', async () => {
    TestBed.configureTestingModule({ imports: [DataPage], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(DataPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const kinds = host.querySelector('#kinds-heading')!;
    const clear = host.querySelector('#clear-heading')!;
    expect(kinds.compareDocumentPosition(clear) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  for (const [lang, words] of [
    ['hi', ['आप क्या ट्रैक करते हैं', 'प्रायोगिक: मकानों के अलावा भी', 'स्कूल दिखाएँ']],
    ['ta', ['நீங்கள் கண்காணிப்பவை', 'சோதனை: வீடுகளைத் தவிர மற்றவை', 'பள்ளிகள் காட்டு']],
    ['te', ['మీరు ట్రాక్ చేసేవి', 'ప్రయోగాత్మకం: ఇళ్లు కాకుండా ఇంకా', 'పాఠశాలలు చూపించు']],
  ] as const) {
    it(`is in ${lang}: the heading, the switch and a row`, async () => {
      localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
      const { host } = await open(lang);
      expect([text(host.querySelector('#kinds-heading')), text(host.querySelector('label[for="kinds-experimental"]')), text(host.querySelector('label[for="kind-on-school"]'))])
        .toEqual(words);
    });
  }
});
