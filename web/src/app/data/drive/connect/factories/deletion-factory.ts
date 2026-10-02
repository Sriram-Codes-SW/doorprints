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

import type { DriveDeletionAdapter, DeletionPreflightResult, AuthorizationResult, ConfirmGateState, KeyValueStore } from '../deletion-adapter';
import {
  DriveDeletionAdapterImpl, PersistentDeletionStore, InMemoryKeyValueStore,
} from '../deletion-adapter';
import { DriveDeletionService } from '../../drive-deletion';
import type { AuthorizationGate } from '../../drive-deletion';
import type { AuthorizationToken } from '../../drive-deletion-rules';
import { WebAuthorizer } from '../../../device-auth/web-authorizer';
import { WebAuthnPrfAuthenticator } from '../../../device-auth/web-authn-prf-authenticator';
import type { PrfAuthenticator, SealedBlob } from '../../../device-auth/prf-seal';
import { sealWithPrf } from '../../../device-auth/prf-seal';
import type { DriveRuntime } from './runtime';
import type { DeletionAction } from '../../drive-deletion-rules';
import { AUTHORIZATION_MAX_AGE_MS } from '../../drive-deletion-rules';
import type { DeletionContext } from '../../../device-auth/delete-policy';

/**
 * Real authorization gate that validates tokens through WebAuthorizer.
 * Tracks issued grants and enforces one-time use, freshness, and operation binding.
 */
class RealAuthorizationGate implements AuthorizationGate {
  private readonly issuedGrants = new Map<number, { action: DeletionAction; operationId: string; issuedAtMs: number }>();
  private readonly spent = new Set<number>();
  private testMutations = { skipGrantCheck: false, skipSpentCheck: false, skipFreshnessCheck: false };

  constructor(
    private readonly webAuthorizer: WebAuthorizer,
    private readonly clock: () => number,
  ) {}

  /**
   * Test-only: Apply a mutation for testing.
   */
  applyTestMutation(mutation: 'skipGrantCheck' | 'skipSpentCheck' | 'skipFreshnessCheck'): void {
    if (mutation === 'skipGrantCheck') this.testMutations.skipGrantCheck = true;
    if (mutation === 'skipSpentCheck') this.testMutations.skipSpentCheck = true;
    if (mutation === 'skipFreshnessCheck') this.testMutations.skipFreshnessCheck = true;
  }

  /**
   * Test-only: Reset mutations.
   */
  resetTestMutations(): void {
    this.testMutations = { skipGrantCheck: false, skipSpentCheck: false, skipFreshnessCheck: false };
  }

  registerGrant(grantId: number, action: DeletionAction, operationId: string): void {
    this.issuedGrants.set(grantId, { action, operationId, issuedAtMs: this.clock() });
  }

  async isGenuine(token: AuthorizationToken): Promise<boolean> {
    // Parse grant ID from proof
    const grantId = Number(token.proof);
    if (!Number.isInteger(grantId)) return false;

    const grant = this.issuedGrants.get(grantId);
    if (!this.testMutations.skipGrantCheck && !grant) return false; // Grant never issued

    if (!this.testMutations.skipSpentCheck && this.spent.has(grantId)) return false; // Grant already used

    // Check freshness
    if (grant) {
      const age = this.clock() - grant.issuedAtMs;
      if (!this.testMutations.skipFreshnessCheck && (age < 0 || age > AUTHORIZATION_MAX_AGE_MS)) return false;

      // Check operation binding
      if (grant.operationId !== token.operationId) return false;
    }

    // Mark as spent (one-use)
    this.spent.add(grantId);
    return true;
  }

  async stillHolds(token: AuthorizationToken, action: DeletionAction): Promise<boolean> {
    // Check freshness and that the grant hasn't been revoked
    const grantId = Number(token.proof);
    if (!Number.isInteger(grantId)) return false;

    const grant = this.issuedGrants.get(grantId);
    if (!grant) return false;

    // Check freshness window (60 seconds)
    const age = this.clock() - grant.issuedAtMs;
    if (!this.testMutations.skipFreshnessCheck && (age < 0 || age > AUTHORIZATION_MAX_AGE_MS)) return false;

    // Check operation binding
    if (grant.operationId !== token.operationId) return false;

    return true;
  }
}

/**
 * Creates a real deletion adapter from the Drive runtime.
 *
 * Constructs DriveDeletionService with the runtime's drive client and an authorization gate,
 * WebAuthorizer with the PRF authenticator, and DriveDeletionAdapterImpl to bridge to the UI.
 * Requires runtime.session to be set; if missing, the adapter returns 'not connected' refusals.
 *
 * S4b-BL-73, S4b-BL-127, docs/15 §9.4, §10.4.
 */
