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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { errorMsg, isQuotaError } from '../core/format';
import { BatchGuardError, DB_NAME, DB_VERSION, STORE_INDEXES, STORE_KEY_PATH, STORE_NAMES, openLocalDb } from './local-db';
import type { OpenedDb } from './local-db';
import { LocalStore } from './local-store.service';

/**
 * The real IndexedDB code path of `local-db.ts` (`IdbDb`, `openLocalDb`), against a small fake of IndexedDB: jsdom has
 * none and no dependency may be added. What matters here: which transactions are made (one, over both stores, for a
 * walk's save), that a failure applies nothing, how an upgrade, a blocked open and a `versionchange` are handled, and
 * how a quota error reads. docs/03 section 6.2b rows 3 and 4, docs/06 TC-U-152.
 */

class FakeRequest<T> {
  result!: T;
  error: DOMException | null = null;
  onsuccess: (() => void) | null = null;
  onerror: (() => void) | null = null;
}

interface StoreData {
  keyPath: string | string[];
  indexes: string[];
  rows: Map<string, unknown>;
}

interface Fault {
  store: string;
  op: 'put' | 'delete' | 'clear';
  error: DOMException;
}

class FakeTx {
  oncomplete: (() => void) | null = null;
  onabort: (() => void) | null = null;
  onerror: (() => void) | null = null;
  error: DOMException | null = null;
  private pending = 0;
  private aborted = false;
  private staged: (() => void)[] = [];

  constructor(
    private readonly db: FakeDb,
    readonly names: string[],
  ) {
    queueMicrotask(() => this.settle());
  }

  objectStore(name: string): FakeObjectStore {
    if (!this.names.includes(name)) throw new DOMException(`${name} is not in this transaction`, 'NotFoundError');
    return new FakeObjectStore(this, name, this.db.stores.get(name)!);
  }

  run<T>(work: () => { result: T; commit?: () => void }): FakeRequest<T> {
    const request = new FakeRequest<T>();
    this.pending++;
    queueMicrotask(() => {
      if (this.aborted) return;
      try {
        const done = work();
        request.result = done.result;
        if (done.commit) this.staged.push(done.commit);
        request.onsuccess?.();
      } catch (err: unknown) {
        request.error = err as DOMException;
        request.onerror?.();
        this.aborted = true;
        this.error = err as DOMException;
        this.onabort?.();
        return;
      }
      this.pending--;
      queueMicrotask(() => this.settle());
    });
    return request;
  }

  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    this.staged = [];
    this.error = new DOMException('The transaction was aborted.', 'AbortError');
    this.onabort?.();
  }

  private settle(): void {
    if (this.aborted || this.pending > 0) return;
    for (const commit of this.staged) commit();
    this.staged = [];
    this.pending = -1; // completes once
    this.oncomplete?.();
  }
}

class FakeObjectStore {
  constructor(
    private readonly tx: FakeTx,
    private readonly name: string,
    private readonly data: StoreData,
  ) {}

  private keyOf(value: unknown): string {
    const record = value as Record<string, unknown>;
    const path = this.data.keyPath;
    return typeof path === 'string' ? String(record[path]) : path.map((p) => String(record[p])).join('\u0000');
  }

  private fail(op: 'put' | 'delete' | 'clear'): void {
    const fault = this.tx['db'].fault;
    if (fault && fault.store === this.name && fault.op === op) throw fault.error;
  }

  get(key: string | string[]) {
    return this.tx.run(() => ({ result: this.data.rows.get(Array.isArray(key) ? key.join('\u0000') : key) }));
  }

  getAll() {
    return this.tx.run(() => ({ result: [...this.data.rows.values()] }));
  }

  getAllKeys() {
    return this.tx.run(() => ({ result: [...this.data.rows.keys()] }));
  }

  count() {
    return this.tx.run(() => ({ result: this.data.rows.size }));
  }

  put(value: unknown) {
    return this.tx.run(() => {
      this.fail('put');
      return { result: this.keyOf(value), commit: () => void this.data.rows.set(this.keyOf(value), structuredClone(value)) };
    });
  }

  delete(key: string | string[]) {
    return this.tx.run(() => {
      this.fail('delete');
      const k = Array.isArray(key) ? key.join('\u0000') : key;
      return { result: undefined, commit: () => void this.data.rows.delete(k) };
    });
  }

