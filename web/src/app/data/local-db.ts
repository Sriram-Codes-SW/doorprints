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

/**
 * The smallest useful IndexedDB wrapper, plus an in-memory stand-in.
 *
 * Why not Dexie or idb (docs/11 §5.10 names them): the web app must install with `npm ci` from a committed lock
 * file and stay at zero running cost, so Sprint 4a adds no new npm dependency. The surface below is the handful of
 * operations the repository needs (get, getAll, getAllByIndex, put, putAll, delete, clear), which is a few dozen
 * lines over the raw API.
 *
 * `MemoryDb` is used in two places: unit tests (jsdom has no IndexedDB), and as the fallback when IndexedDB is
 * blocked — Safari private browsing, Firefox "never remember history", or a browser with site data switched off.
 * In that case the app still works for the session and shows a clear warning that nothing is being kept
 * (LocalStore.storageProblem).
 *
 * **Upgrades** (S4b-BL-71): {@link upgradeLocalDb} runs one step per version, by `oldVersion`, so a browser that
 * skipped a release still gets every step in order, and each step is pinned in `local-db.spec.ts` against a fake
 * (jsdom has no IndexedDB). A tab holding an older version open is told through `versionchange`, closes its
 * database and shows the update banner, so the newer tab's upgrade is not blocked.
 *
 * **Several stores at once** (docs/03 section 6.2b rows 3 and 4): {@link LocalDb.batch} writes and deletes across
 * stores in one transaction, all or nothing. The path trace needs it: saving a walk writes the `saved_walks` row and
 * deletes its `trace_points` rows together, so a walk is never stored twice and a failure (quota, a closed tab) leaves
 * the trace as it was. {@link LocalDb.deleteAll} is the batch delete of one store (the 30-day prune).
 */

/**
 * The object stores of the browser database: the synced rows (houses, visits, photos, records), per-browser settings, and the path trace.
 */
export type StoreName = 'houses' | 'visits' | 'photos' | 'settings' | 'records' | 'trace_points' | 'saved_walks';

/** Every store name, in creation order. */
export const STORE_NAMES: readonly StoreName[] = ['houses', 'visits', 'photos', 'settings', 'records', 'trace_points', 'saved_walks'];

/** The key property of each store, matching the `keyPath` used when it is created (`records`: a compound key). */
export const STORE_KEY_PATH: Readonly<Record<StoreName, string | readonly string[]>> = {
  houses: 'id',
  visits: 'id',
  photos: 'id',
  settings: 'key',
  records: ['type', 'id'],
  trace_points: 'id',
  saved_walks: 'id',
};

/** The indexes of each store; an index is named after the one property it is on, so `MemoryDb` can filter by it. */
export const STORE_INDEXES: Readonly<Record<StoreName, readonly string[]>> = {
  houses: [],
  visits: ['houseId'],
  photos: ['houseId'],
  settings: [],
  records: ['type'],
  trace_points: ['walk'],
  saved_walks: ['houseId'],
};

/** A key of one store: the string key of most stores, or the `[type, id]` pair of `records`. */
export type StoreKey = string | readonly string[];

// Bumped when the stores change; every bump adds a step to `upgradeLocalDb` (S4b-BL-71). Version 1 is Sprint 4a;
// version 2 is slice 0 of the Sprint 4b data model (docs/11 5.30, ADR-28): the `records` store and the `houseId`
// indexes (S4b-BL-66); version 3 is the path trace (docs/11 5.27.8, S4b-FR-17): the `trace_points` and `saved_walks`
// stores. Slice 1 and slice 5 (the house's own new values, photo meta, `moveIn`) change no store and no index: a
// house and a photo only gain fields, so they did not need a version.
/** Name of the browser database that holds all of this browser's house data. */
export const DB_NAME = 'doorprints';
/** Current schema version; see the note above for what each version added. */
export const DB_VERSION = 3;

/** One write of a {@link LocalDb.batch}: a put or a delete in any store. */
export type WriteOp =
  | { readonly op: 'put'; readonly store: StoreName; readonly value: unknown }
  | { readonly op: 'delete'; readonly store: StoreName; readonly key: StoreKey };

