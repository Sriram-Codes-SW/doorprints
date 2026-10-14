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

// A school's readable copies speak in the school's words and leave out what a school hides (kinds PR 16, S4b-BL-256 copies
// part and S4b-BL-266; docs/03 §18.9): no *House*, *Houses*, *Price*, *Score* or *Cost* in the cover, the ranking, the tables
// and the section headings; the fees under *Fees*; no Scores table; a copy of houses, of houses beside a school, and a school
// copied as a house (the flag off) are as they were. The words are the app's dictionaries' (`school.*`) and the copy
// strings' overlay (`kind-export-words.ts`); the expected columns are written out here, not recomputed.
import { describe, expect, it } from 'vitest';
import schoolVectorsJson from '../../../../docs/schemas/kinds/school-vectors.json';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import type { Lang } from '../i18n/languages';
import { houseOf, type CandidateDto } from '../core/candidate';
import { builtInKind } from '../core/dev-kinds';
import { tryHouseFromDto, type HouseRecord } from '../data/records';
import { buildCsvTables } from './csv-export';
import { collect, DEFAULT_EXPORT_OPTIONS } from './export-model';
import { exportTables } from './export-rows';
import { ExportStrings } from './export-strings';
import { FIXTURE_HOUSES, FIXTURE_PHOTO_DATA_URIS } from './golden/fixture';
import { buildHtml } from './html-export';
import type { CopyKinds } from './kind-copy';
import { buildMarkdown } from './markdown-export';
import { buildWorkbook } from './xlsx-sheets';

const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
const school = (): HouseRecord =>
  tryHouseFromDto(houseOf((schoolVectorsJson as unknown as { candidate: CandidateDto }).candidate))!;
const kindsFor = (lang: Lang, flag = true): CopyKinds => ({ fieldsOf: (k) => builtInKind(k, flag).fields, dict: DICTIONARIES[lang] });
const copyOf = (houses: HouseRecord[], lang: Lang, space?: string, flag = true) =>
  collect({
    houses, visits: [], photos: [], exportedAt: '2026-10-14T10:00:00.000Z', kinds: kindsFor(lang, flag),
    options: { ...DEFAULT_EXPORT_OPTIONS, lang, ...(space ? { space } : {}) },
    ...(space ? { inSpace: (h: HouseRecord) => (h.kind ?? 'house') === space } : {}),
  });

/** The words of a house in each language: a school's copy must carry none of them. */
const HOUSE_WORD: Record<Lang, RegExp> = {
  en: /house|BHK|\bflat\b|\bcost\b|\bprice\b|\bscore\b/i,
  hi: /मकान|घर/,
  ta: /வீடு|வீட்/,
  te: /ఇల్లు|ఇళ్లు|ఇంటి/,
};

/** A school with a rating and a visit, so the ranking and the visits table have rows. */
const rated = (): HouseRecord => ({ ...school(), rating: 4 });

describe('a school’s readable copy speaks in the school’s words (HTML and Markdown)', () => {
  for (const lang of LANGS) {
    it(`has no house word in ${lang}: the cover, the ranking, the headings and the empty line`, () => {
      const dict = DICTIONARIES[lang];
      for (const bundle of [copyOf([rated()], lang, 'school'), copyOf([], lang, 'school')]) {
        for (const text of [buildHtml(bundle, dict, FIXTURE_PHOTO_DATA_URIS), buildMarkdown(bundle, dict)]) {
          // The Cost column of a house is a school’s *Fees*; the words in the copy that are not the house’s stay.
          const stripped = text.replace(/<style>[\s\S]*<\/style>/, '').replace(/<[^>]+>/g, ' ');
          expect(stripped.match(HOUSE_WORD[lang]), lang).toBeNull();
        }
      }
    });
  }

  it('writes the school’s counts and options, a ranking of rank, school and status, and the fees under Fees', () => {
    const dict = DICTIONARIES.en;
    const bundle = copyOf([rated()], 'en', 'school');
    const md = buildMarkdown(bundle, dict);
    expect(md).toContain('# Doorprints — your schools');
    expect(md).toContain('Schools: 1 · Visits: 0 · Photos: 0');
    expect(md).toContain('- All schools');
    expect(md).toContain('- Rejected schools included');
    expect(md).toContain('- Photos of every school');
    expect(md).toContain('| No. | School | Status |\n| --- | --- | --- |');
    expect(md).toContain('### Fees\n\n| Detail | Value |\n| --- | --- |\n| Fees per year | ₹96,000 |\n| Admission fee | ₹25,000 |');
    // Details: the school’s own labels (a website, not a listing link), no price, no score, no BHK.
    expect(md).toContain('| Website |');
    for (const absent of ['Listing link', 'Overall score', 'Price', 'BHK', 'Carpet area']) expect(md, absent).not.toContain(absent);
    expect(md).toContain('| Your rating | 4 out of 5 stars |');
    expect(buildMarkdown(copyOf([], 'en', 'school'), dict)).toContain('No school matched the options you chose.');
    const html = buildHtml(bundle, dict, FIXTURE_PHOTO_DATA_URIS);
    expect(html).toContain('<title>Doorprints — your schools</title>');
    expect(html).toContain('<thead><tr><th scope="col">No.</th><th scope="col">School</th><th scope="col">Status</th></tr></thead>');
    expect(html).toContain('<h3>Fees</h3><table class="fields"><tr><th scope="row">Fees per year</th><td>₹96,000</td></tr>');
  });

  it('a space of schools with no school at all is still a school copy; a store of houses and the flag off are as they were', () => {
    expect(copyOf([], 'en', 'school').voice?.kind).toBe('school');
    expect(copyOf([], 'en').voice).toBeUndefined();
    expect(copyOf(FIXTURE_HOUSES, 'en', 'house').voice).toBeUndefined();
    expect(copyOf([school()], 'en', undefined, false).voice).toBeUndefined();
    const off = copyOf([school()], 'en', 'school', false);
    expect(off.voice).toBeUndefined();
    expect(buildMarkdown(off, DICTIONARIES.en)).toContain('Houses: 1');
  });

  it('a school beside a house keeps the house’s words for the copy and the school’s own for its section', () => {
    const mixed = copyOf([FIXTURE_HOUSES[0], rated()], 'en');
    expect(mixed.voice).toBeUndefined();
    const md = buildMarkdown(mixed, DICTIONARIES.en);
    expect(md).toContain('Houses: 2');
    expect(md).toContain('| No. | House | Score | Price | Status |');
    // Pine Hill’s own section: Fees, a website, no price row; the house’s: Cost and a listing link.
    const sections = md.split('\n## ');
    const own = sections.find((x) => /^\d+\. Pine Hill School/.test(x))!;
    expect(own).toContain('### Fees');
    expect(own).toContain('| Website |');
    expect(own).not.toContain('Listing link');
    expect(sections.find((x) => /^\d+\. Green View/.test(x))).toContain('Listing link');
  });
});

