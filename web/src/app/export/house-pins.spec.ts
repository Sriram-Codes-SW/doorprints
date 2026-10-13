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

// The safety test of kinds PR 6 (S4b-BL-210; docs/03 §18.10), written before typed extras reached search and the
// copies: a house's copies (every CSV table, the Markdown, the HTML and the XLSX workbook, in en, hi, ta and te) and
// its list search text are exactly what main made (golden/house-pins.golden.ts). A school's extras are added beside
// them; a house has no extra outside its own hand-written fields, so nothing here may change.
import { describe, expect, it } from 'vitest';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import type { TKey } from '../i18n/en';
import { buildCsvTables } from './csv-export';
import { buildMarkdown } from './markdown-export';
import { buildHtml } from './html-export';
import { buildXlsx } from './xlsx-export';
import { buildWorkbook } from './xlsx-sheets';
import { sha256Hex } from './sha256';
import { utf8 } from './zip';
import { tr } from './deterministic';
import { FIXTURE_BROKERS, FIXTURE_EXPORTED_AT, FIXTURE_HOUSES, FIXTURE_PHOTO_DATA_URIS, fixtureBundle } from './golden/fixture';
import { GOLDEN_XLSX_HOUSES_SHEET, HOUSE_COPY_SHA256, HOUSE_SEARCH_TEXT } from './golden/house-pins.golden';
import { brokerSearchText, searchText } from '../pages/map/map-list';
import { floorLocalSearchText } from '../shared/house-floor';

const LANGS = ['en', 'hi', 'ta', 'te'] as const;
const AT = new Date(FIXTURE_EXPORTED_AT);

/** The XML of one stored entry of a workbook (the writer stores, never deflates). */
function sheetXml(bytes: Uint8Array, entry: string): string {
  const text = new TextDecoder().decode(bytes);
  const start = text.indexOf('<?xml', text.indexOf(entry));
  return text.slice(start, text.indexOf('</worksheet>', start) + '</worksheet>'.length);
}

describe('a house copy is what main made (the safety test of S4b-BL-210)', () => {
  for (const lang of LANGS) {
    it(`every CSV table, the Markdown, the HTML and the workbook in ${lang}`, () => {
      const bundle = fixtureBundle({ lang });
      const dict = DICTIONARIES[lang];
      const got: Record<string, string> = {};
      for (const [name, text] of Object.entries(buildCsvTables(bundle))) got[name] = sha256Hex(utf8(text));
      got['markdown'] = sha256Hex(utf8(buildMarkdown(bundle, dict)));
      got['html'] = sha256Hex(utf8(buildHtml(bundle, dict, FIXTURE_PHOTO_DATA_URIS)));
      got['xlsx'] = sha256Hex(buildXlsx(buildWorkbook(bundle), AT));
      expect(got).toEqual(HOUSE_COPY_SHA256[lang]);
    });
  }

  it('matches the golden Houses sheet of the workbook', () => {
    expect(sheetXml(buildXlsx(buildWorkbook(fixtureBundle()), AT), 'xl/worksheets/sheet1.xml')).toBe(GOLDEN_XLSX_HOUSES_SHEET);
  });
});

describe('a house is searched by what main searched (the safety test of S4b-BL-210)', () => {
  for (const lang of LANGS) {
    it(`the list's search text of each fixture house in ${lang}`, () => {
      const t = (key: TKey, params?: Record<string, string | number>) => tr(DICTIONARIES[lang], key, params);
      const local = lang === 'en' ? undefined : (floor: number) => floorLocalSearchText(t, floor);
      const words = FIXTURE_HOUSES.map((h) => {
        const broker = FIXTURE_BROKERS.find((b) => b.id === h.brokerId);
        return searchText(h, broker ? brokerSearchText(broker.broker) : '', ['a note'], local);
      });
      expect(words).toEqual(HOUSE_SEARCH_TEXT[lang]);
    });
  }
});
