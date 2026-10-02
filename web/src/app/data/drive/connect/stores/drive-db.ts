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

import type { KeysWatermarkStore } from '../../../crypto/keys-file';
import type { DeletionStore, DeletedMarker, PendingDeletion } from '../../drive-deletion';
import type { DriveSyncState, SyncStateStore } from '../../drive-sync-seams';
import type { PhotoState, PhotoStateStore } from '../../drive-photo-seams';
import type { DriveDeviceState, DriveStateStore } from '../../backup/drive-backup-seams';
import type { KeyValueStore } from '../deletion-adapter';

const DB_NAME = 'doorprints-drive';
const DB_VERSION = 1;

const STORE_NAMES = {
  state: 'state',
  keysWatermark: 'keys-watermark',
  deletionPending: 'deletion-pending',
  photoState: 'photo-state',
  deviceKey: 'device-key',
} as const;

export interface DriveDb {
  /** 'indexeddb' when data is really kept; 'memory' when it lives only for this page. */
  readonly kind: 'indexeddb' | 'memory';
  readonly persistent: boolean; // true = IndexedDB, false = in-memory fallback
  close(): void;
}

export interface OpenedDriveDb {
  db: DriveDb;
  keyValueStore: KeyValueStore;
  keysWatermarkStore: KeysWatermarkStore;
  syncStateStore: SyncStateStore;
  deletionStore: DeletionStore;
  photoStateStore: PhotoStateStore;
  driveStateStore: DriveStateStore;
}

/** In-memory implementations for when IndexedDB is unavailable. */
class MemoryKeyValueStore implements KeyValueStore {
  private data = new Map<string, string>();

  async get(key: string): Promise<string | undefined> {
    return this.data.get(key);
  }

  async set(key: string, value: string): Promise<void> {
    this.data.set(key, value);
  }

  async delete(key: string): Promise<void> {
    this.data.delete(key);
  }
}

class MemoryKeysWatermarkStore implements KeysWatermarkStore {
  private value: any = null;

  async load(): Promise<any> {
    return this.value;
  }

  async compareAndSet(expected: any, next: any): Promise<boolean> {
    if (JSON.stringify(this.value) === JSON.stringify(expected)) {
      this.value = next;
      return true;
    }
    return false;
  }
}

class MemorySyncStateStore implements SyncStateStore {
  private state: DriveSyncState = {
    syncFolderId: null,
    lastSeq: 0,
    confirmedSeq: 0,
    lastFileId: null,
    lastChecksum: null,
    lastRowsHash: null,
    generation: 0,
    peers: {},
    failures: 0,
    notBefore: 0,
  };

  async load(): Promise<DriveSyncState> {
    return { ...this.state };
  }

  async save(state: DriveSyncState): Promise<void> {
    this.state = { ...state };
  }
}

class MemoryDeletionStore implements DeletionStore {
  private pending: PendingDeletion | null = null;
  private marker: DeletedMarker | null = null;

  async pending(): Promise<PendingDeletion | null> {
    return this.pending;
  }

  async savePending(p: PendingDeletion): Promise<void> {
    this.pending = p;
  }

  async clearPending(): Promise<void> {
    this.pending = null;
  }

  async marker(): Promise<DeletedMarker | null> {
    return this.marker;
  }

  async recordFinished(m: DeletedMarker, forgetFolder: boolean): Promise<void> {
    this.marker = m;
  }
}

class MemoryPhotoStateStore implements PhotoStateStore {
  private state: PhotoState = { photosFolderId: null, refs: {}, bad: {} };

  async load(): Promise<PhotoState> {
    return { ...this.state };
  }

  async save(state: PhotoState): Promise<void> {
    this.state = { ...state };
  }
}

class MemoryDriveStateStore implements DriveStateStore {
  private state: DriveDeviceState = {
    deviceId: null,
    rootId: null,
    backupsId: null,
    keysId: null,
    controlId: null,
    creatingRootId: null,
    lastBackupId: null,
    lastSuccessAt: null,
    lastAttemptAt: null,
    lastFailure: null,
    lastVerifyAt: null,
    newestSeenAt: null,
    confirmedDrops: [],
  };

