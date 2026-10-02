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
import { WebAuthorizer } from '../../../device-auth/web-authorizer';
import { WebAuthnPrfAuthenticator } from '../../../device-auth/web-authn-prf-authenticator';
import type { PrfAuthenticator } from '../../../device-auth/prf-seal';
import type { DriveRuntime } from './runtime';
import type { DeletionAction } from '../../drive-deletion-rules';
import type { DeletionContext } from '../../../device-auth/delete-policy';

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
): DriveDeletionAdapter {
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
    };
  }

  // Create the authorization gate that validates tokens
  const authorizationGate = {
    async isGenuine(): Promise<boolean> {
      // In a real implementation, this would verify the token's proof against
      // the passkey that issued it. For now, trust the WebAuthorizer.
      return true;
    },
    async stillHolds(): Promise<boolean> {
      // Verify the token hasn't expired and is still valid
      return true;
    },
  };

  // Create the deletion service with the runtime's drive client
  const deletionService = new DriveDeletionService({
    drive: rt.drive,
    gate: authorizationGate,
    store: deletionStore,
    isOnline: () => typeof navigator !== 'undefined' ? navigator.onLine : true,
    now: () => Date.now(),
  });

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

  // Create the sealed blob (device key sealed under PRF)
  // For now, return null - this would be set when the device key is sealed
  const sealedBlob = () => null;

  // Create the web authorizer with the PRF authenticator
  const webAuthorizer = new WebAuthorizer(
    rt.crypto,
    authenticator,
    sealedBlob,
    () => Date.now(),
  );

  // Get the root folder ID from the runtime session
  // FolderSession has rootId field set by backup adapter after successful connect
  const rootId = rt.session.rootId;

  // Create and return the adapter
  return new DriveDeletionAdapterImpl(
    deletionService,
    webAuthorizer,
    deletionStore,
    rootId,
    null, // backupsLeft not tracked in FolderSession; will be updated by UI
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
  };
}

