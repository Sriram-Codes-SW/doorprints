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
import { LANGUAGES } from '../i18n/languages';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { buildCsvTables } from './csv-export';
import { buildMarkdown } from './markdown-export';
import { buildHtml } from './html-export';
import { BACKUP_APP, BACKUP_FORMAT, BACKUP_FORMAT_V2, BACKUP_FORMATS_READ, BACKUP_LIMITS, backupJson, buildBackupData, buildBackupZip } from './backup-export';
import { buildXlsx } from './xlsx-export';
import { buildWorkbook } from './xlsx-sheets';
import { display, exportTables, plain } from './export-rows';
import { ExportStrings } from './export-strings';
import { collect } from './export-model';
import type { CriterionRow } from '../shared/scoring';
import {
  FIXTURE_BROKERS,
  FIXTURE_CRITERIA,
  FIXTURE_EXPORTED_AT,
  FIXTURE_HOUSES,
  FIXTURE_OPTIONS,
  FIXTURE_PHOTOS,
  FIXTURE_PHOTO_DATA_URIS,
  FIXTURE_PHOTO_MAP,
  FIXTURE_PREFERENCES,
  FIXTURE_QUESTIONS,
  FIXTURE_VISITS,
  fixtureBundle,
} from './golden/fixture';
import {
  GOLDEN_ANSWERS_CSV,
  GOLDEN_BROKERS_CSV,
  GOLDEN_CRITERIA_CSV,
  GOLDEN_HOUSES_CSV,
  GOLDEN_PHOTOS_CSV,
  GOLDEN_SCORES_CSV,
  GOLDEN_VISITS_CSV,
} from './golden/csv.golden';
import { GOLDEN_MARKDOWN } from './golden/markdown.golden';
import { GOLDEN_HTML } from './golden/html.golden';
import { GOLDEN_BACKUP_DATA_JSON } from './golden/backup.golden';

const en = DICTIONARIES.en;
const MODIFIED_AT = new Date(FIXTURE_EXPORTED_AT);