/**
 * A condition on a row count, checked INSIDE the transaction of a {@link LocalDb.batch} before its writes: the rows of
 * `store` (those with `index` equal to `value`, or all) must number at least `min` and at most `max`. When it does not hold
 * nothing is written and the batch rejects with a {@link BatchGuardError} carrying `reason`. It is how a limit is kept when
 * two tabs act at once: a count read before the transaction could be stale by the time the writes land.
 */
export interface BatchGuard {
  readonly store: StoreName;
  readonly index?: string;
  readonly value?: string | number;
  readonly min?: number;
  readonly max?: number;
  readonly reason: string;
}

/** A {@link BatchGuard} did not hold: the batch changed nothing. */
export class BatchGuardError extends Error {
  constructor(readonly reason: string) {
    super(`batch refused: ${reason}`);
    this.name = 'BatchGuardError';
  }
}

/** True when `n` is within the guard's bounds. */
function guardHolds(guard: BatchGuard, n: number): boolean {
  return (guard.min === undefined || n >= guard.min) && (guard.max === undefined || n <= guard.max);
}

/**
 * The small async key-value-with-indexes interface the app stores everything through, implemented by IndexedDB and by an in-memory stand-in for when IndexedDB is not usable.
 * Pages never use it directly: `LocalStore` and `TraceStore` own the rules. `batch` is the only way to change several stores atomically.
 */
export interface LocalDb {
  /** 'indexeddb' when the data is really being kept; 'memory' when it lives only for this page. */
  readonly kind: 'indexeddb' | 'memory';
  get<T>(store: StoreName, key: StoreKey): Promise<T | undefined>;
  getAll<T>(store: StoreName): Promise<T[]>;
  /** The rows whose `index` property equals `value`, without reading the rest of the store (S4b-BL-66). */
  getAllByIndex<T>(store: StoreName, index: string, value: string | number): Promise<T[]>;
  /** The keys of a store's rows, without reading the rows (a saved walk is read one at a time by key). */
  keys(store: StoreName): Promise<StoreKey[]>;
  /** How many rows a store holds, or how many have `index` equal to `value`, without reading them. */
  count(store: StoreName, index?: string, value?: string | number): Promise<number>;
  put<T>(store: StoreName, value: T): Promise<void>;
  putAll<T>(store: StoreName, values: readonly T[]): Promise<void>;
  delete(store: StoreName, key: StoreKey): Promise<void>;
  /** Deletes the rows with these keys in one transaction (all or nothing); a key that is not there is skipped. */
  deleteAll(store: StoreName, keys: readonly StoreKey[]): Promise<void>;
  /**
   * Puts and deletes across stores in ONE transaction: every operation is applied or none is (a quota error, a closed
   * tab). The path trace saves a walk with it (docs/03 section 6.2b row 3). With `guards`, their counts are read in the
   * same transaction first (in order; the first that fails rejects with a {@link BatchGuardError} and nothing is written).
   */
  batch(ops: readonly WriteOp[], guards?: readonly BatchGuard[]): Promise<void>;
  /** Empties one store, or every store when no name is given. */
  clear(store?: StoreName): Promise<void>;
  close(): void;
}

/** Why IndexedDB could not be used; shown to the user as a translated message. */
export type StorageProblem = 'unavailable' | 'blocked';

/**
 * The result of opening the database: the db to use and, when IndexedDB could not be used, why (the db is then in memory).
 */
export interface OpenedDb {
  db: LocalDb;
  problem: StorageProblem | null;
}

/** In-memory stand-in with the same behaviour, minus persistence. */
export class MemoryDb implements LocalDb {
  readonly kind = 'memory';
  private readonly stores = new Map<StoreName, Map<string, unknown>>();

  constructor() {
    for (const name of STORE_NAMES) this.stores.set(name, new Map());
  }

  private map(store: StoreName): Map<string, unknown> {
    let map = this.stores.get(store);
    if (!map) {
      map = new Map();
      this.stores.set(store, map);
    }
    return map;
  }

  get<T>(store: StoreName, key: StoreKey): Promise<T | undefined> {
    return Promise.resolve(this.map(store).get(memoryKey(key)) as T | undefined);
  }

  getAll<T>(store: StoreName): Promise<T[]> {
    return Promise.resolve([...this.map(store).values()] as T[]);
  }

