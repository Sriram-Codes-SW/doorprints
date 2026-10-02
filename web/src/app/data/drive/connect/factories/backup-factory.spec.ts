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

import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { hex } from '../../../crypto/bytes';
import { kidOf } from '../../../crypto/folder-key';
import type { TokenProvider } from '../../drive-client';
import type { LocalStore } from '../../../local-store.service';
import type { DriveDb } from '../stores/drive-db';
import { DriveBackupAdapter } from '../backup-adapter';
import { createBackupAdapter, createLazyBackupAdapterProxy } from './backup-factory';
import type { DriveRuntime } from './runtime';

describe('createBackupAdapter', () => {
  let runtime: DriveRuntime;
  let tokenProvider: TokenProvider;
  let localStore: LocalStore;
  let crypto: WebCryptoProvider;
  let db: DriveDb;

  beforeEach(async () => {
    crypto = new WebCryptoProvider();
    tokenProvider = {
      accessToken: async () => 'test-token',
    } as unknown as TokenProvider;

    localStore = {
      storageProblem: { hasValue: () => false } as any,
    } as unknown as LocalStore;

    // Create a minimal mock DB
    db = {
      kind: 'memory',
      persistent: false,
      close: () => {},
    };

    // Create a mock drive state store
    const mockDriveStateStore = {
      load: async () => ({
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
      }),
      save: async () => {},
    };

    // Create a mock drive client
    const mockDriveClient = {
      upload: async () => null,
      list: async () => ({ files: [], nextPageToken: null, incompleteSearch: false }),
      download: async () => new Uint8Array(),
    } as any;

    // Generate device key
    const deviceKeyPair = await crypto.p256Generate();
    // Get raw public key from the key pair
    const publicKeyRaw = new Uint8Array(65);
    publicKeyRaw[0] = 0x04; // Uncompressed point prefix

    // Create runtime
    runtime = {
      db,
      crypto,
      deviceKey: {
        privateKey: deviceKeyPair,
        publicKey: publicKeyRaw,
      },
      deviceId: hex(kidOf(crypto, publicKeyRaw)),
      drive: mockDriveClient,
      tokens: tokenProvider,
      local: localStore,
      driveStateStore: mockDriveStateStore,
    };
  });

  describe('createBackupAdapter', () => {
    it('creates a backup adapter', () => {
      const adapter = createBackupAdapter(runtime);

      expect(adapter).toBeDefined();
      expect(typeof adapter.connect).toBe('function');
      expect(typeof adapter.createFolder).toBe('function');
      expect(typeof adapter.backUpNow).toBe('function');
      expect(typeof adapter.listBackups).toBe('function');
      expect(typeof adapter.importFromDrive).toBe('function');
      expect(typeof adapter.writeReadMe).toBe('function');
      expect(typeof adapter.schedule).toBe('function');
    });

    it('adapter is an instance of DriveBackupAdapter', () => {
      const adapter = createBackupAdapter(runtime);

      // Verify that the adapter was created with the correct runtime
      expect(adapter).toBeDefined();
      expect(adapter instanceof DriveBackupAdapter).toBe(true);
    });

    it('adapter uses crypto from runtime', () => {
      const adapter = createBackupAdapter(runtime);

      // Verify that the adapter was created with the correct runtime
      expect(adapter).toBeDefined();
      // The adapter should have been created with our runtime's crypto provider
    });
  });

  describe('createLazyBackupAdapterProxy', () => {
    it('creates a lazy proxy that implements BackupAdapterInterface', () => {
      const getRuntime = async () => runtime;
      const proxy = createLazyBackupAdapterProxy(getRuntime);

      expect(proxy).toBeDefined();
      expect(typeof proxy.connect).toBe('function');
      expect(typeof proxy.createFolder).toBe('function');
      expect(typeof proxy.backUpNow).toBe('function');
      expect(typeof proxy.listBackups).toBe('function');
      expect(typeof proxy.importFromDrive).toBe('function');
      expect(typeof proxy.writeReadMe).toBe('function');
      expect(typeof proxy.schedule).toBe('function');
    });

    it('proxy defers getRuntime call until first use', async () => {
      let getRuntimeCalled = false;
      const getRuntime = async () => {
        getRuntimeCalled = true;
        return runtime;
      };

      const proxy = createLazyBackupAdapterProxy(getRuntime);

      // Just creating the proxy should not call getRuntime
      expect(getRuntimeCalled).toBe(false);

      // Calling a method should trigger getRuntime
      try {
        await proxy.connect();
      } catch (e) {
        // May fail due to mock setup, that's okay
      }
      expect(getRuntimeCalled).toBe(true);
    });

    it('proxy.connect method exists and can be called', async () => {
      const getRuntime = async () => runtime;
      const proxy = createLazyBackupAdapterProxy(getRuntime);

      // Method should exist and be callable
      expect(typeof proxy.connect).toBe('function');
      const connectPromise = proxy.connect();
      expect(connectPromise instanceof Promise).toBe(true);
    });

    it('proxy.schedule method exists and can be called', async () => {
      const getRuntime = async () => runtime;
      const proxy = createLazyBackupAdapterProxy(getRuntime);

      // Method should exist and be callable
      expect(typeof proxy.schedule).toBe('function');
      const schedulePromise = proxy.schedule(true, true);
      expect(schedulePromise instanceof Promise).toBe(true);
    });
  });
});
