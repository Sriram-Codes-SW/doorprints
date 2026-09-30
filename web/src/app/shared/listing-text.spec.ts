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
import { cleanListingUrl, listingPortal, parseListingText } from './listing-text';
// The one fixture file both parsers read (docs/schemas README 8.2); resolveJsonModule is on for the specs.
import fixtureJson from '../../../../docs/schemas/listing-fixtures.json';

interface Fixture {
  cases: {
    name: string;
    text: string;
    expect: {
      label: string;
      price: number | null;
      priceType: string | null;
      bedrooms: number | null;
      locality: string | null;
      contactPhone: string | null;
      listingUrl: string | null;
      portal: string | null;
      notesStart: string | null;
    };
  }[];
}

/**
 * The no-AI listing parser (docs/11 5.29): every case of `docs/schemas/listing-fixtures.json` reads the same as the
 * Kotlin parser reads it (`ListingFixturesTest`), so a share text fills the same fields on the web and on Android.
 */
describe('parseListingText', () => {
  const fixture = fixtureJson as Fixture;

  it.each(fixture.cases.map((c) => [c.name, c] as const))('%s', (_name, c) => {
    const d = parseListingText(c.text);
    expect(d.label).toBe(c.expect.label);
    expect(d.price).toBe(c.expect.price);
    expect(d.priceType).toBe(c.expect.priceType);
    expect(d.bedrooms).toBe(c.expect.bedrooms);
    // The fixture has no `areaSqft` key (the lead's file): a case whose expected notes begin with an area is one
    // where the parser found it, so the field holds that number; otherwise nothing.
    const areaInNotes = /^(\d+) sq ft/.exec(c.expect.notesStart ?? '');
    expect(d.areaSqft).toBe(areaInNotes ? Number(areaInNotes[1]) : null);
    expect(d.locality).toBe(c.expect.locality);
    expect(d.contactPhone).toBe(c.expect.contactPhone);
    expect(d.listingUrl).toBe(c.expect.listingUrl);
    expect(listingPortal(d.listingUrl)).toBe(c.expect.portal);
    if (c.expect.notesStart) expect(d.notes?.startsWith(c.expect.notesStart)).toBe(true);
    expect(d.notes?.includes(c.text.trim().slice(0, 40))).toBe(true);
  });

  it('cleans tracking parameters only and matches portal hosts by suffix', () => {
    expect(cleanListingUrl('https://a.in/p?utm_source=x&id=7&fbclid=9#photos')).toBe('https://a.in/p?id=7#photos');
    expect(cleanListingUrl('https://a.in/p?gclid=1')).toBe('https://a.in/p');
    expect(listingPortal('https://m.nobroker.in/x')).toBe('NoBroker');
    expect(listingPortal('https://nobroker.in.evil.example/x')).toBeNull();
  });
});
