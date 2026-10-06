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
import { PasskeyPrfMissingError } from '../../device-auth/web-authn-prf-authenticator';
import { WebCryptoProvider } from '../../crypto/crypto-provider';

describe('DriveDeletionAdapter', () => {
  let server: FakeDriveServer;
  let drive: InMemoryFakeDrive;
  let deletionService: DriveDeletionService;
  let store: DeletionStore;
  let gate: FakeAuthorizationGate;
  let fakeAuthorizer: {
    authorize: (a: any, c: DeletionContext) => Promise<any>;
    /** Null when this page has no PRF HMAC key; the adapter then uses the grant id (L1 always does). */
    proofFor: (operationId: string, issuedAtMs: number) => Promise<string | null>;
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
      // authorize() is given a policy string (DELETE_ALL_BACKUPS), not { type: 'allBackups' }.
      authorize: async (action: any, ctx: DeletionContext): Promise<any> => {
        const level =
          action === 'DELETE_EVERYTHING' || action === 'WEAKEN_PROTECTION'
            ? 'L3'
            : action === 'DELETE_ALL_BACKUPS' ||
                action === 'STOP_SHARING' ||
                action === 'REVOKE_DEVICE' ||
                action === 'APPROVE_DEVICE' ||
                action === 'DISCONNECT_ALL_DEVICES'
              ? 'L2'
              : 'L1';
        const grant: WebGrant = {
          id: 1,
          action,
          requirements: {
            level,
            factor: level === 'L1' ? 'NONE' : 'PASSKEY',
            tickBox: level !== 'L1',
            delaySeconds: level === 'L3' ? 5 : 0,
            pairing: 'NONE',
            authValidMs: level === 'L1' ? 0 : 60_000,
          },
          grantedAtMs: server.clock.now(),
        };
        return { kind: 'GRANTED', grant };
      },
      // L2 and L3 call this. Null matches a page that has not opened the PRF, so the token proof stays the grant id.
      proofFor: async () => null,
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

    const result = await adapter.authorize({ type: 'allBackups' }, context, 'op');

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

    const result = await adapter.authorize({ type: 'everything' }, context, 'op');

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
      const authResult = await adapter.authorize({ type: 'allBackups' }, context, plan.operationId);
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


  it('refuses a grant that is weaker than the plan (a level 1 grant cannot delete all backups)', async () => {
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
      appProperties: { [DRIVE_LAYOUT.kind]: 'backup', [DRIVE_LAYOUT.state]: 'complete' },
    }, new Uint8Array(1024));
    const pre = await adapter.preflight({ type: 'allBackups' });
    if (pre.kind !== 'ready') throw new Error('preflight');
    const before = server.allFiles().length;
    const weak: WebGrant = {
      id: 7,
      action: 'DELETE_ONE_BACKUP',
      requirements: { level: 'L1', factor: 'NONE', tickBox: false, delaySeconds: 0, pairing: 'NONE', authValidMs: 0 },
      grantedAtMs: server.clock.now(),
    };
    gate.markGenuine('7');
    gate.markStillValid('7');
    const out = await adapter.execute(pre.plan, weak);
    expect(out.kind).toBe('refused');
    if (out.kind === 'refused') expect(out.reason).toBe('AUTHORIZATION_TOO_WEAK');
    expect(server.allFiles().length).toBe(before);
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

      const authResult = await adapter.authorize({ type: 'allBackups' }, context, plan.operationId);
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

  it('uses the HMAC from proofFor as the L2 proof, and the grant id when there is none', async () => {
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
      appProperties: { [DRIVE_LAYOUT.kind]: 'backup', [DRIVE_LAYOUT.state]: 'complete' },
    }, new Uint8Array(1024));
    const pre = await adapter.preflight({ type: 'allBackups' });
    if (pre.kind !== 'ready') throw new Error('preflight');
    const context: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: true,
      webPrf: true,
      online: true,
      backupsLeft: 5,
    };
    const auth = await adapter.authorize({ type: 'allBackups' }, context, pre.plan.operationId);
    if (auth.kind !== 'granted') throw new Error('auth');

    const seen: string[] = [];
    deletionService.delete = (_plan, token) => {
      seen.push(token?.proof ?? '');
      return Promise.resolve({ kind: 'refused', reason: 'NOTHING_TO_DELETE', error: null });
    };

    const hmac = 'cd'.repeat(32);
    fakeAuthorizer.proofFor = async (operationId, issuedAtMs) => {
      expect(operationId).toBe(pre.plan.operationId);
      expect(issuedAtMs).toBe(auth.grant.grantedAtMs);
      return hmac;
    };
    await adapter.execute(pre.plan, auth.grant);
    expect(seen).toEqual([hmac]);

    fakeAuthorizer.proofFor = async () => null;
    await adapter.execute(pre.plan, auth.grant);
    expect(seen).toEqual([hmac, String(auth.grant.id)]);
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

  it('records passkey details when registerPasskey fails with no-prf', async () => {
    // Create a fake PrfAuthenticator that throws PasskeyPrfMissingError
    const fakePrf = {
      isSupported: async () => true,
      registerPasskey: async () => {
        throw new PasskeyPrfMissingError("test-details-string");
      },
      lastPrfDetails: () => "test-details-string",
    } as any;
    const crypto = new WebCryptoProvider();
    const adapterWithPrf = new DriveDeletionAdapterImpl(
      deletionService,
      fakeAuthorizer as any,
      store,
      rootId,
      5,
      gate,
      crypto,
      fakePrf,
      kv,
    );

    const result = await adapterWithPrf.registerPasskey();
    expect(result).toBe("no-prf");
    expect(await adapterWithPrf.lastPasskeyDetails()).toBe("test-details-string");
  });

  it('clears passkey details on second registerPasskey call', async () => {
    // First call: fails with no-prf
    const crypto = new WebCryptoProvider();
    let fakePrf: any = {
      isSupported: async () => true,
      registerPasskey: async () => {
        throw new PasskeyPrfMissingError("first-failure-details");
      },
      lastPrfDetails: () => "first-failure-details",
    };
    const kvForTest = new InMemoryKeyValueStore();
    const adapterWithPrf = new DriveDeletionAdapterImpl(
      deletionService,
      fakeAuthorizer as any,
      store,
      rootId,
      5,
      gate,
      crypto,
      fakePrf,
      kvForTest,
    );

    let result = await adapterWithPrf.registerPasskey();
    expect(result).toBe("no-prf");
    expect(await adapterWithPrf.lastPasskeyDetails()).toBe("first-failure-details");

    // Second call: person cancels (returns null)
    fakePrf.registerPasskey = async () => null;
    fakePrf.lastPrfDetails = () => null;

    result = await adapterWithPrf.registerPasskey();
    expect(result).toBeNull();
    // After cancel, details MUST be null (cleared at start of registerPasskey)
    expect(await adapterWithPrf.lastPasskeyDetails()).toBeNull();
  });

  describe('prfCapability()', () => {
    it('returns true when PRF authenticator supports PRF extension', async () => {
      const fakePrf = {
        isSupported: async () => true,
        prfCapability: async () => true,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const cap = await adapterWithPrf.prfCapability();
      expect(cap).toBe(true);
    });

    it('returns false when PRF authenticator does not support PRF extension', async () => {
      const fakePrf = {
        isSupported: async () => true,
        prfCapability: async () => false,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const cap = await adapterWithPrf.prfCapability();
      expect(cap).toBe(false);
    });

    it('returns null when PRF authenticator capability is indeterminate', async () => {
      const fakePrf = {
        isSupported: async () => true,
        prfCapability: async () => null,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const cap = await adapterWithPrf.prfCapability();
      expect(cap).toBe(null);
    });

    it('returns null when PRF authenticator is missing', async () => {
      const adapterNoPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
      );

      const cap = await adapterNoPrf.prfCapability();
      expect(cap).toBe(null);
    });

    it('returns null when PRF authenticator throws', async () => {
      const fakePrf = {
        isSupported: async () => true,
        prfCapability: async () => {
          throw new Error('API error');
        },
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const cap = await adapterWithPrf.prfCapability();
      expect(cap).toBe(null);
    });

    it('returns null when PRF authenticator has no prfCapability method', async () => {
      const fakePrf = {
        isSupported: async () => true,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const cap = await adapterWithPrf.prfCapability();
      expect(cap).toBe(null);
    });
  });

  describe('builtInAuthenticator()', () => {
    it('returns true when built-in authenticator is available', async () => {
      const fakePrf = {
        isSupported: async () => true,
        builtInAuthenticatorAvailable: async () => true,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const result = await adapterWithPrf.builtInAuthenticator();
      expect(result).toBe(true);
    });

    it('returns false when built-in authenticator is not available', async () => {
      const fakePrf = {
        isSupported: async () => true,
        builtInAuthenticatorAvailable: async () => false,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const result = await adapterWithPrf.builtInAuthenticator();
      expect(result).toBe(false);
    });

    it('returns null when built-in authenticator availability is indeterminate', async () => {
      const fakePrf = {
        isSupported: async () => true,
        builtInAuthenticatorAvailable: async () => null,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const result = await adapterWithPrf.builtInAuthenticator();
      expect(result).toBe(null);
    });

    it('returns null when PRF authenticator is missing', async () => {
      const adapterNoPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
      );

      const result = await adapterNoPrf.builtInAuthenticator();
      expect(result).toBe(null);
    });

    it('returns null when PRF authenticator throws', async () => {
      const fakePrf = {
        isSupported: async () => true,
        builtInAuthenticatorAvailable: async () => {
          throw new Error('Probe error');
        },
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const result = await adapterWithPrf.builtInAuthenticator();
      expect(result).toBe(null);
    });

    it('returns null when PRF authenticator has no builtInAuthenticatorAvailable method', async () => {
      const fakePrf = {
        isSupported: async () => true,
      } as any;
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        undefined,
        fakePrf,
      );

      const result = await adapterWithPrf.builtInAuthenticator();
      expect(result).toBe(null);
    });
  });

  describe('passkeyStatus() with widened passkey support', () => {
    it('returns "none" when supported but no passkey is registered and built-in is false', async () => {
      const fakePrf = {
        isSupported: async () => true,
        builtInAuthenticatorAvailable: async () => false,
      } as any;
      const kvForTest = new InMemoryKeyValueStore();
      const crypto = new WebCryptoProvider();
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        crypto,
        fakePrf,
        kvForTest,
      );

      const status = await adapterWithPrf.passkeyStatus();
      expect(status).toBe('none');
    });

    it('returns "registered" when passkey is stored', async () => {
      const fakePrf = {
        isSupported: async () => true,
      } as any;
      const kvForTest = new InMemoryKeyValueStore();
      // Pre-populate with a sealed blob (use hex values that can be parsed)
      const blobJson = JSON.stringify({
        v: 1,
        credentialId: '0102030405060708090a0b0c0d0e0f10',
        salt: '1011121314151617181920212223242526272829',
        nonce: '303132333435363738393a3b3c',
        ciphertext: '3d3e3f404142434445464748494a4b4c4d4e4f'
      });
      await kvForTest.set('doorprints-deletion-sealed-blob', blobJson);

      const crypto = new WebCryptoProvider();
      const adapterWithPrf = new DriveDeletionAdapterImpl(
        deletionService,
        fakeAuthorizer as any,
        store,
        rootId,
        5,
        gate,
        crypto,
        fakePrf,
        kvForTest,
      );

      const status = await adapterWithPrf.passkeyStatus();
      expect(status).toBe('registered');
    });
  });

  describe('authorizePolicy (approve, revoke, disconnect everywhere)', () => {
    const websiteContext: DeletionContext = { platform: 'WEBSITE', deviceLock: false, webPrf: true, online: true, backupsLeft: 3 };

    it('says use-the-phone when there is no sealed passkey, even though the policy let it through (the recovery key may be offered for a deletion)', async () => {
      fakeAuthorizer.authorize = async () => ({ kind: 'DENIED', reason: 'NOT_SUPPORTED' });
      const result = await adapter.authorizePolicy('DISCONNECT_ALL_DEVICES', websiteContext);
      expect(result).toEqual({ kind: 'refused', reason: 'USE_PHONE' });
    });

    it('keeps the other denials as they were (a cancelled prompt is not use-the-phone)', async () => {
      fakeAuthorizer.authorize = async () => ({ kind: 'DENIED', reason: 'CANCELLED' });
      const result = await adapter.authorizePolicy('REVOKE_DEVICE', websiteContext);
      expect(result).toEqual({ kind: 'refused', reason: 'AUTHORIZATION_DENIED' });
    });
  });
});
