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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { en } from './en';
import { hi } from './hi';
import { ta } from './ta';
import { dictionary, forgetDictionaries, loadDictionary, loadedDictionaries, registerDictionaries } from './languages';
import { TranslationService } from './translation.service';

/**
 * Only English is in the first download; Hindi, Tamil and Telugu are loaded when chosen (languages.ts). This file
 * starts from English only and puts back what other spec files had loaded (in CI they share this module).
 */
describe('languages loaded on demand', () => {
  let before: ReturnType<typeof loadedDictionaries>;

  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
    before = loadedDictionaries();
    forgetDictionaries();
  });

  afterEach(() => {
    registerDictionaries(before);
  });

  it('has English at once and loads another language when asked', async () => {
    expect(dictionary('en')).toBe(en);
    expect(dictionary('te')).toBeUndefined();
    expect((await loadDictionary('te'))['nav.map']).toBeTruthy();
    expect(dictionary('te')).toBeDefined();
  });

  it('switches only once the language has arrived, so the page never mixes two', async () => {
    const i18n = TestBed.inject(TranslationService);
    const switching = i18n.setLang('hi');
    expect(i18n.lang()).toBe('en');
    expect(i18n.t('nav.map')).toBe(en['nav.map']);
    await switching;
    expect(i18n.lang()).toBe('hi');
    expect(i18n.t('nav.map')).toBe(hi['nav.map']);
    expect(document.documentElement.lang).toBe('hi');
    expect(localStorage.getItem('doorprints.lang')).toBe('hi');
  });

  it('a later choice wins over a slower earlier one', async () => {
    const i18n = TestBed.inject(TranslationService);
    const first = i18n.setLang('ta');
    const second = i18n.setLang('en');
    await Promise.all([first, second]);
    expect(i18n.lang()).toBe('en');
    expect(i18n.t('nav.map')).not.toBe(ta['nav.map']);
  });
});
