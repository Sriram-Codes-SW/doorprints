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
import { BACKUP_FORMAT, BACKUP_FORMAT_V2, buildBackupData, buildBackupZip } from './backup-export';
import { buildCsvTables } from './csv-export';
import { collect } from './export-model';
import { exportTables, plain } from './export-rows';
import { ExportStrings } from './export-strings';
import { buildHtml } from './html-export';
import { buildMarkdown } from './markdown-export';
import { buildWorkbook } from './xlsx-sheets';
import { GOLDEN_VIEWINGS_CSV } from './golden/csv.golden';
import {
  FIXTURE_EXPORTED_AT,
  FIXTURE_HOUSES,
  FIXTURE_OPTIONS,
  FIXTURE_PHOTOS,
  FIXTURE_PHOTO_DATA_URIS,
  FIXTURE_PHOTO_MAP,
  FIXTURE_VIEWINGS,
  FIXTURE_VISITS,
  fixtureBundle,
} from './golden/fixture';
import type { ViewingRow } from '../shared/viewing';

const en = DICTIONARIES.en;
const MODIFIED_AT = new Date(FIXTURE_EXPORTED_AT);
const H1 = FIXTURE_HOUSES[0].id;
/** No rooms, answers or brokers: only what a test adds decides what the copy holds. */
const plainHouses = FIXTURE_HOUSES.map((h) => ({ ...h, rooms: null, answers: null }));
const base = { houses: plainHouses, visits: FIXTURE_VISITS, photos: FIXTURE_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS };
const row = (id: string, over: Partial<ViewingRow['viewing']> = {}, updatedAt = '2026-09-10T06:00:00.000Z'): ViewingRow => ({
  id,
  updatedAt,
  viewing: { id, houseId: H1, startsAt: 1790501400000, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60, ...over },
});