  async load(): Promise<DriveDeviceState> {
    return { ...this.state };
  }

  async save(state: DriveDeviceState): Promise<void> {
    this.state = { ...state };
  }
}

class MemoryDriveDb implements DriveDb {
  readonly kind = 'memory';
  readonly persistent = false;
  close(): void {}
}

/** IndexedDB implementations. */
class IndexedDbKeyValueStore implements KeyValueStore {
  constructor(private db: IDBDatabase) {}

  async get(key: string): Promise<string | undefined> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.state], 'readonly');
      const store = tx.objectStore(STORE_NAMES.state);
      const req = store.get(key);
      req.onsuccess = () => resolve(req.result?.value);
      req.onerror = () => reject(req.error);
    });
  }

  async set(key: string, value: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.state], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.state);
      const req = store.put({ key, value });
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }

  async delete(key: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.state], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.state);
      const req = store.delete(key);
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }
}

class IndexedDbKeysWatermarkStore implements KeysWatermarkStore {
  constructor(private db: IDBDatabase) {}

  async load(): Promise<any> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.keysWatermark], 'readonly');
      const store = tx.objectStore(STORE_NAMES.keysWatermark);
      const req = store.get('watermark');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async compareAndSet(expected: any, next: any): Promise<boolean> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.keysWatermark], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.keysWatermark);
      const getReq = store.get('watermark');

      getReq.onsuccess = () => {
        const current = getReq.result;
        const currentStr = JSON.stringify(current);
        const expectedStr = JSON.stringify(expected);

        if (currentStr !== expectedStr) {
          resolve(false);
          return;
        }

        const putReq = store.put(next, 'watermark');
        putReq.onerror = () => reject(putReq.error);
        tx.oncomplete = () => resolve(true);
      };

      getReq.onerror = () => reject(getReq.error);
    });
  }
}

class IndexedDbSyncStateStore implements SyncStateStore {
  constructor(private db: IDBDatabase) {}

  async load(): Promise<DriveSyncState> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.state], 'readonly');
      const store = tx.objectStore(STORE_NAMES.state);
      const req = store.get('sync-state');
      req.onsuccess = () => {
        const state = req.result?.value;
        resolve(
          state || {
            syncFolderId: null,
            lastSeq: 0,
            confirmedSeq: 0,
            lastFileId: null,
            lastChecksum: null,
            lastRowsHash: null,
            generation: 0,
            peers: {},
            failures: 0,
            notBefore: 0,
          }
        );
      };
      req.onerror = () => reject(req.error);
    });
  }

  async save(state: DriveSyncState): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.state], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.state);
      const req = store.put({ key: 'sync-state', value: state });
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }
}

class IndexedDbDeletionStore implements DeletionStore {
  constructor(private db: IDBDatabase) {}

  async pending(): Promise<PendingDeletion | null> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.deletionPending], 'readonly');
      const store = tx.objectStore(STORE_NAMES.deletionPending);
      const req = store.get('pending');
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  }

  async savePending(pending: PendingDeletion): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.deletionPending], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.deletionPending);
      const req = store.put(pending, 'pending');
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }

  async clearPending(): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.deletionPending], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.deletionPending);
      const req = store.delete('pending');
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }

  async marker(): Promise<DeletedMarker | null> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.deletionPending], 'readonly');
      const store = tx.objectStore(STORE_NAMES.deletionPending);
      const req = store.get('marker');
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  }

  async recordFinished(marker: DeletedMarker, forgetFolder: boolean): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.deletionPending], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.deletionPending);
      const req = store.put(marker, 'marker');
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }
}

class IndexedDbPhotoStateStore implements PhotoStateStore {
  constructor(private db: IDBDatabase) {}