describe('collect', () => {
  it('orders houses by createdAt then id, and never exports tombstones', () => {
    const bundle = fixtureBundle();
    expect(bundle.houses.map((h) => h.house.createdAt)).toEqual([
      '2026-09-01T06:00:00.000Z',
      '2026-09-02T06:00:00.000Z',
      '2026-09-03T06:00:00.000Z',
    ]);
    expect(bundle.houses.some((h) => h.house.deleted)).toBe(false);
    expect(bundle.counts).toEqual({ houses: 3, visits: 3, photos: 2 });
  });

  it('leaves out deleted visits', () => {
    const visits = fixtureBundle().houses.flatMap((h) => h.visits);
    // …aaa4 is a tombstone on house 3; …aaa1/…aaa2 are house 1's and …aaa3 is house 3's live one.
    expect(visits.map((v) => v.id.slice(-1))).toEqual(['1', '2', '3']);
  });

  /**
   * The ordering rule of `docs/schemas/README.md` §5 (ADR-20, NFR-023): visits and photos are **grouped by their
   * house**, in house order, not sorted globally. House 3's visit arrives *between* house 1's two, and house 3's
   * photo was created two days *before* house 1's, so the two rules give different answers on this fixture — which
   * is the whole reason those two rows exist. Pinned here in code, not only in a comment: a regression to a global
   * sort would flip both lists.
   */
  it('groups visits and photos under their house even when they sort earlier globally', () => {
    const bundle = fixtureBundle();
    expect(bundle.houses.flatMap((h) => h.visits.map((v) => v.id.slice(-4)))).toEqual(['aaa1', 'aaa2', 'aaa3']);
    expect(bundle.houses.flatMap((h) => h.photos.map((ph) => ph.id.slice(-4)))).toEqual(['bbb1', 'bbb2']);
    // What a global sort would have produced instead, so the two rules are visibly not the same list.
    const byArrival = bundle.houses
      .flatMap((h) => h.visits)
      .slice()
      .sort((a, b) => (a.arrivedAt < b.arrivedAt ? -1 : a.arrivedAt > b.arrivedAt ? 1 : 0));
    expect(byArrival.map((v) => v.id.slice(-4))).toEqual(['aaa1', 'aaa3', 'aaa2']);
  });

  it('ranks best first and puts unscored houses last', () => {
    // House 1: (3*5 + 2*3 + 2*4) / 7 = 4.14 from the checklist (Water is High in the fixture's criteria, the key from a
    // newer app is ignored), blended 60/40 with its 4 stars; house 3: only its star rating counts (Quiet is archived).
    const scores = fixtureBundle().ranking.map((entry) => entry.score);
    expect(scores[0]).toBeCloseTo(0.6 * (29 / 7) + 0.4 * 4, 10);
    expect(scores[1]).toBe(1);
    expect(scores[2]).toBeNull();
  });

  it('drops contact fields completely when the user leaves them out', () => {
    const bundle = fixtureBundle({ includeContacts: false });
    expect(bundle.houses.map((h) => h.house.contactName)).toEqual([null, null, null]);
    expect(bundle.houses.map((h) => h.house.contactPhone)).toEqual([null, null, null]);
    // The source records are untouched.
    expect(FIXTURE_HOUSES[0].contactPhone).toBe('+91 98400 11111');
  });

  it('carries the brokers, oldest edit first, with the houses of the copy that use them', () => {
    const bundle = fixtureBundle();
    expect(bundle.brokers.map((b) => b.broker.name)).toEqual(['Meena Iyer', 'Ravi Kumar']);
    expect(bundle.brokers.map((b) => b.houses.map((h) => h.id.slice(0, 2)))).toEqual([['33'], ['11']]);
  });

  it('leaves the brokers and every brokerId out when contacts are left out', () => {
    const bundle = fixtureBundle({ includeContacts: false });
    expect(bundle.brokers).toEqual([]);
    expect(bundle.houses.map((h) => h.house.brokerId)).toEqual([null, null, null]);
  });

  it('keeps every broker in a copy of all houses but only the used ones in a partial copy', () => {
    const extra = { id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', updatedAt: '2026-09-11T00:00:00.000Z', broker: { name: 'Unused' } };
    const input = {
      houses: FIXTURE_HOUSES,
      visits: FIXTURE_VISITS,
      photos: FIXTURE_PHOTOS,
      brokers: [...FIXTURE_BROKERS, extra],
      exportedAt: FIXTURE_EXPORTED_AT,
    };
    expect(collect({ ...input, options: FIXTURE_OPTIONS }).brokers.map((b) => b.broker.name)).toEqual(['Meena Iyer', 'Ravi Kumar', 'Unused']);
    expect(collect({ ...input, options: { ...FIXTURE_OPTIONS, scope: 'shortlisted' } }).brokers.map((b) => b.broker.name)).toEqual(['Ravi Kumar']);
  });

  it('honours the scope and rejected options', () => {
    expect(fixtureBundle({ scope: 'shortlisted' }).houses).toHaveLength(1);
    expect(fixtureBundle({ includeRejected: false }).houses.map((h) => h.house.status)).toEqual(['SHORTLISTED', 'NEW']);
    expect(fixtureBundle({ scope: 'selected', selectedIds: [FIXTURE_HOUSES[2].id] }).houses).toHaveLength(1);
  });

  it('honours the photo option', () => {
    expect(fixtureBundle({ photos: 'none' }).counts.photos).toBe(0);
    // House 1 is the only shortlisted house, so "shortlisted only" keeps its photo and drops house 3's.
    const shortlistedOnly = fixtureBundle({ photos: 'shortlisted' });
    expect(shortlistedOnly.counts.photos).toBe(1);
    expect(shortlistedOnly.houses.flatMap((h) => h.photos.map((p) => p.id.slice(-4)))).toEqual(['bbb1']);
  });
});

describe('CSV export', () => {
  const tables = buildCsvTables(fixtureBundle());

  it('matches the golden houses.csv', () => {
    expect(tables['houses.csv']).toBe(GOLDEN_HOUSES_CSV);
  });

  /**
   * Only **scored** items get a row now, in the shared display order with unknown keys last, which is what
   * `ExportRows.scores` does. The old web table wrote a row for every built-in item whether or not the house had
   * a score for it, so the two apps' score tables had different row counts for the same data.
   */
  it('matches the golden scores.csv (scored items only, fixed order, unknown keys last)', () => {
    expect(tables['scores.csv']).toBe(GOLDEN_SCORES_CSV);
  });

  it('matches the golden visits.csv and photos.csv', () => {
    expect(tables['visits.csv']).toBe(GOLDEN_VISITS_CSV);
    expect(tables['photos.csv']).toBe(GOLDEN_PHOTOS_CSV);
  });

  it('matches the golden brokers.csv, which only a copy with brokers has', () => {
    expect(tables['brokers.csv']).toBe(GOLDEN_BROKERS_CSV);
    const none = buildCsvTables(collect({ houses: FIXTURE_HOUSES, visits: FIXTURE_VISITS, photos: FIXTURE_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS }));
    expect(Object.keys(none).sort()).toEqual(['answers.csv', 'houses.csv', 'photos.csv', 'rooms.csv', 'scores.csv', 'visits.csv']);
    expect(Object.keys(buildCsvTables(fixtureBundle({ includeContacts: false })))).not.toContain('brokers.csv');
  });

  it('matches the golden criteria.csv, which only a copy with criterion records has', () => {
    expect(tables['criteria.csv']).toBe(GOLDEN_CRITERIA_CSV);
    const none = buildCsvTables(collect({ houses: FIXTURE_HOUSES, visits: FIXTURE_VISITS, photos: FIXTURE_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS }));
    expect(Object.keys(none)).not.toContain('criteria.csv');
    // Criteria are not contacts: a copy without contact details still has them.
    expect(Object.keys(buildCsvTables(fixtureBundle({ includeContacts: false })))).toContain('criteria.csv');
  });

  it('writes the broker column right after the phone: the name, and the agency in brackets', () => {
    const [header, first, second, third] = tables['houses.csv'].replace('\uFEFF', '').split('\r\n');
    const columns = header.split(',');
    expect(columns.slice(13, 16)).toEqual(['Contact name', 'Phone', 'Broker']);
    expect(first).toContain('Ravi Kumar (Adyar Homes)');
    expect(second).not.toContain('Adyar');
    expect(third).toContain('Meena Iyer (Beach Road Realty)');
  });

  it('guards against spreadsheet formula injection', () => {
    // A label starting with "=" and a phone number starting with "+" are both prefixed with an apostrophe.
    expect(tables['houses.csv']).toContain(",'=SUM(A1:A9)");
    expect(tables['houses.csv']).toContain(",'+91 98400 11111,");
  });

  it('starts every table with a BOM and uses CRLF line endings', () => {
    for (const text of Object.values(tables)) {
      expect(text.startsWith('\uFEFF')).toBe(true);
      expect(text.endsWith('\r\n')).toBe(true);
      expect(text.split('\r\n')[0]).not.toContain('\n');
    }
  });

  /**
   * The headings come from the shared `ExportStrings` table, so the export language option reaches CSV and XLSX
   * as it always has reached HTML and Markdown. Before `export-rows.ts` these headings were hardcoded English
   * API field names, which made the option a silent no-op for two of the six formats.
   */
  it('writes its headings in the export language, not the app language', () => {
    for (const language of LANGUAGES) {
      const header = buildCsvTables(fixtureBundle({ lang: language.code }))['houses.csv'].split('\r\n')[0];
      expect(header).toContain(ExportStrings.of(language.code).get('col.rank'));
    }
    expect(buildCsvTables(fixtureBundle({ lang: 'ta' }))['houses.csv']).not.toBe(
      buildCsvTables(fixtureBundle({ lang: 'en' }))['houses.csv'],
    );
  });

  /** Blank columns headed "Phone" in a file the user asked to have no contacts in would be a poor promise. */
  it('drops the contact columns entirely when contacts are excluded', () => {
    const header = buildCsvTables(fixtureBundle({ includeContacts: false }))['houses.csv'].split('\r\n')[0];
    expect(header).not.toContain(ExportStrings.of('en').get('col.contactPhone'));
    expect(header.split(',')).toHaveLength(36);
    expect(buildCsvTables(fixtureBundle())['houses.csv'].split('\r\n')[0].split(',')).toHaveLength(39);
  });

  /**
   * The formula guard is for **text** cells only, as in `CsvWriter.kt`. A leading minus is part of a number, and
   * an apostrophe in front of it would stop a spreadsheet reading -12.978321 as a coordinate; `deterministic.spec`
   * pins that case on `csvCell` itself.
   */
  it('does not put the formula guard in front of a number cell', () => {
    const negative = buildCsvTables(
      fixtureBundle({ scope: 'selected', selectedIds: [FIXTURE_HOUSES[0].id] }),
    )['houses.csv'];
    expect(negative).not.toContain(",'13.006000");
    expect(negative).toContain(',13.006000,');
  });
});

describe('Markdown export', () => {
  it('matches the golden file', () => {
    expect(buildMarkdown(fixtureBundle(), en)).toBe(GOLDEN_MARKDOWN);
  });

  it('escapes Markdown control characters in user text', () => {
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md).toContain('Too noisy \\| too dark');
    expect(md).toContain('\\<no smoking\\>');
  });

  it('lists the brokers after the houses, and leaves them out with the contacts', () => {
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md.indexOf('## Brokers')).toBeGreaterThan(md.indexOf('## 3. '));
    expect(buildMarkdown(fixtureBundle({ includeContacts: false }), en)).not.toContain('Brokers');
  });

  it('leaves contact rows out when contacts are excluded', () => {
    const md = buildMarkdown(fixtureBundle({ includeContacts: false }), en);
    expect(md).not.toContain('Ravi Kumar');
    expect(md).not.toContain('98400');
  });
});

