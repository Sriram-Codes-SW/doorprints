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

// Typed extras in search and in the readable copies (kinds PR 6, S4b-BL-210; docs/03 ADR-36 §18.10), the website's half
// of docs/schemas/kinds/kind-search-vectors.json: a school's extras are found by the list search in en, hi, ta and te,
// appear in the CSV, XLSX, HTML and Markdown copies with the kind's labels and their type's form, and nothing of it
// happens while the developer flag is off or for a house. The expected words and cells are the vectors', never
// recomputed with the code under test; the labels are school-vectors.json's.
import { afterEach, describe, expect, it } from 'vitest';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import schoolJson from '../../../../docs/schemas/kinds/school.json';
import schoolVectorsJson from '../../../../docs/schemas/kinds/school-vectors.json';
import vectorsJson from '../../../../docs/schemas/kinds/kind-search-vectors.json';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import type { Lang } from '../i18n/languages';
import type { TKey } from '../i18n/en';
import { HOUSE_FIELDS } from '../core/kind-fields';
import { SCHOOL_FIELDS } from '../core/school-kind';
import { DEV_KINDS_KEY, builtInKind } from '../core/dev-kinds';
import { houseOf, type CandidateDto } from '../core/candidate';
import { tryHouseFromDto, type HouseRecord } from '../data/records';
import { searchText } from '../pages/map/map-list';
import { tr } from './deterministic';
import { collect, DEFAULT_EXPORT_OPTIONS } from './export-model';
import { buildCsvTables } from './csv-export';
import { buildMarkdown } from './markdown-export';
import { buildHtml } from './html-export';
import { buildWorkbook } from './xlsx-sheets';
import { exportTables, plain } from './export-rows';
import { FIXTURE_HOUSES, fixtureBundle } from './golden/fixture';
import { FIXTURE_PHOTO_DATA_URIS } from './golden/fixture';
import type { CopyKinds } from './kind-copy';
import { ExportStrings } from './export-strings';

interface CopyCase { id: string; text: string; cell: string; xlsx: 'text' | 'number' | 'money' }
interface Vectors {
  format: string;
  searched: string[];
  search: Record<Lang, string[]>;
  invalid: { extras: Record<string, unknown>; words: string[]; copies: CopyCase[] };
  flagOff: { words: string[]; copies: CopyCase[]; columns: number };
  copies: Record<Lang, CopyCase[]>;
}
const V = vectorsJson as unknown as Vectors;
const LABELS = (schoolVectorsJson as unknown as { kind: { labels: { web: Record<Lang, Record<string, string>> } } }).kind.labels.web;
const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
const school = (): HouseRecord =>
  tryHouseFromDto(houseOf((schoolVectorsJson as unknown as { candidate: CandidateDto }).candidate))!;
const wordsOf = (lang: Lang) => (key: TKey) => tr(DICTIONARIES[lang], key);
const kindsFor = (lang: Lang, flag: boolean): CopyKinds => ({ fieldsOf: (k) => builtInKind(k, flag).fields, dict: DICTIONARIES[lang] });
const copyOf = (houses: HouseRecord[], lang: Lang, flag: boolean) =>
  collect({ houses, visits: [], photos: [], exportedAt: '2026-10-13T10:00:00.000Z', options: { ...DEFAULT_EXPORT_OPTIONS, lang }, kinds: kindsFor(lang, flag) });

afterEach(() => localStorage.removeItem(DEV_KINDS_KEY));

describe('the kind files name what search reads', () => {
  it('the school searches all seven extras; the house’s list is unchanged and none of its extras is read through the kind', () => {
    expect(V.format).toBe('doorprints-kind-search-vectors/1');
    expect(schoolJson.search.filter((id) => schoolJson.extras.some((f) => f.id === id))).toEqual(V.searched);
    expect(SCHOOL_FIELDS.search).toEqual(schoolJson.search);
    expect(SCHOOL_FIELDS.others(true).map((f) => f.id)).toEqual(V.searched);
    expect(HOUSE_FIELDS.search).toEqual(houseJson.search);
    expect(HOUSE_FIELDS.others()).toEqual([]);
  });
});

describe('a school is found by its extras (the list search)', () => {
  for (const lang of LANGS) {
    it(`adds the vectors’ words after everything else in ${lang}`, () => {
      const h = school();
      const before = searchText(h);
      expect(searchText(h, '', [], undefined, wordsOf(lang), SCHOOL_FIELDS)).toBe([before, ...V.search[lang]].join(' ').toLowerCase());
    });
  }

  it('reads the candidate’s kind as the page does: the school only while the developer flag is on', () => {
    const h = school();
    const before = searchText(h);
    expect(searchText(h, '', [], undefined, wordsOf('hi'))).toBe(before);
    expect(V.flagOff.words).toEqual([]);
    localStorage.setItem(DEV_KINDS_KEY, 'school');
    expect(searchText(h, '', [], undefined, wordsOf('hi'))).toBe([before, ...V.search.hi].join(' ').toLowerCase());
    expect(searchText(h).includes('cbse')).toBe(false);
  });

  it('a value of the wrong type, an option the kind does not offer, an empty text and a switch that is off add nothing', () => {
    const h: HouseRecord = { ...school(), extrasOther: V.invalid.extras as HouseRecord['extrasOther'] };
    expect(searchText(h, '', [], undefined, wordsOf('en'), SCHOOL_FIELDS)).toBe(searchText(h));
    expect(V.invalid.words).toEqual([]);
  });

  it('a house adds no word, whatever its extras', () => {
    for (const h of FIXTURE_HOUSES) expect(searchText(h, '', [], undefined, wordsOf('ta'), HOUSE_FIELDS)).toBe(searchText(h));
  });
});

