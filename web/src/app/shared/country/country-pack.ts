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

/**
 * The country pack (ADR-38, docs/03 section 21): everything the app knows about the one country it is built for that a
 * feature could ask instead of assuming: how money is written and how an amount is read, the shapes of a phone number,
 * the geocoder's country, the default map view, the locale defaults and the words of the AI prompts that name a currency.
 * A new country is a new object of this shape, and no feature changes.
 *
 * Three things are deliberately not here:
 *  - India's boundary lines. They are the Government of India's depiction (ADR-22, `shared/india-boundaries.ts`, the
 *    GeoJSON); a pack may only point at the border test ({@link CountryPack.geo} `contains`), never carry the lines.
 *  - The words of the screens (the dictionaries, `i18n/`), which are language work.
 *  - The kinds (`docs/schemas/kinds/`), which describe what is remembered, not where.
 *
 * The Kotlin twin is `android/shared/.../country/CountryPack.kt`; `docs/schemas/country-pack-vectors.json` holds both to
 * the same answers (`country-pack.spec.ts`, `CountryPackVectorsTest`).
 *
 * Code reads the pack through {@link country} at the moment it needs it (never at module load), so a test can swap it
 * with {@link useCountryPack} and see every call site follow.
 */

import { INDIA_PACK } from './india-pack';

/** How one amount word ("lakh", "k") counts, and where a pasted listing may use it. */
export interface AmountUnit {
  /** The word, lower case, as written after a number. */
  readonly word: string;
  /** What it multiplies the number by. */
  readonly multiplier: number;
  /**
   * Where the listing parser (not the AI-draft sanitiser, which reads every word) takes it as a price: `always` (also in
   * a bare "45 lakh"), `afterSymbol` (only after a currency sign or word: a lone "5 l" is not a price) or `never`.
   */
  readonly listing: 'always' | 'afterSymbol' | 'never';
}

export interface CountryPack {
  /** The ISO 3166-1 alpha-2 code, lower case. */
  readonly id: string;
  /** The English name of the country, as the AI prompts name the market the app is for. */
  readonly name: string;
  readonly money: {
    /** The sign written before an amount: `₹12,50,000`. */
    readonly symbol: string;
    /** The ISO 4217 code, for the screens' `Intl` currency format (which writes the sign and the grouping in the app language's locale). */
    readonly code: string;
    /** The ASCII word the AI documents and prompts use for the currency: `Rs 25000`. */
    readonly word: string;
    /** The plural name of the currency, as the AI schema descriptions say it ("in rupees"). */
    readonly name: string;
    /** Regular-expression sources of what a pasted listing puts before an amount: the sign, the word, the code. */
    readonly markers: readonly string[];
    /** `digits` (0-9 only, no sign, no leading zeros) with the country's group separators: `1250000` gives `12,50,000`. */
    group(digits: string): string;
    /** The spreadsheet number format of a currency cell, unescaped (the writer escapes the quotes for XML). */
    readonly xlsxFormat: string;
    /** The amount words and what they multiply by. */
    readonly units: readonly AmountUnit[];
    /** From this many whole units a price with no rent words is a sale (ten lakh in India). */
    readonly saleFrom: number;
  };
  readonly phone: {
    /** Regular-expression source of a number as a listing writes it (mobile with or without the country code, a landline with its area code). */
    readonly listing: string;
    /** Regular-expression sources of the country's own number shapes, tried by the redactor between the international `+cc` shape and the long digit run. */
    readonly redact: readonly string[];
    /** A number is compared on its last this-many digits, and a saved one is also looked for without what precedes them. */
    readonly keptDigits: number;
    /** Fewer digits than this and a number identifies nobody (the comparison key is null). */
    readonly keyMinDigits: number;
    /** The digit a national number may start with (the trunk prefix). */
    readonly trunkPrefix: string;
    /** A landline with a trunk prefix and its area code is often written without them: this many leading digits are dropped. */
    readonly landlineAreaCodeSkip: number;
  };
  /** The zero of each non-Latin digit script whose ten digits follow it, read as 0-9 before any number is matched. */
  readonly digitZeros: readonly number[];
  readonly geo: {
    /** The country code the geocoder is limited to (`countrycodes` of Nominatim, lower case). */
    readonly countryCode: string;
    /** The English name added after a name sent to a geocoder that takes no country code, or null. */
    readonly queryName: string | null;
    /** The box a platform geocoder is asked for; the quick first test of a found point. */
    readonly box: { readonly south: number; readonly west: number; readonly north: number; readonly east: number };
    /** Where a map starts when nothing says where to look. */
    readonly defaultView: { readonly lat: number; readonly lon: number; readonly zoom: number };
    /** Whether a point is inside the country's border as the country depicts it (a reference to the border test, never the lines). */
    contains(lat: number, lon: number): boolean;
  };
  readonly locale: {
    /** The BCP 47 tag of the app's language when it has none of its own (speech input's hint). */
    readonly fallback: string;
    /** The app languages whose speech tag has a region: language code to tag. */
    readonly speech: Readonly<Record<string, string>>;
  };
  readonly area: {
    /** The unit a listing's area is written in and the app records, as words in the documents the AI reads. */
    readonly unit: string;
  };
  readonly prompt: {
    /** The amounts the AI is shown as examples of a price copied as written: `"25,000", "1.2 Cr", "85 lakh"`. */
    readonly priceExamples: string;
    /** The same for a fee per year. */
    readonly feeExamples: string;
    /** The example inside the fee field's schema description. */
    readonly feeSchemaExample: string;
  };
  readonly voice: {
    /** The words a transcriber is asked to spell right for a house hunt (the country's own amount words, the area unit). */
    readonly houseKeyTerms: string;
  };
}

