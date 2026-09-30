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
import {
  areaFromPayload,
  areaNoteFromPayload,
  areaNoteToPayload,
  areaToPayload,
  areasReaching,
  distancesToPlaces,
  hasRealPoint,
  nearestFirst,
  newAreaIdRandom,
  newAreaNoteIdRandom,
  newPlaceIdRandom,
  notesReaching,
  placeFromPayload,
  placeToPayload,
  sameStreet,
  sortByName,
} from './area';
import type { Area, AreaNoteRow, Place } from './area';
import { haversineMeters, km1 } from '../core/ai/ai-core';

/**
 * Slice 4a (docs/11 "Design of slice 4a"): the record readers and the two derived rules. The vectors N1..N5 and D1..D3
 * are the same ones Kotlin's `AreasTest` and the server's `HouseDocumentsTest` use.
 */
describe('area records', () => {
  it('reads an area and writes the payload keys in the contract order, enabled only when false', () => {
    const area = areaFromPayload('a_1f2e3d4c', { name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 500 });
    expect(area).toEqual({ id: 'a_1f2e3d4c', name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 500, enabled: true });
    expect(Object.keys(areaToPayload(area as Area))).toEqual(['name', 'lat', 'lon', 'radiusM']);
    expect(Object.keys(areaToPayload({ ...(area as Area), enabled: false }))).toEqual(['name', 'lat', 'lon', 'radiusM', 'enabled']);
    expect(areaToPayload({ ...(area as Area), enabled: false })['enabled']).toBe(false);
  });

  it('reads a radius outside 200..2000 (or not a whole number, or missing) as 500', () => {
    const read = (radiusM: unknown) => areaFromPayload('a_00000001', { name: 'X', lat: 1, lon: 2, radiusM })?.radiusM;
    expect(read(199)).toBe(500);
    expect(read(2001)).toBe(500);
    expect(read(650.5)).toBe(500);
    expect(read('900')).toBe(500);
    expect(read(undefined)).toBe(500);
    expect(read(200)).toBe(200);
    expect(read(2000)).toBe(2000);
  });

  it('skips an area with a bad id, a blank or over-long name or a point out of range; only enabled:false turns it off', () => {
    const ok = { name: 'X', lat: 1, lon: 2, radiusM: 500 };
    expect(areaFromPayload('bad id', ok)).toBeNull();
    expect(areaFromPayload('..', ok)).toBeNull();
    expect(areaFromPayload('a_00000001', { ...ok, name: '  ' })).toBeNull();
    expect(areaFromPayload('a_00000001', { ...ok, name: 'n'.repeat(101) })).toBeNull();
    expect(areaFromPayload('a_00000001', { ...ok, lat: 91 })).toBeNull();
    expect(areaFromPayload('a_00000001', { ...ok, lon: -180.5 })).toBeNull();
    expect(areaFromPayload('a_00000001', { ...ok, lat: '1' })).toBeNull();
    expect(areaFromPayload('a_00000001', null)).toBeNull();
    expect(areaFromPayload('a_00000001', { ...ok, enabled: 'no' })?.enabled).toBe(true);
    expect(areaFromPayload('a_00000001', { ...ok, enabled: false })?.enabled).toBe(false);
    expect(areaFromPayload('My-area.1', ok)?.id).toBe('My-area.1');
  });

  it('reads a place (name 1..60, a valid point) and writes name, lat, lon', () => {
    const place = placeFromPayload('p_0a1b2c3d', { name: 'Office', lat: 13.0827, lon: 80.2707 });
    expect(place).toEqual({ id: 'p_0a1b2c3d', name: 'Office', lat: 13.0827, lon: 80.2707 });
    expect(Object.keys(placeToPayload(place as Place))).toEqual(['name', 'lat', 'lon']);
    expect(placeFromPayload('p_0a1b2c3d', { name: 'n'.repeat(61), lat: 1, lon: 1 })).toBeNull();
    expect(placeFromPayload('p_0a1b2c3d', { name: '', lat: 1, lon: 1 })).toBeNull();
    expect(placeFromPayload('p_0a1b2c3d', { name: 'A', lat: 1, lon: 181 })).toBeNull();
  });

  it('reads an area note with exactly one target and a text; skips neither, both, a blank text or an over-long one', () => {
    expect(areaNoteFromPayload('n_11223344', { areaId: 'a_1f2e3d4c', text: 'Water' })).toEqual({ id: 'n_11223344', areaId: 'a_1f2e3d4c', text: 'Water' });
    expect(areaNoteFromPayload('n_11223344', { street: 'MG Road', text: 'Noisy' })).toEqual({ id: 'n_11223344', street: 'MG Road', text: 'Noisy' });
    expect(areaNoteFromPayload('n_11223344', { text: 'Noisy' })).toBeNull();
    expect(areaNoteFromPayload('n_11223344', { areaId: 'a', street: 's', text: 'Noisy' })).toBeNull();
    expect(areaNoteFromPayload('n_11223344', { street: 'MG Road', text: '   ' })).toBeNull();
    expect(areaNoteFromPayload('n_11223344', { street: 'MG Road', text: 'x'.repeat(1001) })).toBeNull();
    expect(areaNoteFromPayload('n_11223344', { street: 's'.repeat(101), text: 'x' })).toBeNull();
    expect(areaNoteFromPayload('n_11223344', { areaId: 'a'.repeat(65), text: 'x' })).toBeNull();
    // A dangling area id is fine: the note just reaches nothing.
    expect(areaNoteFromPayload('n_11223344', { areaId: 'a_gone', text: 'x' })?.areaId).toBe('a_gone');
    expect(Object.keys(areaNoteToPayload({ id: 'n_1', areaId: 'a_1', text: 't' }))).toEqual(['areaId', 'text']);
    expect(Object.keys(areaNoteToPayload({ id: 'n_1', street: 's', text: 't' }))).toEqual(['street', 'text']);
  });

  it('draws ids of the right shape', () => {
    expect(newAreaIdRandom()).toMatch(/^a_[0-9a-f]{8}$/);
    expect(newPlaceIdRandom()).toMatch(/^p_[0-9a-f]{8}$/);
    expect(newAreaNoteIdRandom()).toMatch(/^n_[0-9a-f]{8}$/);
  });

  it('sorts by name ignoring case, then id', () => {
    const list = [{ id: 'b', n: 'office' }, { id: 'a', n: 'Office' }, { id: 'c', n: 'Amma' }];
    expect(sortByName(list, (x) => x.n).map((x) => x.id)).toEqual(['c', 'a', 'b']);
  });
});

