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
import { BatchGuardError, DB_VERSION, MemoryDb, STORE_INDEXES, STORE_KEY_PATH, STORE_NAMES, openLocalDb, upgradeLocalDb } from './local-db';
import type { UpgradeDb, UpgradeStore, UpgradeTx } from './local-db';
import {
  MAX_RECORD_PAYLOAD_BYTES,
  cleanCost,
  cleanRooms,
  houseFromDto,
  isRecordId,
  isoNow,
  millis,
  recordToDto,
  tryHouseFromDto,
  tryRecordFromDto,
  tryVisitFromDto,
  visitFromDto,
} from './records';
import { LocalDataError } from '../core/local-error';
import type { HouseDto, RecordDto, VisitDto } from '../core/models';

describe('MemoryDb', () => {
  it('stores and reads back by the store’s key path', async () => {
    const db = new MemoryDb();
    await db.put('houses', { id: 'a', label: 'One' });
    await db.put('settings', { key: 'cursor.house', value: '7' });
    expect(await db.get<{ label: string }>('houses', 'a')).toEqual({ id: 'a', label: 'One' });
    expect(await db.get<{ value: string }>('settings', 'cursor.house')).toEqual({
      key: 'cursor.house',
      value: '7',
    });
  });

  it('copies on write, so a later edit of the caller’s object does not change the store', async () => {
    const db = new MemoryDb();
    const row = { id: 'a', label: 'One' };
    await db.put('houses', row);
    row.label = 'Changed';
    expect((await db.get<{ label: string }>('houses', 'a'))?.label).toBe('One');
  });

  it('replaces a row with the same key and deletes by key', async () => {
    const db = new MemoryDb();
    await db.putAll('visits', [
      { id: 'v1', lat: 1 },
      { id: 'v2', lat: 2 },
    ]);
    await db.put('visits', { id: 'v1', lat: 9 });
    expect(await db.getAll('visits')).toHaveLength(2);
    await db.delete('visits', 'v2');
    expect(await db.getAll('visits')).toHaveLength(1);
    expect((await db.get<{ lat: number }>('visits', 'v1'))?.lat).toBe(9);
  });

  it('clears one store or all of them', async () => {
    const db = new MemoryDb();
    await db.put('houses', { id: 'a' });
    await db.put('settings', { key: 'k', value: 'v' });
    await db.clear('houses');
    expect(await db.getAll('houses')).toHaveLength(0);
    expect(await db.getAll('settings')).toHaveLength(1);
    await db.clear();
    expect(await db.getAll('settings')).toHaveLength(0);
  });

  it('returns undefined for a key it does not have', async () => {
    expect(await new MemoryDb().get('houses', 'missing')).toBeUndefined();
  });

  it('getMany returns the rows under the given keys (compound ones too) and skips a key that is not there', async () => {
    const db = new MemoryDb();
    await db.put('houses', { id: 'a', label: 'A' });
    await db.put('houses', { id: 'b', label: 'B' });
    await db.put('records', { type: 'broker', id: 'x', payload: {} });
    await db.put('records', { type: 'place', id: 'x', payload: {} });
    expect((await db.getMany<{ id: string }>('houses', ['b', 'zzz', 'a'])).map((r) => r.id)).toEqual(['b', 'a']);
    expect((await db.getMany<{ type: string }>('records', [['place', 'x'], ['other', 'x']])).map((r) => r.type)).toEqual(['place']);
    expect(await db.getMany('houses', [])).toEqual([]);
  });

  it('keys the records store by (type, id), so the same id under two types is two rows', async () => {
    const db = new MemoryDb();
    await db.put('records', { type: 'broker', id: 'x', payload: { name: 'A' } });
    await db.put('records', { type: 'place', id: 'x', payload: { name: 'B' } });
    expect(await db.getAll('records')).toHaveLength(2);
    expect((await db.get<{ payload: { name: string } }>('records', ['place', 'x']))?.payload.name).toBe('B');
    await db.delete('records', ['broker', 'x']);
    expect((await db.getAll<{ type: string }>('records')).map((r) => r.type)).toEqual(['place']);
  });

  it('reads by an index the way IndexedDB does: only the rows whose property equals the value', async () => {
    const db = new MemoryDb();
    await db.putAll('photos', [
      { id: 'p1', houseId: 'h1' },
      { id: 'p2', houseId: 'h2' },
      { id: 'p3', houseId: 'h1' },
    ]);
    expect((await db.getAllByIndex<{ id: string }>('photos', 'houseId', 'h1')).map((p) => p.id)).toEqual(['p1', 'p3']);
    expect(await db.getAllByIndex('photos', 'houseId', 'h9')).toEqual([]);
  });
});

