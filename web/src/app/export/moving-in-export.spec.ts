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
import type { HouseRecord, PhotoRecord } from '../data/records';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { LANGUAGES } from '../i18n/languages';
import { BACKUP_FORMAT, BACKUP_FORMAT_V2, backupJson, buildBackupData } from './backup-export';
import { buildCsvTables } from './csv-export';
import { collect } from './export-model';
import { exportTables, movingInView, photoNote, photoRoomName, plain } from './export-rows';
import { ExportStrings } from './export-strings';
import { buildHtml } from './html-export';
import { buildMarkdown } from './markdown-export';
import { buildWorkbook } from './xlsx-sheets';
import {
  FIXTURE_AREAS,
  FIXTURE_AREA_NOTES,
  FIXTURE_EXPORTED_AT,
  FIXTURE_HOUSES,
  FIXTURE_OPTIONS,
  FIXTURE_PHOTOS,
  FIXTURE_PHOTO_DATA_URIS,
  FIXTURE_PLACES,
  FIXTURE_PLAIN_HOUSES,
  FIXTURE_PLAIN_PHOTOS,
  FIXTURE_VISITS,
  fixtureBundle,
} from './golden/fixture';

const en = DICTIONARIES.en;
const ROOM = 'c1111111-1111-4111-8111-111111111111';
const base = { houses: FIXTURE_PLAIN_HOUSES, visits: FIXTURE_VISITS, photos: FIXTURE_PLAIN_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS };
const withHouse0 = (over: Partial<HouseRecord>): HouseRecord[] => FIXTURE_PLAIN_HOUSES.map((h, i) => (i === 0 ? { ...h, ...over } : h));
const withPhoto0 = (over: Partial<PhotoRecord>): PhotoRecord[] => FIXTURE_PLAIN_PHOTOS.map((p, i) => (i === 0 ? { ...p, ...over } : p));

