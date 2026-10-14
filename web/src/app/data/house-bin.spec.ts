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

import { beforeEach, describe, expect, it } from 'vitest';
import { BIN_DAYS, binRows, inBin, nextStamp, parseNotes, withNote } from './house-bin';
import { LocalStore } from './local-store.service';
import type { HouseRecord } from './records';
import type { HouseDto } from '../core/models';

/** The list behind *Recently deleted* and the flip that brings a tombstone back (deletion safeguards PR 1; docs/06 TC-U-215). */
const NOW = Date.parse('2026-10-14T12:00:00.000Z');
const DAY = 24 * 60 * 60 * 1000;
const iso = (ms: number) => new Date(ms).toISOString();

const row = (id: string, over: Partial<HouseRecord> = {}): HouseRecord =>
  ({ id, label: id, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: true, dirty: false, syncVersion: 0, updatedAt: iso(NOW - DAY), ...over }) as HouseRecord;
const house = (id: string, over: Partial<HouseDto> = {}): HouseDto => ({ id, label: id, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...over });
const jpeg = (): Blob => new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' });
const both = { kind: 'house', multi: true, dflt: 'house', available: ['house', 'school'] };

describe('inBin', () => {
  it('is a tombstone younger than 30 days, and not the 30th day to the millisecond', () => {
    expect(BIN_DAYS).toBe(30);
    expect(inBin(row('a', { updatedAt: iso(NOW - 30 * DAY + 1) }), NOW)).toBe(true);
    expect(inBin(row('a', { updatedAt: iso(NOW - 30 * DAY) }), NOW)).toBe(false);
    expect(inBin(row('a', { deleted: false }), NOW)).toBe(false);
    expect(inBin(row('a', { updatedAt: null }), NOW)).toBe(false);
  });
});

describe('binRows', () => {
  const rows = [
    row('h-old', { updatedAt: iso(NOW - 5 * DAY) }),
    row('h-new', { updatedAt: iso(NOW - DAY) }),
    row('s1', { kind: 'school', updatedAt: iso(NOW - 2 * DAY) }),
    row('c1', { kind: 'clinic', updatedAt: iso(NOW - 3 * DAY) }),
    row('gone', { updatedAt: iso(NOW - 40 * DAY) }),
    row('live', { deleted: false }),
  ];

  it('lists this space only, newest first; a kind with no space belongs to the default space', () => {
    expect(binRows(rows, both, NOW).map((r) => r.id)).toEqual(['h-new', 'c1', 'h-old']);
    expect(binRows(rows, { ...both, kind: 'school' }, NOW).map((r) => r.id)).toEqual(['s1']);
  });

  it('lists every tombstone when there is one space', () => {
    expect(binRows(rows, { ...both, multi: false }, NOW).map((r) => r.id)).toEqual(['h-new', 's1', 'c1', 'h-old']);
  });

  it('breaks a tie by id and does not change the input', () => {
    const same = [row('b'), row('a')];
    expect(binRows(same, both, NOW).map((r) => r.id)).toEqual(['a', 'b']);
    expect(same.map((r) => r.id)).toEqual(['b', 'a']);
  });
});

describe('nextStamp', () => {
  it('is now, or one millisecond past a stamp that is ahead of the clock', () => {
    expect(nextStamp(iso(NOW - 1000), NOW)).toBe(iso(NOW));
    expect(nextStamp(iso(NOW + 5000), NOW)).toBe(iso(NOW + 5001));
    expect(nextStamp(null, NOW)).toBe(iso(NOW));
  });
});

describe('the photo notes', () => {
  it('reads what was written and nothing odd', () => {
    expect(parseNotes('{"a":2,"b":0,"c":"x"}')).toEqual({ a: 2 });
    for (const raw of [null, '', 'nope', '[1]', '7', 'null']) expect(parseNotes(raw), String(raw)).toEqual({});
  });

  it('sets, removes and prunes to the ids still in the list', () => {
    expect(withNote({ a: 1, b: 2 }, 'c', 3, new Set(['a', 'c']))).toEqual({ a: 1, c: 3 });
    expect(withNote({ a: 1 }, 'a', 0, new Set(['a']))).toEqual({});
    expect(withNote({ a: 1 }, 'a', 4, new Set(['a']))).toEqual({ a: 4 });
  });
});