  clear() {
    return this.tx.run(() => {
      this.fail('clear');
      return { result: undefined, commit: () => this.data.rows.clear() };
    });
  }

  index(name: string) {
    if (!this.data.indexes.includes(name)) throw new DOMException(`The index ${name} does not exist`, 'NotFoundError');
    // IndexedDB leaves a row out of an index whose property is not a valid key (absent, null, a boolean...).
    const indexed = () => ([...this.data.rows.values()] as Record<string, unknown>[]).filter((r) => typeof r[name] === 'string' || typeof r[name] === 'number');
    return {
      getAll: (value: unknown) => this.tx.run(() => ({ result: indexed().filter((r) => r[name] === value) })),
      count: (value?: unknown) => this.tx.run(() => ({ result: value === undefined ? indexed().length : indexed().filter((r) => r[name] === value).length })),
    };
  }
}

/** `IDBObjectStore.createIndex`: a second index of the same name is a `ConstraintError`. */
function createIndexOn(data: StoreData, index: string): void {
  if (data.indexes.includes(index)) throw new DOMException(`The index ${index} exists`, 'ConstraintError');
  data.indexes.push(index);
}

class FakeDb {
  readonly stores = new Map<string, StoreData>();
  readonly log: { names: string[]; mode: string }[] = [];
  fault: Fault | null = null;
  closed = false;
  onversionchange: (() => void) | null = null;

  transaction(names: string | string[], mode: string): FakeTx {
    if (this.closed) throw new DOMException('The database connection is closing.', 'InvalidStateError');
    const list = typeof names === 'string' ? [names] : names;
    this.log.push({ names: list, mode });
    return new FakeTx(this, list);
  }

  createObjectStore(name: string, options: { keyPath: string | string[] }) {
    const data: StoreData = { keyPath: options.keyPath, indexes: [], rows: new Map() };
    this.stores.set(name, data);
    return { createIndex: (index: string) => createIndexOn(data, index) };
  }

  /** A deep copy of every store, to roll an aborted upgrade back to. */
  snapshot(): Map<string, StoreData> {
    return new Map([...this.stores].map(([n, d]) => [n, { keyPath: d.keyPath, indexes: [...d.indexes], rows: structuredClone(d.rows) }]));
  }

  restore(snap: Map<string, StoreData>): void {
    this.stores.clear();
    for (const [n, d] of snap) this.stores.set(n, d);
  }

  close(): void {
    this.closed = true;
  }

  shape(): Record<string, { keyPath: string | string[]; indexes: string[] }> {
    return Object.fromEntries([...this.stores].map(([name, s]) => [name, { keyPath: s.keyPath, indexes: s.indexes }]));
  }
}

type OpenMode = 'ok' | 'error' | 'blocked' | 'throws';

/** The database that "exists" in the browser, and how the next `open` goes. */
class FakeFactory {
  db = new FakeDb();
  version = 0;
  mode: OpenMode = 'ok';
  opened: { name: string; version: number }[] = [];
  upgrades: number[] = [];
  /** A name (`createIndex:kind`, `put:houses`) whose upgrade call throws: the browser aborts the versionchange transaction. */
  failUpgradeAt: string | null = null;

