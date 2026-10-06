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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LANGUAGES, Lang } from './languages';
import { DICTIONARIES } from './all-dictionaries';
import { TranslationService } from './translation.service';

const LANG_STORAGE_KEY = 'doorprints.lang';

/** Intl output in Node can use a (narrow) no-break space after the symbol depending on ICU version. */
const RUPEES_125000 = /^₹\s?1,25,000$/;

describe('TranslationService', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  function serviceIn(lang: Lang): TranslationService {
    const service = new TranslationService();
    service.setLang(lang);
    return service;
  }

  describe('language state', () => {
    it('starts with the saved language', () => {
      localStorage.setItem(LANG_STORAGE_KEY, 'ta');
      const service = new TranslationService();
      expect(service.lang()).toBe('ta');
      expect(service.locale()).toBe('ta-IN');
      expect(document.documentElement.lang).toBe('ta');
    });

    it('ignores an unsupported saved value', () => {
      localStorage.setItem(LANG_STORAGE_KEY, 'fr');
      const service = new TranslationService();
      expect(['en', 'hi', 'ta', 'te']).toContain(service.lang());
    });

    it('setLang switches locale, document lang and remembers the choice', () => {
      const service = new TranslationService();
      service.setLang('te');
      expect(service.lang()).toBe('te');
      expect(service.locale()).toBe('te-IN');
      expect(document.documentElement.lang).toBe('te');
      expect(localStorage.getItem(LANG_STORAGE_KEY)).toBe('te');
    });

    it('setLang ignores unknown languages', () => {
      const service = serviceIn('hi');
      service.setLang('fr' as Lang);
      expect(service.lang()).toBe('hi');
    });

    it('still switches language when storage is unavailable', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('QuotaExceededError');
      });
      const service = new TranslationService();
      service.setLang('hi');
      expect(service.lang()).toBe('hi');
    });
  });

  describe('t()', () => {
    it('returns the string of the current language', () => {
      for (const { code } of LANGUAGES) {
        expect(serviceIn(code).t('nav.map')).toBe(DICTIONARIES[code]['nav.map']);
      }
    });

    it('fills placeholders, formatting numbers for the locale', () => {
      const service = serviceIn('en');
      expect(service.t('common.bhk', { n: 3 })).toBe('3 BHK');
      expect(service.t('duration.hoursMinutes', { h: 1, m: 5 })).toBe('1 h 5 min');
      expect(service.t('price.perMonth', { price: 'X' })).toBe('X/month');
    });

    it('leaves unknown placeholders untouched', () => {
      expect(serviceIn('en').t('common.bhk', { other: 1 })).toBe('{n} BHK');
    });

    it('translates nested messages', () => {
      const service = serviceIn('en');
      expect(service.t('common.scoreValue', { score: { key: 'common.notSet' } })).toBe('Score Not set');
      expect(service.msg({ key: 'common.bhk', params: { n: 2 } })).toBe('2 BHK');
    });
  });

  describe('number()', () => {
    it.each(LANGUAGES.map((l): [Lang, string] => [l.code, l.locale]))('uses Indian digit grouping in %s', (code, locale) => {
      const service = serviceIn(code);
      expect(service.number(12345678)).toBe(new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(12345678));
      expect(service.number(12345678)).toBe('1,23,45,678');
    });

    it('respects the requested fraction digits', () => {
      const service = serviceIn('en');
      expect(service.number(4, 1)).toBe('4.0');
      expect(service.number(3.5, 1)).toBe('3.5');
      expect(service.number(2.75, 2)).toBe('2.75');
      expect(service.number(1234.4)).toBe('1,234');
    });

    it('follows language changes (formatter cache is per locale)', () => {
      const service = serviceIn('en');
      const before = service.number(1500.5, 1);
      service.setLang('hi');
      expect(service.number(1500.5, 1)).toBe(new Intl.NumberFormat('hi-IN', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(1500.5));
      expect(before).toBe('1,500.5');
    });
  });

  describe('price()', () => {
    it.each(LANGUAGES.map((l): [Lang, string] => [l.code, l.locale]))('formats rupees with lakh grouping in %s', (code, locale) => {
      const service = serviceIn(code);
      const expected = new Intl.NumberFormat(locale, { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(125000);
      expect(service.price(125000)).toBe(expected);
      expect(service.price(125000, 'SALE')).toBe(expected);
      expect(service.price(125000)).toMatch(RUPEES_125000);
    });

    it.each(LANGUAGES.map((l) => l.code))('adds the translated per-month suffix for rent in %s', (code) => {
      const service = serviceIn(code);
      const base = service.price(25000);
      expect(service.price(25000, 'RENT')).toBe(service.t('price.perMonth', { price: base }));
      expect(service.price(25000, 'RENT')).toContain(base);
    });

    it('rounds to whole rupees', () => {
      expect(serviceIn('en').price(999.6)).toMatch(/^₹\s?1,000$/);
    });

    it('shows an en dash when there is no price', () => {
      const service = serviceIn('en');
      expect(service.price(null)).toBe('–');
      expect(service.price(undefined, 'RENT')).toBe('–');
    });

    it('shows ₹0 for a price of zero', () => {
      expect(serviceIn('en').price(0)).toMatch(/^₹\s?0$/);
    });
  });

  describe('score()', () => {
    it('shows one decimal', () => {
      const service = serviceIn('en');
      expect(service.score(4)).toBe('4.0');
      expect(service.score(3.5)).toBe('3.5');
      expect(service.score(0)).toBe('0.0');
    });

    it('shows an en dash when nothing is scored', () => {
      const service = serviceIn('en');
      expect(service.score(null)).toBe('–');
      expect(service.score(undefined)).toBe('–');
    });
  });

  describe('dateOnly() and timeOnly() (no Angular locale data: the Drive backups table used the date pipe, which throws for hi, ta and te)', () => {
    const at = Date.UTC(2026, 9, 1, 10, 0, 0);

    it('are dashes for nothing', () => {
      const service = serviceIn('en');
      expect(service.dateOnly(null)).toBe('–');
      expect(service.timeOnly(undefined)).toBe('–');
      expect(service.dateOnly(Number.NaN)).toBe('–');
    });

    for (const lang of ['en', 'hi', 'ta', 'te'] as const) {
      it(`${lang}: a date and a time that are the Intl text of that language`, () => {
        const service = serviceIn(lang);
        const locale = service.locale();
        expect(service.dateOnly(at)).toBe(new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(at)));
        expect(service.timeOnly(at)).toBe(new Intl.DateTimeFormat(locale, { timeStyle: 'short' }).format(new Date(at)));
        expect(service.dateOnly(at)).not.toBe('–');
      });
    }

    it('differ between English and Hindi', () => {
      expect(serviceIn('hi').dateOnly(at)).not.toBe(serviceIn('en').dateOnly(at));
    });
  });

  describe('dateTime() and duration()', () => {
    it('returns an en dash for missing or invalid dates', () => {
      const service = serviceIn('en');
      expect(service.dateTime(null)).toBe('–');
      expect(service.dateTime('')).toBe('–');
      expect(service.dateTime('not a date')).toBe('–');
      expect(service.dateTime('2026-09-22T10:30:00Z')).not.toBe('–');
    });

    it('formats durations in minutes and hours', () => {
      const service = serviceIn('en');
      expect(service.duration('2026-09-22T10:00:00Z', '2026-09-22T10:45:00Z')).toBe('45 min');
      expect(service.duration('2026-09-22T10:00:00Z', '2026-09-22T11:05:00Z')).toBe('1 h 5 min');
      expect(service.duration('2026-09-22T10:00:00Z', null)).toBe('');
      expect(service.duration('2026-09-22T10:00:00Z', '2026-09-22T09:00:00Z')).toBe('');
    });
  });
});
