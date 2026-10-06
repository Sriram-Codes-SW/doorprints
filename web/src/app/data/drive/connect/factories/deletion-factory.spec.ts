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

import { InMemoryKeyValueStore } from '../deletion-adapter';
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { createDeletionAdapter, createLazyDeletionAdapterProxy } from './deletion-factory';
import { createTestDeletionAdapter } from './deletion-factory.test-support';
import { InMemoryFakeDrive, FakeDriveServer } from '../../in-memory-fake-drive';
import { FakePrfAuthenticator } from '../../../device-auth/prf-seal';
import { FakeTokenProvider } from '../../fake-drive-faults';
import type { DriveRuntime } from './runtime';
import type { DeletionDecision } from '../../../device-auth/delete-policy';
import type { FolderSession } from '../../drive-sync-seams';

describe('deletion factories', () => {
  let crypto: WebCryptoProvider;
  let fakeDrive: InMemoryFakeDrive;
  let mockRuntime: DriveRuntime;

  beforeEach(async () => {
    crypto = new WebCryptoProvider();
    const server = new FakeDriveServer();
    const tokens = new FakeTokenProvider();
    fakeDrive = new InMemoryFakeDrive(server, tokens);

    // Create a minimal mock FolderSession for testing
    const mockSession = {
      rootId: 'root-id',
      deviceId: 'test-device-id',
      keys: {} as any,
      deviceKid: new Uint8Array(16),
      guard: {} as any,
      refresh: async () => ({} as any),
      reopen: null,
    } as unknown as FolderSession;

    // Create a mock runtime with minimal setup for testing
    mockRuntime = {
      db: {
        kind: 'memory' as const,
        persistent: false,
        close: () => {},
      },
      crypto,
      deviceKey: {
        privateKey: { keyId: new Uint8Array(16) } as any,
        publicKey: new Uint8Array(65),
      },
      deviceId: 'test-device-id',
      drive: fakeDrive,
      tokens,
      local: null as any,
      driveStateStore: null as any,
      folderTrustStores: null as any,
      kv: new InMemoryKeyValueStore(),
      syncStateStore: null as any,
      photoStateStore: null as any,
      session: mockSession,
    };
  });

  describe('createDeletionAdapter', () => {
    it('is exported as a function', () => {
      expect(typeof createDeletionAdapter).toBe('function');
    });

    it('creates an adapter with all required methods', () => {
      const adapter = createDeletionAdapter(mockRuntime);

      expect(adapter).toBeDefined();
      expect(typeof adapter.preflight).toBe('function');
      expect(typeof adapter.decide).toBe('function');
      expect(typeof adapter.authorize).toBe('function');
      expect(typeof adapter.execute).toBe('function');
      expect(typeof adapter.resume).toBe('function');
      expect(typeof adapter.confirmGate).toBe('function');
    });
  });

  describe('createLazyDeletionAdapterProxy', () => {
    it('is exported as a function', () => {
      expect(typeof createLazyDeletionAdapterProxy).toBe('function');
    });

    it('returns a proxy with adapter methods', () => {
      const getRuntime = async () => mockRuntime;
      const proxy = createLazyDeletionAdapterProxy(getRuntime);

      expect(proxy).toBeDefined();
      expect(typeof proxy.preflight).toBe('function');
      expect(typeof proxy.decide).toBe('function');
      expect(typeof proxy.authorize).toBe('function');
      expect(typeof proxy.execute).toBe('function');
      expect(typeof proxy.resume).toBe('function');
      expect(typeof proxy.confirmGate).toBe('function');
    });

    it('defers runtime creation until first async call', async () => {
      let runtimeCreated = false;
      const getRuntime = async () => {
        runtimeCreated = true;
        return mockRuntime;
      };

      const proxy = createLazyDeletionAdapterProxy(getRuntime);

      // Runtime should not be created yet
      expect(runtimeCreated).toBe(false);

      // Call a synchronous method - should still not create runtime
      proxy.confirmGate(
        { type: 'oneBackup', fileId: 'backup-1' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: false,
          online: true,
          backupsLeft: null,
        }
      );
      expect(runtimeCreated).toBe(false);

      // Call an async method - should create runtime
      await proxy.preflight({ type: 'oneBackup', fileId: 'backup-1' });
      expect(runtimeCreated).toBe(true);
    });
  });

  describe('L1 deletion without passkey', () => {
    it('L1 delete works without passkey (decide allows L1)', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const decision = adapter.decide(
        { type: 'oneBackup', fileId: 'backup-1' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: false,
          online: true,
          backupsLeft: 5,
        }
      ) as any;

      expect(decision.outcome).toBe('ALLOWED');
      expect(decision.requirements.level).toBe('L1');
      expect(decision.requirements.factor).toBe('NONE');
    });

    it('L1 confirm gate requires no tick box and no delay', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const gate = adapter.confirmGate(
        { type: 'oneBackup', fileId: 'backup-1' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: false,
          online: true,
          backupsLeft: 5,
        }
      );

      expect(gate.tickBoxRequired).toBe(false);
      expect(gate.delayMs).toBe(0);
      expect(gate.enabled(null, Date.now())).toBe(true);
    });
  });

  describe('L2/L3 deletion with passkey requirements', () => {
    it('L2 DELETE_ALL_BACKUPS requires passkey and tick box', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const decision = adapter.decide(
        { type: 'allBackups' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: true,
          online: true,
          backupsLeft: 5,
        }
      ) as any;

      expect(decision.outcome).toBe('ALLOWED');
      expect(decision.requirements.level).toBe('L2');
      expect(decision.requirements.factor).toBe('PASSKEY');
      expect(decision.requirements.tickBox).toBe(true);
      expect(decision.requirements.delaySeconds).toBe(0);
    });

    it('L3 DELETE_EVERYTHING requires passkey, tick box, and 5s delay', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const decision = adapter.decide(
        { type: 'everything' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: true,
          online: true,
          backupsLeft: 5,
        }
      ) as any;

      expect(decision.outcome).toBe('ALLOWED');
      expect(decision.requirements.level).toBe('L3');
      expect(decision.requirements.factor).toBe('PASSKEY');
      expect(decision.requirements.tickBox).toBe(true);
      expect(decision.requirements.delaySeconds).toBe(5);
    });

    it('L3 confirm gate enforces 5s delay', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const gate = adapter.confirmGate(
        { type: 'everything' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: true,
          online: true,
          backupsLeft: 5,
        }
      );

      expect(gate.tickBoxRequired).toBe(true);
      expect(gate.delayMs).toBe(5000);

      const tickedAt = Date.now();
      const nowMs = tickedAt + 4999; // 4.999 seconds later

      // Not enough time has passed
      expect(gate.enabled(tickedAt, nowMs)).toBe(false);

      // After 5 seconds, enabled
      expect(gate.enabled(tickedAt, tickedAt + 5000)).toBe(true);
    });
  });

  describe('L2/L3 refused without passkey support', () => {
    it('L2 DELETE_ALL_BACKUPS refused without webPrf support', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const decision = adapter.decide(
        { type: 'allBackups' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: false, // No PRF support
          online: true,
          backupsLeft: 5,
        }
      ) as any;

      expect(decision.outcome).toBe('REFUSED');
      expect(decision.reason).toBe('USE_PHONE');
    });

    it('L3 DELETE_EVERYTHING refused without webPrf support', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const decision = adapter.decide(
        { type: 'everything' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: false, // No PRF support
          online: true,
          backupsLeft: 5,
        }
      ) as any;

      expect(decision.outcome).toBe('REFUSED');
      expect(decision.reason).toBe('USE_PHONE');
    });

    it('Deletion refused when offline', () => {
      const adapter = createTestDeletionAdapter(mockRuntime);

      const decision = adapter.decide(
        { type: 'oneBackup', fileId: 'backup-1' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: true,
          online: false, // Offline
          backupsLeft: 5,
        }
      ) as any;

      expect(decision.outcome).toBe('REFUSED');
      expect(decision.reason).toBe('OFFLINE');
    });
  });

  describe('Grant expiration and one-use', () => {
    it('Adapter initializes successfully', () => {
      // Basic initialization test - the detailed grant/expiration tests
      // belong in web-authorizer.spec.ts
      const adapter = createTestDeletionAdapter(mockRuntime);
      expect(adapter).toBeDefined();
      expect(typeof adapter.authorize).toBe('function');
    });
  });

  describe('Adapter initialization', () => {
    it('initializes with fake drive and crypto', () => {
      const adapter = createDeletionAdapter(mockRuntime);
      expect(adapter).toBeDefined();

      // Verify the adapter can call its methods without errors
      const gate = adapter.confirmGate(
        { type: 'oneBackup', fileId: 'test' },
        {
          platform: 'WEBSITE',
          deviceLock: false,
          webPrf: false,
          online: true,
          backupsLeft: 5,
        }
      );
      expect(gate).toBeDefined();
    });
  });

  describe('Lazy proxy prfCapability forwarding', () => {
    it('forwards prfCapability when false to underlying adapter', async () => {
      const fakePrf = new FakePrfAuthenticator(crypto);
      fakePrf.capability = false;

      const adapter = createLazyDeletionAdapterProxy(
        async () => mockRuntime,
        { prf: fakePrf }
      );

      const cap = await adapter.prfCapability?.();
      expect(cap).toBe(false);
    });

    it('forwards prfCapability when true to underlying adapter', async () => {
      const fakePrf = new FakePrfAuthenticator(crypto);
      fakePrf.capability = true;

      const adapter = createLazyDeletionAdapterProxy(
        async () => mockRuntime,
        { prf: fakePrf }
      );

      const cap = await adapter.prfCapability?.();
      expect(cap).toBe(true);
    });
  });
});
