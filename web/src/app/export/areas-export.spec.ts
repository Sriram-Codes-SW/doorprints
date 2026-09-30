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
import { collect } from './export-model';
import { ExportStrings } from './export-strings';
import { buildHtml } from './html-export';
import { buildMarkdown } from './markdown-export';
import {
  FIXTURE_AREAS,
  FIXTURE_AREA_NOTES,
  FIXTURE_EXPORTED_AT,
  FIXTURE_HOUSES,
  FIXTURE_OPTIONS,
  FIXTURE_PHOTOS,
  FIXTURE_PHOTO_DATA_URIS,
  FIXTURE_PHOTO_MAP,
  FIXTURE_PLACES,
  FIXTURE_VISITS,
  fixtureBundle,
} from './golden/fixture';
import type { AreaNoteRow, AreaRow, PlaceRow } from '../shared/area';

const en = DICTIONARIES.en;
const MODIFIED_AT = new Date(FIXTURE_EXPORTED_AT);
/** No rooms, answers or brokers: only what a test adds decides what the copy holds. */
const plainHouses = FIXTURE_HOUSES.map((h) => ({ ...h, rooms: null, answers: null }));
const base = { houses: plainHouses, visits: FIXTURE_VISITS, photos: FIXTURE_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS };
const areaRow = (id: string, updatedAt = '2026-09-10T06:00:00.000Z', over: Partial<AreaRow['area']> = {}): AreaRow => ({
  id,
  updatedAt,
  area: { id, name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 500, enabled: true, ...over },
});
const placeRow = (id: string, updatedAt = '2026-09-10T06:00:00.000Z'): PlaceRow => ({ id, updatedAt, place: { id, name: 'Office', lat: 13.0827, lon: 80.2707 } });
const noteRow = (id: string, updatedAt = '2026-09-10T06:00:00.000Z'): AreaNoteRow => ({ id, updatedAt, note: { id, street: 'MG Road', text: 'Noisy' } });

