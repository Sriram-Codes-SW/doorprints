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
import { WebCryptoProvider } from '../../crypto/crypto-provider';
import type { LockRunner } from './lock-runner';
import { DriveBackupService } from './drive-backup.service';
import { InMemoryFakeDrive } from '../in-memory-fake-drive';
import type { DriveStateStore, DriveDeviceState } from './drive-backup-seams';
import { DbFolderTrustStores } from '../connect/factories/runtime';
import type { DeviceIdentity } from './drive-backup-seams';

const p = new WebCryptoProvider();

/**
 * Fake LockRunner that serializes concurrent calls for testing the race condition fix.
 * Uses a queue to ensure only one function runs at a time within a lock name.
 */
class FakeLockRunner implements LockRunner {
  private lockQueues: Map<string, Promise<void>> = new Map();

  async request<T>(lockName: string, fn: () => Promise<T>): Promise<T> {
    // Get the current lock promise (if any) for this lock name
    const previousPromise = this.lockQueues.get(lockName) ?? Promise.resolve();

    // Create a new promise that will be resolved after we finish
    let resolveNext: () => void;
    const nextPromise = new Promise<void>((resolve) => {
      resolveNext = resolve;
    });

    // Store the new promise as the current lock for this name
    this.lockQueues.set(lockName, nextPromise);

    try {
      // Wait for the previous lock to complete before running our function
      await previousPromise;
      // Run the function
      return await fn();
    } finally {
      // Resolve the promise so the next caller can proceed
      resolveNext!();
    }
  }
}

/**
 * Fake state store for testing.
 */
class MemoryStateStore implements DriveStateStore {
  private state: DriveDeviceState = {
    rootId: null,
    keysId: null,
    controlId: null,
    backupsId: null,
    creatingRootId: null,
    deviceId: null,
    confirmedDrops: [],
    lastSuccessAt: null,
    lastAttemptAt: null,
    lastFailure: null,
    lastVerifyAt: null,
    newestSeenAt: null,
    lastBackupId: null,
  };

  async load(): Promise<DriveDeviceState> {
    return { ...this.state };
  }

  async save(state: DriveDeviceState): Promise<void> {
    this.state = { ...state };
  }
}

/**
 * In-memory KV store that actually persists data (unlike the test stub above).
 */
class InMemoryKeyValueStore {
  private data: Map<string, string> = new Map();

  async get(k: string): Promise<string | undefined> {
    return this.data.get(k);
  }

  async set(k: string, v: string): Promise<void> {
    this.data.set(k, v);
  }
}

describe('DriveBackupService race condition (createFolder)', () => {
  it('creates a folder and returns READY', async () => {
    const drive = new InMemoryFakeDrive();
    const state = new MemoryStateStore();
    const kv = new InMemoryKeyValueStore();
    const folderTrustStores = new DbFolderTrustStores(kv);

    const device: DeviceIdentity = {
      key: await p.p256Generate(),
      name: 'Test Device',
      platform: 'web',
    };

    const service = new DriveBackupService(drive, p, device, state, folderTrustStores, () => Date.now(), () => 0, undefined, new FakeLockRunner());

    const result = await service.createFolder(true);
    expect(result.connection.kind).toBe('READY');
    expect(result.recoveryKey).not.toBeNull();

    // Verify the folder was created
    const savedState = await state.load();
    expect(savedState.rootId).not.toBeNull();
    expect(savedState.keysId).not.toBeNull();
    expect(savedState.controlId).not.toBeNull();
  });

  it('two concurrent createFolder calls create only one folder (second finds the first)', async () => {
    const drive = new InMemoryFakeDrive();
    const state = new MemoryStateStore();
    const kv = new InMemoryKeyValueStore();
    const folderTrustStores = new DbFolderTrustStores(kv);

    const device: DeviceIdentity = {
      key: await p.p256Generate(),
      name: 'Test Device',
      platform: 'web',
    };

    const lockRunner = new FakeLockRunner();
    const service = new DriveBackupService(drive, p, device, state, folderTrustStores, () => Date.now(), () => 0, undefined, lockRunner);

    // Launch two concurrent createFolder calls
    const [result1, result2] = await Promise.all([
      service.createFolder(true),
      service.createFolder(false), // No recovery key on second
    ]);

    // Both should succeed (not error)
    expect(result1.connection.kind).toBe('READY');
    expect(result2.connection.kind).toBe('READY');

    // Both should reference the same root folder
    const state1 = (result1.connection as any).folder;
    const state2 = (result2.connection as any).folder;
    expect(state1.rootId).toBe(state2.rootId);
    expect(state1.keysId).toBe(state2.keysId);
    expect(state1.controlId).toBe(state2.controlId);

    // Only the first call should have a recovery key (it's the creator)
    // The second call adopted the folder and doesn't have a recovery key
    expect(result1.recoveryKey).not.toBeNull();
    expect(result2.recoveryKey).toBeNull();

    // Verify both tabs have the same folder root ID stored in state
    const savedState = await state.load();
    expect(savedState.rootId).not.toBeNull();
    expect(savedState.rootId).toBe(state1.rootId);
    expect(savedState.rootId).toBe(state2.rootId);
  });

  it('without Web Locks, concurrent calls still work (fallback behavior)', async () => {
    // Using the default WebLockRunner, which feature-detects and falls back to unlocked
    const drive = new InMemoryFakeDrive();
    const state = new MemoryStateStore();
    const kv = new InMemoryKeyValueStore();
    const folderTrustStores = new DbFolderTrustStores(kv);

    const device: DeviceIdentity = {
      key: await p.p256Generate(),
      name: 'Test Device',
      platform: 'web',
    };

    // Use default WebLockRunner (feature-detects Web Locks)
    const service = new DriveBackupService(drive, p, device, state, folderTrustStores, () => Date.now(), () => 0);

    const result = await service.createFolder(true);
    expect(result.connection.kind).toBe('READY');
  });
});
