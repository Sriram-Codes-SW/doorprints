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
import { afterEach, describe, expect, it } from 'vitest';
import { App } from './app';
import { AiService } from './core/ai.service';
import { TranslationService } from './i18n/translation.service';
import type { Lang } from './i18n/languages';

/**
 * The shell's navigation with only houses, pinned before kinds PR B (S4b-BL-279; docs/03 §18.12): the same destinations,
 * in the same order, on the same addresses, with the assistant on or off. Written first, green on `main`, kept green by
 * the change (docs/06 TC-U-216).
 */
function fakeAi(on: boolean) {
  const enabled = signal(on);
  return {
    enabled,
    // The change asks which kinds the assistant reads; the house only, as the pin was first written.
    enabledFor: () => enabled(),
    serverEnabled: signal(on),
    usesOwnKey: signal(false),
    refresh: () => undefined,
    offReason: signal(on ? null : 'optOut'),
  };
}

async function nav(on: boolean, lang: Lang = 'en'): Promise<string[]> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([]), { provide: AiService, useValue: fakeAi(on) }] });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  const links = [...(fixture.nativeElement as HTMLElement).querySelectorAll('nav.nav a')];
  return links.map((a) => `${a.getAttribute('href')} ${a.textContent?.replace(/\s+/g, ' ').trim()}`);
}

describe('the shell’s navigation with only houses, pinned (S4b-BL-279)', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('with the assistant on: Map, Compare, Ask, Plan, Your data, Connect', async () => {
    expect(await nav(true)).toEqual(['/ Map', '/compare Compare', '/ask Ask', '/plan Plan', '/data Your data', '/connect Connect']);
  });

  it('with the assistant off: no Ask and no Plan', async () => {
    expect(await nav(false)).toEqual(['/ Map', '/compare Compare', '/data Your data', '/connect Connect']);
  });

  it('in Hindi the same six addresses', async () => {
    expect((await nav(true, 'hi')).map((l) => l.split(' ')[0])).toEqual(['/', '/compare', '/ask', '/plan', '/data', '/connect']);
  });
});