  getAllByIndex<T>(store: StoreName, index: string, value: string | number): Promise<T[]> {
    const rows = [...this.map(store).values()] as Record<string, unknown>[];
    return Promise.resolve(rows.filter((row) => row[index] === value) as T[]);
  }

  keys(store: StoreName): Promise<StoreKey[]> {
    const path = STORE_KEY_PATH[store];
    const rows = [...this.map(store).values()] as Record<string, unknown>[];
    return Promise.resolve(rows.map((row) => (typeof path === 'string' ? String(row[path]) : path.map((p) => String(row[p])))));
  }

  count(store: StoreName, index?: string, value?: string | number): Promise<number> {
    const rows = this.map(store);
    if (index === undefined) return Promise.resolve(rows.size);
    return Promise.resolve([...rows.values()].filter((row) => (row as Record<string, unknown>)[index] === value).length);
  }

  put<T>(store: StoreName, value: T): Promise<void> {
    this.putNow(store, value);
    return Promise.resolve();
  }

  private putNow(store: StoreName, value: unknown): void {
    const record = value as Record<string, unknown>;
    const path = STORE_KEY_PATH[store];
    const key = typeof path === 'string' ? String(record[path]) : path.map((p) => String(record[p]));
    // Copy, so a later edit of the caller's object cannot change what is "stored" (IndexedDB copies too).
    this.map(store).set(memoryKey(key), { ...record });
  }

  async putAll<T>(store: StoreName, values: readonly T[]): Promise<void> {
    for (const value of values) await this.put(store, value);
  }

  delete(store: StoreName, key: StoreKey): Promise<void> {
    this.map(store).delete(memoryKey(key));
    return Promise.resolve();
  }

  deleteAll(store: StoreName, keys: readonly StoreKey[]): Promise<void> {
    for (const key of keys) this.map(store).delete(memoryKey(key));
    return Promise.resolve();
  }

  /** Synchronous inside, so no other call can see half of it; the operations are checked before any is applied. */
  batch(ops: readonly WriteOp[], guards: readonly BatchGuard[] = []): Promise<void> {
    for (const op of ops) {
      if (!STORE_NAMES.includes(op.store)) return Promise.reject(new Error(`unknown store ${op.store}`));
    }
    for (const guard of guards) {
      const rows = [...this.map(guard.store).values()] as Record<string, unknown>[];
      const n = guard.index === undefined ? rows.length : rows.filter((r) => r[guard.index!] === guard.value).length;
      if (!guardHolds(guard, n)) return Promise.reject(new BatchGuardError(guard.reason));
    }
    for (const op of ops) {
      if (op.op === 'put') this.putNow(op.store, op.value);
      else this.map(op.store).delete(memoryKey(op.key));
    }
    return Promise.resolve();
  }

  clear(store?: StoreName): Promise<void> {
    for (const name of store ? [store] : STORE_NAMES) this.map(name).clear();
    return Promise.resolve();
  }

  close(): void {
    // nothing to release
  }
}

/** The one map key of a row in {@link MemoryDb}: a compound key joined on a character no id can hold. */
function memoryKey(key: StoreKey): string {
  return typeof key === 'string' ? key : key.join('\u0000');
}

class IdbDb implements LocalDb {
  readonly kind = 'indexeddb';
  private readonly db: IDBDatabase;

  constructor(db: IDBDatabase) {
    this.db = db;
  }

  get<T>(store: StoreName, key: StoreKey): Promise<T | undefined> {
    return this.run(store, 'readonly', (s) => s.get(idbKey(key)) as IDBRequest<T | undefined>);
  }

  getAll<T>(store: StoreName): Promise<T[]> {
    return this.run(store, 'readonly', (s) => s.getAll() as IDBRequest<T[]>);
  }

  getAllByIndex<T>(store: StoreName, index: string, value: string | number): Promise<T[]> {
    return this.run(store, 'readonly', (s) => s.index(index).getAll(value) as IDBRequest<T[]>);
  }

  keys(store: StoreName): Promise<StoreKey[]> {
    return this.run(store, 'readonly', (s) => s.getAllKeys() as IDBRequest<StoreKey[]>);
  }

  count(store: StoreName, index?: string, value?: string | number): Promise<number> {
    return this.run(store, 'readonly', (s) => (index === undefined ? s.count() : s.index(index).count(value)));
  }