describe('HTML export', () => {
  const html = buildHtml(fixtureBundle(), en, FIXTURE_PHOTO_DATA_URIS);

  it('matches the golden file', () => {
    expect(html).toBe(GOLDEN_HTML);
  });

  it('has a Brokers section after the houses, with the broker of a house in its own row', () => {
    expect(html.indexOf('<section class="brokers">')).toBeGreaterThan(html.lastIndexOf('<section class="house">'));
    expect(html).toContain('<th scope="row">Broker</th><td>Ravi Kumar (Adyar Homes)</td>');
    const without = buildHtml(fixtureBundle({ includeContacts: false }), en, FIXTURE_PHOTO_DATA_URIS);
    expect(without).not.toContain('class="brokers"');
    expect(without).not.toContain('Ravi Kumar');
  });

  it('is self-contained and runs no script', () => {
    expect(html).not.toContain('<script');
    expect(html).toContain("default-src 'none'; img-src data:; style-src 'unsafe-inline'");
    expect(html).toContain('data:image/jpeg;base64,');
    expect(html).not.toContain('http://');
  });

  it('escapes user text rather than rendering it as HTML', () => {
    expect(html).toContain('&lt;no smoking&gt;');
    expect(html).not.toContain('<no smoking>');
  });

  it('starts every house on its own page when printed', () => {
    expect(html).toContain('page-break-before: always');
    expect(html).toContain('@page { size: A4; margin: 14mm; }');
  });

  it('writes the chosen language into <html lang> and translates the headings', () => {
    for (const language of LANGUAGES) {
      const page = buildHtml(fixtureBundle({ lang: language.code }), DICTIONARIES[language.code], new Map());
      expect(page).toContain(`<html lang="${language.code}">`);
      expect(page).toContain(DICTIONARIES[language.code]['exp.ranking']);
    }
  });
});