describe('LocalStore.restoreHouse', () => {
  let store: LocalStore;
  const T1 = Date.parse('2026-10-01T10:00:00.000Z');

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('flips a tombstone back, dirty, with a newer stamp, and leaves the other rows alone', async () => {
    await store.saveHouse(house('h1', { street: 'MG Road' }), T1);
    const other = await store.saveHouse(house('h2', { kind: 'school' }), T1);
    await store.deleteHouse('h1', T1 + 1000);
    const result = await store.restoreHouse('h1', NOW);
    expect(result?.house).toMatchObject({ id: 'h1', deleted: false, dirty: true, street: 'MG Road', updatedAt: iso(NOW) });
    expect(await store.getHouse('h1')).toMatchObject({ deleted: false, dirty: true });
    expect(await store.getHouse('h2')).toEqual(other);
  });

  it('does nothing for a live house or an unknown id', async () => {
    const saved = await store.saveHouse(house('h1'), T1);
    expect(await store.restoreHouse('h1', NOW)).toBeUndefined();
    expect(await store.restoreHouse('nope', NOW)).toBeUndefined();
    expect(await store.getHouse('h1')).toEqual(saved);
  });

  it('lists the tombstones through deletedHouses', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.saveHouse(house('h2'), T1);
    await store.deleteHouse('h2', T1 + 1);
    expect((await store.deletedHouses()).map((h) => h.id)).toEqual(['h2']);
  });

  it('puts held photos back under new ids with their bytes, to be uploaded again, and counts the photos without bytes', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.photos.add('h1', jpeg(), 'p1', T1, { roomId: null, tags: ['kitchen'], caption: 'sink' });
    await store.photos.put({ ...(await store.photos.get('p1'))!, uploaded: true });
    await store.photos.add('h1', jpeg(), 'p2', T1);
    const held = await store.photos.ofHouse('h1');
    await store.deleteHouse('h1', T1 + 1000);
    const result = await store.restoreHouse('h1', NOW, held);
    expect(result).toMatchObject({ photosBack: 2, photosGone: 0 }); // p1's own tombstone has no bytes, its copy does
    const live = await store.photos.ofHouse('h1');
    expect(live).toHaveLength(2);
    for (const p of live) {
      expect(p.blob).not.toBeNull();
      expect(p).toMatchObject({ houseId: 'h1', deleted: false, uploaded: false, updatedAt: iso(NOW) });
      expect(['p1', 'p2']).not.toContain(p.id);
    }
    expect(live.find((p) => p.caption === 'sink')).toMatchObject({ metaDirty: true, tags: ['kitchen'] });
  });

  it('brings back a tombstone that still holds its bytes, and removes the stale row', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.photos.add('h1', jpeg(), 'p1', T1);
    await store.deleteHouse('h1', T1 + 1000);
    await store.photos.put({ id: 'kept', houseId: 'h1', blob: jpeg(), contentType: 'image/jpeg', sizeBytes: 3, createdAt: null, updatedAt: iso(T1), deleted: true, syncVersion: 0, uploaded: false });
    const result = await store.restoreHouse('h1', NOW);
    expect(result).toMatchObject({ photosBack: 1, photosGone: 0 });
    expect(await store.photos.get('kept')).toBeUndefined();
    expect(await store.photos.ofHouse('h1')).toHaveLength(1);
  });

  it('counts the photo tombstones whose bytes are gone', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.photos.add('h1', jpeg(), 'p1', T1);
    await store.photos.put({ ...(await store.photos.get('p1'))!, uploaded: true });
    await store.deleteHouse('h1', T1 + 1000);
    expect(await store.restoreHouse('h1', NOW)).toMatchObject({ photosBack: 0, photosGone: 1 });
    expect(await store.photos.ofHouse('h1')).toEqual([]);
  });
});