  open(name: string, version: number) {
    this.opened.push({ name, version });
    if (this.mode === 'throws') throw new DOMException('denied', 'SecurityError');
    const request = {
      result: this.db,
      error: null as DOMException | null,
      transaction: null as unknown,
      onupgradeneeded: null as ((e: { oldVersion: number }) => void) | null,
      onsuccess: null as (() => void) | null,
      onerror: null as (() => void) | null,
      onblocked: null as (() => void) | null,
    };
    queueMicrotask(() => {
      if (this.mode === 'blocked') {
        request.onblocked?.();
        // The older tab lets go later; the open then succeeds, but nobody is waiting any more.
        queueMicrotask(() => request.onsuccess?.());
        return;
      }
      if (this.mode === 'error') {
        request.error = new DOMException('refused', 'UnknownError');
        request.onerror?.();
        return;
      }
      if (this.version < version) {
        let aborted = false;
        const before = this.db.snapshot();
        const abort = (err: unknown): void => {
          if (aborted) return;
          aborted = true;
          // The browser aborts the versionchange transaction: every store, index and row is as it was, the version is the old one.
          this.db.restore(before);
          request.error = err as DOMException;
          request.onerror?.();
        };
        const fail = (what: string): void => {
          if (this.failUpgradeAt === what) throw new DOMException(`injected failure at ${what}`, 'UnknownError');
        };
        request.transaction = {
          objectStore: (n: string) => {
            const data = this.db.stores.get(n)!;
            return {
              createIndex: (index: string) => {
                fail(`createIndex:${index}`);
                createIndexOn(data, index);
              },
              // The versionchange transaction's reads and writes (the version-4 step): the request answers at once.
              getAll: () => {
                const read = { result: [...data.rows.values()], onsuccess: null as (() => void) | null };
                queueMicrotask(() => {
                  try {
                    read.onsuccess?.();
                  } catch (err: unknown) {
                    abort(err); // an exception in a request callback aborts the versionchange transaction
                  }
                });
                return read;
              },
              put: (value: { id: string }) => {
                fail(`put:${n}`);
                data.rows.set(value.id, structuredClone(value));
              },
            };
          },
        };
        this.upgrades.push(this.version);
        try {
          request.onupgradeneeded?.({ oldVersion: this.version });
        } catch (err: unknown) {
          abort(err);
          return;
        }
        // The open succeeds once the versionchange transaction has ended: after the upgrade's own requests.
        queueMicrotask(() => {
          if (aborted) return;
          this.version = version;
          request.onsuccess?.();
        });
        return;
      }
      request.onsuccess?.();
    });
    return request;
  }
}

function install(factory: FakeFactory): void {
  vi.stubGlobal('indexedDB', factory);
}

/** A browser that already holds the version-2 database (five stores) with a house in it. */
function versionTwo(): FakeFactory {
  const factory = new FakeFactory();
  const db = factory.db;
  db.createObjectStore('houses', { keyPath: 'id' });
  db.createObjectStore('visits', { keyPath: 'id' }).createIndex('houseId');
  db.createObjectStore('photos', { keyPath: 'id' }).createIndex('houseId');
  db.createObjectStore('settings', { keyPath: 'key' });
  db.createObjectStore('records', { keyPath: ['type', 'id'] }).createIndex('type');
  db.stores.get('houses')!.rows.set('h1', { id: 'h1', label: 'Kept' });
  factory.version = 2;
  return factory;
}

async function open(factory: FakeFactory, onVersionChange?: () => void): Promise<OpenedDb> {
  install(factory);
  return openLocalDb(onVersionChange);
}

afterEach(() => vi.unstubAllGlobals());