describe('JSON backup', () => {
  /**
   * The importer limits, value for value with Kotlin `BackupFormat` in `:shared` (MAX_ENTRIES, MAX_UNCOMPRESSED_BYTES,
   * MAX_COMPRESSION_RATIO, MAX_DATA_JSON_BYTES). `data.json` is capped at 16 MiB, the number docs/01 SEC-041 and
   * docs/02 T-T8 state; this mirror used to say 64 MiB (docs/10 §11.3 row 7). Change both sides together.
   */
  it('mirrors the Kotlin importer limits exactly', () => {
    expect(BACKUP_LIMITS).toEqual({
      maxEntries: 5_000,
      maxUncompressedBytes: 1_073_741_824,
      maxCompressionRatio: 100,
      maxDataJsonBytes: 16_777_216,
    });
  });

  /**
   * S4b-BL-72: the web still writes `/1` (slice 1 of docs/11 5.30 is the first to write a new list) and a reader,
   * when S4b-BL-75 adds one, accepts `/1` and `/2` and refuses a newer number. Change together with Kotlin
   * `BackupFormat.READABLE` and the Android tests of it.
   */
  it('pins the backup format it writes and the formats a reader accepts', () => {
    expect(BACKUP_FORMAT).toBe('doorprints-backup/1');
    expect(BACKUP_FORMAT_V2).toBe('doorprints-backup/2');
    expect(BACKUP_FORMATS_READ).toEqual(['doorprints-backup/1', 'doorprints-backup/2']);
    expect(BACKUP_FORMATS_READ).toContain(BACKUP_FORMAT);
    expect(BACKUP_FORMATS_READ).toContain(BACKUP_FORMAT_V2);
  });

  it('matches the golden data.json', () => {
    const json = backupJson(buildBackupData(fixtureBundle()));
    expect(json).toBe(GOLDEN_BACKUP_DATA_JSON);
    // The byte count the golden's comment states, so a silent re-generation cannot quietly shrink the contract.
    expect(new TextEncoder().encode(json).length).toBe(4569);
  });

  /**
   * The rule `docs/schemas/README.md` §5 pins and the reason the fixture interleaves: **grouped by house**, not
   * sorted globally. House 3's visit arrives between house 1's two and house 3's photo predates house 1's, so a
   * regression to a global sort would move both of house 3's rows up — and this asserts they stay last.
   */
  it('groups the visit and photo rows by house, so house 3 comes last despite sorting earlier', () => {
    const data = buildBackupData(fixtureBundle());
    expect(data.visits.map((v) => v.id.slice(-4))).toEqual(['aaa1', 'aaa2', 'aaa3']);
    expect(data.photos.map((p) => p.id.slice(-4))).toEqual(['bbb1', 'bbb2']);
    // Globally the two rows would come earlier, which is what makes the assertion above meaningful.
    expect(data.visits[2].arrivedAt).toBeLessThan(data.visits[1].arrivedAt);
    expect(data.photos[1].createdAt).toBeLessThan(data.photos[0].createdAt);
  });

  it('uses the format id and the epoch-millisecond timestamps the Android writer uses', () => {
    const data = buildBackupData(fixtureBundle());
    expect(data.format).toBe(BACKUP_FORMAT_V2);
    expect(data.exportedAt).toBe(Date.parse(FIXTURE_EXPORTED_AT));
    expect(data.houses[0].updatedAt).toBe(Date.parse('2026-09-10T08:30:00.000Z'));
    expect(data.visits[1].leftAt).toBeUndefined();
    expect(data.photos[0].fileName).toBe(`${FIXTURE_PHOTOS[0].id}.jpg`);
  });

  it('leaves null optional fields out instead of writing them, like kotlinx explicitNulls = false', () => {
    const json = backupJson(buildBackupData(fixtureBundle()));
    expect(json).not.toContain('null');
    expect(json).not.toContain('"dirty"');
    expect(json).not.toContain('"syncVersion"');
    expect(json).not.toContain('"deleted"');
  });

  /** The rule of docs/schemas README 1.1: `/2` when the copy holds brokers or rooms. */
  it('writes /2 with rooms and no brokers, and /2 with the list when it has brokers', () => {
    const plain2Rooms = buildBackupData(
      collect({ houses: FIXTURE_HOUSES, visits: FIXTURE_VISITS, photos: FIXTURE_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS }),
    );
    expect(plain2Rooms.format).toBe(BACKUP_FORMAT_V2);
    expect(Object.keys(plain2Rooms)).toEqual(['format', 'exportedAt', 'houses', 'visits', 'photos']);
    const withBrokers = buildBackupData(fixtureBundle());
    expect(withBrokers.format).toBe(BACKUP_FORMAT_V2);
    expect(withBrokers.brokers?.map((b) => b.id.slice(0, 2))).toEqual(['bb', 'aa']);
    expect(Object.keys(withBrokers.brokers?.[1] ?? {})).toEqual(['id', 'name', 'phone', 'agency', 'feeTerms', 'notes', 'rating', 'updatedAt']);
  });

  it('leaves the brokers and every brokerId out of a copy without contact details, but keeps rooms and writes /2', () => {
    const data = buildBackupData(fixtureBundle({ includeContacts: false }));
    expect(data.format).toBe(BACKUP_FORMAT_V2);
    expect(data.brokers).toBeUndefined();
    const json = backupJson(data);
    expect(json).not.toContain('brokerId');
    expect(json).not.toContain('"brokers"');
    // Rooms are kept even without contacts
    expect(json).toContain('"rooms"');
  });

  it('counts the brokers in the manifest of a /2 copy only', () => {
    const withBrokers = new TextDecoder().decode(buildBackupZip(fixtureBundle(), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT));
    expect(withBrokers).toContain('"format":"doorprints-backup/2"');
    expect(withBrokers).toContain('"counts":{"houses":3,"visits":3,"photos":2,"brokers":2,"criteria":3,"preferences":1,"questions":3}');
    const without = new TextDecoder().decode(buildBackupZip(fixtureBundle({ includeContacts: false }), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT));
    // Criteria and preferences are not contacts: a copy without contact details keeps them, and their counts.
    expect(without).toContain('"counts":{"houses":3,"visits":3,"photos":2,"criteria":3,"preferences":1,"questions":3}');
  });

  it('writes the manifest last, with a SHA-256 for every other entry', () => {
    const bytes = buildBackupZip(fixtureBundle(), FIXTURE_PHOTO_MAP, '<html></html>', MODIFIED_AT);
    const text = new TextDecoder().decode(bytes);
    expect(text).toContain('data.json');
    expect(text).toContain('Doorprints-copy-2026-09-22.html');
    expect(text).toContain(`photos/${FIXTURE_PHOTOS[0].id}.jpg`);
    expect(text).toContain('manifest.json');
    expect(text).toContain(`"app":"${BACKUP_APP}"`);
    expect(text).toContain('"photoScope":"ALL"');
    expect(text).toContain('"scope":"ALL"');
  });

  it('is byte-for-byte reproducible', () => {
    const once = buildBackupZip(fixtureBundle(), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT);
    const twice = buildBackupZip(fixtureBundle(), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT);
    expect(Array.from(once)).toEqual(Array.from(twice));
  });
});