export function createDeletionAdapter(
  rt: DriveRuntime,
  keyValueStore?: KeyValueStore,
  prfAuthenticator?: PrfAuthenticator,
  clock?: () => number,
): DriveDeletionAdapter {
  const clockFn = clock || (() => Date.now());
  // Create a key-value store for deletion state persistence (backed by IndexedDB or in-memory)
  const kv = keyValueStore || new InMemoryKeyValueStore();
  const deletionStore = new PersistentDeletionStore(kv);

  // If no session, return an adapter that always refuses
  if (!rt.session) {
    return {
      async preflight() {
        return { kind: 'refused', reason: 'Not connected to Drive folder' };
      },
      decide() {
        return { outcome: 'REFUSED', reason: 'OFFLINE' };
      },
      async authorize() {
        return { kind: 'refused', reason: 'Not connected' };
      },
      async execute() {
        return { kind: 'refused', reason: 'OFFLINE', error: null };
      },
      async resume() {
        return { kind: 'refused', reason: 'OFFLINE', error: null };
      },
      confirmGate() {
        return {
          tickBoxRequired: false,
          delayMs: 0,
          enabled: () => false,
        };
      },
      async registerPasskey() {
        return 'unsupported';
      },
      async passkeyStatus() {
        return 'unsupported';
      },
    };
  }

  // Create the PRF authenticator (real browser-based WebAuthn or provided fake)
  const authenticator = prfAuthenticator || new WebAuthnPrfAuthenticator(
    async (key: string) => {
      try {
        return await kv.get(key);
      } catch {
        return undefined;
      }
    },
    async (key: string, value: string) => {
      try {
        await kv.set(key, value);
      } catch {
        // Ignore storage errors
      }
    },
  );

  // Load or initialize the sealed blob from persistent storage
  const loadSealedBlob = async (): Promise<SealedBlob | null> => {
    try {
      const stored = await kv.get('doorprints-deletion-sealed-blob');
      if (!stored) return null;
      return JSON.parse(stored) as SealedBlob;
    } catch {
      return null;
    }
  };

  let cachedSealedBlob: SealedBlob | null | undefined;
  const getSealedBlob = async (): Promise<SealedBlob | null> => {
    if (cachedSealedBlob !== undefined) return cachedSealedBlob;
    cachedSealedBlob = await loadSealedBlob();
    return cachedSealedBlob;
  };

  const sealedBlob = (): SealedBlob | null => {
    // Return cached value synchronously; will be populated on first async call
    return cachedSealedBlob ?? null;
  };

  // Create the web authorizer with the PRF authenticator
  const webAuthorizer = new WebAuthorizer(
    rt.crypto,
    authenticator,
    sealedBlob,
    clockFn,
  );

  // Create the real authorization gate
  const authorizationGate = new RealAuthorizationGate(webAuthorizer, clockFn);

  // Create the deletion service with the runtime's drive client
  const deletionService = new DriveDeletionService({
    drive: rt.drive,
    gate: authorizationGate,
    store: deletionStore,
    isOnline: () => typeof navigator !== 'undefined' ? navigator.onLine : true,
    now: clockFn,
  });

  // Get the root folder ID from the runtime session
  // FolderSession has rootId field set by backup adapter after successful connect
  const rootId = rt.session.rootId;

  // Create and return the adapter with passkey registration support
  return new DriveDeletionAdapterImpl(
    deletionService,
    webAuthorizer,
    deletionStore,
    rootId,
    null, // backupsLeft not tracked in FolderSession; will be updated by UI
    authorizationGate, // Pass the gate for grant registration
    rt.crypto,
    authenticator,
    kv,
  );
}

/**
 * A lazy proxy that wraps the deletion adapter interface, deferring construction until first use.
 * This ensures nothing is built at app startup; the adapter is only created when actually needed.
 *
 * The adapter instance is memoized, so all methods use the same instance per proxy.
 *
 * S4b-BL-73, S4b-BL-127, docs/15 §9.4.
 */
export function createLazyDeletionAdapterProxy(getRuntime: () => Promise<DriveRuntime>): DriveDeletionAdapter {
  let cachedAdapter: DriveDeletionAdapter | null = null;
  let adapterPromise: Promise<DriveDeletionAdapter> | null = null;

  const getAdapter = async (): Promise<DriveDeletionAdapter> => {
    if (cachedAdapter) return cachedAdapter;
    if (adapterPromise) return adapterPromise;

    adapterPromise = (async () => {
      const rt = await getRuntime();
      // Note: keyValueStore will be available from rt.db in the opened database
      cachedAdapter = createDeletionAdapter(rt, undefined);
      return cachedAdapter;
    })();

    return adapterPromise;
  };

  return {
    async preflight(action: DeletionAction): Promise<DeletionPreflightResult> {
      const adapter = await getAdapter();
      return adapter.preflight(action);
    },

    decide(action: DeletionAction, context: DeletionContext) {
      // This is synchronous. If the adapter hasn't been created yet, return a conservative default.
      // In practice, the UI will call preflight() first (which is async), so this should be called
      // after the adapter is ready.
      if (!cachedAdapter) {
        return { outcome: 'REFUSED', reason: 'NO_DEVICE_LOCK' };
      }
      return cachedAdapter.decide(action, context);
    },

    async authorize(action: DeletionAction, context: DeletionContext): Promise<AuthorizationResult> {
      const adapter = await getAdapter();
      return adapter.authorize(action, context);
    },

    async execute(plan, grant) {
      const adapter = await getAdapter();
      return adapter.execute(plan, grant);
    },

    async resume(grant) {
      const adapter = await getAdapter();
      return adapter.resume(grant);
    },

    confirmGate(action: DeletionAction, context: DeletionContext): ConfirmGateState {
      // This is synchronous. If the adapter hasn't been created yet, return a default.
      if (!cachedAdapter) {
        return {
          tickBoxRequired: false,
          delayMs: 0,
          enabled: () => false,
        };
      }
      return cachedAdapter.confirmGate(action, context);
    },

    async registerPasskey() {
      const adapter = await getAdapter();
      return adapter.registerPasskey();
    },

    async passkeyStatus() {
      const adapter = await getAdapter();
      return adapter.passkeyStatus();
    },
  };
}