describe('openLocalDb against a fake IndexedDB', () => {
  it('opens the database by its name and version and runs every upgrade step on a fresh install', async () => {
    const factory = new FakeFactory();
    const opened = await open(factory);
    expect(opened.db.kind).toBe('indexeddb');
    expect(opened.problem).toBeNull();
    expect(factory.opened).toEqual([{ name: DB_NAME, version: DB_VERSION }]);
    expect(factory.upgrades).toEqual([0]);
    expect(Object.keys(factory.db.shape())).toEqual(['houses', 'visits', 'photos', 'settings', 'records', 'trace_points', 'saved_walks']);
    for (const name of STORE_NAMES) {
      expect(factory.db.shape()[name].keyPath, name).toEqual(STORE_KEY_PATH[name]);
      expect(factory.db.shape()[name].indexes, name).toEqual(STORE_INDEXES[name]);
    }
  });

  it('upgrades a version-2 database by adding the two stores, and keeps the rows it holds (as candidates)', async () => {
    const factory = versionTwo();
    const opened = await open(factory);
    expect(factory.upgrades).toEqual([2]);
    expect(Object.keys(factory.db.shape()).slice(5)).toEqual(['trace_points', 'saved_walks']);
    expect(await opened.db.get('houses', 'h1')).toEqual({ id: 'h1', kind: 'house', label: 'Kept', checklist: {}, deleted: false, syncVersion: 0, dirty: false });
  });

  it('does not upgrade a database that is already at version 5', async () => {
    const factory = versionTwo();
    await open(factory);
    const again = new FakeFactory();
    again.db = factory.db;
    again.version = 5;
    await open(again);
    expect(again.upgrades).toEqual([]);
  });

  it('upgrades a version-3 database in place: its houses become candidates before the open succeeds (kinds step 3)', async () => {
    const factory = versionTwo();
    await open(factory);
    const houses = factory.db.stores.get('houses')!;
    houses.indexes.length = 0; // a version-3 browser has no kind index; the upgrade adds it
    houses.rows.set('h1', { id: 'h1', label: 'Sea View', lat: 13.05, lon: 80.28, status: 'NEW', price: 1250000, priceType: 'SALE', bedrooms: 3, checklist: {}, deleted: false, syncVersion: 2, dirty: true });
    const again = new FakeFactory();
    again.db = factory.db;
    again.version = 3;
    const opened = await open(again);
    expect(again.upgrades).toEqual([3]);
    expect(await opened.db.get('houses', 'h1')).toEqual({
      id: 'h1', kind: 'house', label: 'Sea View', lat: 13.05, lon: 80.28, status: 'NEW', money: { amount: 1250000, cadence: 'once' },
      extras: { bedrooms: 3 }, checklist: {}, deleted: false, syncVersion: 2, dirty: true,
    });
  });

  it('falls back to memory when the browser refuses to open (private browsing, site data off)', async () => {
    const factory = new FakeFactory();
    factory.mode = 'throws';
    const opened = await open(factory);
    expect(opened.db.kind).toBe('memory');
    expect(opened.problem).toBe('unavailable');
  });

  it('falls back to memory when reading the indexedDB property itself throws', async () => {
    vi.stubGlobal('indexedDB', undefined);
    Object.defineProperty(globalThis, 'indexedDB', {
      configurable: true,
      get() {
        throw new DOMException('denied', 'SecurityError');
      },
    });
    try {
      const opened = await openLocalDb();
      expect(opened.db.kind).toBe('memory');
      expect(opened.problem).toBe('unavailable');
    } finally {
      Reflect.deleteProperty(globalThis, 'indexedDB');
    }
  });

  it('falls back to memory with the "blocked" reason when the open fails', async () => {
    const factory = new FakeFactory();
    factory.mode = 'error';
    const opened = await open(factory);
    expect(opened.db.kind).toBe('memory');
    expect(opened.problem).toBe('blocked');
  });

  it('falls back to memory when an older tab holds the database open (blocked), and closes the late connection', async () => {
    const factory = new FakeFactory();
    factory.mode = 'blocked';
    const opened = await open(factory);
    expect(opened.db.kind).toBe('memory');
    expect(opened.problem).toBe('blocked');
    await Promise.resolve();
    await Promise.resolve();
    expect(factory.db.closed).toBe(true);
  });

  it('closes its connection on versionchange (so a newer tab can upgrade), tells the app, and then refuses every write', async () => {
    const factory = new FakeFactory();
    const told = vi.fn();
    const opened = await open(factory, told);
    await opened.db.put('houses', { id: 'a' });
    factory.db.onversionchange?.();
    expect(factory.db.closed).toBe(true);
    expect(told).toHaveBeenCalledTimes(1);
    await expect(opened.db.put('houses', { id: 'b' })).rejects.toMatchObject({ name: 'InvalidStateError' });
    await expect(opened.db.batch([{ op: 'put', store: 'saved_walks', value: { id: 's', houseId: 'h' } }])).rejects.toMatchObject({ name: 'InvalidStateError' });
  });
});