describe('shared ExportRows contract', () => {
  /**
   * `ExportRows.kt` states the contract: "Every exporter on Android and on the web reads its rows from here, so
   * a CSV, an XLSX sheet and the HTML table always show the same values in the same order, and the two apps
   * agree cell for cell." These pin the parts of it that the web CSV and XLSX used to get wrong.
   */
  it('uses one column list for the CSV and the workbook', () => {
    const tables = exportTables(fixtureBundle());
    const sheets = buildWorkbook(fixtureBundle());
    expect(sheets.map((s) => s.name)).toEqual(['houses', 'scores', 'visits', 'photos', 'brokers', 'rooms', 'criteria', 'answers']);
    sheets.forEach((sheet, i) => {
      expect(sheet.header).toEqual(tables[i].columns);
      expect(sheet.rows).toHaveLength(tables[i].rows.length);
    });
    const csvHeader = buildCsvTables(fixtureBundle())['houses.csv'].split('\r\n')[0].replace('\uFEFF', '');
    expect(csvHeader.split(',')).toEqual(tables[0].columns);
  });

  it('ranks each house, and counts its visits and photos, in the houses table', () => {
    const houses = exportTables(fixtureBundle())[0];
    // H2 has no score, so it ranks last even though it is second by createdAt.
    expect(houses.rows.map((row) => plain(row[0]))).toEqual(['1', '3', '2']);
    const visitsColumn = houses.columns.indexOf(ExportStrings.of('en').get('col.visits'));
    expect(houses.rows.map((row) => plain(row[visitsColumn]))).toEqual(['2', '0', '1']);
  });

  /**
   * The `visits` and `photos` **tables** are the one place that is deliberately *not* grouped: `ExportBundle` on
   * Android keeps one flat, globally sorted list and `ExportRows.visits/photos` read it straight, so the web
   * flattening sorts globally too (see the comment on `flatVisits` in export-rows.ts) and the two apps' CSV and
   * XLSX agree row for row. The JSON backup is the grouped one. With the interleaving fixture rows the two orders
   * differ, so both are now pinned and neither can be "fixed" into the other by accident.
   */
  it('sorts the visits and photos tables globally, unlike the grouped JSON backup', () => {
    const [, , visits, photos] = exportTables(fixtureBundle());
    expect(visits.rows.map((row) => plain(row[row.length - 1]).slice(-4))).toEqual(['aaa1', 'aaa3', 'aaa2']);
    expect(photos.rows.map((row) => plain(row[row.length - 1]).slice(-4))).toEqual(['bbb2', 'bbb1']);
  });

  it('lists only scored checklist items, built-in order first then unknown keys', () => {
    const scores = exportTables(fixtureBundle())[1];
    expect(scores.rows.map((row) => plain(row[1]))).toEqual([
      'water',
      'power',
      'parking',
      'newItemFromNewerApp',
      'noise',
    ]);
  });

  it('computes whole minutes for a finished visit and nothing for an open one', () => {
    const visits = exportTables(fixtureBundle())[2];
    const minutes = visits.columns.indexOf(ExportStrings.of('en').get('col.minutes'));
    // Globally sorted: house 1's finished visit, house 3's finished visit, then house 1's still-open one.
    expect(visits.rows.map((row) => plain(row[minutes]))).toEqual(['25', '30', '']);
  });

  it('renders a money cell as a plain number for machines and with ₹ for readers', () => {
    expect(plain({ kind: 'money', amount: 1250000 })).toBe('1250000');
    expect(display({ kind: 'money', amount: 1250000 }, ExportStrings.of('en'))).toBe('₹12,50,000');
    expect(display({ kind: 'blank' }, ExportStrings.of('en'))).toBe('—');
  });
});