  async load(): Promise<PhotoState> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.photoState], 'readonly');
      const store = tx.objectStore(STORE_NAMES.photoState);
      const req = store.get('photo-state');
      req.onsuccess = () => {
        const state = req.result;
        resolve(
          state || { photosFolderId: null, refs: {}, bad: {} }
        );
      };
      req.onerror = () => reject(req.error);
    });
  }

  async save(state: PhotoState): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.photoState], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.photoState);
      const req = store.put(state, 'photo-state');
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }
}

class IndexedDbDriveStateStore implements DriveStateStore {
  constructor(private db: IDBDatabase) {}

  async load(): Promise<DriveDeviceState> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.state], 'readonly');
      const store = tx.objectStore(STORE_NAMES.state);
      const req = store.get('device-state');
      req.onsuccess = () => {
        const state = req.result?.value;
        resolve(
          state || {
            deviceId: null,
            rootId: null,
            backupsId: null,
            keysId: null,
            controlId: null,
            creatingRootId: null,
            lastBackupId: null,
            lastSuccessAt: null,
            lastAttemptAt: null,
            lastFailure: null,
            lastVerifyAt: null,
            newestSeenAt: null,
            confirmedDrops: [],
          }
        );
      };
      req.onerror = () => reject(req.error);
    });
  }

  async save(state: DriveDeviceState): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([STORE_NAMES.state], 'readwrite');
      const store = tx.objectStore(STORE_NAMES.state);
      const req = store.put({ key: 'device-state', value: state });
      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }
}

class IndexedDbDriveDb implements DriveDb {
  readonly kind = 'indexeddb';
  readonly persistent = true;

  constructor(private db: IDBDatabase) {}

  close(): void {
    this.db.close();
  }
}

function upgradeDb(db: IDBDatabase, oldVersion: number, newVersion: number): void {
  // Version 1: initial schema with all stores
  if (oldVersion < 1) {
    if (!db.objectStoreNames.contains(STORE_NAMES.state)) {
      db.createObjectStore(STORE_NAMES.state, { keyPath: 'key' });
    }
    if (!db.objectStoreNames.contains(STORE_NAMES.keysWatermark)) {
      db.createObjectStore(STORE_NAMES.keysWatermark);
    }
    if (!db.objectStoreNames.contains(STORE_NAMES.deletionPending)) {
      db.createObjectStore(STORE_NAMES.deletionPending);
    }
    if (!db.objectStoreNames.contains(STORE_NAMES.photoState)) {
      db.createObjectStore(STORE_NAMES.photoState);
    }
    if (!db.objectStoreNames.contains(STORE_NAMES.deviceKey)) {
      db.createObjectStore(STORE_NAMES.deviceKey);
    }
  }
}

export async function openDriveDb(): Promise<OpenedDriveDb> {
  try {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);

      req.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        upgradeDb(db, event.oldVersion, event.newVersion || DB_VERSION);
      };

      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });

    const driveDb = new IndexedDbDriveDb(db);
    const keyValueStore = new IndexedDbKeyValueStore(db);
    const keysWatermarkStore = new IndexedDbKeysWatermarkStore(db);
    const syncStateStore = new IndexedDbSyncStateStore(db);
    const deletionStore = new IndexedDbDeletionStore(db);
    const photoStateStore = new IndexedDbPhotoStateStore(db);
    const driveStateStore = new IndexedDbDriveStateStore(db);

    return {
      db: driveDb,
      keyValueStore,
      keysWatermarkStore,
      syncStateStore,
      deletionStore,
      photoStateStore,
      driveStateStore,
    };
  } catch (err) {
    // Fall back to in-memory implementation
    const memoryDb = new MemoryDriveDb();
    return {
      db: memoryDb,
      keyValueStore: new MemoryKeyValueStore(),
      keysWatermarkStore: new MemoryKeysWatermarkStore(),
      syncStateStore: new MemorySyncStateStore(),
      deletionStore: new MemoryDeletionStore(),
      photoStateStore: new MemoryPhotoStateStore(),
      driveStateStore: new MemoryDriveStateStore(),
    };
  }
}