/** Slice 4a (docs/11 "Design of slice 4a"): areas, places and area notes in the backup and the readable copies. */
describe('areas, places and area notes in the copies', () => {
  it('writes /1 with none of them and /2 with only one of them, the lists after viewings in the contract order', () => {
    expect(buildBackupData(collect(base)).format).toBe(BACKUP_FORMAT);
    expect(Object.keys(buildBackupData(collect(base)))).not.toContain('areas');
    for (const [key, extra] of [
      ['areas', { areas: [areaRow('a_00000001')] }],
      ['places', { places: [placeRow('p_00000001')] }],
      ['areaNotes', { areaNotes: [noteRow('n_00000001')] }],
    ] as const) {
      const only = buildBackupData(collect({ ...base, ...extra }));
      expect(only.format, key).toBe(BACKUP_FORMAT_V2);
      expect(Object.keys(only), key).toEqual(['format', 'exportedAt', 'houses', 'visits', 'photos', key]);
    }
    expect(Object.keys(buildBackupData(fixtureBundle())).slice(-4)).toEqual(['viewings', 'areas', 'places', 'areaNotes']);
  });

  it('writes each list by last edit then id with the keys in the contract order; enabled only when false', () => {
    const data = buildBackupData(fixtureBundle());
    expect(data.areas?.map((a) => a.id)).toEqual(['a_1f2e3d4c', 'a_5b6c7d8e']);
    expect(Object.keys(data.areas![0])).toEqual(['id', 'name', 'lat', 'lon', 'radiusM', 'updatedAt']);
    expect(Object.keys(data.areas![1])).toEqual(['id', 'name', 'lat', 'lon', 'radiusM', 'enabled', 'updatedAt']);
    expect(Object.keys(data.places![0])).toEqual(['id', 'name', 'lat', 'lon', 'updatedAt']);
    expect(Object.keys(data.areaNotes![0])).toEqual(['id', 'areaId', 'text', 'updatedAt']);
    expect(Object.keys(data.areaNotes![1])).toEqual(['id', 'street', 'text', 'updatedAt']);
    const tied = buildBackupData(collect({ ...base, places: [placeRow('p_0000000b'), placeRow('p_0000000a')] })).places ?? [];
    expect(tied.map((p) => p.id)).toEqual(['p_0000000a', 'p_0000000b']);
  });

  it('keeps all three in a copy made without contact details and in a copy of chosen houses', () => {
    const noContacts = buildBackupData(fixtureBundle({ includeContacts: false }));
    expect([noContacts.areas?.length, noContacts.places?.length, noContacts.areaNotes?.length]).toEqual([2, 2, 2]);
    const chosen = buildBackupData(fixtureBundle({ scope: 'selected', selectedIds: [FIXTURE_HOUSES[1].id] }));
    expect([chosen.areas?.length, chosen.places?.length, chosen.areaNotes?.length]).toEqual([2, 2, 2]);
  });

  it('counts them in the manifest after the viewings, only when there are some', () => {
    const text = new TextDecoder().decode(buildBackupZip(fixtureBundle(), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT));
    expect(text).toContain('"viewings":2,"areas":2,"places":2,"areaNotes":2}');
    const none = new TextDecoder().decode(buildBackupZip(collect(base), FIXTURE_PHOTO_MAP, 'x', MODIFIED_AT));
    expect(none).not.toContain('"areas"');
    expect(none).not.toContain('"areaNotes"');
  });

  it('gives a house the notes that reach it (newest first, with where from) and its distances nearest first', () => {
    const [h1, h2, h3] = collect({ ...base, areas: FIXTURE_AREAS, places: FIXTURE_PLACES, areaNotes: FIXTURE_AREA_NOTES }).houses;
    expect(h1.areaNotes.map((n) => [n.id, n.source])).toEqual([['n_55667788', 'MG Road'], ['n_11223344', 'Adyar']]);
    expect(h1.distances.map((d) => [d.place.name, d.km])).toEqual([['Office', '8.6'], ["Amma's home", '288.5']]);
    // House 2 sits at (0, 0): no point, so no distance and no area note; house 3 has a point but no note.
    expect(h2.distances).toEqual([]);
    expect(h2.areaNotes).toEqual([]);
    expect(h3.areaNotes).toEqual([]);
    expect(h3.distances.map((d) => d.km)).toEqual(['3.8', '291.1']);
  });

  it('puts the Area notes and Distances sections after the Viewings and before the checklist, and leaves them out when empty', () => {
    const md = buildMarkdown(fixtureBundle(), en);
    expect(md.indexOf('### Viewings')).toBeLessThan(md.indexOf('### Area notes'));
    expect(md.indexOf('### Area notes')).toBeLessThan(md.indexOf('### Distances'));
    expect(md.indexOf('### Distances')).toBeLessThan(md.indexOf('### Checklist'));
    expect(md).toContain('| Office | 8.6 |');
    expect(md).toContain('| Noisy after 9 pm: the bus depot is on the corner. | MG Road |');
    const html = buildHtml(fixtureBundle(), en, FIXTURE_PHOTO_DATA_URIS);
    expect(html.indexOf('<h3>Viewings</h3>')).toBeLessThan(html.indexOf('<h3>Area notes</h3>'));
    expect(html.indexOf('<h3>Area notes</h3>')).toBeLessThan(html.indexOf('<h3>Distances</h3>'));
    expect(html.indexOf('<h3>Distances</h3>')).toBeLessThan(html.indexOf('<h3>Checklist</h3>'));
    for (const empty of [buildMarkdown(collect(base), en), buildHtml(collect(base), en, new Map())]) {
      expect(empty).not.toContain('Area notes');
      expect(empty).not.toContain('Distances');
    }
  });

  it('writes no new CSV file or sheet for them', async () => {
    const { buildCsvTables } = await import('./csv-export');
    const { buildWorkbook } = await import('./xlsx-sheets');
    const tables = Object.keys(buildCsvTables(fixtureBundle()));
    expect(tables.filter((n) => /area|place|distance/i.test(n))).toEqual([]);
    expect(buildWorkbook(fixtureBundle()).map((s) => s.name).filter((n) => /area|place|distance/i.test(n))).toEqual([]);
  });

  it('names the sections and columns in every export language', () => {
    for (const language of LANGUAGES) {
      const strings = ExportStrings.of(language.code);
      for (const key of ['section.areaNotes', 'section.distances', 'col.place', 'col.km', 'col.noteSource'] as const) {
        expect(strings.get(key).trim(), `${language.code}/${key}`).not.toBe('');
      }
    }
    expect(ExportStrings.of('ta').get('section.distances')).not.toBe(ExportStrings.of('en').get('section.distances'));
  });
});
