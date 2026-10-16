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

import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { ChooserPage, HINT_KEY, spaceCards } from './chooser-page';

@Component({ template: '' })
class Blank {}

const at = (iso: string) => ({ createdAt: iso, updatedAt: iso });
const H1: HouseDto = { ...newHouse(13, 80), id: 'h1', label: 'Green View', ...at('2026-10-01T08:00:00.000Z') };
const H2: HouseDto = { ...newHouse(13, 80), id: 'h2', label: 'Lake View', ...at('2026-10-05T08:00:00.000Z') };
const S1: HouseDto = { ...newHouse(26, 91), id: 's1', kind: 'school', label: 'Pine Hill School', ...at('2026-10-03T08:00:00.000Z') };
const GONE: HouseDto = { ...newHouse(13, 80), id: 'g', label: 'Gone', deleted: true, ...at('2026-10-09T08:00:00.000Z') };

/**
 * The chooser (kinds PR 8, S4b-BL-212; docs/03 §18.12; docs/06 TC-U-214): its heading and one button per enabled kind,
 * the default first and marked, each with its count and last change in its accessible name, in en, hi, ta and te; a
 * kind with no entries says so; choosing one opens its space, and Back comes back to the chooser.
 */
const WORDS: Record<Lang, { heading: string; cards: string[]; names: string[]; hint: string }> = {
  en: { heading: 'What are you looking for?', cards: ['SchoolsDefaultSaved: 1', 'HousesSaved: 2'], names: ['Schools Default Saved: 1 ', 'Houses Saved: 2 '], hint: 'Tap a card to switch. Your houses are unchanged.' },
  hi: { heading: 'आप क्या ढूँढ रहे हैं?', cards: ['स्कूलडिफ़ॉल्टसहेजे गए: 1', 'मकानसहेजे गए: 2'], names: ['स्कूल डिफ़ॉल्ट सहेजे गए: 1 ', 'मकान सहेजे गए: 2 '], hint: 'बदलने के लिए किसी कार्ड पर टैप करें। आपके मकान वैसे ही रहेंगे।' },
  ta: { heading: 'நீங்கள் எதைத் தேடுகிறீர்கள்?', cards: ['பள்ளிகள்இயல்புநிலைசேமித்தவை: 1', 'வீடுகள்சேமித்தவை: 2'], names: ['பள்ளிகள் இயல்புநிலை சேமித்தவை: 1 ', 'வீடுகள் சேமித்தவை: 2 '], hint: 'மாற ஒரு அட்டையைத் தட்டவும். உங்கள் வீடுகள் மாறாது.' },
  te: { heading: 'మీరు ఏమి వెతుకుతున్నారు?', cards: ['పాఠశాలలుడిఫాల్ట్సేవ్ చేసినవి: 1', 'ఇళ్లుసేవ్ చేసినవి: 2'], names: ['పాఠశాలలు డిఫాల్ట్ సేవ్ చేసినవి: 1 ', 'ఇళ్లు సేవ్ చేసినవి: 2 '], hint: 'మారడానికి ఒక కార్డ్‌ను నొక్కండి. మీ ఇళ్లు మారవు.' },
};

async function open(lang: Lang, houses: HouseDto[] = [H1, H2, S1, GONE]) {
  localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, 'school');
  TestBed.configureTestingModule({
    imports: [ChooserPage],
    providers: [
      provideLocationMocks(),
      provideRouter([{ path: '', component: Blank }, { path: 'k/house', component: Blank }, { path: 'k/school', component: Blank }, { path: 'data', component: Blank }]),
      { provide: LocalDataService, useValue: { settled: signal(0), houses: () => of(houses) } },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  TestBed.inject(Router).initialNavigation();
  await TestBed.inject(Router).navigateByUrl('/');
  const fixture = TestBed.createComponent(ChooserPage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}
const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

afterEach(() => {
  localStorage.clear();
  TestBed.resetTestingModule();
});

describe('the chooser', () => {
  for (const lang of ['en', 'hi', 'ta', 'te'] as const) {
    it(`in ${lang}: the heading, a button per enabled kind, the default first, the counts in each name`, async () => {
      const { host } = await open(lang);
      const h1 = host.querySelectorAll('h1');
      expect([h1.length, text(h1[0]), h1[0].getAttribute('tabindex')]).toEqual([1, WORDS[lang].heading, '-1']);
      const buttons = [...host.querySelectorAll<HTMLButtonElement>('button.space-card')];
      expect(buttons.map((b) => b.id)).toEqual(['space-school', 'space-house']);
      expect(buttons.map((b) => b.type)).toEqual(['button', 'button']);
      const parts = (b: Element) => [b.querySelector('.name'), b.querySelector('.badge'), b.querySelector('.meta')].filter((x) => !!x).map((x) => x!.textContent!.trim()).join('');
      expect(buttons.map(parts)).toEqual(WORDS[lang].cards);
      // The visible words are the accessible name (no aria-label): the count and the last change are in it.
      expect(buttons.map((b, i) => text(b).slice(0, WORDS[lang].names[i].length))).toEqual(WORDS[lang].names);
      expect(buttons.every((b) => !b.hasAttribute('aria-label') && /2026/.test(text(b)))).toBe(true);
      expect(text(host.querySelector('.hint span'))).toBe(WORDS[lang].hint);
    });
  }

  it('a kind with no entries says so; deleted entries do not count', async () => {
    const { host } = await open('en', [H1, GONE]);
    expect(text(host.querySelector('#space-school .meta'))).toBe('Nothing saved yet');
    expect(text(host.querySelector('#space-school'))).toBe('Schools Default Nothing saved yet');
    expect(text(host.querySelector('#space-house'))).toContain('Saved: 1');
  });

  it('choosing a kind opens its space, and Back comes back to the chooser', async () => {
    const { fixture, host } = await open('en');
    host.querySelector<HTMLButtonElement>('#space-house')!.click();
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/k/house');
    TestBed.inject(Location).back();
    await new Promise((r) => setTimeout(r, 0));
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/');
  });

  it('a kind turned off that still has entries is listed below, muted, and still opens; the cards are tallied by space', () => {
    const cards = spaceCards([H1, S1, { ...S1, id: 's2', kind: 'clinic' }], ['house'], 'house', ['house', 'school'], (id) => id);
    expect(cards.shown.map((c) => [c.id, c.count, c.isDefault])).toEqual([['house', 2, true]]);
    expect(cards.hidden.map((c) => [c.id, c.count])).toEqual([['school', 1]]);
    expect(cards.shown[0].updated).toBe(Date.parse('2026-10-03T08:00:00.000Z'));
  });

  it('a link to Settings > Kinds', async () => {
    const { host } = await open('en');
    const a = host.querySelector<HTMLAnchorElement>('.manage a')!;
    expect([text(a), a.getAttribute('href')]).toEqual(['Choose what you track in Your data', '/data#kinds-heading']);
  });

  it('shows its one hint until it is dismissed, then never again in this browser', async () => {
    const first = await open('en');
    first.host.querySelector<HTMLButtonElement>('.hint button')!.click();
    first.fixture.detectChanges();
    expect([first.host.querySelector('.hint'), localStorage.getItem(HINT_KEY)]).toEqual([null, 'done']);
    TestBed.resetTestingModule();
    localStorage.setItem(HINT_KEY, 'done');
    const again = await open('en');
    expect(again.host.querySelector('.hint')).toBeNull();
  });
});