/**
 * The upgrade path (S4b-BL-71), against a fake of the three IndexedDB objects an upgrade touches: jsdom has no
 * IndexedDB and no new dependency is allowed, and what matters is *which* stores and indexes each step creates.
 */
describe('upgradeLocalDb', () => {
  class FakeStore implements UpgradeStore {
    readonly indexes: { name: string; keyPath: string }[] = [];
    createIndex(name: string, keyPath: string): unknown {
      this.indexes.push({ name, keyPath });
      return undefined;
    }
  }
  class FakeDb implements UpgradeDb, UpgradeTx {
    readonly stores = new Map<string, FakeStore>();
    readonly keyPaths = new Map<string, string | string[]>();
    createObjectStore(name: string, options: { keyPath: string | string[] }): UpgradeStore {
      if (this.stores.has(name)) throw new Error(`ConstraintError: ${name} exists`);
      const store = new FakeStore();
      this.stores.set(name, store);
      this.keyPaths.set(name, options.keyPath);
      return store;
    }
    objectStore(name: string): UpgradeStore {
      const store = this.stores.get(name);
      if (!store) throw new Error(`NotFoundError: ${name}`);
      return store;
    }
    /** What the database holds, in a shape a test can compare whole. */
    shape(): Record<string, { keyPath: string | string[]; indexes: string[] }> {
      const out: Record<string, { keyPath: string | string[]; indexes: string[] }> = {};
      for (const [name, store] of this.stores) {
        out[name] = { keyPath: this.keyPaths.get(name)!, indexes: store.indexes.map((i) => i.name) };
      }
      return out;
    }
  }

  const VERSION_2 = {
    houses: { keyPath: 'id', indexes: [] },
    visits: { keyPath: 'id', indexes: ['houseId'] },
    photos: { keyPath: 'id', indexes: ['houseId'] },
    settings: { keyPath: 'key', indexes: [] },
    records: { keyPath: ['type', 'id'], indexes: ['type'] },
  };
  /** Version 3 (S4b-FR-17): the path trace's two stores beside the five of version 2. */
  const VERSION_3 = {
    ...VERSION_2,
    trace_points: { keyPath: 'id', indexes: ['walk'] },
    saved_walks: { keyPath: 'id', indexes: ['houseId'] },
  };

  it('is at version 3 (the path trace, docs/11 5.27.8) and has seven stores, so clear() empties both new ones', () => {
    expect(DB_VERSION).toBe(3);
    expect([...STORE_NAMES]).toEqual(['houses', 'visits', 'photos', 'settings', 'records', 'trace_points', 'saved_walks']);
  });

  it('creates every store and index on a fresh install', () => {
    const db = new FakeDb();
    upgradeLocalDb(db, 0, db);
    expect(db.shape()).toEqual(VERSION_3);
    // The wrapper's own tables of the stores agree with what the upgrade made.
    for (const name of STORE_NAMES) {
      expect(db.shape()[name].keyPath, name).toEqual(STORE_KEY_PATH[name]);
      expect(db.shape()[name].indexes, name).toEqual(STORE_INDEXES[name]);
    }
  });

  it('brings a version-1 database up by adding only the records store and the two indexes', () => {
    const db = new FakeDb();
    upgradeLocalDb(db, 0, db);
    // Forget the version-2 additions, so this is what a Sprint 4a browser holds.
    db.stores.delete('records');
    db.keyPaths.delete('records');
    db.stores.get('photos')!.indexes.length = 0;
    db.stores.get('visits')!.indexes.length = 0;

    // ...and the version-3 stores are not there yet either.
    db.stores.delete('trace_points');
    db.stores.delete('saved_walks');

    upgradeLocalDb(db, 1, db);
    expect(db.shape()).toEqual(VERSION_3);
  });

  it('brings a version-2 database up by adding only the two path trace stores (one step per version)', () => {
    const db = new FakeDb();
    upgradeLocalDb(db, 0, db);
    db.stores.delete('trace_points');
    db.stores.delete('saved_walks');
    db.keyPaths.delete('trace_points');
    db.keyPaths.delete('saved_walks');
    expect(db.shape()).toEqual(VERSION_2);

    const created: string[] = [];
    const original = db.createObjectStore.bind(db);
    db.createObjectStore = (name, options) => {
      created.push(name);
      return original(name, options);
    };
    upgradeLocalDb(db, 2, db);
    expect(created).toEqual(['trace_points', 'saved_walks']); // the existing stores are untouched
    expect(db.shape()).toEqual(VERSION_3);
  });

  it('does nothing for a database already at the current version', () => {
    const db = new FakeDb();
    upgradeLocalDb(db, 0, db);
    upgradeLocalDb(db, DB_VERSION, db);
    expect(db.shape()).toEqual(VERSION_3);
  });

  it('indexes every store the way the wrapper expects: an index named after the property it is on', () => {
    const db = new FakeDb();
    upgradeLocalDb(db, 0, db);
    for (const [name, store] of db.stores) {
      for (const index of store.indexes) expect(index.keyPath, `${name}.${index.name}`).toBe(index.name);
    }
  });
});

