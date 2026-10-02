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

import { DriveDeletionAdapterImpl, PersistentDeletionStore, InMemoryKeyValueStore } from '../deletion-adapter';
import { DriveDeletionService } from '../../drive-deletion';
import type { AuthorizationGate } from '../../drive-deletion';
import { WebAuthorizer } from '../../../device-auth/web-authorizer';
import { FakePrfAuthenticator } from '../../../device-auth/prf-seal';
import type { DriveRuntime } from './runtime';
import type { DriveDeletionAdapter } from '../deletion-adapter';
import type { PrfAuthenticator } from '../../../device-auth/prf-seal';
import type { DeletionAction } from '../../drive-deletion-rules';

/**
 * Simple fake authorization gate for testing.
 */
class FakeAuthorizationGate implements AuthorizationGate {
  issuedGrants = new Map<number, { action: DeletionAction; operationId: string }>();
  spent = new Set<number>();

  registerGrant(grantId: number, action: DeletionAction, operationId: string): void {
    this.issuedGrants.set(grantId, { action, operationId });
  }

  async isGenuine(): Promise<boolean> {
    return true;
  }

  async stillHolds(): Promise<boolean> {
    return true;
  }
}

/**
 * Test-only helper to extract gate and service from an adapter for mutation testing.
 * DO NOT USE IN PRODUCTION.
 */
export function getGateAndServiceForTesting(adapter: DriveDeletionAdapter): { gate: any & { applyTestMutation?: (m: string) => void; resetTestMutations?: () => void }; service: any } {
  // Cast to access private fields for testing only
  const impl = adapter as any;
  return {
    gate: impl.deletionService?.d?.gate || {},
    service: impl.deletionService || {},
  };
}

/**
 * Test helper: creates a deletion adapter with a fake PRF authenticator and in-memory stores.
 * Used in specs to avoid requiring real WebAuthn or IndexedDB.
 */
export function createTestDeletionAdapter(
  rt: DriveRuntime,
  options: {
    prfAuthenticator?: PrfAuthenticator;
    rootId?: string;
    backupsLeft?: number | null;
  } = {},
): DriveDeletionAdapter {
  const kv = new InMemoryKeyValueStore();
  const deletionStore = new PersistentDeletionStore(kv);

  const authorizationGate = new FakeAuthorizationGate();

  const deletionService = new DriveDeletionService({
    drive: rt.drive,
    gate: authorizationGate,
    store: deletionStore,
    isOnline: () => (typeof navigator !== 'undefined' ? navigator.onLine : true),
    now: () => Date.now(),
  });

  const prfAuthenticator = options.prfAuthenticator || new FakePrfAuthenticator(rt.crypto);
  const sealedBlob = () => null;

  const webAuthorizer = new WebAuthorizer(
    rt.crypto,
    prfAuthenticator,
    sealedBlob,
    () => Date.now(),
  );

  const rootId = options.rootId || rt.session?.rootId || '';
  const backupsLeft = options.backupsLeft !== undefined ? options.backupsLeft : 5; // Default to 5 for testing

  return new DriveDeletionAdapterImpl(
    deletionService,
    webAuthorizer,
    deletionStore,
    rootId,
    backupsLeft,
    authorizationGate,
    rt.crypto,
    prfAuthenticator,
    kv,
  );
}