describe('AreaNotes.reaching (vectors N1..N5)', () => {
  const area: Area = { id: 'a_1', name: 'Adyar', lat: 13, lon: 80, radiusM: 500, enabled: true };
  const row = (id: string, note: AreaNoteRow['note'], updatedAt: string | null): AreaNoteRow => ({ id, updatedAt, note });
  const areaNote = row('n_a', { id: 'n_a', areaId: 'a_1', text: 'Flooding' }, '2026-09-01T00:00:00.000Z');
  const streetNote = row('n_s', { id: 'n_s', street: 'MG Road', text: 'Noisy' }, '2026-09-02T00:00:00.000Z');
  const at = (dLat: number, over: Partial<{ street: string; locationSource: string }> = {}) => ({ lat: 13 + dLat, lon: 80, ...over });

  it('N1: a house 50 m from the area centre gets the area note', () => {
    expect(haversineMeters(13.00045, 80, 13, 80)).toBeGreaterThan(49);
    expect(haversineMeters(13.00045, 80, 13, 80)).toBeLessThan(51);
    expect(notesReaching(at(0.00045), [area], [areaNote]).map((n) => n.id)).toEqual(['n_a']);
  });

  it('N2: a house 600 m from a 500 m area does not', () => {
    expect(haversineMeters(13.0054, 80, 13, 80)).toBeGreaterThan(599);
    expect(notesReaching(at(0.0054), [area], [areaNote])).toEqual([]);
  });

  it('N3: an APPROX house gets no area note but still gets a street note', () => {
    const house = at(0.0001, { street: 'MG Road', locationSource: 'APPROX' });
    expect(notesReaching(house, [area], [areaNote, streetNote]).map((n) => n.id)).toEqual(['n_s']);
  });

  it('N4: "mg road " matches "MG Road"; a blank street matches nothing', () => {
    expect(notesReaching(at(1, { street: 'mg road ' }), [area], [streetNote]).map((n) => n.id)).toEqual(['n_s']);
    expect(notesReaching(at(1, { street: '  ' }), [area], [row('n_b', { id: 'n_b', street: ' ', text: 'x' }, null)])).toEqual([]);
    expect(sameStreet(undefined, 'MG Road')).toBe(false);
  });

  it('N5: a note whose area was deleted is shown on no house', () => {
    expect(notesReaching(at(0), [], [areaNote])).toEqual([]);
  });

  it('lists the newest updatedAt first, ties by id', () => {
    const tied = row('n_0', { id: 'n_0', street: 'MG Road', text: 'Tied' }, '2026-09-02T00:00:00.000Z');
    const ids = notesReaching(at(0, { street: 'MG Road' }), [area], [areaNote, streetNote, tied]).map((n) => n.id);
    expect(ids).toEqual(['n_0', 'n_s', 'n_a']);
  });

  it('a house at (0, 0) has no point: no area note, but a street note still reaches it', () => {
    const origin = { lat: 0, lon: 0, street: 'MG Road' };
    expect(hasRealPoint(origin)).toBe(false);
    expect(notesReaching(origin, [{ ...area, lat: 0, lon: 0 }], [areaNote, streetNote]).map((n) => n.id)).toEqual(['n_s']);
    expect(areasReaching(origin, [{ ...area, lat: 0, lon: 0 }])).toEqual([]);
  });

  it('areasReaching lists the areas whose circle holds the house', () => {
    const far: Area = { ...area, id: 'a_2', lat: 14 };
    expect(areasReaching(at(0.001), [area, far]).map((a) => a.id)).toEqual(['a_1']);
  });
});