let active: CountryPack | null = null;

/** The active pack: the one the app ships with (India's), unless a test swapped it. */
export function country(): CountryPack {
  return active ?? INDIA_PACK;
}

/** Makes `pack` the active one (tests only: the app ships one pack) and returns the call that puts the previous one back. */
export function useCountryPack(pack: CountryPack): () => void {
  const before = active;
  active = pack;
  return () => {
    active = before;
  };
}

// ---------------------------------------------------------------- derived patterns

/** A phone digit run's international start and its long form, the parts of the redactor that belong to no country. */
const PLUS_CC = '\\+\\d(?:[ .()\\-]{0,2}\\d){6,14}';
const LONG_RUN = '\\d{10,15}';
const WORD = '[\\p{L}\\p{M}\\p{N}]';

export interface Derived {
  /** The listing parser's phone number. */
  readonly listingPhone: RegExp;
  /** The listing parser's price: a marked amount, or a bare one with a unit word. */
  readonly listingPrice: RegExp;
  /** An amount as the AI-draft sanitiser reads it: a number and an optional unit word. */
  readonly draftAmount: RegExp;
  /** The redactor's phone-like text (global, unicode). */
  readonly redactPhone: RegExp;
  /** One character of a non-Latin digit script (global). */
  readonly nativeDigit: RegExp;
  /** A unit word's multiplier (lower case), 1 for none. */
  readonly multiplier: Map<string, number>;
}

const cache = new WeakMap<CountryPack, Derived>();

/** The patterns built from the active pack, once per pack. */
export function derived(pack: CountryPack = country()): Derived {
  let d = cache.get(pack);
  if (d) return d;
  const words = (pick: (u: AmountUnit) => boolean) => pack.money.units.filter(pick).map((u) => u.word).join('|');
  const afterSymbol = words((u) => u.listing !== 'never');
  const bare = words((u) => u.listing === 'always');
  const all = words(() => true);
  const markers = pack.money.markers.join('|');
  const nativeDigit = pack.digitZeros.map((zero) => `\\u${hex(zero)}-\\u${hex(zero + 9)}`).join('');
  d = {
    listingPhone: new RegExp(pack.phone.listing),
    listingPrice: new RegExp(
      `(?:${markers})\\s*([\\d,]+(?:\\.\\d+)?)\\s*(${afterSymbol})?\\b|\\b(\\d+(?:\\.\\d+)?)\\s*(${bare})\\b`,
      'i',
    ),
    draftAmount: new RegExp(`(\\d+(?:\\.\\d+)?)\\s*(${all})?\\b`, 'i'),
    redactPhone: new RegExp(
      `(?<![\\p{L}\\p{M}\\p{N}+])(?:${[PLUS_CC, ...pack.phone.redact, LONG_RUN].join('|')})(?!${WORD})`,
      'gu',
    ),
    nativeDigit: new RegExp(`[${nativeDigit}]`, 'g'),
    multiplier: new Map(pack.money.units.map((u) => [u.word, u.multiplier])),
  };
  cache.set(pack, d);
  return d;
}

function hex(code: number): string {
  return code.toString(16).padStart(4, '0');
}

/** `Rs 25000`: an amount as the AI documents and prompts write it. */
export function promptMoney(amount: number | string): string {
  return `${country().money.word} ${amount}`;
}

/** `text` with every character that means something inside a regular-expression character class escaped. */
export function escapeForClass(text: string): string {
  return text.replace(/[\]\\^-]/g, '\\$&');
}