describe('MemoryDb: the path trace stores and the multi-store operations', () => {
  it('reads trace points by the walk index with a number, and counts without reading', async () => {
    const db = new MemoryDb();
    await db.putAll('trace_points', [
      { id: '5-5', walk: 5, at: 5 },
      { id: '5-6', walk: 5, at: 6 },
      { id: '9-9', walk: 9, at: 9 },
    ]);
    expect((await db.getAllByIndex<{ id: string }>('trace_points', 'walk', 5)).map((r) => r.id)).toEqual(['5-5', '5-6']);
    expect(await db.count('trace_points')).toBe(3);
    expect(await db.count('trace_points', 'walk', 9)).toBe(1);
    expect(await db.count('saved_walks')).toBe(0);
  });

  it('lists the keys of a store, a compound key as its parts', async () => {
    const db = new MemoryDb();
    await db.putAll('saved_walks', [{ id: 'a', houseId: 'h' }, { id: 'b', houseId: 'h' }]);
    await db.put('records', { type: 'broker', id: 'x', payload: {} });
    expect(await db.keys('saved_walks')).toEqual(['a', 'b']);
    expect(await db.keys('records')).toEqual([['broker', 'x']]);
    expect(await db.keys('trace_points')).toEqual([]);
  });

  it('deleteAll removes the rows with those keys and skips a key that is not there', async () => {
    const db = new MemoryDb();
    await db.putAll('trace_points', [{ id: 'a', walk: 1 }, { id: 'b', walk: 1 }, { id: 'c', walk: 2 }]);
    await db.deleteAll('trace_points', ['a', 'c', 'missing']);
    expect((await db.getAll<{ id: string }>('trace_points')).map((r) => r.id)).toEqual(['b']);
    await db.deleteAll('trace_points', []);
    expect(await db.count('trace_points')).toBe(1);
  });

  it('batch writes and deletes across two stores together', async () => {
    const db = new MemoryDb();
    await db.putAll('trace_points', [{ id: '7-1', walk: 7 }, { id: '7-2', walk: 7 }]);
    await db.batch([
      { op: 'put', store: 'saved_walks', value: { id: 's1', houseId: 'h1' } },
      { op: 'delete', store: 'trace_points', key: '7-1' },
      { op: 'delete', store: 'trace_points', key: '7-2' },
    ]);
    expect(await db.count('trace_points')).toBe(0);
    expect((await db.get<{ houseId: string }>('saved_walks', 's1'))?.houseId).toBe('h1');
  });

  it('batch applies nothing when one operation is invalid', async () => {
    const db = new MemoryDb();
    await db.put('trace_points', { id: 'a', walk: 1 });
    await expect(
      db.batch([
        { op: 'put', store: 'saved_walks', value: { id: 's1', houseId: 'h1' } },
        { op: 'delete', store: 'nope' as 'houses', key: 'x' },
        { op: 'delete', store: 'trace_points', key: 'a' },
      ]),
    ).rejects.toThrow(/unknown store/);
    expect(await db.count('saved_walks')).toBe(0);
    expect(await db.count('trace_points')).toBe(1);
  });

  it('batch with guards checks the counts first and applies nothing when one fails (BatchGuardError with its reason)', async () => {
    const db = new MemoryDb();
    await db.putAll('saved_walks', [{ id: 's1', houseId: 'h1' }, { id: 's2', houseId: 'h1' }, { id: 's9', houseId: 'other' }]);
    await db.put('trace_points', { id: 'a', walk: 1 });
    const ops = [{ op: 'put' as const, store: 'saved_walks' as const, value: { id: 's3', houseId: 'h1' } }, { op: 'delete' as const, store: 'trace_points' as const, key: 'a' }];
    const failure = await db.batch(ops, [{ store: 'saved_walks', index: 'houseId', value: 'h1', max: 1, reason: 'houseFull' }]).then(() => null, (e: unknown) => e);
    expect(failure).toBeInstanceOf(BatchGuardError);
    expect((failure as BatchGuardError).reason).toBe('houseFull');
    expect(await db.count('saved_walks')).toBe(3);
    expect(await db.count('trace_points')).toBe(1);
    const missing = await db.batch(ops, [{ store: 'trace_points', index: 'walk', value: 1, min: 2, reason: 'gone' }]).then(() => null, (e: unknown) => e);
    expect((missing as BatchGuardError).reason).toBe('gone');
    await db.batch(ops, [
      { store: 'saved_walks', index: 'houseId', value: 'h1', max: 2, reason: 'houseFull' }, // 2 of h1, not the 3 rows of the store
      { store: 'saved_walks', max: 3, reason: 'deviceFull' },
      { store: 'trace_points', index: 'walk', value: 1, min: 1, reason: 'gone' },
    ]);
    expect(await db.count('saved_walks')).toBe(4);
    expect(await db.count('trace_points')).toBe(0);
  });

  it('clear() with no name empties the two new stores too (Remove all Doorprints data from this browser)', async () => {
    const db = new MemoryDb();
    await db.put('trace_points', { id: 'a', walk: 1 });
    await db.put('saved_walks', { id: 's', houseId: 'h' });
    await db.clear();
    expect(await db.count('trace_points')).toBe(0);
    expect(await db.count('saved_walks')).toBe(0);
  });

  it('copies a saved walk on write too', async () => {
    const db = new MemoryDb();
    const row = { id: 's', houseId: 'h', points: [1, 2, 3] };
    await db.put('saved_walks', row);
    row.houseId = 'other';
    expect((await db.get<{ houseId: string }>('saved_walks', 's'))?.houseId).toBe('h');
  });
});

