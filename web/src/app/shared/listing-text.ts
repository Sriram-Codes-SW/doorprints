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

import type { HouseDraft } from '../core/ai.service';

/**
 * The no-AI listing parser (docs/11 5.29, S4b-FR-4; 5.9): what a portal's share sheet hands over (its title, price,
 * BHK, locality, the link) read into a {@link HouseDraft} in the browser, with no network and no model. The page is
 * never fetched. The port of `android/shared`'s `ListingText.kt`; `docs/schemas/listing-fixtures.json` is what both
 * must read the same way (`listing-text.spec.ts`).
 */

/** A share sheet's text is capped here (SEC-043); more is never a listing. */
export const LISTING_MAX_CHARS = 20_000;

/** The portals named in the source label, by host (a subdomain counts). */
export const LISTING_PORTALS: ReadonlyArray<readonly [string, string]> = [
  ['magicbricks.com', 'MagicBricks'],
  ['99acres.com', '99acres'],
  ['housing.com', 'Housing.com'],
  ['nobroker.in', 'NoBroker'],
  ['squareyards.com', 'Square Yards'],
  ['nestaway.com', 'NestAway'],
];

const URL_RE = /https?:\/\/[^\s<>"']+/;
const TRACKING = /^(utm_.*|fbclid|gclid|igshid|ref|src)$/;
const PHONE = /(?:\+91[\s-]?)?(?:0)?[6-9]\d{4}[\s-]?\d{5}\b/;
const BHK = /\b(\d{1,2})\s*-?\s*(?:BHK|bhk|Bhk|bedroom|bedrooms|BR)\b/;
const STUDIO = /\b(studio|1\s*RK)\b/i;
const PRICE =
  /(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)\s*(k|thousand|lac|lakh|lakhs|lacs|l|cr|crore|crores)?\b|\b(\d+(?:\.\d+)?)\s*(k|thousand|lac|lakh|lakhs|lacs|cr|crore|crores)\b/i;
const RENT = /\b(for rent|rent|rental|per month|\/\s*month|monthly|lease|pm)\b/i;
const SALE = /\b(for sale|sale|resale|buy|selling)\b/i;
const CITIES =
  'Bengaluru|Bangalore|Chennai|Hyderabad|Mumbai|Navi Mumbai|Thane|Pune|Delhi|New Delhi|Gurgaon|Gurugram|Noida|Kolkata|Kochi|Coimbatore|Mysuru|Mysore|Ahmedabad|Jaipur|Lucknow|Chandigarh|Indore|Bhopal|Nagpur|Surat|Vadodara|Visakhapatnam|Vijayawada|Thiruvananthapuram|Trivandrum|Madurai|Mangaluru|Mangalore';
const LOCALITY = new RegExp(
  `\\b(?:in|at|near)\\s+([A-Z][\\w.'-]*(?:\\s+(?:[A-Z0-9][\\w.'-]*|of|the)){0,4}?),?\\s+(?:${CITIES})\\b`,
);
const AREA = /(\d{3,5})\s*(?:sq\.?\s*ft|sqft|sq\.?\s*feet|square\s*feet|sq\.?\s*m)\b/i;
const FURNISHING = /\b(fully[- ]furnished|semi[- ]furnished|unfurnished|furnished)\b/i;
const SALE_FROM_RUPEES = 1_000_000;
const LABEL_MAX = 120;
const NOTES_MAX = 2000;
const PHONE_MAX = 50;
const URL_MAX = 1000;
const PRICE_MAX = 1_000_000_000_000;
const BEDROOMS_MAX = 20;
const AREA_MAX = 100_000;

/** The first link in the text, as written (trailing punctuation dropped), or null. */
export function listingUrlIn(text: string): string | null {
  const m = URL_RE.exec(text);
  return m ? m[0].replace(/[.,);!?]+$/, '') : null;
}

/** The link without its tracking parameters (`utm_*`, `fbclid`, `gclid`, …) and without a bare `?`. */
export function cleanListingUrl(url: string): string {
  const q = url.indexOf('?');
  if (q < 0) return url;
  const hash = url.indexOf('#');
  const fragment = hash >= 0 ? url.slice(hash + 1) : '';
  const query = url.slice(q + 1, hash >= 0 ? hash : undefined);
  const kept = query.split('&').filter((p) => p.length > 0 && !TRACKING.test(p.split('=')[0].toLowerCase()));
  return url.slice(0, q) + (kept.length ? '?' + kept.join('&') : '') + (fragment ? '#' + fragment : '');
}