describe('Distances.toPlaces (vectors D1..D3)', () => {
  const place = (id: string, lat: number, lon: number): Place => ({ id, name: id, lat, lon });

  it('D1: (13.0067, 80.2574) to (13.0827, 80.2707) is 8 572.7 m, 8.6 km', () => {
    const [d] = distancesToPlaces({ lat: 13.0067, lon: 80.2574 }, [place('office', 13.0827, 80.2707)]);
    expect(Math.abs(d.meters - 8572.7)).toBeLessThan(0.1);
    expect(d.km).toBe('8.6');
  });

  it('D2: the same point is 0.0 km', () => {
    const [d] = distancesToPlaces({ lat: 13.0067, lon: 80.2574 }, [place('here', 13.0067, 80.2574)]);
    expect(d.meters).toBe(0);
    expect(d.km).toBe('0.0');
    expect(d.minutes).toBe(0);
  });

  it('D3: (12.9716, 77.5946) to (13.0, 77.6) is 3 211.7 m, 3.2 km', () => {
    const [d] = distancesToPlaces({ lat: 12.9716, lon: 77.5946 }, [place('home', 13.0, 77.6)]);
    expect(Math.abs(d.meters - 3211.7)).toBeLessThan(0.1);
    expect(d.km).toBe('3.2');
  });

  it('rounds to one decimal half up and gives the Plan walking estimate', () => {
    expect(km1(8550)).toBe('8.6');
    expect(km1(8549.9)).toBe('8.5');
    expect(km1(50)).toBe('0.1');
    expect(km1(49.9)).toBe('0.0');
    // Plan's own function: ceil(metres x 1.3 / 80).
    const [d] = distancesToPlaces({ lat: 12.9716, lon: 77.5946 }, [place('home', 13.0, 77.6)]);
    expect(d.minutes).toBe(Math.ceil((d.meters * 1.3) / 80));
  });

  it('gives none to a house without a point, and orders nearest first on request', () => {
    expect(distancesToPlaces({ lat: 0, lon: 0 }, [place('p', 1, 1)])).toEqual([]);
    const both = distancesToPlaces({ lat: 13, lon: 80 }, [place('far', 14, 80), place('near', 13.01, 80)]);
    expect(both.map((d) => d.place.id)).toEqual(['far', 'near']);
    expect(nearestFirst(both).map((d) => d.place.id)).toEqual(['near', 'far']);
  });
});
