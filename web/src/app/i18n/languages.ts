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
import { hi } from './hi';
import { ta } from './ta';
import { te } from './te';

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

export const DICTIONARIES: Readonly<Record<Lang, Dict>> = { en, hi, ta, te };

export function isLang(value: unknown): value is Lang {
  return value === 'en' || value === 'hi' || value === 'ta' || value === 'te';
}
