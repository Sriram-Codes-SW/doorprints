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

import { describe, expect, it } from 'vitest';
import { en } from './en';
import { LANGUAGES, Lang, isLang } from './languages';
import { DICTIONARIES } from './all-dictionaries';

/** Sorted list of `{name}` placeholders in a string (duplicates kept, so counts must match too). */
function placeholders(text: string): string[] {
  return [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
}

const enKeys = Object.keys(en).sort();
const others = (Object.keys(DICTIONARIES) as Lang[]).filter((l) => l !== 'en');

describe('translation dictionaries', () => {
  it('has a dictionary for every language in the switcher, and nothing else', () => {
    expect(Object.keys(DICTIONARIES).sort()).toEqual(LANGUAGES.map((l) => l.code).sort());
    expect([...others].sort()).toEqual(['hi', 'ta', 'te']);
  });

  it('English strings are all non-empty', () => {
    for (const key of enKeys) {
      expect(en[key as keyof typeof en].trim(), key).not.toBe('');
    }
  });

  describe.each(others)('%s', (lang) => {
    const dict = DICTIONARIES[lang] as Readonly<Record<string, string>>;

    it('has exactly the same keys as en', () => {
      const keys = Object.keys(dict).sort();
      expect(keys.filter((k) => !(k in en)), 'extra keys').toEqual([]);
      expect(enKeys.filter((k) => !(k in dict)), 'missing keys').toEqual([]);
      expect(keys).toEqual(enKeys);
    });

    it('has no empty strings', () => {
      for (const key of enKeys) {
        expect(typeof dict[key], key).toBe('string');
        expect(dict[key].trim(), key).not.toBe('');
      }
    });

    it('keeps the same {placeholders} as en for every key', () => {
      const mismatches = enKeys
        .filter((key) => placeholders(dict[key]).join(',') !== placeholders(en[key as keyof typeof en]).join(','))
        .map((key) => `${key}: en=${en[key as keyof typeof en]} | ${lang}=${dict[key]}`);
      expect(mismatches).toEqual([]);
    });

    it('Drive strings use the language script except brand phrases', () => {
      const brand = new Set(['driveBackups.import', 'driveBackups.importBackup']);
      const indic = /[\u0900-\u097F\u0B80-\u0BFF\u0C00-\u0C7F]/;
      const latinOnly = enKeys.filter(
        (key) => key.startsWith('drive') && !brand.has(key) && !indic.test(dict[key]),
      );
      expect(latinOnly).toEqual([]);
    });
  });
});

describe('isLang', () => {
  it('accepts only supported language codes', () => {
    for (const l of LANGUAGES) expect(isLang(l.code)).toBe(true);
    for (const v of ['fr', 'EN', 'en-IN', '', null, undefined, 1]) expect(isLang(v)).toBe(false);
  });
});
