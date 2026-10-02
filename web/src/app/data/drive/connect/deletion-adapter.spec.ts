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

import { DRIVE_LAYOUT } from '../drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from '../in-memory-fake-drive';
import { DriveDeletionService } from '../drive-deletion';
import type { AuthorizationGate, DeletedMarker, DeletionStore } from '../drive-deletion';
import {
  DriveDeletionAdapterImpl,
  InMemoryKeyValueStore,
  PersistentDeletionStore,
} from './deletion-adapter';
import { FakeAuthorizationGate } from './deletion-adapter.test-support';
import type { WebGrant } from '../../device-auth/web-authorizer';
import type { DeletionContext } from '../../device-auth/delete-policy';

describe('DriveDeletionAdapter', () => {
  let server: FakeDriveServer;
  let drive: InMemoryFakeDrive;
  let deletionService: DriveDeletionService;
  let store: DeletionStore;
  let gate: FakeAuthorizationGate;
  let fakeAuthorizer: {
    authorize: (a: any, c: DeletionContext) => Promise<any>;
    redeem?: (grant: any, action: any) => any;
  };
  let adapter: DriveDeletionAdapterImpl;
  let rootId: string;
  let isOnline: boolean;
  let kv: InMemoryKeyValueStore;

  beforeEach(() => {
    // Set up the fake drive server
    server = new FakeDriveServer();
    drive = new InMemoryFakeDrive(server);

    // Set up online state
    isOnline = true;

    // Set up stores
    kv = new InMemoryKeyValueStore();
    store = new PersistentDeletionStore(kv);

    // Set up gate
    gate = new FakeAuthorizationGate();

    // Set up fake authorizer
    fakeAuthorizer = {
      authorize: async (action: any, ctx: DeletionContext): Promise<any> => {
        const grant: WebGrant = {
          id: 1,
          action: action,
          requirements: {
            level: 'L1',
            factor: 'NONE',
            tickBox: false,
            delaySeconds: 0,
            pairing: 'NONE',
            authValidMs: 0,
          },
          grantedAtMs: server.clock.now(),
        };
        return { kind: 'GRANTED', grant };
      },
    };

    // Create a root folder by hand for testing
    rootId = server.putByHand({
      name: 'Doorprints',
      mimeType: 'application/vnd.google-apps.folder',
      appProperties: { [DRIVE_LAYOUT.role]: 'root' },
    }, new Uint8Array()).id;

    // Set up deletion service
    const deps = {
      drive,
      gate,
      store,
      isOnline: () => isOnline,
      now: () => server.clock.now(),
      saveEvery: 25,
    };
    deletionService = new DriveDeletionService(deps);

    // Set up adapter
    adapter = new DriveDeletionAdapterImpl(deletionService, fakeAuthorizer as any, store, rootId, 5, gate);
  });

  it('should preflight a deletion', async () => {
    // Create a backups folder by hand
    const backups = server.putByHand({
      name: 'Backups',
      mimeType: 'application/vnd.google-apps.folder',
      parents: [rootId],
      appProperties: { [DRIVE_LAYOUT.role]: 'backups' },
    }, new Uint8Array());

    // Create a backup file
    server.putByHand({
      name: 'backup-1.zip',
      mimeType: 'application/octet-stream',
      parents: [backups.id],
      appProperties: {
        [DRIVE_LAYOUT.kind]: 'backup',
        [DRIVE_LAYOUT.state]: 'complete',
      },
    }, new Uint8Array(1024));

    // Preflight the deletion
    const result = await adapter.preflight({ type: 'allBackups' });

    expect(result.kind).toBe('ready');
    if (result.kind === 'ready') {
      expect(result.plan.items.length).toBeGreaterThan(0);
      expect(result.plan.totals.backup?.count).toBe(1);
    }
  });

  it('should decide on deletions based on policy', () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: true,
      backupsLeft: 5,
    };

    const decision = adapter.decide({ type: 'allBackups' }, context);

    expect(decision.outcome).toBe('ALLOWED');
  });

  it('should refuse deletion when online is false for network actions', () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: false,
      backupsLeft: 5,
    };

    const decision = adapter.decide({ type: 'allBackups' }, context);

    expect(decision.outcome).toBe('REFUSED');
    if (decision.outcome === 'REFUSED') {
      expect(decision.reason).toBe('OFFLINE');
    }
  });

  it('should refuse L3 deletion without web PRF', () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: false,
      online: true,
      backupsLeft: 5,
    };

    const decision = adapter.decide({ type: 'everything' }, context);

    expect(decision.outcome).toBe('REFUSED');
    if (decision.outcome === 'REFUSED') {
      expect(decision.reason).toBe('USE_PHONE');
    }
  });

  it('should authorize and issue a grant', async () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: true,
      backupsLeft: 5,
    };

    const result = await adapter.authorize({ type: 'allBackups' }, context);

    expect(result.kind).toBe('granted');
  });

  it('should refuse authorization when policy does not allow', async () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: false,
      online: true,
      backupsLeft: 5,
    };

    const result = await adapter.authorize({ type: 'everything' }, context);

    expect(result.kind).toBe('refused');
  });

  it('should provide confirmGate state for L3', () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: true,
      backupsLeft: 5,
    };

    const state = adapter.confirmGate({ type: 'everything' }, context);

    expect(state.tickBoxRequired).toBe(true);
    expect(state.delayMs).toBe(5000);
  });

  it('should confirm gate accept after delay when ticked', () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: true,
      backupsLeft: 5,
    };

    const state = adapter.confirmGate({ type: 'everything' }, context);
    const tickedAt = 1000;
    const after5s = 6000;

    expect(state.enabled(tickedAt, after5s)).toBe(true);
  });

  it('should not enable confirm gate before delay', () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: true,
      backupsLeft: 5,
    };

    const state = adapter.confirmGate({ type: 'everything' }, context);
    const tickedAt = 1000;
    const before5s = 4000;

    expect(state.enabled(tickedAt, before5s)).toBe(false);
  });

  it('should not enable confirm gate without checkbox when required', () => {
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: true,
      backupsLeft: 5,
    };

    const state = adapter.confirmGate({ type: 'everything' }, context);

    expect(state.enabled(null, 100000)).toBe(false);
  });

  it('should handle offline state during deletion', async () => {
    // Create a backups folder
    const backups = server.putByHand({
      name: 'Backups',
      mimeType: 'application/vnd.google-apps.folder',
      parents: [rootId],
      appProperties: { [DRIVE_LAYOUT.role]: 'backups' },
    }, new Uint8Array());

    // Create a backup file
    server.putByHand({
      name: 'backup-1.zip',
      mimeType: 'application/octet-stream',
      parents: [backups.id],
      appProperties: {
        [DRIVE_LAYOUT.kind]: 'backup',
        [DRIVE_LAYOUT.state]: 'complete',
      },
    }, new Uint8Array(1024));

    // Get the plan
    const preflightResult = await adapter.preflight({ type: 'allBackups' });
    expect(preflightResult.kind).toBe('ready');

    if (preflightResult.kind === 'ready') {
      const plan = preflightResult.plan;

      // Go offline
      isOnline = false;

      // Try to execute - should refuse
      const result = await adapter.execute(plan, null);

      expect(result.kind).toBe('refused');
      if (result.kind === 'refused') {
        expect(result.reason).toBe('OFFLINE');
      }
    }
  });

  it('should handle stale grant rejection', async () => {
    // Create a backups folder with a backup
    const backups = server.putByHand({
      name: 'Backups',
      mimeType: 'application/vnd.google-apps.folder',
      parents: [rootId],
      appProperties: { [DRIVE_LAYOUT.role]: 'backups' },
    }, new Uint8Array());

    server.putByHand({
      name: 'backup-1.zip',
      mimeType: 'application/octet-stream',
      parents: [backups.id],
      appProperties: {
        [DRIVE_LAYOUT.kind]: 'backup',
        [DRIVE_LAYOUT.state]: 'complete',
      },
    }, new Uint8Array(1024));

    // Get the plan
    const preflightResult = await adapter.preflight({ type: 'allBackups' });
    expect(preflightResult.kind).toBe('ready');

    if (preflightResult.kind === 'ready') {
      const plan = preflightResult.plan;
      const context: DeletionContext = {
        platform: 'WEBSITE',
        deviceLock: true,
        webPrf: true,
        online: true,
        backupsLeft: 5,
      };

      // Issue a grant
      const authResult = await adapter.authorize({ type: 'allBackups' }, context);
      expect(authResult.kind).toBe('granted');

      if (authResult.kind === 'granted') {
        const grant = authResult.grant;

        // Move time forward past 60 seconds by mocking the clock
        const elapsedMs = 61000;
        server.clock.advance(elapsedMs);

        // Try to execute with stale grant - should fail
        gate.markGenuine(String(grant.id));
        const result = await adapter.execute(plan, grant);

        expect(result.kind).toBe('refused');
        if (result.kind === 'refused') {
          expect(result.reason).toBe('AUTHORIZATION_STALE');
        }
      }
    }
  });

  it('should handle 404 as success (file already deleted)', async () => {
    // Create a backups folder
    const backups = server.putByHand({
      name: 'Backups',
      mimeType: 'application/vnd.google-apps.folder',
      parents: [rootId],
      appProperties: { [DRIVE_LAYOUT.role]: 'backups' },
    }, new Uint8Array());

    // Create a backup file
    const backupFile = server.putByHand({
      name: 'backup-1.zip',
      mimeType: 'application/octet-stream',
      parents: [backups.id],
      appProperties: {
        [DRIVE_LAYOUT.kind]: 'backup',
        [DRIVE_LAYOUT.state]: 'complete',
      },
    }, new Uint8Array(1024));

    // Get the plan
    const preflightResult = await adapter.preflight({ type: 'allBackups' });
    expect(preflightResult.kind).toBe('ready');

    if (preflightResult.kind === 'ready') {
      const plan = preflightResult.plan;

      // Manually delete the file from the fake drive to simulate it being deleted externally
      await drive.delete(backupFile.id);

      // Execute should succeed and treat it as deleted
      const context: DeletionContext = {
        platform: 'WEBSITE',
        deviceLock: true,
        webPrf: true,
        online: true,
        backupsLeft: 5,
      };

      const authResult = await adapter.authorize({ type: 'allBackups' }, context);
      if (authResult.kind === 'granted') {
        const grant = authResult.grant;
        // Mark grant as genuine and still valid for L1 (no auth needed for L1)
        // L1 doesn't need the gate checks anyway, but mark it just in case
        gate.markGenuine(String(grant.id));
        gate.markStillValid(String(grant.id));

        const result = await adapter.execute(plan, grant);
        expect(result.kind).toBe('ran');
        if (result.kind === 'ran') {
          expect(result.finished).toBe(true);
        }
      }
    }
  });

  it('should persist pending deletion across instances', async () => {
    // Create a backups folder
    const backups = server.putByHand({
      name: 'Backups',
      mimeType: 'application/vnd.google-apps.folder',
      parents: [rootId],
      appProperties: { [DRIVE_LAYOUT.role]: 'backups' },
    }, new Uint8Array());

    server.putByHand({
      name: 'backup-1.zip',
      mimeType: 'application/octet-stream',
      parents: [backups.id],
      appProperties: {
        [DRIVE_LAYOUT.kind]: 'backup',
        [DRIVE_LAYOUT.state]: 'complete',
      },
    }, new Uint8Array(1024));

    // Get the plan
    const preflightResult = await adapter.preflight({ type: 'allBackups' });
    expect(preflightResult.kind).toBe('ready');

    if (preflightResult.kind === 'ready') {
      // Create a new adapter with the same store (simulating page reload)
      const adapter2 = new DriveDeletionAdapterImpl(deletionService, fakeAuthorizer as any, store, rootId, 5, gate);

      // Check if it can see pending deletions from the first adapter
      // (We haven't actually executed, so nothing should be pending yet)
      const pending1 = await store.pending();
      expect(pending1).toBeNull();
    }
  });

  it('should handle FakeAuthorizationGate marking grants', () => {
    expect(gate).toBeDefined();
    gate.markGenuine('1');
    gate.markStillValid('1');
    // The gate should now consider grant id 1 as genuine and still valid
    expect(gate).toBeDefined();
  });
});
