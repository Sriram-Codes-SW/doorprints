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

import { en } from './en';
import type { Dict } from './en';

export type Lang = 'en' | 'hi' | 'ta' | 'te';

export interface LanguageInfo {
  readonly code: Lang;
  /** Name in the language itself, shown in the switcher. */
  readonly nativeName: string;
  /** BCP 47 locale for Intl number/date formatting (Indian conventions: ₹ and lakh grouping). */
  readonly locale: string;
}

export const LANGUAGES: readonly LanguageInfo[] = [
  { code: 'en', nativeName: 'English', locale: 'en-IN' },
  { code: 'hi', nativeName: 'हिन्दी', locale: 'hi-IN' },
  { code: 'ta', nativeName: 'தமிழ்', locale: 'ta-IN' },
  { code: 'te', nativeName: 'తెలుగు', locale: 'te-IN' },
];

/**
 * Dictionaries loaded so far. English is built in (it is also every key's fallback); Hindi, Tamil and Telugu are each
 * their own chunk, loaded when chosen (main.ts loads the saved one before the app starts), so a visit carries one
 * language's strings instead of all four (about 220 KB less for English). The service worker precaches the chunks,
 * so switching language works offline too. Tests that need every language import `all-dictionaries.ts`.
 */
const loaded: Partial<Record<Lang, Dict>> = { en };
const loaders: Record<Exclude<Lang, 'en'>, () => Promise<Dict>> = {
  hi: () => import('./hi').then((m) => m.hi),
  ta: () => import('./ta').then((m) => m.ta),
  te: () => import('./te').then((m) => m.te),
};

/** [lang]'s dictionary if it is already loaded (English always is). */
export function dictionary(lang: Lang): Dict | undefined {
  return loaded[lang];
}

/** Loads [lang]'s dictionary once; rejects when its chunk cannot be fetched (offline before the first visit). */
export async function loadDictionary(lang: Lang): Promise<Dict> {
  const have = loaded[lang];
  if (have) return have;
  const dict = await loaders[lang as Exclude<Lang, 'en'>]();
  loaded[lang] = dict;
  return dict;
}

/** The dictionaries loaded so far (a copy; tests restore it with {@link registerDictionaries}). */
export function loadedDictionaries(): Partial<Record<Lang, Dict>> {
  return { ...loaded };
}

/** Forgets every dictionary but English (tests of the loading itself; spec files share this module in CI). */
export function forgetDictionaries(): void {
  for (const lang of Object.keys(loaded) as Lang[]) if (lang !== 'en') delete loaded[lang];
}

/** Makes [dicts] available at once, without loading (all-dictionaries.ts, for tests). */
export function registerDictionaries(dicts: Partial<Record<Lang, Dict>>): void {
  Object.assign(loaded, dicts);
}

export function isLang(value: unknown): value is Lang {
  return value === 'en' || value === 'hi' || value === 'ta' || value === 'te';
}