  async put<T>(store: StoreName, value: T): Promise<void> {
    await this.run(store, 'readwrite', (s) => s.put(value));
  }

  /** One transaction for the whole batch, so a pulled page of rows is applied all or nothing. */
  putAll<T>(store: StoreName, values: readonly T[]): Promise<void> {
    return this.batch(values.map((value) => ({ op: 'put', store, value })));
  }

  deleteAll(store: StoreName, keys: readonly StoreKey[]): Promise<void> {
    return this.batch(keys.map((key) => ({ op: 'delete', store, key })));
  }

  /**
   * One `readwrite` transaction over every store the operations (and guards) name: all of them are applied, or none. The
   * guards' counts are requested first; the writes are issued from the last count's callback, still inside the transaction,
   * so no other tab can change a count between the check and the writes.
   */
  batch(ops: readonly WriteOp[], guards: readonly BatchGuard[] = []): Promise<void> {
    if (ops.length === 0) return Promise.resolve();
    return new Promise<void>((resolve, reject) => {
      let tx: IDBTransaction;
      const write = (): void => {
        for (const op of ops) {
          const os = tx.objectStore(op.store);
          if (op.op === 'put') os.put(op.value);
          else os.delete(idbKey(op.key));
        }
      };
      try {
        tx = this.db.transaction([...new Set([...ops.map((o) => o.store), ...guards.map((g) => g.store)])], 'readwrite');
        if (guards.length === 0) write();
        else {
          const counts: number[] = [];
          guards.forEach((guard, i) => {
            const os = tx.objectStore(guard.store);
            const request = guard.index === undefined ? os.count() : os.index(guard.index).count(guard.value);
            request.onsuccess = () => {
              counts[i] = request.result;
              if (counts.filter((c) => c !== undefined).length < guards.length) return;
              const failed = guards.find((g, k) => !guardHolds(g, counts[k]));
              if (failed) {
                reject(new BatchGuardError(failed.reason)); // no write was issued: the transaction ends empty
              } else {
                try {
                  write();
                } catch (err: unknown) {
                  reject(asError(err, 'IndexedDB transaction failed'));
                  tx.abort();
                }
              }
            };
          });
        }
      } catch (err: unknown) {
        reject(asError(err, 'IndexedDB transaction failed'));
        return;
      }
      tx.oncomplete = () => resolve();
      tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
      tx.onerror = () => reject(tx.error ?? new Error('IndexedDB transaction failed'));
    });
  }

  async delete(store: StoreName, key: StoreKey): Promise<void> {
    await this.run(store, 'readwrite', (s) => s.delete(idbKey(key)));
  }

  async clear(store?: StoreName): Promise<void> {
    for (const name of store ? [store] : STORE_NAMES) {
      await this.run(name, 'readwrite', (s) => s.clear());
    }
  }

  close(): void {
    this.db.close();
  }

  private run<T>(store: StoreName, mode: IDBTransactionMode, action: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      let tx: IDBTransaction;
      try {
        tx = this.db.transaction(store, mode);
      } catch (err: unknown) {
        reject(asError(err, 'IndexedDB transaction failed'));
        return;
      }
      let request: IDBRequest<T>;
      try {
        request = action(tx.objectStore(store));
      } catch (err: unknown) {
        reject(asError(err, 'IndexedDB request failed'));
        return;
      }
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
      tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
    });
  }
}

/**
 * The error itself when the browser threw one (a `DOMException` is not always an `Error` across realms, and its `name`
 * is what says `QuotaExceededError` or `InvalidStateError`), else a plain one.
 */
function asError(err: unknown, fallback: string): Error {
  return err !== null && typeof err === 'object' ? (err as Error) : new Error(fallback);
}

/** A compound key is passed to IndexedDB as a mutable array (the DOM typing wants one). */
function idbKey(key: StoreKey): IDBValidKey {
  return typeof key === 'string' ? key : [...key];
}

/** The part of `IDBDatabase`, `IDBObjectStore` and `IDBTransaction` an upgrade uses; `local-db.spec.ts` fakes it. */
export interface UpgradeStore {
  createIndex(name: string, keyPath: string): unknown;
}
/** The part of an `IDBDatabase` a schema upgrade uses. */
export interface UpgradeDb {
  createObjectStore(name: string, options: { keyPath: string | string[] }): UpgradeStore;
}
/** The part of an `IDBTransaction` a schema upgrade uses. */
export interface UpgradeTx {
  objectStore(name: string): UpgradeStore;
}

