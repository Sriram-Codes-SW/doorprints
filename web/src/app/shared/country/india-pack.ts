import type { CountryPack } from './country-pack';
import { inIndia } from './india-border';

/**
 * The one country pack the app ships (ADR-38): India. Every India-specific constant the features used to carry (the rupee
 * and its lakh/crore grouping and amount words, the Indian phone shapes, the geocoder's country and box, the starting map
 * view, the speech locale and the AI prompts' currency words) is here and nowhere else; `tools/check-country-literals.mjs`
 * fails when a new one appears outside this file. The Kotlin twin is `android/shared/.../country/IndiaPack.kt`.
 *
 * The boundary lines are not here: `geo.contains` points at `inIndia`, the Government of India's outline test (ADR-22).
 */
export const INDIA_PACK: CountryPack = {
  id: 'in',
  name: 'India',
  money: {
    symbol: '₹',
    code: 'INR',
    word: 'Rs',
    name: 'rupees',
    markers: ['₹', 'Rs\\.?', 'INR'],
    /** 1250000 -> 12,50,000: the last three digits, then pairs. */
    group(digits: string): string {
      if (digits.length <= 3) return digits;
      const last3 = digits.slice(-3);
      let rest = digits.slice(0, -3);
      const parts: string[] = [];
      while (rest.length > 2) {
        parts.unshift(rest.slice(-2));
        rest = rest.slice(0, -2);
      }
      if (rest.length > 0) parts.unshift(rest);
      return `${parts.join(',')},${last3}`;
    },
    xlsxFormat: '"₹"#,##,##0',
    units: [
      { word: 'k', multiplier: 1_000, listing: 'always' },
      { word: 'thousand', multiplier: 1_000, listing: 'always' },
      { word: 'l', multiplier: 100_000, listing: 'afterSymbol' },
      { word: 'lac', multiplier: 100_000, listing: 'always' },
      { word: 'lakh', multiplier: 100_000, listing: 'always' },
      { word: 'lakhs', multiplier: 100_000, listing: 'always' },
      { word: 'lacs', multiplier: 100_000, listing: 'always' },
      { word: 'cr', multiplier: 10_000_000, listing: 'always' },
      { word: 'crore', multiplier: 10_000_000, listing: 'always' },
      { word: 'crores', multiplier: 10_000_000, listing: 'always' },
      { word: 'm', multiplier: 1_000_000, listing: 'never' },
      { word: 'mn', multiplier: 1_000_000, listing: 'never' },
      { word: 'million', multiplier: 1_000_000, listing: 'never' },
    ],
    saleFrom: 1_000_000,
  },
  phone: {
    // A mobile number (with or without +91 or a 0), or a landline with its STD code (011 4155 0101, (022) 2655 0101).
    listing: '(?:\\+91[\\s-]?)?(?:0)?[6-9]\\d{4}[\\s-]?\\d{5}\\b|(?<!\\d)\\(?0\\d{2,4}\\)?[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}\\b',
    redact: [
      '(?:(?:\\+?91|0)[ \\-]?)?[6-9](?:[ .\\-]?\\d){9}', // Indian mobile
      '\\(?0\\d{2,4}\\)?[ \\-]?\\d{3,4}[ \\-]?\\d{3,4}', // STD code (also "(022)") + landline
    ],
    keptDigits: 10,
    keyMinDigits: 6,
    trunkPrefix: '0',
    landlineAreaCodeSkip: 3,
  },
  /** Arabic-Indic, Urdu, Devanagari, Bengali, Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam. */
  digitZeros: [0x0660, 0x06f0, 0x0966, 0x09e6, 0x0a66, 0x0ae6, 0x0b66, 0x0be6, 0x0c66, 0x0ce6, 0x0d66],
  geo: {
    countryCode: 'in',
    queryName: 'India',
    // ADR-22: the north reaches 37.1 degrees in Ladakh, as the Government of India depicts the country.
    box: { south: 6.5, west: 68.1, north: 37.1, east: 97.4 },
    defaultView: { lat: 20.5937, lon: 78.9629, zoom: 4 },
    contains: inIndia,
  },
  locale: {
    fallback: 'en-IN',
    speech: { hi: 'hi-IN', ta: 'ta-IN', te: 'te-IN' },
  },
  area: { unit: 'sq ft' },
  prompt: {
    priceExamples: '"25,000", "1.2 Cr", "85 lakh"',
    feeExamples: '"96,000", "1.2 lakh"',
    feeSchemaExample: "'96,000' or '1.2 lakh'",
  },
  voice: {
    houseKeyTerms: '2 BHK, 3 BHK, lakh, crore, sq ft, rent, deposit, maintenance, society, locality',
  },
};
