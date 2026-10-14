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
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import type { AskResponse } from '../../core/ai.service';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { AskPage } from './ask-page';

/**
 * The house's Ask page, pinned before kinds PR B (S4b-BL-279; docs/03 §18.12): its words, its filters, the call it makes
 * and the links of its answer are unchanged with only houses. Written first, green on `main`, kept green by the change
 * (docs/06 TC-U-216).
 */
const ANSWER: AskResponse = {
  answer: 'Quiet one is [house:11111111-1111-1111-1111-111111111111].',
  citations: [{ houseId: '11111111-1111-1111-1111-111111111111', label: 'Green View', snippet: 'Quiet street' }],
  grounded: true,
  retrieved: 2,
};

async function open(lang: Lang) {
  TestBed.resetTestingModule();
  const ask = vi.fn(() => of(ANSWER));
  TestBed.configureTestingModule({
    imports: [AskPage],
    providers: [
      provideRouter([]),
      { provide: AiService, useValue: { enabled: signal(true), enabledFor: () => true, offReason: signal(null), usesOwnKey: signal(false), ownHost: signal(''), ask } },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(AskPage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, ask, host: fixture.nativeElement as HTMLElement };
}

const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('the house’s Ask page, pinned (S4b-BL-279)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('English: the heading, the intro, the question, the three filters and the disclosure', async () => {
    const { host } = await open('en');
    expect(text(host.querySelector('h1'))).toBe('Ask about your houses');
    expect(text(host.querySelector('.page-head p'))).toBe('Answers use only the houses, notes and visits you saved, and link to the houses they come from.');
    expect(host.querySelector('textarea')!.getAttribute('placeholder')).toBe('e.g. Which house had the best water supply?');
    expect([...host.querySelectorAll('.filter-grid label')].map(text)).toEqual(['Status', 'Maximum price (₹)', 'Minimum BHK']);
    expect(text(host.querySelector('#ask-submit'))).toBe('Ask');
  });

  it('Tamil: the heading and the filters', async () => {
    const { host } = await open('ta');
    expect(text(host.querySelector('h1'))).toBe('உங்கள் வீடுகளைப் பற்றிக் கேளுங்கள்');
    expect([...host.querySelectorAll('.filter-grid label')].length).toBe(3);
  });

  it('asks with the question and no filters, and links the answer and its sources to /houses/<id>', async () => {
    const { fixture, ask, host } = await open('en');
    const page = fixture.componentInstance as unknown as { question: string; submit(): void };
    page.question = 'Which is quiet?';
    page.submit();
    fixture.detectChanges();
    expect(ask).toHaveBeenCalledTimes(1);
    expect(ask.mock.calls[0]).toEqual(['Which is quiet?', undefined]);
    expect([...host.querySelectorAll('.answer a')].map((a) => a.getAttribute('href'))).toEqual([
      '/houses/11111111-1111-1111-1111-111111111111',
      '/houses/11111111-1111-1111-1111-111111111111',
    ]);
  });
});