/**
 * Brings a database at `oldVersion` (0 for a fresh install) up to {@link DB_VERSION}, one step per version, inside
 * the `versionchange` transaction `tx` (S4b-BL-71). Every step is written once and never edited: a browser at
 * version 1 runs the version-2 step alone, a fresh install runs them all. Pure, so it is unit-tested with a fake;
 * the browser's `onupgradeneeded` below is its only caller.
 */
export function upgradeLocalDb(db: UpgradeDb, oldVersion: number, tx: UpgradeTx): void {
  if (oldVersion < 1) {
    // Version 1 (Sprint 4a): the four stores.
    for (const name of ['houses', 'visits', 'photos', 'settings'] as const) {
      db.createObjectStore(name, { keyPath: STORE_KEY_PATH[name] as string });
    }
  }
  if (oldVersion < 2) {
    // Version 2 (Sprint 4b slice 0): the `records` store, keyed by (type, id) and read by type (docs/11 5.30), and
    // the `houseId` indexes so a house's photos are found without reading every photo's bytes (S4b-BL-66).
    db.createObjectStore('records', { keyPath: ['type', 'id'] }).createIndex('type', 'type');
    tx.objectStore('photos').createIndex('houseId', 'houseId');
    tx.objectStore('visits').createIndex('houseId', 'houseId');
  }
  if (oldVersion < 3) {
    // Version 3 (S4b-FR-17, docs/11 5.27.8): the path trace. `trace_points` holds the 30-day trace, one row per kept point
    // (key `<walkId>-<atMs>`, read by walk); `saved_walks` holds the walks the person linked to a house (read by house).
    // Stores only: the existing ones are untouched, so a failed upgrade aborts and the database stays at version 2.
    db.createObjectStore('trace_points', { keyPath: 'id' }).createIndex('walk', 'walk');
    db.createObjectStore('saved_walks', { keyPath: 'id' }).createIndex('houseId', 'houseId');
  }
}

/**
 * Opens the local database, falling back to memory when the browser refuses. Never rejects: the caller gets a
 * working store either way plus the reason, so the UI can warn instead of breaking (S4-01 "private mode").
 *
 * `onVersionChange` is called when another tab has opened a newer version of the app: this tab's database is
 * closed first (so the other tab's upgrade can go ahead) and every later read or write here fails, so the caller
 * shows "reload to continue" (LocalStore.closedByNewerTab).
 */
export function openLocalDb(onVersionChange?: () => void): Promise<OpenedDb> {
  let factory: IDBFactory | undefined;
  try {
    factory = typeof indexedDB === 'undefined' ? undefined : indexedDB;
  } catch {
    factory = undefined; // reading the property itself throws in some locked-down browsers
  }
  if (!factory) return Promise.resolve<OpenedDb>({ db: new MemoryDb(), problem: 'unavailable' });
  const idb: IDBFactory = factory;

  return new Promise<OpenedDb>((resolve) => {
    let settled = false;
    const fallback = (problem: StorageProblem) => {
      if (settled) return;
      settled = true;
      resolve({ db: new MemoryDb(), problem });
    };
    let request: IDBOpenDBRequest;
    try {
      request = idb.open(DB_NAME, DB_VERSION);
    } catch {
      fallback('unavailable');
      return;
    }
    request.onupgradeneeded = (event) => {
      const tx = request.transaction;
      if (tx) upgradeLocalDb(request.result, event.oldVersion, tx);
    };
    request.onsuccess = () => {
      const db = request.result;
      if (settled) {
        db.close();
        return;
      }
      settled = true;
      db.onversionchange = () => {
        db.close();
        onVersionChange?.();
      };
      resolve({ db: new IdbDb(db), problem: null });
    };
    request.onerror = () => fallback('blocked');
    // Another tab holds an older version open and did not close it (a build before `onversionchange` was handled,
    // or a tab that is still shutting down): carry on in memory rather than hanging for ever. Rare since S4b-BL-71,
    // because an older tab now closes its database and asks the user to reload.
    request.onblocked = () => fallback('blocked');
  });
}
