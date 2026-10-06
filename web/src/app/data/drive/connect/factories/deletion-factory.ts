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

import type { DriveDeletionAdapter, DeletionPreflightResult, AuthorizationResult, ConfirmGateState, KeyValueStore, PolicyDeletionAction } from '../deletion-adapter';
import { DriveDeletionAdapterImpl, PersistentDeletionStore, confirmGateOf, decideDeletion } from '../deletion-adapter';
import { DriveDeletionService } from '../../drive-deletion';
import { RealAuthorizationGate } from './deletion-gate';
import { WebAuthorizer } from '../../../device-auth/web-authorizer';
import { WebAuthnPrfAuthenticator } from '../../../device-auth/web-authn-prf-authenticator';
import type { PrfAuthenticator, SealedBlob } from '../../../device-auth/prf-seal';
import { SEALED_BLOB_KEY, sealedBlobFromJson } from '../../../device-auth/prf-seal';
import type { DriveRuntime } from './runtime';
import type { DeletionAction } from '../../drive-deletion-rules';
import type { DeletionContext } from '../../../device-auth/delete-policy';
import type { RecoveryKey } from '../../../crypto/recovery-key';

/** What the lazy proxy may be given instead of the browser's own (tests pass a scripted passkey and a clock). */
export interface DeletionFactoryDeps {
  readonly prf?: PrfAuthenticator;
  readonly clock?: () => number;
  /** Optional recovery verification seam for authorizeWithRecoveryKey (passed from the backup factory). */
  readonly recovery?: { verify(key: RecoveryKey): Promise<boolean> };
}

const REFUSING: DriveDeletionAdapter = {
  async preflight() {
    return { kind: 'refused', reason: 'Not connected to Drive folder' };
  },
  decide() {
    return { outcome: 'REFUSED', reason: 'OFFLINE' };
  },
  async authorize() {
    return { kind: 'refused', reason: 'Not connected' };
  },
  async authorizePolicy() {
    return { kind: 'refused', reason: 'Not connected' };
  },
  async authorizeWithRecoveryKey() {
    return { kind: 'refused', reason: 'Not connected' };
  },
  forgetProof() {
    // No-op when not connected
  },
  async execute() {
    return { kind: 'refused', reason: 'OFFLINE', error: null };
  },
  async resume() {
    return { kind: 'refused', reason: 'OFFLINE', error: null };
  },
  confirmGate() {
    return { tickBoxRequired: false, delayMs: 0, enabled: () => false };
  },
  async registerPasskey() {
    return 'unsupported';
  },
  async passkeyStatus() {
    return 'unsupported';
  },
  async prfCapability() {
    return null;
  },
};

/**
 * Creates a real deletion adapter from the Drive runtime.
 *
 * Constructs DriveDeletionService with the runtime's drive client and an authorization gate,
 * WebAuthorizer with the PRF authenticator, and DriveDeletionAdapterImpl to bridge to the UI.
 * Requires runtime.session to be set; if missing, the adapter returns 'not connected' refusals.
 * The deletion list and the passkey's sealed blob live in the runtime's key-value store (IndexedDB), so a half-done
 * deletion and the passkey survive a reload.
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
  const kv = keyValueStore ?? rt.kv;
  const deletionStore = new PersistentDeletionStore(kv);

  if (!rt.session) return REFUSING;

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

  const sealedBlob = async (): Promise<SealedBlob | null> => {
    try {
      const stored = await kv.get(SEALED_BLOB_KEY);
      return stored ? sealedBlobFromJson(stored) : null;
    } catch {
      return null;
    }
  };

  const webAuthorizer = new WebAuthorizer(
    rt.crypto,
    authenticator,
    sealedBlob,
    clockFn,
    prfAuthenticator ? undefined : deps.recovery,
  );
  const authorizationGate = new RealAuthorizationGate(webAuthorizer, clockFn);

  const deletionService = new DriveDeletionService({
    drive: rt.drive,
    gate: authorizationGate,
    store: deletionStore,
    isOnline: () => typeof navigator !== 'undefined' ? navigator.onLine : true,
    now: clockFn,
  });

  return new DriveDeletionAdapterImpl(
    deletionService,
    webAuthorizer,
    deletionStore,
    rt.session.rootId,
    null, // how many backups are left is the screen's to say, per call (the policy fails closed on null)
    authorizationGate,
    rt.crypto,
    authenticator,
    kv,
  );
}

/**
 * A lazy proxy that wraps the deletion adapter interface, deferring construction until first use.
 * Nothing is built at app startup. The adapter is rebuilt whenever the runtime's folder session changes (a reconnect, a
 * new folder after a deletion), so it never works on a folder the device has left.
 *
 * S4b-BL-73, S4b-BL-127, docs/15 §9.4.
 */
export function createLazyDeletionAdapterProxy(getRuntime: () => Promise<DriveRuntime>, deps: DeletionFactoryDeps = {}): DriveDeletionAdapter {
  let current: { readonly session: unknown; readonly adapter: DriveDeletionAdapter } | null = null;

  const getAdapter = async (): Promise<DriveDeletionAdapter> => {
    const rt = await getRuntime();
    if (!current || current.session !== (rt.session ?? null)) {
      current = { session: rt.session ?? null, adapter: createDeletionAdapter(rt, undefined, deps.prf, deps.clock) };
    }
    return current.adapter;
  };

  return {
    async preflight(action: DeletionAction): Promise<DeletionPreflightResult> {
      return (await getAdapter()).preflight(action);
    },

    decide(action: DeletionAction, context: DeletionContext) {
      // The policy needs no folder: the same answer before and after the first call.
      return decideDeletion(action, context);
    },

    async authorize(action: DeletionAction, context: DeletionContext, operationId: string): Promise<AuthorizationResult> {
      return (await getAdapter()).authorize(action, context, operationId);
    },

    async authorizePolicy(action: PolicyDeletionAction, context: DeletionContext): Promise<AuthorizationResult> {
      return (await getAdapter()).authorizePolicy(action, context);
    },

    async authorizeWithRecoveryKey(action: DeletionAction, context: DeletionContext, operationId: string, key: RecoveryKey): Promise<AuthorizationResult> {
      return (await getAdapter()).authorizeWithRecoveryKey(action, context, operationId, key);
    },

    forgetProof(): void {
      (getAdapter() as Promise<DriveDeletionAdapter>).then(a => a.forgetProof());
    },

    async execute(plan, grant) {
      return (await getAdapter()).execute(plan, grant);
    },

    async resume(grant) {
      return (await getAdapter()).resume(grant);
    },

    confirmGate(action: DeletionAction, context: DeletionContext): ConfirmGateState {
      return confirmGateOf(decideDeletion(action, context));
    },

    async registerPasskey() {
      return (await getAdapter()).registerPasskey();
    },

    async lastPasskeyDetails() {
      return (await getAdapter()).lastPasskeyDetails?.() ?? null;
    },

    async passkeyStatus() {
      return (await getAdapter()).passkeyStatus();
    },

    async prfCapability() {
      return (await getAdapter()).prfCapability?.() ?? null;
    },

    async builtInAuthenticator() {
      return (await getAdapter()).builtInAuthenticator?.() ?? null;
    },
  };
}