describe('a school’s extras in the copies', () => {
  for (const lang of LANGS) {
    it(`each extra with its label and its type’s form, in the CSV, the workbook, the HTML and the Markdown, in ${lang}`, () => {
      const bundle = copyOf([school()], lang, true);
      const want = V.copies[lang];
      const labels = want.map((c) => LABELS[lang][`field.${c.id}`]);
      expect(bundle.houses[0].extras!.map((e) => [e.label, e.text, plain(e.cell)])).toEqual(want.map((c, i) => [labels[i], c.text, c.cell]));

      // A column per extra right after the floor, then the visits, as the house's columns were.
      const s = ExportStrings.of(lang);
      const houses = exportTables(bundle)[0];
      const start = houses.columns.indexOf(s.get('col.floor')) + 1;
      expect(houses.columns.slice(start, start + labels.length + 1)).toEqual([...labels, s.get('col.visits')]);
      expect(houses.columns.length).toBe(exportTables(copyOf([], lang, true))[0].columns.length + want.length);
      const csv = buildCsvTables(bundle)['houses.csv'].replace('﻿', '').split('\r\n');
      expect(csv[0].split(',')).toEqual(houses.columns);
      expect(houses.rows[0].slice(start, start + want.length).map(plain)).toEqual(want.map((c) => c.cell));
      const sheet = buildWorkbook(bundle)[0];
      expect(sheet.header.slice(start, start + labels.length)).toEqual(labels);
      expect(sheet.rows[0].slice(start, start + want.length).map((c) => c.kind === 'text' ? 'text' : c.kind)).toEqual(want.map((c) => c.xlsx));

      const html = buildHtml(bundle, DICTIONARIES[lang], FIXTURE_PHOTO_DATA_URIS);
      const md = buildMarkdown(bundle, DICTIONARIES[lang]);
      const saved = html.indexOf(`<th scope="row">${tr(DICTIONARIES[lang], 'exp.fieldSaved')}</th>`);
      let last = -1;
      for (const [i, c] of want.entries()) {
        const at = html.indexOf(`<tr><th scope="row">${labels[i]}</th><td>${c.text}</td></tr>`);
        expect(at, c.id).toBeGreaterThan(last);
        last = at;
        expect(md, c.id).toContain(`| ${labels[i]} | ${c.text} |`);
      }
      expect(saved).toBeGreaterThan(last);
    });
  }

  it('with the developer flag off a school is copied as a house: no extra row and no extra column', () => {
    const off = copyOf([school()], 'en', false);
    expect([off.houses[0].extras ?? [], (off.extraColumns ?? []).length]).toEqual([V.flagOff.copies, V.flagOff.columns]);
    expect(exportTables(off)[0].columns).toEqual(exportTables(copyOf([], 'en', false))[0].columns);
    const invalid = copyOf([{ ...school(), extrasOther: V.invalid.extras as HouseRecord['extrasOther'] }], 'en', true);
    expect((invalid.houses[0].extras ?? []).map((e) => [e.key, e.text, plain(e.cell)]))
      .toEqual(V.invalid.copies.map((c) => [`school.${c.id}`, c.text, c.cell]));
    expect((invalid.extraColumns ?? []).length).toBe(V.copies.en.length);
  });

  it('a house beside a school has blank extra cells and no extra rows', () => {
    const mixed = copyOf([FIXTURE_HOUSES[0], school()], 'en', true);
    const table = exportTables(mixed)[0];
    const start = table.columns.indexOf(ExportStrings.of('en').get('col.floor')) + 1;
    const house = table.rows.find((r) => plain(r[table.columns.indexOf(ExportStrings.of('en').get('col.id'))]) === FIXTURE_HOUSES[0].id)!;
    expect(house.slice(start, start + V.copies.en.length).map(plain)).toEqual(V.copies.en.map(() => ''));
    expect(mixed.houses.find((e) => e.house.id === FIXTURE_HOUSES[0].id)!.extras).toBeUndefined();
  });

  it('a copy of houses read through the kinds is byte for byte the copy made without them, in every language', () => {
    for (const lang of LANGS) {
      const plainCopy = fixtureBundle({ lang });
      const viaKinds = fixtureBundle({ lang }, FIXTURE_HOUSES, kindsFor(lang, true));
      expect(viaKinds.extraColumns, lang).toBeUndefined();
      expect(buildCsvTables(viaKinds), lang).toEqual(buildCsvTables(plainCopy));
      expect(buildMarkdown(viaKinds, DICTIONARIES[lang]), lang).toBe(buildMarkdown(plainCopy, DICTIONARIES[lang]));
      expect(buildHtml(viaKinds, DICTIONARIES[lang], FIXTURE_PHOTO_DATA_URIS), lang).toBe(buildHtml(plainCopy, DICTIONARIES[lang], FIXTURE_PHOTO_DATA_URIS));
    }
  });
});