describe('XLSX export', () => {
  it('is a ZIP with one worksheet per table', () => {
    const bytes = buildXlsx(buildWorkbook(fixtureBundle()), MODIFIED_AT);
    const text = new TextDecoder('latin1').decode(bytes);
    expect(text.startsWith('PK\u0003\u0004')).toBe(true);
    for (const part of [
      '[Content_Types].xml',
      'xl/workbook.xml',
      'xl/styles.xml',
      'xl/worksheets/sheet1.xml',
      'xl/worksheets/sheet4.xml',
    ]) {
      expect(text).toContain(part);
    }
  });

  it('is byte-for-byte reproducible', () => {
    const once = buildXlsx(buildWorkbook(fixtureBundle()), MODIFIED_AT);
    const twice = buildXlsx(buildWorkbook(fixtureBundle()), MODIFIED_AT);
    expect(Array.from(once)).toEqual(Array.from(twice));
  });

  it('writes a formula-looking label as an inline string, never as a formula', () => {
    const bytes = buildXlsx(buildWorkbook(fixtureBundle()), MODIFIED_AT);
    const text = new TextDecoder().decode(bytes);
    expect(text).toContain('t="inlineStr"');
    expect(text).not.toContain('<f>');
  });
});

describe('determinism across runs', () => {
  it('produces identical text for the same data and options', () => {
    const a = collect({
      houses: FIXTURE_HOUSES,
      visits: FIXTURE_VISITS,
      photos: FIXTURE_PHOTOS,
      exportedAt: FIXTURE_EXPORTED_AT,
      options: FIXTURE_OPTIONS,
    });
    // Same inputs in a different order must still come out in the fixed export order.
    const b = collect({
      houses: [...FIXTURE_HOUSES].reverse(),
      visits: [...FIXTURE_VISITS].reverse(),
      photos: [...FIXTURE_PHOTOS].reverse(),
      exportedAt: FIXTURE_EXPORTED_AT,
      options: { ...FIXTURE_OPTIONS },
    });
    expect(buildMarkdown(b, en)).toBe(buildMarkdown(a, en));
    expect(buildCsvTables(b)).toEqual(buildCsvTables(a));
    expect(buildHtml(b, en, FIXTURE_PHOTO_DATA_URIS)).toBe(buildHtml(a, en, FIXTURE_PHOTO_DATA_URIS));
  });
});

