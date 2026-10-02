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

import type {
  AuthorizationGate, DeletionPlan, DeletionStore, DriveDeletionDeps, DriveDeletionService, DeletionOutcome, PendingDeletion, DeletedMarker,
} from '../drive-deletion';
import type { AuthorizationToken, DeletionAction } from '../drive-deletion-rules';
import type { DeletionContext, DeletionDecision } from '../../device-auth/delete-policy';
import { decide } from '../../device-auth/delete-policy';
import type { WebGrant } from '../../device-auth/web-authorizer';

/**
 * The web's deletion adapter (S4b-BL-73): bridges the DriveDeletionService with the UI layer, the deletion policy,
 * and the WebAuthorizer. Implements all four deletion operations (preflight, decide, authorize, execute) and provides
 * UI state helpers for the confirmation dialog.
 *
 * No Angular DI: dependencies are injected plain (for testability, no singletons). The adapter is a plain class with
 * dependency injection.
 */

export interface DriveDeletionAdapter {
  /** What a deletion of `action` would remove (counts and bytes only, no names). */
  preflight(action: DeletionAction): Promise<DeletionPreflightResult>;

  /** Whether the action is allowed given the current context (device lock, online, etc.). */
  decide(action: DeletionAction, context: DeletionContext): DeletionDecision;

  /** Issues a one-use 60-second authorization token for the action; policy must have said ALLOWED. */
  authorize(action: DeletionAction, context: DeletionContext): Promise<AuthorizationResult>;

  /** Executes the plan; requires a valid authorization token for L2/L3. */
  execute(plan: DeletionPlan, grant: WebGrant | null): Promise<DeletionOutcome>;

  /** Resumes an interrupted deletion; requires a fresh authorization token for L2/L3. */
  resume(grant: WebGrant | null): Promise<DeletionOutcome>;

  /** UI helper: whether confirm is enabled and how long to wait (5s for L3 only). */
  confirmGate(action: DeletionAction, context: DeletionContext): ConfirmGateState;
}

export type DeletionPreflightResult =
  | { readonly kind: 'ready'; readonly plan: DeletionPlan }
  | { readonly kind: 'refused'; readonly reason: string };

export type AuthorizationResult =
  | { readonly kind: 'granted'; readonly grant: WebGrant }
  | { readonly kind: 'refused'; readonly reason: string };

export interface ConfirmGateState {
  /** Whether a confirm checkbox is required (true for L3 and DELETE_ALL_BACKUPS). */
  readonly tickBoxRequired: boolean;
  /** Milliseconds to wait after the checkbox is ticked (5000 for L3 only). */
  readonly delayMs: number;
  /** Whether the confirm button is enabled given tick state and elapsed time. */
  enabled(tickedAt: number | null, nowMs: number): boolean;
}

/**
 * Types for converting between DeletionAction (Kotlin format) and DeletionAction (policy format).
 * Web uses Kotlin-style actions from the Backup/Sync services, but must convert to policy actions.
 */
export type PolicyDeletionAction =
  | 'DELETE_ONE_BACKUP'
  | 'REMOVE_SHARED_HUNT'
  | 'DISCONNECT_THIS_DEVICE'
  | 'TURN_AUTO_BACKUP_OFF'
  | 'DELETE_ALL_BACKUPS'
  | 'STOP_SHARING'
  | 'REVOKE_DEVICE'
  | 'APPROVE_DEVICE'
  | 'DISCONNECT_ALL_DEVICES'
  | 'DELETE_EVERYTHING'
  | 'WEAKEN_PROTECTION';

/** Conversion from Kotlin DeletionAction to policy DeletionAction. */
export function toPolicyAction(action: DeletionAction): PolicyDeletionAction {
  switch (action.type) {
    case 'oneBackup':
      return 'DELETE_ONE_BACKUP';
    case 'olderBackups':
      return 'DELETE_ALL_BACKUPS';
    case 'allBackups':
      return 'DELETE_ALL_BACKUPS';
    case 'everything':
      return 'DELETE_EVERYTHING';
    default:
      return 'DELETE_ONE_BACKUP';
  }
}

/**
 * In-memory adapter implementation with full delegation to DriveDeletionService, DeletionPolicy, and WebAuthorizer.
 * All methods are real, no stubs.
 */
export class DriveDeletionAdapterImpl implements DriveDeletionAdapter {
  constructor(
    private readonly deletionService: DriveDeletionService,
    private readonly webAuthorizer: { authorize(action: PolicyDeletionAction, ctx: DeletionContext): Promise<{ kind: string; grant?: WebGrant; reason?: string }> },
    private readonly store: DeletionStore,
    private readonly rootId: string,
    private readonly backupsLeft: number | null,
  ) {}

  async preflight(action: DeletionAction): Promise<DeletionPreflightResult> {
    const result = await this.deletionService.preflight(this.rootId, action);
    if (result.kind === 'ready') {
      return { kind: 'ready', plan: result.plan };
    }
    return { kind: 'refused', reason: result.reason };
  }

  decide(action: DeletionAction, context: DeletionContext): DeletionDecision {
    const policyAction = toPolicyAction(action);
    const ctx: DeletionContext = {
      ...context,
      backupsLeft: this.backupsLeft,
    };
    return decide(policyAction, ctx);
  }

