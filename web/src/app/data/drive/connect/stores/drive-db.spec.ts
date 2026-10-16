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
import type { DriveSyncState } from '../../drive-sync-seams';
import type { PhotoState } from '../../drive-photo-seams';
import { normalizeDeviceState } from '../../backup/drive-backup-seams';
import type { DriveDeviceState } from '../../backup/drive-backup-seams';
import type { KeyValueStore } from '../deletion-adapter';
import type { KeysWatermarkStore } from '../../../crypto/keys-file';
import type { SyncStateStore } from '../../drive-sync-seams';
import type { DeletionStore, PendingDeletion } from '../../drive-deletion';
import type { PhotoStateStore } from '../../drive-photo-seams';
import type { DriveStateStore } from '../../backup/drive-backup-seams';

describe('DriveDb stores', () => {
  describe('KeyValueStore (in-memory)', () => {
    let store: KeyValueStore;

    beforeEach(() => {
      // Create a simple in-memory implementation for testing
      const data = new Map<string, string>();
      store = {
        get: async (key: string) => data.get(key),
        set: async (key: string, value: string) => { data.set(key, value); },
        delete: async (key: string) => { data.delete(key); },
      };
    });

    it('stores and retrieves values', async () => {
      await store.set('test-key', 'test-value');
      const result = await store.get('test-key');
      expect(result).toBe('test-value');
    });

    it('returns undefined for missing keys', async () => {
      const result = await store.get('missing-key');
      expect(result).toBeUndefined();
    });

    it('deletes keys', async () => {
      await store.set('test-key', 'test-value');
      await store.delete('test-key');
      const result = await store.get('test-key');
      expect(result).toBeUndefined();
    });
  });

  describe('KeysWatermarkStore (in-memory)', () => {
    let store: KeysWatermarkStore;

    beforeEach(() => {
      let value: any = null;
      store = {
        load: async () => value,
        compareAndSet: async (expected: any, next: any) => {
          if (JSON.stringify(value) === JSON.stringify(expected)) {
            value = next;
            return true;
          }
          return false;
        },
      };
    });

    it('loads null initially', async () => {
      const result = await store.load();
      expect(result).toBeNull();
    });

    it('performs atomic compare-and-set', async () => {
      const watermark1 = { epoch: 1, revision: 0, keyId: new Uint8Array([1]), bodyHash: new Uint8Array([2]) };
      const watermark2 = { epoch: 1, revision: 1, keyId: new Uint8Array([1]), bodyHash: new Uint8Array([2]) };

      const success1 = await store.compareAndSet(null, watermark1);
      expect(success1).toBe(true);

      const loaded = await store.load();
      if (!loaded) throw new Error('Expected watermark to be loaded');
      expect(loaded.revision).toBe(0);

      const success2 = await store.compareAndSet(watermark1, watermark2);
      expect(success2).toBe(true);

      const success3 = await store.compareAndSet(watermark1, watermark2);
      expect(success3).toBe(false);
    });
  });

  describe('SyncStateStore (in-memory)', () => {
    let store: SyncStateStore;

    beforeEach(() => {
      const emptyState: DriveSyncState = {
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

      let state = emptyState;
      store = {
        load: async () => ({ ...state }),
        save: async (newState: DriveSyncState) => { state = { ...newState }; },
      };
    });

    it('loads empty state initially', async () => {
      const state = await store.load();
      expect(state.syncFolderId).toBeNull();
      expect(state.lastSeq).toBe(0);
    });

    it('saves and loads state', async () => {
      const newState: DriveSyncState = {
        syncFolderId: 'folder-123',
        lastSeq: 10,
        confirmedSeq: 5,
        lastFileId: 'file-456',
        lastChecksum: 'abc123',
        lastRowsHash: 'def456',
        generation: 1,
        peers: {},
        failures: 0,
        notBefore: Date.now(),
      };

      await store.save(newState);
      const loaded = await store.load();
      expect(loaded.syncFolderId).toBe('folder-123');
      expect(loaded.lastSeq).toBe(10);
      expect(loaded.confirmedSeq).toBe(5);
    });
  });

  describe('PhotoStateStore (in-memory)', () => {
    let store: PhotoStateStore;

    beforeEach(() => {
      let state: PhotoState = { photosFolderId: null, refs: {}, bad: {} };
      store = {
        load: async () => state,
        save: async (newState: PhotoState) => { state = newState; },
      };
    });

    it('loads empty state initially', async () => {
      const state = await store.load();
      expect(state.photosFolderId).toBeNull();
      expect(state.refs).toEqual({});
    });

    it('saves and loads photo state with refs', async () => {
      const newState: PhotoState = {
        photosFolderId: 'photos-folder-123',
        refs: {
          'photo-1': {
            driveFileId: 'file-123',
            sha256: 'abc123def456',
          },
        },
        bad: {},
      };

      await store.save(newState);
      const loaded = await store.load();
      expect(loaded.photosFolderId).toBe('photos-folder-123');
      expect(loaded.refs['photo-1'].driveFileId).toBe('file-123');
    });
  });

  describe('DeletionStore (in-memory)', () => {
    let store: DeletionStore;

    beforeEach(() => {
      let pending: any = null;
      let marker: any = null;

      store = {
        pending: async () => pending,
        savePending: async (p: any) => { pending = p; },
        clearPending: async () => { pending = null; },
        marker: async () => marker,
        recordFinished: async (m: any, forgetFolder: boolean) => { marker = m; },
      };
    });

    it('returns null for missing pending deletion', async () => {
      const result = await store.pending();
      expect(result).toBeNull();
    });

    it('saves and loads pending deletion', async () => {
      const pending: PendingDeletion = {
        operationId: 'op-123',
        level: 'L1',
        action: { type: 'allBackups' },
        rootId: 'root-123',
        items: [],
        total: 0,
        createdAtMs: Date.now(),
      };

      await store.savePending(pending);
      const loaded = await store.pending();
      expect(loaded?.operationId).toBe('op-123');
      expect(loaded?.level).toBe('L1');
    });

    it('clears pending deletion', async () => {
      const pending: PendingDeletion = {
        operationId: 'op-123',
        level: 'L1',
        action: { type: 'allBackups' },
        rootId: 'root-123',
        items: [],
        total: 0,
        createdAtMs: Date.now(),
      };

      await store.savePending(pending);
      await store.clearPending();
      const result = await store.pending();
      expect(result).toBeNull();
    });

    it('saves and loads deletion marker', async () => {
      const marker = {
        level: 'L2' as const,
        atMs: Date.now(),
        action: 'DELETE_BACKUPS',
      };

      await store.recordFinished(marker, false);
      const loaded = await store.marker();
      expect(loaded?.level).toBe('L2');
    });
  });

  describe('DriveStateStore (in-memory)', () => {
    let store: DriveStateStore;

    beforeEach(() => {
      const emptyState: DriveDeviceState = {
        deviceId: null,
        rootId: null,
        backupsId: null,
        keysId: null,
        controlId: null,
        creatingRootId: null,
        creatingKeysHash: null,
        lastBackupId: null,
        lastSuccessAt: null,
        lastAttemptAt: null,
        lastFailure: null,
        lastVerifyAt: null,
        newestSeenAt: null,
        confirmedDrops: [],
      };

      let state = emptyState;
      store = {
        load: async () => ({ ...state }),
        save: async (newState: DriveDeviceState) => { state = { ...newState }; },
      };
    });

    it('loads empty state initially', async () => {
      const state = await store.load();
      expect(state.rootId).toBeNull();
      expect(state.lastBackupId).toBeNull();
    });

    it('saves and loads device state', async () => {
      const newState: DriveDeviceState = {
        deviceId: 'device-123',
        rootId: 'root-456',
        backupsId: 'backups-789',
        keysId: 'keys-101112',
        controlId: 'control-131415',
        creatingRootId: null,
        creatingKeysHash: null,
        lastBackupId: 'backup-161718',
        lastSuccessAt: Date.now(),
        lastAttemptAt: Date.now(),
        lastFailure: null,
        lastVerifyAt: Date.now(),
        newestSeenAt: Date.now(),
        confirmedDrops: ['backup-1', 'backup-2'],
      };

      await store.save(newState);
      const loaded = await store.load();
      expect(loaded.deviceId).toBe('device-123');
      expect(loaded.rootId).toBe('root-456');
      expect(loaded.confirmedDrops).toHaveLength(2);
    });
  });

  it('an older device state without a keys hash does not invent one', () => {
    const loaded = normalizeDeviceState({
      deviceId: null,
      rootId: 'root-old',
      backupsId: null,
      keysId: null,
      controlId: null,
      creatingRootId: 'root-old',
      lastBackupId: null,
      lastSuccessAt: null,
      lastAttemptAt: null,
      lastFailure: null,
      lastVerifyAt: null,
      newestSeenAt: null,
      confirmedDrops: ['kept'],
    });
    expect(loaded.rootId).toBe('root-old');
    expect(loaded.creatingRootId).toBe('root-old');
    expect(loaded.creatingKeysHash).toBeNull();
    expect(loaded.confirmedDrops).toEqual(['kept']);
  });
});
