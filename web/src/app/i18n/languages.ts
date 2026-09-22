import { Dict, en } from './en';
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