  async authorize(action: DeletionAction, context: DeletionContext): Promise<AuthorizationResult> {
    const decision = this.decide(action, context);
    if (decision.outcome === 'REFUSED') {
      return { kind: 'refused', reason: decision.reason };
    }

    const policyAction = toPolicyAction(action);
    const result = await this.webAuthorizer.authorize(policyAction, context);

    if (result.kind === 'GRANTED' && result.grant) {
      return { kind: 'granted', grant: result.grant };
    }
    return { kind: 'refused', reason: result.reason || 'AUTHORIZATION_FAILED' };
  }

  async execute(plan: DeletionPlan, grant: WebGrant | null): Promise<DeletionOutcome> {
    const token = this.grantToToken(plan, grant);
    return this.deletionService.delete(plan, token);
  }

  async resume(grant: WebGrant | null): Promise<DeletionOutcome> {
    const pending = await this.store.pending();
    if (!pending) {
      return {
        kind: 'refused',
        reason: 'NOTHING_PENDING',
        error: null,
      };
    }
    const token = this.grantToTokenWithOperationId(grant, pending.operationId, pending.level);
    return this.deletionService.resume(token);
  }

  confirmGate(action: DeletionAction, context: DeletionContext): ConfirmGateState {
    const decision = this.decide(action, context);
    if (decision.outcome === 'REFUSED') {
      return {
        tickBoxRequired: false,
        delayMs: 0,
        enabled: () => false,
      };
    }

    const req = decision.requirements;
    return {
      tickBoxRequired: req.tickBox,
      delayMs: req.delaySeconds * 1000,
      enabled: (tickedAt: number | null, nowMs: number): boolean => {
        if (req.tickBox && !tickedAt) return false;
        if (tickedAt === null) tickedAt = nowMs;
        return nowMs - tickedAt >= req.delaySeconds * 1000;
      },
    };
  }

  private grantToToken(plan: DeletionPlan, grant: WebGrant | null): AuthorizationToken | null {
    if (!grant) return null;
    return {
      level: plan.level,
      issuedAtMs: grant.grantedAtMs,
      operationId: plan.operationId,
      proof: String(grant.id),
    };
  }

  private grantToTokenWithOperationId(grant: WebGrant | null, operationId: string, level: 'L1' | 'L2' | 'L3'): AuthorizationToken | null {
    if (!grant) return null;
    return {
      level,
      issuedAtMs: grant.grantedAtMs,
      operationId,
      proof: String(grant.id),
    };
  }
}

/**
 * A simple key-value store interface for DeletionStore persistence.
 * Can be backed by IndexedDB or in-memory (for tests).
 */
export interface KeyValueStore {
  get(key: string): Promise<string | undefined>;
  set(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
}

/**
 * DeletionStore implementation backed by an injected KeyValueStore.
 */
export class PersistentDeletionStore implements DeletionStore {
  constructor(private readonly kv: KeyValueStore) {}

  async pending(): Promise<PendingDeletion | null> {
    const stored = await this.kv.get('doorprints-deletion-pending');
    if (!stored) return null;
    return JSON.parse(stored) as PendingDeletion;
  }

  async savePending(pending: PendingDeletion): Promise<void> {
    await this.kv.set('doorprints-deletion-pending', JSON.stringify(pending));
  }

  async clearPending(): Promise<void> {
    await this.kv.delete('doorprints-deletion-pending');
  }

  async marker(): Promise<DeletedMarker | null> {
    const stored = await this.kv.get('doorprints-deletion-marker');
    if (!stored) return null;
    return JSON.parse(stored) as DeletedMarker;
  }

  async recordFinished(marker: DeletedMarker, forgetFolder: boolean): Promise<void> {
    await this.kv.set('doorprints-deletion-marker', JSON.stringify(marker));
  }
}

/**
 * In-memory KeyValueStore implementation for testing and simple cases.
 */
export class InMemoryKeyValueStore implements KeyValueStore {
  private data = new Map<string, string>();

  async get(key: string): Promise<string | undefined> {
    return this.data.get(key);
  }

  async set(key: string, value: string): Promise<void> {
    this.data.set(key, value);
  }

  async delete(key: string): Promise<void> {
    this.data.delete(key);
  }

  clear(): void {
    this.data.clear();
  }
}

/**
 * Fake AuthorizationGate for testing.
 */
export class FakeAuthorizationGate implements AuthorizationGate {
  private genuine = new Set<number>();
  private stillValid = new Set<number>();

  constructor() {
    this.resetAll();
  }

  markGenuine(proof: string): void {
    this.genuine.add(Number(proof));
  }

  markStillValid(proof: string): void {
    this.stillValid.add(Number(proof));
  }

  resetAll(): void {
    this.genuine.clear();
    this.stillValid.clear();
  }

  async isGenuine(token: AuthorizationToken): Promise<boolean> {
    return this.genuine.has(Number(token.proof));
  }

  async stillHolds(token: AuthorizationToken): Promise<boolean> {
    return this.stillValid.has(Number(token.proof));
  }
}