describe('the IndexedDB wrapper: several stores, one transaction', () => {
  const walkRows = [1, 2, 3].map((n) => ({ id: `7-${n}`, walk: 7, at: n }));

  async function withWalk(): Promise<{ factory: FakeFactory; db: OpenedDb['db'] }> {
    const factory = new FakeFactory();
    const { db } = await open(factory);
    await db.putAll('trace_points', walkRows);
    factory.db.log.length = 0;
    return { factory, db };
  }

  it('saves a walk in ONE readwrite transaction over both stores: the saved row in, the trace rows out', async () => {
    const { factory, db } = await withWalk();
    await db.batch([
      { op: 'put', store: 'saved_walks', value: { id: 's1', houseId: 'h1', points: [1, 2] } },
      ...walkRows.map((r) => ({ op: 'delete' as const, store: 'trace_points' as const, key: r.id })),
    ]);
    expect(factory.db.log).toEqual([{ names: ['saved_walks', 'trace_points'], mode: 'readwrite' }]);
    expect(await db.count('trace_points')).toBe(0);
    expect(await db.get('saved_walks', 's1')).toEqual({ id: 's1', houseId: 'h1', points: [1, 2] });
  });

  it('checks the limits INSIDE the same transaction as the writes (counts first, then the batch), and applies them when they hold', async () => {
    const { factory, db } = await withWalk();
    await db.put('saved_walks', { id: 'old', houseId: 'h1' });
    factory.db.log.length = 0;
    await db.batch(
      [
        { op: 'put', store: 'saved_walks', value: { id: 's1', houseId: 'h1', points: [1, 2] } },
        ...walkRows.map((r) => ({ op: 'delete' as const, store: 'trace_points' as const, key: r.id })),
      ],
      [
        { store: 'saved_walks', index: 'houseId', value: 'h1', max: 1, reason: 'houseFull' },
        { store: 'saved_walks', max: 1, reason: 'deviceFull' },
        { store: 'trace_points', index: 'walk', value: 7, min: walkRows.length, reason: 'gone' },
      ],
    );
    expect(factory.db.log).toEqual([{ names: ['saved_walks', 'trace_points'], mode: 'readwrite' }]);
    expect(await db.count('trace_points')).toBe(0);
    expect(await db.count('saved_walks')).toBe(2);
  });

  it('a failed limit aborts the transaction, applies NOTHING and rejects with the guard\'s reason (two tabs cannot both pass)', async () => {
    const { db } = await withWalk();
    await db.put('saved_walks', { id: 'old', houseId: 'h1' });
    const ops = [
      { op: 'put' as const, store: 'saved_walks' as const, value: { id: 's1', houseId: 'h1' } },
      ...walkRows.map((r) => ({ op: 'delete' as const, store: 'trace_points' as const, key: r.id })),
    ];
    const full = await db.batch(ops, [{ store: 'saved_walks', index: 'houseId', value: 'h1', max: 0, reason: 'houseFull' }]).then(() => null, (e: unknown) => e);
    expect(full).toBeInstanceOf(BatchGuardError);
    expect((full as BatchGuardError).reason).toBe('houseFull');
    const gone = await db.batch(ops, [{ store: 'trace_points', index: 'walk', value: 7, min: walkRows.length + 1, reason: 'gone' }]).then(() => null, (e: unknown) => e);
    expect((gone as BatchGuardError).reason).toBe('gone');
    expect(await db.count('trace_points')).toBe(3);
    expect(await db.count('saved_walks')).toBe(1);
  });

  it('applies NOTHING when the transaction fails (the quota is hit writing the saved row): the trace stays as it was', async () => {
    const { factory, db } = await withWalk();
    factory.db.fault = { store: 'saved_walks', op: 'put', error: new DOMException('The quota has been exceeded.', 'QuotaExceededError') };
    const failure = await db
      .batch([
        ...walkRows.map((r) => ({ op: 'delete' as const, store: 'trace_points' as const, key: r.id })),
        { op: 'put', store: 'saved_walks', value: { id: 's1', houseId: 'h1', points: [1] } },
      ])
      .then(
        () => null,
        (err: unknown) => err,
      );
    expect(failure).toBeInstanceOf(DOMException);
    expect(await db.count('trace_points')).toBe(3);
    expect(await db.count('saved_walks')).toBe(0);
  });

  it('reads a quota failure as the browser being full, in the app language, not as raw English', async () => {
    const { factory, db } = await withWalk();
    factory.db.fault = { store: 'trace_points', op: 'put', error: new DOMException('The quota has been exceeded.', 'QuotaExceededError') };
    const failure = await db.put('trace_points', { id: '7-4', walk: 7, at: 4 }).then(
      () => null,
      (err: unknown) => err,
    );
    expect(isQuotaError(failure)).toBe(true);
    expect(errorMsg(failure)).toEqual({ key: 'error.storageFull' });
  });

  it('deleteAll is one transaction for the keys, and none at all for no keys', async () => {
    const { factory, db } = await withWalk();
    await db.deleteAll('trace_points', []);
    expect(factory.db.log).toEqual([]);
    await db.deleteAll('trace_points', ['7-1', '7-3']);
    expect(factory.db.log).toEqual([{ names: ['trace_points'], mode: 'readwrite' }]);
    expect((await db.getAll<{ id: string }>('trace_points')).map((r) => r.id)).toEqual(['7-2']);
  });

  it('getMany reads the named rows in ONE transaction, skips a missing key, and makes no transaction for no keys', async () => {
    const { factory, db } = await withWalk();
    expect(await db.getMany('trace_points', [])).toEqual([]);
    expect(factory.db.log).toEqual([]);
    const rows = await db.getMany<{ id: string }>('trace_points', ['7-3', 'nope', '7-1']);
    expect(rows.map((r) => r.id).sort()).toEqual(['7-1', '7-3']);
    expect(factory.db.log).toEqual([{ names: ['trace_points'], mode: 'readonly' }]);
  });

  it('putAll is one transaction too, and counts by index without reading rows', async () => {
    const { factory, db } = await withWalk();
    await db.putAll('trace_points', [{ id: '8-1', walk: 8, at: 1 }, { id: '8-2', walk: 8, at: 2 }]);
    expect(factory.db.log).toEqual([{ names: ['trace_points'], mode: 'readwrite' }]);
    expect(await db.count('trace_points')).toBe(5);
    expect(await db.count('trace_points', 'walk', 8)).toBe(2);
    expect((await db.getAllByIndex<{ id: string }>('trace_points', 'walk', 7)).map((r) => r.id)).toEqual(['7-1', '7-2', '7-3']);
  });

  it('lists the keys of a store without reading the rows', async () => {
    const { db } = await withWalk();
    expect(await db.keys('trace_points')).toEqual(['7-1', '7-2', '7-3']);
    expect(await db.keys('saved_walks')).toEqual([]);
  });

  it('clear() with no name empties all seven stores', async () => {
    const { factory, db } = await withWalk();
    await db.put('saved_walks', { id: 's', houseId: 'h' });
    await db.clear();
    for (const name of STORE_NAMES) expect(factory.db.stores.get(name)!.rows.size, name).toBe(0);
  });

  it('clear() is ONE readwrite transaction over all the stores, or over the one named', async () => {
    const { factory, db } = await withWalk();
    await db.clear();
    await db.clear('trace_points');
    expect(factory.db.log).toEqual([
      { names: [...STORE_NAMES], mode: 'readwrite' },
      { names: ['trace_points'], mode: 'readwrite' },
    ]);
  });

  it('clear() that aborts on the third store leaves every store as it was (Remove all data is all or nothing)', async () => {
    const { factory, db } = await withWalk();
    for (const name of (['houses', 'visits', 'photos'] as const)) await db.put(name, { id: `${name}-row` }); // houses, visits, photos: all keyed by `id`
    const before = STORE_NAMES.map((n) => [n, [...factory.db.stores.get(n)!.rows.keys()]]);
    factory.db.fault = { store: 'photos', op: 'clear', error: new DOMException('Disk error.', 'UnknownError') };
    await expect(db.clear()).rejects.toThrow();
    expect(STORE_NAMES.map((n) => [n, [...factory.db.stores.get(n)!.rows.keys()]])).toEqual(before);
    expect(before.slice(0, 3).every(([, keys]) => keys.length > 0)).toBe(true);
  });
});

