/**
 * The smallest useful IndexedDB wrapper, plus an in-memory stand-in.
 *
 * Why not Dexie or idb (docs/11 §5.10 names them): the web app must install with `npm ci` from a committed lock
 * file and stay at zero running cost, so Sprint 4a adds no new npm dependency. The surface below is the handful of
 * operations the repository needs (get, getAll, put, putAll, delete, clear), which is a few dozen lines over the
 * raw API.
 *
 * `MemoryDb` is used in two places: unit tests (jsdom has no IndexedDB), and as the fallback when IndexedDB is
 * blocked — Safari private browsing, Firefox "never remember history", or a browser with site data switched off.
 * In that case the app still works for the session and shows a clear warning that nothing is being kept
 * (LocalStore.storageWarning).
 */

export type StoreName = 'houses' | 'visits' | 'photos' | 'settings';

export const STORE_NAMES: readonly StoreName[] = ['houses', 'visits', 'photos', 'settings'];

/** The key property of each store, matching the `keyPath` used when it is created. */
export const STORE_KEY_PATH: Readonly<Record<StoreName, string>> = {
  houses: 'id',
  visits: 'id',
  photos: 'id',
  settings: 'key',
};

// Bumped when the stores change. Version 1 is Sprint 4a; Sprint 4b adds criteria, questions and viewings.
export const DB_NAME = 'doorprints';
export const DB_VERSION = 1;

export interface LocalDb {
  /** 'indexeddb' when the data is really being kept; 'memory' when it lives only for this page. */
  readonly kind: 'indexeddb' | 'memory';
  get<T>(store: StoreName, key: string): Promise<T | undefined>;
  getAll<T>(store: StoreName): Promise<T[]>;
  put<T>(store: StoreName, value: T): Promise<void>;
  putAll<T>(store: StoreName, values: readonly T[]): Promise<void>;
  delete(store: StoreName, key: string): Promise<void>;
  /** Empties one store, or every store when no name is given. */
  clear(store?: StoreName): Promise<void>;
  close(): void;
}

/** Why IndexedDB could not be used; shown to the user as a translated message. */
export type StorageProblem = 'unavailable' | 'blocked';

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

  get<T>(store: StoreName, key: string): Promise<T | undefined> {
    return Promise.resolve(this.map(store).get(key) as T | undefined);
  }

  getAll<T>(store: StoreName): Promise<T[]> {
    return Promise.resolve([...this.map(store).values()] as T[]);
  }

  put<T>(store: StoreName, value: T): Promise<void> {
    const record = value as Record<string, unknown>;
    const key = String(record[STORE_KEY_PATH[store]]);
    // Copy, so a later edit of the caller's object cannot change what is "stored" (IndexedDB copies too).
    this.map(store).set(key, { ...record });
    return Promise.resolve();
  }

  async putAll<T>(store: StoreName, values: readonly T[]): Promise<void> {
    for (const value of values) await this.put(store, value);
  }

  delete(store: StoreName, key: string): Promise<void> {
    this.map(store).delete(key);
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

class IdbDb implements LocalDb {
  readonly kind = 'indexeddb';
  private readonly db: IDBDatabase;

  constructor(db: IDBDatabase) {
    this.db = db;
  }

  get<T>(store: StoreName, key: string): Promise<T | undefined> {
    return this.run(store, 'readonly', (s) => s.get(key) as IDBRequest<T | undefined>);
  }

  getAll<T>(store: StoreName): Promise<T[]> {
    return this.run(store, 'readonly', (s) => s.getAll() as IDBRequest<T[]>);
  }

  async put<T>(store: StoreName, value: T): Promise<void> {
    await this.run(store, 'readwrite', (s) => s.put(value));
  }

  /** One transaction for the whole batch, so a pulled page of rows is applied all or nothing. */
  putAll<T>(store: StoreName, values: readonly T[]): Promise<void> {
    if (values.length === 0) return Promise.resolve();
    return new Promise<void>((resolve, reject) => {
      const tx = this.db.transaction(store, 'readwrite');
      const os = tx.objectStore(store);
      for (const value of values) os.put(value);
      tx.oncomplete = () => resolve();
      tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
      tx.onerror = () => reject(tx.error ?? new Error('IndexedDB transaction failed'));
    });
  }

  async delete(store: StoreName, key: string): Promise<void> {
    await this.run(store, 'readwrite', (s) => s.delete(key));
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
        reject(err instanceof Error ? err : new Error('IndexedDB transaction failed'));
        return;
      }
      let request: IDBRequest<T>;
      try {
        request = action(tx.objectStore(store));
      } catch (err: unknown) {
        reject(err instanceof Error ? err : new Error('IndexedDB request failed'));
        return;
      }
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
      tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
    });
  }
}

/**
 * Opens the local database, falling back to memory when the browser refuses. Never rejects: the caller gets a
 * working store either way plus the reason, so the UI can warn instead of breaking (S4-01 "private mode").
 */
export function openLocalDb(): Promise<OpenedDb> {
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
    request.onupgradeneeded = () => {
      const db = request.result;
      for (const name of STORE_NAMES) {
        if (!db.objectStoreNames.contains(name)) db.createObjectStore(name, { keyPath: STORE_KEY_PATH[name] });
      }
    };
    request.onsuccess = () => {
      if (settled) {
        request.result.close();
        return;
      }
      settled = true;
      resolve({ db: new IdbDb(request.result), problem: null });
    };
    request.onerror = () => fallback('blocked');
    // Another tab holds an older version open: carry on in memory rather than hanging for ever.
    request.onblocked = () => fallback('blocked');
  });
}