/** Slice 5 in the copies: the `/2` rule, the photo columns, the Moving in section and the status words. */
describe('photo meta and moving in in the copies', () => {
  it('writes /1 with nothing of slice 5 and /2 for each thing of it alone', () => {
    expect(buildBackupData(collect(base)).format).toBe(BACKUP_FORMAT);
    for (const [what, input] of [
      ['a TAKEN house', { ...base, houses: withHouse0({ status: 'TAKEN' }) }],
      ['a NOT_CHOSEN house', { ...base, houses: withHouse0({ status: 'NOT_CHOSEN' }) }],
      ['a house with moveIn', { ...base, houses: withHouse0({ moveIn: { notes: 'Keys' } }) }],
      ['a photo with only a caption', { ...base, photos: withPhoto0({ caption: 'Hall' }) }],
      ['a photo with only a room', { ...base, photos: withPhoto0({ roomId: ROOM }) }],
      ['a photo with only tags', { ...base, photos: withPhoto0({ tags: ['DAMP'] }) }],
      ['a photo with only a stamp', { ...base, photos: withPhoto0({ metaUpdatedAt: 5 }) }],
    ] as const) {
      expect(buildBackupData(collect(input)).format, what).toBe(BACKUP_FORMAT_V2);
    }
  });

  it('does not count the meta of a photo the copy leaves out', () => {
    const data = buildBackupData(collect({ ...base, photos: withPhoto0({ caption: 'Hall' }), options: { ...FIXTURE_OPTIONS, photos: 'none' } }));
    expect(data.format).toBe(BACKUP_FORMAT);
  });

  it('writes a photo row with only the meta keys that are set, in the contract order', () => {
    const tagsOnly = buildBackupData(collect({ ...base, photos: withPhoto0({ tags: ['DAMP'] }) }));
    expect(Object.keys(tagsOnly.photos[0])).toEqual(['id', 'houseId', 'fileName', 'createdAt', 'tags']);
    const all = buildBackupData(fixtureBundle());
    expect(all.photos[0]).toMatchObject({ roomId: ROOM, tags: ['KITCHEN_FITTINGS', 'MOVE_IN', 'damp corner'], caption: 'Kitchen at move-in: tap drips slightly.', metaUpdatedAt: 1790000000000 });
    expect(Object.keys(all.photos[1])).toEqual(['id', 'houseId', 'fileName', 'createdAt']);
  });

  it('writes moveIn after answers with the date, notes and items, and leaves out an empty one', () => {
    const data = buildBackupData(fixtureBundle());
    expect(data.houses[0].moveIn).toEqual({
      date: 1790812800000,
      notes: 'Keys handed over by Ravi. Electricity meter reads 4521.',
      items: [
        { id: 'mi_agreement', text: 'Rental agreement signed and registered', done: true, sort: 0 },
        { id: 'mi_police', text: 'Police verification done', sort: 1 },
      ],
    });
    const empty = buildBackupData(collect({ ...base, houses: withHouse0({ status: 'TAKEN', moveIn: {} }) }));
    expect(empty.houses[0].moveIn).toBeUndefined();
    expect(backupJson(empty)).not.toContain('moveIn');
  });

  it('photos.csv gains room, tags and caption, joined with "; ", the room by name', () => {
    const table = exportTables(fixtureBundle()).find((t) => t.name === 'photos');
    expect(table?.columns).toEqual(['House', 'File', 'Added', 'House id', 'Id', 'Room', 'Tags', 'Caption']);
    const rows = table?.rows.map((r) => r.map(plain));
    expect(rows?.[1].slice(5)).toEqual(['Master bedroom', 'KITCHEN_FITTINGS; MOVE_IN; damp corner', 'Kitchen at move-in: tap drips slightly.']);
    expect(rows?.[0].slice(5)).toEqual(['', '', '']);
    const csv = buildCsvTables(fixtureBundle())['photos.csv'];
    expect(csv.split('\r\n')[0]).toBe('\ufeffHouse,File,Added,House id,Id,Room,Tags,Caption');
  });

  it('names a room by its type when it has no name, and leaves a room that is gone empty', () => {
    const s = ExportStrings.of('en');
    const rooms = [{ id: 'r1', type: 'KITCHEN' as const }, { id: 'r2', type: 'HALL' as const, name: ' Big hall ' }];
    expect(photoRoomName(rooms, 'r1', s)).toBe('Kitchen');
    expect(photoRoomName(rooms, 'r2', s)).toBe('Big hall');
    expect(photoRoomName(rooms, 'gone', s)).toBe('');
    expect(photoRoomName(rooms, null, s)).toBe('');
  });

  it('adds the same three columns to the Photos sheet of the workbook', () => {
    const sheet = buildWorkbook(fixtureBundle()).find((x) => x.name === 'photos');
    expect(sheet?.header.slice(-3)).toEqual(['Room', 'Tags', 'Caption']);
    expect(sheet?.rows[1].slice(-1)).toEqual([{ kind: 'text', value: 'Kitchen at move-in: tap drips slightly.' }]);
  });

  it('lists the photos with their room, tags and caption in the Markdown and HTML copies', () => {
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md).toContain('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg\` — Master bedroom · KITCHEN\\_FITTINGS, MOVE\\_IN, damp corner · Kitchen at move-in: tap drips slightly.');
    expect(md).toContain('- \`bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2.jpg\`\n');
    const html = buildHtml(fixtureBundle(), en, FIXTURE_PHOTO_DATA_URIS);
    expect(html).toContain('<figcaption>Master bedroom · KITCHEN_FITTINGS, MOVE_IN, damp corner · Kitchen at move-in: tap drips slightly.</figcaption>');
    expect(html.match(/<figcaption>/g)).toHaveLength(1);
    const inBackup = buildHtml(fixtureBundle(), en, new Map(), { photosAsFileNames: true });
    expect(inBackup).toContain('<code>bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg</code> — Master bedroom · ');
  });

  it('puts the Moving in section after Distances and before the checklist, in Markdown and HTML', () => {
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md.indexOf('### Distances')).toBeLessThan(md.indexOf('### Moving in'));
    expect(md.indexOf('### Moving in')).toBeLessThan(md.indexOf('### Checklist'));
    expect(md).toContain('| When | 2026-10-01 |');
    expect(md).toContain('- ✓ Rental agreement signed and registered');
    expect(md).toContain('- ○ Police verification done');
    const html = buildHtml(fixtureBundle(), en, new Map());
    expect(html.indexOf('<h3>Distances</h3>')).toBeLessThan(html.indexOf('<h3>Moving in</h3>'));
    expect(html.indexOf('<h3>Moving in</h3>')).toBeLessThan(html.indexOf('<h3>Checklist</h3>'));
    expect(html).toContain('<li>✓ Rental agreement signed and registered</li><li>○ Police verification done</li>');
  });

  it('leaves the Moving in section out of a house without moveIn', () => {
    const bundle = collect(base);
    expect(buildMarkdown(bundle, en)).not.toContain('Moving in');
    expect(buildHtml(bundle, en, new Map())).not.toContain('Moving in');
    expect(movingInView(bundle.houses[0], ExportStrings.of('en'))).toEqual({ facts: [], items: [] });
  });

  it('writes the section and the status words in every export language', () => {
    for (const { code } of LANGUAGES) {
      const s = ExportStrings.of(code);
      expect(s.get('section.movingIn').trim(), code).not.toBe('');
      expect(s.status('TAKEN'), code).not.toBe('TAKEN');
      expect(s.status('NOT_CHOSEN'), code).not.toBe('NOT_CHOSEN');
      const md = buildMarkdown(collect({ ...base, houses: withHouse0({ status: 'TAKEN', moveIn: { notes: 'x' } }), options: { ...FIXTURE_OPTIONS, lang: code } }), DICTIONARIES[code]);
      expect(md, code).toContain(`### ${s.get('section.movingIn')}`);
    }
    expect(ExportStrings.of('en').status('TAKEN')).toBe('Taken');
    expect(ExportStrings.of('en').status('NOT_CHOSEN')).toBe('Not chosen');
  });

  it('shows the two new statuses in houses.csv and the ranking', () => {
    const houses = withHouse0({ status: 'NOT_CHOSEN' });
    const tables = buildCsvTables(collect({ ...base, houses }));
    expect(tables['houses.csv']).toContain(',Not chosen,');
    expect(buildMarkdown(collect({ ...base, houses: withHouse0({ status: 'TAKEN' }) }), en)).toContain('| ⌂ Taken |');
  });

  it('describes a photo for a listing: the parts that exist, joined with a middle dot', () => {
    const entry = fixtureBundle().houses[0];
    const plainPhoto = FIXTURE_PLAIN_PHOTOS[0];
    expect(photoNote(entry, plainPhoto, ExportStrings.of('en'))).toBe('');
    expect(photoNote(entry, { ...plainPhoto, caption: 'Hall' }, ExportStrings.of('en'))).toBe('Hall');
    expect(photoNote(entry, FIXTURE_PHOTOS[0], ExportStrings.of('en'))).toBe('Master bedroom · KITCHEN_FITTINGS, MOVE_IN, damp corner · Kitchen at move-in: tap drips slightly.');
  });

  it('keeps slice 5 in a copy made without contact details', () => {
    const data = buildBackupData(collect({ ...base, houses: FIXTURE_HOUSES, photos: FIXTURE_PHOTOS, areas: FIXTURE_AREAS, places: FIXTURE_PLACES, areaNotes: FIXTURE_AREA_NOTES, options: { ...FIXTURE_OPTIONS, includeContacts: false } }));
    expect(data.houses[0].moveIn?.items).toHaveLength(2);
    expect(data.photos[0].caption).toBe('Kitchen at move-in: tap drips slightly.');
  });
});
