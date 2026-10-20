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

import type { CountryPack } from '../country/country-pack';

/**
 * A synthetic country, "Zedland" (`zz`), for tests only (ADR-38, WP-C2): `$` before the amount with 1,234,567 grouping,
 * the words k, mm and bn for thousands, millions and billions, seven-digit phone numbers with `+99`, Thai digits, a unit of
 * square metres and a box around (0, 0). It shares nothing with India's pack, so a call site that still assumed India gives
 * another answer under it and the test fails. The Kotlin twin is `ZzPack.kt` (androidHostTest); the answers are
 * `docs/schemas/country-pack-vectors.json` section `zz`. It lives under `testing/`, which no build ships.
 */
export const ZZ_PACK: CountryPack = {
  id: 'zz',
  name: 'Zedland',
  money: {
    symbol: '$',
    code: 'ZZD',
    word: 'Zd',
    name: 'zeds',
    markers: ['\\$', 'Zd\\.?'],
    group(digits: string): string {
      return digits.replace(/\B(?=(\d{3})+$)/g, ',');
    },
    xlsxFormat: '"$"#,##0',
    units: [
      { word: 'k', multiplier: 1_000, listing: 'always' },
      { word: 'mm', multiplier: 1_000_000, listing: 'always' },
      { word: 'bn', multiplier: 1_000_000_000, listing: 'afterSymbol' },
    ],
    saleFrom: 500_000,
  },
  phone: {
    listing: '(?:\\+99[\\s-]?)?[2-5]\\d{2}[\\s-]?\\d{4}\\b',
    redact: ['(?:\\+?99[ \\-]?)?[2-5]\\d{2}[ \\-]?\\d{4}'],
    keptDigits: 7,
    keyMinDigits: 5,
    trunkPrefix: '8',
    landlineAreaCodeSkip: 2,
  },
  digitZeros: [0x0e50],
  geo: {
    countryCode: 'zz',
    queryName: 'Zedland',
    box: { south: -10, west: -10, north: 10, east: 10 },
    defaultView: { lat: 1.5, lon: 2.5, zoom: 6 },
    contains: (lat, lon) => Math.abs(lat) <= 5 && Math.abs(lon) <= 5,
  },
  locale: { fallback: 'zz-ZZ', speech: { hi: 'zz-ZZ', zq: 'zq-ZZ' } },
  area: { unit: 'sq m' },
  prompt: {
    priceExamples: '"25,000", "3 mm"',
    feeExamples: '"96,000", "2 mm"',
    feeSchemaExample: "'96,000' or '2 mm'",
  },
  voice: { houseKeyTerms: '2 rooms, mm, bn, sq m, rent' },
};