/** Slice 3b-1 (docs/11 5.8): viewings in the backup and the readable copies. */
describe('viewings in the copies', () => {
  it('writes /1 with no viewing and /2 with only a viewing, the list after the others', () => {
    expect(buildBackupData(collect(base)).format).toBe(BACKUP_FORMAT);
    expect(Object.keys(buildBackupData(collect(base)))).not.toContain('viewings');
    const only = buildBackupData(collect({ ...base, viewings: [row('v_00000001')] }));
    expect(only.format).toBe(BACKUP_FORMAT_V2);
    expect(Object.keys(only)).toEqual(['format', 'exportedAt', 'houses', 'visits', 'photos', 'viewings']);
    expect(Object.keys(buildBackupData(fixtureBundle()))).toEqual([
      'format', 'exportedAt', 'houses', 'visits', 'photos', 'brokers', 'criteria', 'preferences', 'questions', 'viewings',
    ]);
  });

  it('writes the rows by last edit then id, with the keys in the contract order and the optional ones only when set', () => {
    const rows = buildBackupData(fixtureBundle()).viewings ?? [];
    expect(rows.map((v) => v.id)).toEqual(['v_3c4d5e6f', 'v_a1b2c3d4']);
    expect(Object.keys(rows[0])).toEqual(['id', 'houseId', 'startsAt', 'durationMin', 'kind', 'status', 'remindMin', 'withWhom', 'visitId', 'updatedAt']);
    expect(Object.keys(rows[1])).toEqual(['id', 'houseId', 'startsAt', 'durationMin', 'kind', 'status', 'remindMin', 'huntReminder', 'notes', 'updatedAt']);
    const plain = buildBackupData(collect({ ...base, viewings: [row('v_00000001')] })).viewings ?? [];
    expect(Object.keys(plain[0])).toEqual(['id', 'houseId', 'startsAt', 'durationMin', 'kind', 'status', 'remindMin', 'updatedAt']);
    // Same edit time: by id.
    const tied = buildBackupData(collect({ ...base, viewings: [row('v_0000000b'), row('v_0000000a')] })).viewings ?? [];
    expect(tied.map((v) => v.id)).toEqual(['v_0000000a', 'v_0000000b']);
  });

  it('writes huntReminder only when true', () => {
    const rows = buildBackupData(collect({ ...base, viewings: [row('v_00000001', { huntReminder: true }), row('v_00000002')] })).viewings ?? [];
    expect(rows.map((v) => 'huntReminder' in v)).toEqual([true, false]);
    expect(rows[0].huntReminder).toBe(true);
  });

  it('blanks withWhom in a copy made without contact details and keeps everything else', () => {
    const withPeople = [row('v_00000001', { withWhom: 'Ravi Kumar', notes: 'Bring a tape' })];
    const kept = buildBackupData(collect({ ...base, viewings: withPeople })).viewings ?? [];
    expect(kept[0].withWhom).toBe('Ravi Kumar');
    const blanked = buildBackupData(collect({ ...base, viewings: withPeople, options: { ...FIXTURE_OPTIONS, includeContacts: false } })).viewings ?? [];
    expect('withWhom' in blanked[0]).toBe(false);
    expect(blanked[0]).toMatchObject({ notes: 'Bring a tape', kind: 'FIRST', status: 'PLANNED', remindMin: 60 });
    // The store's own row is untouched.
    expect(withPeople[0].viewing.withWhom).toBe('Ravi Kumar');
  });

  it('counts the viewings in the manifest only when there are some', () => {
    const text = new TextDecoder().decode(buildBackupZip(fixtureBundle(), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT));
    expect(text).toContain('"viewings":2');
    expect(new TextDecoder().decode(buildBackupZip(collect(base), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT))).not.toContain('"viewings"');
  });

  it('keeps a viewing of a house that is gone in a copy of every house, and only those of the houses chosen otherwise', () => {
    const gone = row('v_00000009', { houseId: 'a-house-that-is-gone' });
    const all = buildBackupData(collect({ ...base, viewings: [gone, ...FIXTURE_VIEWINGS] })).viewings ?? [];
    expect(all.map((v) => v.id)).toContain('v_00000009');
    const selected = buildBackupData(
      collect({ ...base, viewings: [gone, ...FIXTURE_VIEWINGS], options: { ...FIXTURE_OPTIONS, scope: 'selected', selectedIds: [FIXTURE_HOUSES[1].id] } }),
    ).viewings;
    expect(selected).toBeUndefined();
    const first = buildBackupData(
      collect({ ...base, viewings: [gone, ...FIXTURE_VIEWINGS], options: { ...FIXTURE_OPTIONS, scope: 'selected', selectedIds: [H1] } }),
    ).viewings ?? [];
    expect(first.map((v) => v.id)).toEqual(['v_3c4d5e6f', 'v_a1b2c3d4']);
  });

  it('matches the golden viewings.csv, which only a copy with viewings has', () => {
    expect(buildCsvTables(fixtureBundle())['viewings.csv']).toBe(GOLDEN_VIEWINGS_CSV);
    expect(Object.keys(buildCsvTables(collect(base)))).not.toContain('viewings.csv');
  });

  it('lists the table rows by start then id, blanks with whom without contact details and the house label when it is gone', () => {
    const late = row('v_00000001', { startsAt: 1790501400000 });
    const early = row('v_00000002', { startsAt: 1788604800000, withWhom: 'Meena' });
    const gone = row('v_00000003', { houseId: 'gone', startsAt: 1788604800000 });
    const table = exportTables(collect({ ...base, viewings: [late, early, gone] })).find((t) => t.name === 'viewings');
    expect(table?.columns).toEqual([
      'House', 'When', 'Duration (minutes)', 'Kind', 'Status', 'Reminder (minutes before)', 'With whom', 'Notes', 'House id', 'Id', 'Visit id',
    ]);
    expect(table?.rows.map((r) => plain(r[9]))).toEqual(['v_00000002', 'v_00000003', 'v_00000001']);
    expect(plain(table!.rows[0][6])).toBe('Meena');
    expect(plain(table!.rows[1][0])).toBe('');
    const noContacts = exportTables(collect({ ...base, viewings: [early], options: { ...FIXTURE_OPTIONS, includeContacts: false } })).find((t) => t.name === 'viewings');
    expect(plain(noContacts!.rows[0][6])).toBe('');
  });

  it('adds a Viewings sheet to the workbook only when the copy has viewings', () => {
    expect(buildWorkbook(fixtureBundle()).map((s) => s.name)).toContain('viewings');
    expect(buildWorkbook(collect(base)).map((s) => s.name)).not.toContain('viewings');
  });

  it('puts the Viewings section after the Questions and before the checklist, upcoming first then newest first', () => {
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md.indexOf('### Questions')).toBeLessThan(md.indexOf('### Viewings'));
    expect(md.indexOf('### Viewings')).toBeLessThan(md.indexOf('### Checklist'));
    expect(md.indexOf('Second viewing')).toBeLessThan(md.indexOf('First viewing'));
    const html = buildHtml(fixtureBundle(), en, FIXTURE_PHOTO_DATA_URIS);
    expect(html.indexOf('<h3>Questions</h3>')).toBeLessThan(html.indexOf('<h3>Viewings</h3>'));
    expect(html.indexOf('<h3>Viewings</h3>')).toBeLessThan(html.indexOf('<h3>Checklist</h3>'));
    expect(buildMarkdown(collect(base), en)).not.toContain('### Viewings');
    expect(buildHtml(collect(base), en, new Map())).not.toContain('<h3>Viewings</h3>');
  });

  it('orders the rest newest first when nothing is upcoming, judged by the copy\'s own time', () => {
    const past = [row('v_00000001', { startsAt: 1788000000000, status: 'DONE' }), row('v_00000002', { startsAt: 1789000000000, status: 'CANCELLED' })];
    const bundle = collect({ ...base, viewings: past });
    expect(bundle.houses[0].viewings.map((v) => v.id)).toEqual(['v_00000002', 'v_00000001']);
    // A PLANNED one before the copy's time is not upcoming.
    const missed = collect({ ...base, viewings: [row('v_00000003', { startsAt: 1788000000000 }), row('v_00000004', { startsAt: 1789000000000, status: 'DONE' })] });
    expect(missed.houses[0].viewings.map((v) => v.id)).toEqual(['v_00000004', 'v_00000003']);
  });

  it('leaves the with-whom column out of the readable copies without contact details, and never names the person', () => {
    const md = buildMarkdown(fixtureBundle({ includeContacts: false }), en);
    expect(md).toContain('| When | Kind | Status | Notes |');
    expect(md).not.toContain('With whom');
    const html = buildHtml(fixtureBundle({ includeContacts: false }), en, FIXTURE_PHOTO_DATA_URIS);
    expect(html).not.toContain('With whom');
    expect(buildMarkdown(fixtureBundle(), en)).toContain('With whom');
  });

  it('names the kind and the status in each export language, all different, and the section and column words', () => {
    for (const language of LANGUAGES) {
      const strings = ExportStrings.of(language.code);
      const kinds = (['FIRST', 'SECOND', 'FOLLOW_UP'] as const).map((k) => strings.viewingKind(k));
      const statuses = (['PLANNED', 'DONE', 'CANCELLED'] as const).map((s) => strings.viewingStatus(s));
      expect(new Set(kinds).size, language.code).toBe(3);
      expect(new Set(statuses).size, language.code).toBe(3);
      for (const key of ['section.viewings', 'table.viewings', 'col.when', 'col.kind', 'col.withWhom'] as const) {
        expect(strings.get(key).trim(), `${language.code}/${key}`).not.toBe('');
      }
    }
    expect(ExportStrings.of('ta').viewingKind('FIRST')).not.toBe(ExportStrings.of('en').viewingKind('FIRST'));
    expect(ExportStrings.of('en').viewingKind('NEW_KIND')).toBe('NEW_KIND');
  });
});