/** Slice 2 (docs/11 5.4): criteria and the ranking in the copies. */
describe('criteria and ranking in the copies', () => {
  const noRooms = FIXTURE_HOUSES.map((h) => ({ ...h, rooms: null, answers: null }));
  const base = { houses: noRooms, visits: FIXTURE_VISITS, photos: FIXTURE_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS };
  /** House 1 scored 3 for Power, and Power is made a must-have from 4: it misses it. */
  const powerMustHave: CriterionRow = { key: 'power', updatedAt: '2026-09-11T00:00:00.000Z', criterion: { key: 'power', weight: 2, mustHave: true, minScore: 4, sort: 1 } };
  const custom: CriterionRow = { key: 'c_1a2b3c4d', updatedAt: '2026-09-03T06:00:00.000Z', criterion: FIXTURE_CRITERIA[1].criterion };

  it('writes /1 with no broker, room, answer, criterion, preference or question, and /2 with only criteria or only a preference', () => {
    expect(buildBackupData(collect(base)).format).toBe(BACKUP_FORMAT);
    const onlyCriteria = buildBackupData(collect({ ...base, criteria: FIXTURE_CRITERIA }));
    expect(onlyCriteria.format).toBe(BACKUP_FORMAT_V2);
    expect(Object.keys(onlyCriteria)).toEqual(['format', 'exportedAt', 'houses', 'visits', 'photos', 'criteria']);
    const onlyPreference = buildBackupData(collect({ ...base, preferences: FIXTURE_PREFERENCES }));
    expect(onlyPreference.format).toBe(BACKUP_FORMAT_V2);
    expect(Object.keys(onlyPreference)).toEqual(['format', 'exportedAt', 'houses', 'visits', 'photos', 'preferences']);
  });

  it('keeps criteria and preferences in a copy without contact details', () => {
    const data = buildBackupData(fixtureBundle({ includeContacts: false }));
    expect(data.brokers).toBeUndefined();
    expect(data.criteria?.map((c) => c.key)).toEqual(['noise', 'c_1a2b3c4d', 'water']);
    expect(data.preferences).toEqual([{ key: 'score.ratingShare', value: '0.4', updatedAt: 1789029000000 }]);
  });

  it('writes the criteria by last edit then key, the label of a custom one only, and archived only when true', () => {
    const rows = buildBackupData(fixtureBundle()).criteria ?? [];
    expect(rows.map((c) => c.updatedAt)).toEqual([1788328800000, 1788415200000, 1789029000000]);
    expect(Object.keys(rows[0])).toEqual(['key', 'weight', 'mustHave', 'minScore', 'sort', 'archived', 'updatedAt']);
    expect(Object.keys(rows[1])).toEqual(['key', 'label', 'weight', 'mustHave', 'minScore', 'sort', 'updatedAt']);
    expect(Object.keys(rows[2])).toEqual(['key', 'weight', 'mustHave', 'minScore', 'sort', 'updatedAt']);
  });

  it('shows the label of a custom criterion in the scores table and on the house page', () => {
    const scored = [{ ...noRooms[0], checklist: { ...noRooms[0].checklist, c_1a2b3c4d: 5 } }, ...noRooms.slice(1)];
    const bundle = collect({ ...base, houses: scored, criteria: FIXTURE_CRITERIA, preferences: FIXTURE_PREFERENCES });
    const scores = buildCsvTables(bundle)['scores.csv'];
    expect(scores).toContain('Green View 2BHK,c_1a2b3c4d,Pets allowed,5,');
    expect(buildMarkdown(bundle, en)).toContain('| Pets allowed | 5 out of 5 |');
    expect(buildHtml(bundle, en, new Map())).toContain('<th scope="row">Pets allowed</th><td>5 out of 5</td>');
    // Without the criterion record the raw key is all there is, as before.
    const bare = collect({ ...base, houses: scored });
    expect(buildCsvTables(bare)['scores.csv']).toContain('Green View 2BHK,c_1a2b3c4d,c_1a2b3c4d,5,');
  });

  it('puts a house that misses a must-have last, marks it "Must-have missed" and names what it missed', () => {
    const bundle = collect({ ...base, criteria: [powerMustHave] });
    expect(bundle.ranking.map((e) => e.house.id.slice(0, 2))).toEqual(['33', '22', '11']);
    expect(bundle.ranking.map((e) => e.result.failedMustHave)).toEqual([[], [], ['power']]);
    const md = buildMarkdown(bundle, en);
    expect(md).toContain('| No. | House | Score | Price | Status | Must-haves |');
    expect(md).toContain('| 3 | Green View 2BHK | 4.0 | ₹32,000/month | ★ Shortlisted | ✕ Must-have missed |');
    expect(md).toContain('| Must-have missed | Power backup |');
    expect(md).toContain('| Coverage | Scored 3 of 10 that matter |');
    const html = buildHtml(bundle, en, new Map());
    expect(html).toContain('<td>✕ Must-have missed</td>');
    expect(html).toContain('<th scope="row">Must-have missed</th><td>Power backup</td>');
    // No column at all when nobody misses one.
    expect(buildMarkdown(fixtureBundle(), en)).not.toContain('Must-haves |');
  });

  it('adds a Criteria sheet to the workbook and a coverage line and the rating share to the readable copies', () => {
    expect(buildWorkbook(fixtureBundle()).map((sheet) => sheet.name)).toContain('criteria');
    expect(buildWorkbook(collect(base)).map((sheet) => sheet.name)).not.toContain('criteria');
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md).toContain('Rating counts for 40%');
    expect(md).toContain('## Criteria');
    expect(buildMarkdown(collect(base), en)).not.toContain('Rating counts for');
  });

  it('names the weights in the export language, and the four languages carry the criteria words (Under review outside English)', () => {
    for (const language of LANGUAGES) {
      const strings = ExportStrings.of(language.code);
      const names = [0, 1, 2, 3].map((w) => strings.get(`weight.${w as 0 | 1 | 2 | 3}`));
      expect(new Set(names).size, language.code).toBe(4);
      for (const key of ['table.criteria', 'col.weight', 'col.mustHave', 'col.minScore', 'col.archived', 'col.ratingShare'] as const) {
        expect(strings.get(key).trim(), `${language.code}/${key}`).not.toBe('');
      }
    }
    expect(ExportStrings.of('ta').get('weight.3')).not.toBe(ExportStrings.of('en').get('weight.3'));
  });

  it('orders equal scores as the contract says and keeps the rank column in that order', () => {
    const bundle = collect({ ...base, criteria: [powerMustHave, custom] });
    const houses = exportTables(bundle)[0];
    expect(houses.rows.map((row) => plain(row[0]))).toEqual(['3', '2', '1']);
  });
});