describe('a school’s tables (CSV and workbook) leave out what a school hides', () => {
  const EN = ExportStrings.of('en', 'school');
  const SCHOOL_COLUMNS = ['Rank', 'School', 'Status', 'Stars', 'Address', 'Street', 'Locality', 'Latitude', 'Longitude', 'Contact name', 'Phone', 'Website', 'Notes'];

  it('the Schools table: the school’s own columns, its extras before the visits, no price, score, BHK, cost, rooms, floor or broker column', () => {
    const table = exportTables(copyOf([rated()], 'en', 'school'))[0];
    expect(table.title).toBe('Schools');
    const columns = table.columns;
    expect(columns.slice(0, SCHOOL_COLUMNS.length)).toEqual(SCHOOL_COLUMNS);
    expect(columns.slice(SCHOOL_COLUMNS.length)).toEqual([
      'Location source', 'Board', 'Medium of instruction', 'Grades offered', 'Commute (minutes)', 'School bus', 'Fees per year',
      'Admission fee', 'Visits', 'Photos', 'Added', 'Last changed', 'Id',
    ]);
    expect(table.rows[0]).toHaveLength(columns.length);
  });

  it('the workbook and the CSV have the Schools, Visits and Photos tables, and no Scores table; a house copy has its four', () => {
    const school = copyOf([rated()], 'en', 'school');
    expect(buildWorkbook(school).map((s) => s.name)).toEqual(['houses', 'visits', 'photos']);
    expect(Object.keys(buildCsvTables(school))).toEqual(['houses.csv', 'visits.csv', 'photos.csv']);
    expect(buildWorkbook(copyOf(FIXTURE_HOUSES, 'en')).map((s) => s.name)).toEqual(['houses', 'scores', 'visits', 'photos', 'rooms', 'answers']);
  });

  for (const lang of LANGS) {
    it(`the Visits and Photos tables say School, not House, in ${lang}`, () => {
      const tables = exportTables(copyOf([rated()], lang, 'school'));
      const s = ExportStrings.of(lang, 'school');
      // The nouns are written out here (the school’s, in the app’s dictionaries too), not read back from the overlay.
      const SCHOOL: Record<Lang, [string, string]> = {
        en: ['School', 'School id'], hi: ['स्कूल', 'स्कूल आईडी'], ta: ['பள்ளி', 'பள்ளி அடையாளம்'], te: ['పాఠశాల', 'పాఠశాల ఐడీ'],
      };
      for (const t of tables.filter((x) => x.name === 'visits' || x.name === 'photos')) {
        expect(t.columns, t.name).toContain(SCHOOL[lang][0]);
        expect(t.columns, t.name).toContain(SCHOOL[lang][1]);
      }
      expect(tables.find((x) => x.name === 'houses')!.title).toBe(s.get('table.houses'));
      const house = ExportStrings.of(lang);
      expect(s.get('table.houses')).not.toBe(house.get('table.houses'));
      // Everything the overlay does not name is the house’s word, key for key.
      expect(s.get('col.visits')).toBe(house.get('col.visits'));
    });
  }
});
