import { describe, expect, it } from 'vitest';
import { en } from './en';
import { DICTIONARIES, LANGUAGES, Lang, isLang } from './languages';

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
  });
});

describe('isLang', () => {
  it('accepts only supported language codes', () => {
    for (const l of LANGUAGES) expect(isLang(l.code)).toBe(true);
    for (const v of ['fr', 'EN', 'en-IN', '', null, undefined, 1]) expect(isLang(v)).toBe(false);
  });
});