/** Slice 3a (docs/11 5.5): the viewing questions in the copies. */
describe('viewing questions in the copies', () => {
  const plainHouses = FIXTURE_HOUSES.map((h) => ({ ...h, rooms: null, answers: null }));
  const base = { houses: plainHouses, visits: FIXTURE_VISITS, photos: FIXTURE_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS };

  it('matches the golden answers.csv, which only a copy with an answer has', () => {
    expect(buildCsvTables(fixtureBundle())['answers.csv']).toBe(GOLDEN_ANSWERS_CSV);
    expect(Object.keys(buildCsvTables(collect(base)))).not.toContain('answers.csv');
    // Answers are not contacts: a copy without contact details still has them.
    expect(Object.keys(buildCsvTables(fixtureBundle({ includeContacts: false })))).toContain('answers.csv');
  });

  it('writes no questions.csv: the bank is settings and travels in the backup', () => {
    expect(Object.keys(buildCsvTables(fixtureBundle()))).not.toContain('questions.csv');
  });

  it('puts the Questions section after the Rooms table and before the checklist, open ones first, "–" for no answer', () => {
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md.indexOf('### Rooms')).toBeLessThan(md.indexOf('### Questions'));
    expect(md.indexOf('### Questions')).toBeLessThan(md.indexOf('### Checklist'));
    expect(md).toContain('| Is the terrace open to tenants? | – | Open |');
    expect(md.indexOf('Is the terrace open')).toBeLessThan(md.indexOf('How much is the maintenance per month'));
    const html = buildHtml(fixtureBundle(), en, FIXTURE_PHOTO_DATA_URIS);
    expect(html.indexOf('<h3>Rooms</h3>')).toBeLessThan(html.indexOf('<h3>Questions</h3>'));
    expect(html.indexOf('<h3>Questions</h3>')).toBeLessThan(html.indexOf('<h3>Checklist</h3>'));
    expect(html).toContain('<td>Answered</td>');
    expect(buildMarkdown(collect(base), en)).not.toContain('### Questions');
    expect(buildHtml(collect(base), en, new Map())).not.toContain('<h3>Questions</h3>');
  });

  it('lists the answers by house then open first in the table, with the three ids', () => {
    const table = exportTables(fixtureBundle()).find((t) => t.name === 'answers');
    expect(table?.columns).toEqual(['House', 'Question', 'Answer', 'Status', 'House id', 'Id', 'Question id']);
    expect(table?.rows.map((row) => plain(row[5]))).toEqual([
      'a2222222-2222-4222-8222-222222222222',
      'a1111111-1111-4111-8111-111111111111',
    ]);
    expect(table?.rows.map((row) => plain(row[6]))).toEqual(['', 'qd_maintenance']);
  });

  it('adds an Answers sheet to the workbook only when a house has answers', () => {
    expect(buildWorkbook(fixtureBundle()).map((sheet) => sheet.name)).toContain('answers');
    expect(buildWorkbook(collect(base)).map((sheet) => sheet.name)).not.toContain('answers');
  });

  it('writes /1 with neither, /2 with only questions, /2 with only answers', () => {
    expect(buildBackupData(collect(base)).format).toBe(BACKUP_FORMAT);
    const onlyQuestions = buildBackupData(collect({ ...base, questions: FIXTURE_QUESTIONS }));
    expect(onlyQuestions.format).toBe(BACKUP_FORMAT_V2);
    expect(Object.keys(onlyQuestions)).toEqual(['format', 'exportedAt', 'houses', 'visits', 'photos', 'questions']);
    const onlyAnswers = buildBackupData(collect({ ...base, houses: plainHouses.map((h, i) => (i === 0 ? { ...h, answers: FIXTURE_HOUSES[0].answers } : h)) }));
    expect(onlyAnswers.format).toBe(BACKUP_FORMAT_V2);
    expect(Object.keys(onlyAnswers)).toEqual(['format', 'exportedAt', 'houses', 'visits', 'photos']);
    expect(onlyAnswers.houses[0].answers).toHaveLength(2);
  });

  it('keeps the questions and the answers whole in a copy without contact details, phone number and all', () => {
    const withPhone = FIXTURE_HOUSES.map((h, i) =>
      i === 0 ? { ...h, answers: [{ id: 'a1', text: 'Who do I call?', answer: 'Call 98400 11111', status: 'ANSWERED' as const, sort: 0 }] } : h,
    );
    const data = buildBackupData(collect({ ...base, houses: withPhone, questions: FIXTURE_QUESTIONS, options: { ...FIXTURE_OPTIONS, includeContacts: false } }));
    expect(data.questions).toHaveLength(3);
    expect(data.houses[0].answers?.[0].answer).toBe('Call 98400 11111');
    expect(data.houses[0].brokerId).toBeUndefined();
  });

  it('writes the questions by last edit then id, archived only when true, and counts them only when there are some', () => {
    const rows = buildBackupData(fixtureBundle()).questions ?? [];
    expect(rows.map((q) => q.id)).toEqual(['qd_deposit', 'qd_maintenance', 'q_9f8e7d6c']);
    expect(rows.map((q) => 'archived' in q)).toEqual([false, false, true]);
    expect(Object.keys(rows[0])).toEqual(['id', 'text', 'category', 'appliesTo', 'defaultOn', 'sort', 'updatedAt']);
    const without = new TextDecoder().decode(buildBackupZip(collect(base), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT));
    expect(without).not.toContain('"questions"');
  });

  it('names the statuses in the export language, and the four languages carry the question words (Under review outside English)', () => {
    for (const language of LANGUAGES) {
      const strings = ExportStrings.of(language.code);
      const names = (['OPEN', 'ANSWERED', 'SKIPPED'] as const).map((s) => strings.get(`answerStatus.${s}`));
      expect(new Set(names).size, language.code).toBe(3);
      for (const key of ['section.questions', 'table.answers', 'col.question', 'col.answer', 'col.questionId'] as const) {
        expect(strings.get(key).trim(), `${language.code}/${key}`).not.toBe('');
      }
    }
    expect(ExportStrings.of('hi').get('answerStatus.OPEN')).not.toBe(ExportStrings.of('en').get('answerStatus.OPEN'));
  });
});