/** The portal's name for the link ("MagicBricks"), or null when the host is not one of {@link LISTING_PORTALS}. */
export function listingPortal(url: string | null): string | null {
  if (!url) return null;
  const host = url.replace(/^[a-z]+:\/\//i, '').split('/')[0].split(':')[0].toLowerCase();
  const hit = LISTING_PORTALS.find(([site]) => host === site || host.endsWith('.' + site));
  return hit ? hit[1] : null;
}

/** "25,000", "1.2 Cr", "45 lakh" -> whole rupees, as the Kotlin sanitiser reads them; null when not a price. */
function rupees(value: string): number | null {
  const m = /(\d+(?:\.\d+)?)\s*(k|thousand|l|lac|lakh|lakhs|lacs|cr|crore|crores|m|mn|million)?\b/i.exec(
    value.replace(/[,_]/g, ''),
  );
  if (!m) return null;
  const unit = (m[2] ?? '').toLowerCase();
  const multiplier =
    unit === 'k' || unit === 'thousand'
      ? 1_000
      : ['l', 'lac', 'lakh', 'lakhs', 'lacs'].includes(unit)
        ? 100_000
        : ['cr', 'crore', 'crores'].includes(unit)
          ? 10_000_000
          : ['m', 'mn', 'million'].includes(unit)
            ? 1_000_000
            : 1;
  const [whole, fraction = ''] = m[1].split('.');
  if (whole.length > 15) return null;
  let total = Number(whole) * multiplier;
  if (fraction) {
    const digits = fraction.slice(0, 12);
    total += Math.floor((Number(digits) * multiplier) / 10 ** digits.length);
  }
  return total < 0 || total > PRICE_MAX ? null : total;
}

/** The draft from the share text: the fields the text says, the whole text kept in the notes so nothing is lost. */
export function parseListingText(text: string): HouseDraft {
  const t = text.slice(0, LISTING_MAX_CHARS);
  const url = listingUrlIn(t);
  // The words are read with the links taken out: a portal's address says "rent" or "buy" for its own reasons.
  const words = t.replace(new RegExp(URL_RE.source, 'g'), ' ');
  const priceMatch = PRICE.exec(words);
  const priceText = priceMatch ? (priceMatch[1] ? `${priceMatch[1]} ${priceMatch[2] ?? ''}` : `${priceMatch[3]} ${priceMatch[4]}`).trim() : null;
  const price = priceText ? rupees(priceText) : null;
  const priceType = RENT.test(words) ? 'RENT' : SALE.test(words) ? 'SALE' : price !== null && price >= SALE_FROM_RUPEES ? 'SALE' : null;
  const bhk = BHK.exec(words);
  const bedroomsRaw = bhk ? Number(bhk[1]) : STUDIO.test(words) ? 0 : null;
  const bedrooms = bedroomsRaw !== null && bedroomsRaw <= BEDROOMS_MAX ? bedroomsRaw : null;
  const area = AREA.exec(words);
  // The same match feeds the notes' first line ("1150 sq ft, …") and the carpet area field (slice 1a).
  const areaSqft = area && Number(area[1]) >= 1 && Number(area[1]) <= AREA_MAX ? Number(area[1]) : null;
  const furnishing = FURNISHING.exec(words);
  const details = [
    area ? `${area[1]} sq ft` : null,
    furnishing ? furnishing[1].toLowerCase().replace(/^./, (c) => c.toUpperCase()) : null,
  ].filter((d): d is string => d !== null);
  const notes = (details.length ? details.join(', ') + '\n\n' + t : t).trim().slice(0, NOTES_MAX);
  const phone = PHONE.exec(words)?.[0].trim().slice(0, PHONE_MAX) ?? null;
  const locality = LOCALITY.exec(words)?.[1] ?? null;
  const label = firstLabel(t, url) ?? defaultLabel(bedrooms, locality);
  return {
    label,
    address: null,
    street: null,
    locality,
    price,
    priceType,
    bedrooms,
    areaSqft,
    contactName: null,
    contactPhone: phone,
    listingUrl: url && url.length <= URL_MAX ? cleanListingUrl(url) : null,
    notes,
    amenities: [],
    warnings: [],
  };
}

/** "House", "Studio", "2BHK in Indiranagar": the Kotlin sanitiser's label when the text gives none. */
function defaultLabel(bedrooms: number | null, locality: string | null): string {
  const what = bedrooms === null ? 'House' : bedrooms === 0 ? 'Studio' : `${bedrooms}BHK`;
  return locality ? `${what} in ${locality}` : what;
}

/** The first line that is not the link: what the portal calls the listing. */
function firstLabel(text: string, url: string | null): string | null {
  for (const raw of text.split('\n')) {
    const line = raw.trim().replace(/\s+/g, ' ');
    if (!line) continue;
    if (url && line.includes(url)) continue;
    if (URL_RE.test(line) && line.replace(URL_RE, '').trim() === '') continue;
    return line.slice(0, LABEL_MAX).trim();
  }
  return null;
}