describe('openLocalDb', () => {
  it('falls back to memory and reports why when IndexedDB is unavailable', async () => {
    // The test environment (jsdom) has no IndexedDB, which is exactly the private-browsing case.
    const opened = await openLocalDb();
    expect(opened.db.kind).toBe('memory');
    expect(opened.problem).toBe('unavailable');
  });

  it('knows a key path for every store', () => {
    for (const name of STORE_NAMES) expect(STORE_KEY_PATH[name], name).toBeTruthy();
  });
});

describe('records', () => {
  const wire: HouseDto = {
    id: 'h1',
    label: 'One',
    lat: 13,
    lon: 80,
    status: 'SHORTLISTED',
    checklist: { water: 4, bad: Number.NaN as unknown as number },
    deleted: false,
    syncVersion: 3,
  };

  it('keeps only sane values from the wire', () => {
    const record = houseFromDto(wire);
    expect(record.checklist).toEqual({ water: 4 });
    expect(record.status).toBe('SHORTLISTED');
    expect(record.address).toBeNull();
    expect(record.dirty).toBe(false);
  });

  it('falls back to NEW for an unknown status', () => {
    const record = houseFromDto({ ...wire, status: 'WHATEVER' as HouseDto['status'] });
    expect(record.status).toBe('NEW');
  });

  /** Slice 1a: the house values are coerced like every other field (out of range = unknown for that field). */
  it('keeps the carpet area, the location source and the cost only within their ranges', () => {
    const full = houseFromDto({
      ...wire,
      areaSqft: 1150,
      locationSource: 'APPROX',
      cost: { deposit: 64000, depositMonths: 2, maintenanceIncluded: false, availableFrom: '2026-10-15', agreedPrice: 31000 },
    });
    expect(full.areaSqft).toBe(1150);
    expect(full.locationSource).toBe('APPROX');
    expect(Object.keys(full.cost ?? {})).toEqual(['deposit', 'depositMonths', 'maintenanceIncluded', 'availableFrom', 'agreedPrice']);
    expect(full.cost).toEqual({ deposit: 64000, depositMonths: 2, maintenanceIncluded: false, availableFrom: '2026-10-15', agreedPrice: 31000 });

    const bad = houseFromDto({
      ...wire,
      areaSqft: 100_001,
      locationSource: 'SATELLITE' as HouseDto['locationSource'],
      cost: {
        deposit: -1,
        depositMonths: 121,
        maintenanceIncluded: 'yes' as unknown as boolean,
        availableFrom: '2026-02-30',
        lockInMonths: 11.4,
        myOffer: Number.NaN,
      },
    });
    expect(bad.areaSqft).toBeNull();
    expect(bad.locationSource).toBeNull();
    expect(bad.cost).toEqual({ lockInMonths: 11 });
    expect(houseFromDto({ ...wire, areaSqft: 0 }).areaSqft).toBeNull();
  });

  it('reads an empty or missing cost as no cost, never as an empty object', () => {
    expect(houseFromDto({ ...wire, cost: {} }).cost).toBeNull();
    expect(houseFromDto({ ...wire, cost: { deposit: null } }).cost).toBeNull();
    expect(houseFromDto(wire).cost).toBeNull();
    expect(houseFromDto({ ...wire, cost: 'lots' as unknown as HouseDto['cost'] }).cost).toBeNull();
    expect(cleanCost({ availableFrom: '2026-10-15' })).toEqual({ availableFrom: '2026-10-15' });
    expect(cleanCost({ availableFrom: '15/10/2026' })).toBeNull();
  });

  it('coerces rooms: caps at 30, converts unknown type to OTHER, drops duplicates (keeping first), and sorts by sort then id', () => {
    const base = [
      { id: 'r1', type: 'BEDROOM', name: 'Master', lengthCm: 300, widthCm: 300, condition: 4, sort: 1 },
      { id: 'r1', type: 'KITCHEN', name: 'Kitchen', lengthCm: 200, widthCm: 200, condition: 3, sort: 0 }, // duplicate id, should be dropped
      { id: 'r2', type: 'UNKNOWN' as never, name: 'Unknown room', lengthCm: 100, widthCm: 100, condition: 2, sort: 2 }, // unknown type → OTHER
      { id: 'r3', type: 'HALL', name: 'Hall', lengthCm: null, widthCm: null, condition: null, sort: 0 }, // null dimensions/condition
    ];
    const result = cleanRooms(base as never);
    expect(result).not.toBeNull();
    expect(result!).toHaveLength(3);
    expect(result![0].id).toBe('r3'); // sort 0, id r3
    expect(result![0].type).toBe('HALL');
    expect(result![1].id).toBe('r1'); // sort 1, id r1
    expect(result![1].type).toBe('BEDROOM');
    expect(result![1].name).toBe('Master');
    expect(result![2].id).toBe('r2'); // sort 2, id r2
    expect(result![2].type).toBe('OTHER'); // unknown → OTHER
  });

  it('keeps the first 30 by sort, not by position', () => {
    const reversed = Array.from({ length: 32 }, (_, i) => ({ id: 'r' + String(i).padStart(2, '0'), type: 'OTHER', sort: 31 - i }));
    const result = cleanRooms(reversed as never)!;
    expect(result).toHaveLength(30);
    expect(result.map((r) => r.sort)).toEqual(Array.from({ length: 30 }, (_, i) => i));
  });

  it('stores a blank room name as absent, never as an empty string', () => {
    const [room] = cleanRooms([{ id: 'r1', type: 'HALL', name: '   ', sort: 0 }] as never)!;
    expect('name' in room).toBe(false);
  });

  it('returns null when rooms is empty, null, or all entries are dropped', () => {
    expect(cleanRooms([])).toBeNull();
    expect(cleanRooms(null as never)).toBeNull();
    expect(cleanRooms([{ id: 'r1', type: 'BEDROOM', sort: 0 }, { id: 'r1', type: 'KITCHEN', sort: 1 }])).toHaveLength(1);
  });

  it('sorts checklist keys, so the same scores always serialise the same way', () => {
    const record = houseFromDto({ ...wire, checklist: { zinc: 1, alpha: 2 } });
    expect(Object.keys(record.checklist)).toEqual(['alpha', 'zinc']);
  });

  it('normalises visits and defaults an unknown source to MANUAL', () => {
    const visit: VisitDto = {
      id: 'v1',
      lat: 1,
      lon: 2,
      arrivedAt: '2026-09-05T11:00:00.000Z',
      source: 'ROBOT' as VisitDto['source'],
      deleted: false,
      syncVersion: 0,
    };
    expect(visitFromDto(visit).source).toBe('MANUAL');
    expect(visitFromDto(visit, true).dirty).toBe(true);
  });

  it('parses timestamps to epoch milliseconds and survives rubbish', () => {
    expect(millis('1970-01-01T00:00:01.000Z')).toBe(1000);
    expect(millis('nonsense')).toBe(0);
    expect(millis(null)).toBe(0);
    expect(isoNow(1000)).toBe('1970-01-01T00:00:01.000Z');
  });

  /**
   * The id is the one field that may not be coerced: `String(undefined)` is the literal id "undefined", so a
   * malformed row would become a real house on the map and two of them would collide on that key. The server
   * refuses a blank id (`BackupValidation.checkData`) and S4-04's import reuses these mappers, so both the sync
   * pull and the importer are held to it here.
   */
  it('accepts only a non-empty string id', () => {
    expect(isRecordId('h1')).toBe(true);
    for (const bad of [undefined, null, '', '   ', 5, {}, []]) expect(isRecordId(bad), JSON.stringify(bad)).toBe(false);
  });

  it('returns null for an untrusted row with no usable id, rather than inventing one', () => {
    expect(tryHouseFromDto({ ...wire, id: undefined as unknown as string })).toBeNull();
    expect(tryHouseFromDto({ ...wire, id: '  ' })).toBeNull();
    expect(tryHouseFromDto({ ...wire, id: 7 as unknown as string })).toBeNull();
    expect(tryHouseFromDto(null)).toBeNull();
    expect(tryHouseFromDto(wire)?.id).toBe('h1');
    expect(tryVisitFromDto(null)).toBeNull();
  });

  /**
   * The record envelope (docs/11 5.30 item 2). The server holds the same patterns and the same 64 KB cap on the
   * serialised payload; a row outside them is untrusted and skipped by the pull, as a house with no id is.
   */
  describe('the record envelope', () => {
    const envelope: RecordDto = {
      type: 'broker',
      id: 'b1',
      payload: { name: 'Anita', phone: '+91 98765 43210' },
      updatedAt: '2026-09-30T10:00:00.000Z',
      deleted: false,
      syncVersion: 12,
    };

    it('keeps a good row as it is, plus the dirty flag', () => {
      expect(tryRecordFromDto(envelope)).toEqual({ ...envelope, dirty: false });
      expect(tryRecordFromDto(envelope, true)?.dirty).toBe(true);
      expect(recordToDto(tryRecordFromDto(envelope)!)).toEqual(envelope);
    });

    it('refuses a type outside the server’s pattern', () => {
      for (const bad of ['', 'Broker', '1broker', 'a-b', 'a b', 'a'.repeat(41), undefined, 7]) {
        expect(tryRecordFromDto({ ...envelope, type: bad as unknown as string }), JSON.stringify(bad)).toBeNull();
      }
      expect(tryRecordFromDto({ ...envelope, type: 'photoMeta2' })).not.toBeNull();
    });

    it('refuses an id outside the server’s pattern', () => {
      for (const bad of ['', ' ', 'a/b', '.', '..', 'a'.repeat(65), 'क', null, 3]) {
        expect(tryRecordFromDto({ ...envelope, id: bad as unknown as string }), JSON.stringify(bad)).toBeNull();
      }
      expect(tryRecordFromDto({ ...envelope, id: '5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10' })?.id).toBeDefined();
    });

    it('replaces a payload that is not a plain object by an empty one', () => {
      for (const bad of [null, undefined, 'text', 5, true, [1, 2], new Date(0)]) {
        expect(tryRecordFromDto({ ...envelope, payload: bad as unknown as Record<string, unknown> })?.payload, String(bad)).toEqual({});
      }
    });

    it('refuses a payload over the server’s cap, measured in UTF-8 bytes of its JSON', () => {
      // {"n":"<x…>"} is 8 bytes of punctuation around the text.
      const atCap = { n: 'x'.repeat(MAX_RECORD_PAYLOAD_BYTES - 8) };
      expect(tryRecordFromDto({ ...envelope, payload: atCap })).not.toBeNull();
      expect(tryRecordFromDto({ ...envelope, payload: { n: `${atCap.n}x` } })).toBeNull();
      // A multi-byte character counts as its bytes, not as one.
      expect(tryRecordFromDto({ ...envelope, payload: { n: 'क'.repeat(MAX_RECORD_PAYLOAD_BYTES / 3) } })).toBeNull();
    });

    it('takes the same defaults as a visit for the bookkeeping fields', () => {
      const bare = tryRecordFromDto({ type: 'place', id: 'p', payload: {} } as RecordDto);
      expect(bare).toEqual({ type: 'place', id: 'p', payload: {}, updatedAt: null, deleted: false, syncVersion: 0, dirty: false });
      expect(tryRecordFromDto(null)).toBeNull();
    });
  });

  it('throws a translated error from the strict mapper, which local writes use', () => {
    let thrown: unknown = null;
    try {
      houseFromDto({ ...wire, id: '' });
    } catch (err: unknown) {
      thrown = err;
    }
    expect(thrown).toBeInstanceOf(LocalDataError);
    expect((thrown as LocalDataError).key).toBe('error.badRecord');
  });
});
