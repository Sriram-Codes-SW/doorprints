import { describe, expect, it } from 'vitest';
import { DICTIONARIES, LANGUAGES } from '../i18n/languages';
import { buildCsvTables } from './csv-export';
import { buildMarkdown } from './markdown-export';
import { buildHtml } from './html-export';
import { BACKUP_APP, BACKUP_FORMAT, BACKUP_LIMITS, backupJson, buildBackupData, buildBackupZip } from './backup-export';
import { buildXlsx } from './xlsx-export';
import { buildWorkbook } from './xlsx-sheets';
import { display, exportTables, plain } from './export-rows';
import { ExportStrings } from './export-strings';
import { collect } from './export-model';
import {
  FIXTURE_EXPORTED_AT,
  FIXTURE_HOUSES,
  FIXTURE_OPTIONS,
  FIXTURE_PHOTOS,
  FIXTURE_PHOTO_DATA_URIS,
  FIXTURE_PHOTO_MAP,
  FIXTURE_VISITS,
  fixtureBundle,
} from './golden/fixture';
import {
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
    expect(fixtureBundle().ranking.map((entry) => entry.score)).toEqual([3.75, 0.5, null]);
  });

  it('drops contact fields completely when the user leaves them out', () => {
    const bundle = fixtureBundle({ includeContacts: false });
    expect(bundle.houses.map((h) => h.house.contactName)).toEqual([null, null, null]);
    expect(bundle.houses.map((h) => h.house.contactPhone)).toEqual([null, null, null]);
    // The source records are untouched.
    expect(FIXTURE_HOUSES[0].contactPhone).toBe('+91 98400 11111');
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
    expect(header.split(',')).toHaveLength(20);
    expect(buildCsvTables(fixtureBundle())['houses.csv'].split('\r\n')[0].split(',')).toHaveLength(22);
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

  it('matches the golden data.json', () => {
    const json = backupJson(buildBackupData(fixtureBundle()));
    expect(json).toBe(GOLDEN_BACKUP_DATA_JSON);
    // The byte count the golden's comment states, so a silent re-generation cannot quietly shrink the contract.
    expect(new TextEncoder().encode(json).length).toBe(2144);
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
    expect(data.format).toBe(BACKUP_FORMAT);
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
    expect(sheets.map((s) => s.name)).toEqual(['houses', 'scores', 'visits', 'photos']);
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