/**
 * Version 5 (S4b-BL-286): the `kind` index on `houses`. The migration only ADDS the index; these specs open a realistic
 * version-4 database and prove every row of every store is byte-identical afterwards, that an upgrade that fails halfway
 * loses nothing and is retried by the next open, and that the per-kind counts read from the index equal the list's.
 */
describe('the version-5 upgrade: an index on houses.kind', () => {
  const house = (id: string, over: Record<string, unknown> = {}) => ({
    id, kind: 'house', label: `H ${id}`, lat: 13, lon: 80, status: 'NEW', money: { amount: 1000, cadence: 'month' }, extras: { bedrooms: 2 },
    checklist: {}, createdAt: '2026-09-01T10:00:00.000Z', updatedAt: '2026-09-02T10:00:00.000Z', deleted: false, syncVersion: 3, dirty: false, ...over,
  });

  /** A browser that holds the version-4 database (no kind index) with a bit of everything in every store. */
  function versionFour(): FakeFactory {
    const factory = new FakeFactory();
    const db = factory.db;
    db.createObjectStore('houses', { keyPath: 'id' });
    db.createObjectStore('visits', { keyPath: 'id' }).createIndex('houseId');
    db.createObjectStore('photos', { keyPath: 'id' }).createIndex('houseId');
    db.createObjectStore('settings', { keyPath: 'key' });
    db.createObjectStore('records', { keyPath: ['type', 'id'] }).createIndex('type');
    db.createObjectStore('trace_points', { keyPath: 'id' }).createIndex('walk');
    db.createObjectStore('saved_walks', { keyPath: 'id' }).createIndex('houseId');
    const put = (store: string, key: string, row: unknown) => void db.stores.get(store)!.rows.set(key, row);
    put('houses', 'h1', house('h1'));
    put('houses', 'h2', house('h2', { kind: 'school', extras: { board: 'CBSE' } }));
    put('houses', 'h3', house('h3', { deleted: true, dirty: true })); // a tombstone keeps its kind
    put('houses', 'h4', house('h4', { kind: 'school', deleted: true }));
    put('houses', 'h5', house('h5', { kind: 'school' }));
    const { kind: _k, ...bare } = house('h6'); // a row stored without `kind` (an interrupted version-4 upgrade)
    put('houses', 'h6', bare);
    put('houses', 'h7', house('h7', { kind: 'garage' })); // a kind this build does not list
    put('visits', 'v1', { id: 'v1', houseId: 'h1', lat: 1, lon: 2, arrivedAt: '2026-09-02T09:00:00.000Z', source: 'MANUAL', deleted: false, syncVersion: 0, dirty: false });
    put('photos', 'p1', { id: 'p1', houseId: 'h1', blob: new Uint8Array([1, 2, 3, 255]), mime: 'image/jpeg', deleted: false, updatedAt: '2026-09-02T09:00:00.000Z' });
    put('photos', 'p2', { id: 'p2', houseId: 'h3', blob: null, deleted: true, updatedAt: '2026-09-03T09:00:00.000Z' });
    put('settings', 'cursor', { key: 'cursor', value: '42' });
    put('records', 'broker\u0000b1', { type: 'broker', id: 'b1', payload: { name: 'Asha' }, deleted: false });
    put('trace_points', '7-1', { id: '7-1', walk: 7, at: 1 });
    put('saved_walks', 'w1', { id: 'w1', houseId: 'h1', points: [[1, 2], [3, 4]] });
    factory.version = 4;
    return factory;
  }

  /** Every store's rows, deep-copied, in key order: what "byte-identical" compares. */
  const rowsOf = (factory: FakeFactory) =>
    Object.fromEntries([...factory.db.stores].map(([name, d]) => [name, [...d.rows].sort(([a], [b]) => (a < b ? -1 : 1)).map(([k, v]) => [k, structuredClone(v)])]));
  const bytes = (rows: unknown) => JSON.stringify(rows, (_k, v) => (v instanceof Uint8Array ? { u8: [...v] } : v));

  it('adds the kind index to a version-4 database and changes no row of any store (houses, tombstones, photos, saved walks, rows without kind)', async () => {
    const factory = versionFour();
    const before = rowsOf(factory);
    const shapeBefore = factory.db.shape();
    const opened = await open(factory);
    expect(opened.problem).toBeNull();
    expect(factory.upgrades).toEqual([4]);
    expect(factory.db.stores.get('houses')!.indexes).toEqual(['kind']);
    const after = rowsOf(factory);
    expect(after).toEqual(before);
    expect(bytes(after)).toBe(bytes(before));
    // The version-4 step did not run: the row without kind is still without kind.
    expect('kind' in (factory.db.stores.get('houses')!.rows.get('h6') as object)).toBe(false);
    // Nothing but that index differs in the shape.
    expect(factory.db.shape()).toEqual({ ...shapeBefore, houses: { keyPath: 'id', indexes: ['kind'] } });
  });

  it('counts each kind from the index and equals the count from the loaded list, rows without kind and unknown kinds apart, tombstones included', async () => {
    const factory = versionFour();
    install(factory);
    const store = new LocalStore();
    await store.ready();
    const viaIndex = await store.houseRowCounts(['house', 'school']);
    expect(viaIndex.source).toBe('index');
    expect(viaIndex).toMatchObject({ other: 1, total: 7 }); // h7 is a garage
    expect([...viaIndex.byKind]).toEqual([['house', 3], ['school', 3]]); // h1, h3 (a tombstone), and h6 (no kind, read as a house)
    // The same store with the index gone (a database a failed upgrade left at version 4): counted from the list.
    factory.db.stores.get('houses')!.indexes.length = 0;
    const viaList = await store.houseRowCounts(['house', 'school']);
    expect(viaList.source).toBe('list');
    expect({ ...viaList, source: 'index' }).toEqual(viaIndex);
    // Live candidates: the rows minus the tombstones, as the list reads them.
    const live = (await store.liveHouses()).map((h) => h.id).sort();
    expect(live).toEqual(['h1', 'h2', 'h5', 'h6', 'h7']);
  });

  it('reads the same counts from the in-memory stand-in (no IndexedDB): the index path works there too', async () => {
    vi.stubGlobal('indexedDB', undefined);
    const store = new LocalStore();
    await store.ready();
    await store.saveHouse({ id: 'a', label: 'A', lat: 1, lon: 2, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 });
    const counts = await store.houseRowCounts(['house', 'school']);
    expect(counts).toMatchObject({ total: 1, other: 0, source: 'index' });
    expect([...counts.byKind]).toEqual([['house', 1], ['school', 0]]);
  });

  it('an index that cannot be created aborts the whole upgrade: version, rows and stores stay as they were, and the next open retries', async () => {
    const factory = versionFour();
    const before = rowsOf(factory);
    const shapeBefore = factory.db.shape();
    factory.failUpgradeAt = 'createIndex:kind';
    const opened = await open(factory);
    expect(opened.db.kind).toBe('memory'); // the app runs from memory with its warning, as for any failed open
    expect(opened.problem).toBe('blocked');
    expect(factory.version).toBe(4);
    expect(bytes(rowsOf(factory))).toBe(bytes(before));
    expect(factory.db.shape()).toEqual(shapeBefore);
    // The browser recovers (quota freed, the other tab gone): the next open upgrades, with every row still there.
    factory.failUpgradeAt = null;
    const retry = await open(factory);
    expect(retry.db.kind).toBe('indexeddb');
    expect(factory.version).toBe(5);
    expect(factory.db.stores.get('houses')!.indexes).toEqual(['kind']);
    expect(bytes(rowsOf(factory))).toBe(bytes(before));
  });

  it('a version-3 database that fails at the kind index loses nothing, not even the candidate rewrite it would have made', async () => {
    const factory = versionFour();
    const houses = factory.db.stores.get('houses')!;
    houses.rows.clear();
    houses.rows.set('h1', { id: 'h1', label: 'Old', lat: 1, lon: 2, status: 'NEW', price: 5, priceType: 'SALE', checklist: {}, deleted: false, syncVersion: 1, dirty: false });
    factory.version = 3;
    const before = rowsOf(factory);
    factory.failUpgradeAt = 'createIndex:kind';
    await open(factory);
    expect(factory.version).toBe(3);
    expect(bytes(rowsOf(factory))).toBe(bytes(before));
    factory.failUpgradeAt = null;
    const opened = await open(factory);
    expect(opened.db.kind).toBe('indexeddb');
    expect(factory.upgrades).toEqual([3, 3]);
    // Both steps ran on the retry: the row is a candidate now and the index counts it.
    expect(await opened.db.count('houses', 'kind', 'house')).toBe(1);
  });

  it('a failure while rewriting rows (version 3) also leaves the old version, rows and no index', async () => {
    const factory = versionFour();
    factory.version = 3;
    const before = rowsOf(factory);
    factory.failUpgradeAt = 'put:houses';
    await open(factory);
    expect(factory.version).toBe(3);
    expect(bytes(rowsOf(factory))).toBe(bytes(before));
    expect(factory.db.stores.get('houses')!.indexes).toEqual([]);
  });

  it('a fresh install has the index from the start and counts zero', async () => {
    const factory = new FakeFactory();
    const opened = await open(factory);
    expect(factory.db.stores.get('houses')!.indexes).toEqual(['kind']);
    expect(await opened.db.count('houses', 'kind', 'house')).toBe(0);
    expect(await opened.db.countIndexed('houses', 'kind')).toBe(0);
    await opened.db.put('houses', { id: 'x', kind: 'house' });
    await opened.db.put('houses', { id: 'y' });
    expect(await opened.db.count('houses')).toBe(2);
    expect(await opened.db.countIndexed('houses', 'kind')).toBe(1); // a row without kind is not in the index
  });

  it('opening twice upgrades once: a second open at version 5 does not create the index again', async () => {
    const factory = versionFour();
    await open(factory);
    const opened = await open(factory);
    expect(opened.db.kind).toBe('indexeddb');
    expect(factory.upgrades).toEqual([4]);
  });
});
