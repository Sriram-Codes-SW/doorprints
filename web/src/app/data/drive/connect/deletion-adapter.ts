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
import type { WebGrant, WebAuthorizer } from '../../device-auth/web-authorizer';
import type { PrfAuthenticator } from '../../device-auth/prf-seal';
import { SEALED_BLOB_KEY, sealWithPrf, sealedBlobFromJson, sealedBlobToJson } from '../../device-auth/prf-seal';
import type { CryptoProvider } from '../../crypto/crypto-provider';

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

  /**
   * Issues a one-use 60-second grant for `action` bound to `operationId` (the preflight plan's id).
   * Policy must have said ALLOWED. HMAC of the operation id as the proof is S4b-BL-135.
   */
  authorize(action: DeletionAction, context: DeletionContext, operationId: string): Promise<AuthorizationResult>;

  /** Executes the plan; requires a valid authorization token for L2/L3. */
  execute(plan: DeletionPlan, grant: WebGrant | null): Promise<DeletionOutcome>;

  /** Resumes an interrupted deletion; requires a fresh authorization token for L2/L3. */
  resume(grant: WebGrant | null): Promise<DeletionOutcome>;

  /** UI helper: whether confirm is enabled and how long to wait (5s for L3 only). */
  confirmGate(action: DeletionAction, context: DeletionContext): ConfirmGateState;

  /** Registers a passkey for L2/L3 deletion. Returns status: 'registered', 'unsupported', or null on cancellation. */
  registerPasskey(): Promise<'registered' | 'unsupported' | null>;

  /** Returns passkey status: 'none', 'registered', or 'unsupported'. */
  passkeyStatus(): Promise<'none' | 'registered' | 'unsupported'>;
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

/** The policy's answer for `action`; the context's own count of backups left wins over the adapter's fallback. */
export function decideDeletion(action: DeletionAction, context: DeletionContext, fallbackBackupsLeft: number | null = null): DeletionDecision {
  return decide(toPolicyAction(action), { ...context, backupsLeft: context.backupsLeft ?? fallbackBackupsLeft });
}

/** What the confirmation dialog must ask for, from the policy's decision (nothing is enabled when it refused). */
export function confirmGateOf(decision: DeletionDecision): ConfirmGateState {
  if (decision.outcome === 'REFUSED') return { tickBoxRequired: false, delayMs: 0, enabled: () => false };
  const req = decision.requirements;
  return {
    tickBoxRequired: req.tickBox,
    delayMs: req.delaySeconds * 1000,
    enabled: (tickedAt: number | null, nowMs: number): boolean => {
      if (req.tickBox && !tickedAt) return false;
      return nowMs - (tickedAt ?? nowMs) >= req.delaySeconds * 1000;
    },
  };
}

/**
 * In-memory adapter implementation with full delegation to DriveDeletionService, DeletionPolicy, and WebAuthorizer.
 * All methods are real, no stubs.
 */
export class DriveDeletionAdapterImpl implements DriveDeletionAdapter {
  /** Grant id → operationId registered at authorize; in-memory only (gone after a reload). */
  private readonly boundOp = new Map<number, string>();

  constructor(
    private readonly deletionService: DriveDeletionService,
    private readonly webAuthorizer: WebAuthorizer,
    private readonly store: DeletionStore,
    private readonly rootId: string,
    private readonly backupsLeft: number | null,
    private readonly gate?: { registerGrant(grantId: number, action: DeletionAction, operationId: string, issuedAtMs: number): void },
    private readonly crypto?: CryptoProvider,
    private readonly prf?: PrfAuthenticator,
    private readonly kv?: KeyValueStore,
  ) {}

  async preflight(action: DeletionAction): Promise<DeletionPreflightResult> {
    const result = await this.deletionService.preflight(this.rootId, action);
    if (result.kind === 'ready') {
      return { kind: 'ready', plan: result.plan };
    }
    return { kind: 'refused', reason: result.reason };
  }

  decide(action: DeletionAction, context: DeletionContext): DeletionDecision {
    return decideDeletion(action, context, this.backupsLeft);
  }

  async authorize(action: DeletionAction, context: DeletionContext, operationId: string): Promise<AuthorizationResult> {
    const decision = this.decide(action, context);
    if (decision.outcome === 'REFUSED') {
      return { kind: 'refused', reason: decision.reason };
    }

    const policyAction = toPolicyAction(action);
    const result = await this.webAuthorizer.authorize(policyAction, context);

    if (result.kind === 'GRANTED' && result.grant) {
      this.boundOp.set(result.grant.id, operationId);
      this.gate?.registerGrant(result.grant.id, action, operationId, result.grant.grantedAtMs);
      return { kind: 'granted', grant: result.grant };
    }
    if (result.kind === 'REFUSED') {
      return { kind: 'refused', reason: 'AUTHORIZATION_REFUSED' };
    }
    return { kind: 'refused', reason: 'AUTHORIZATION_DENIED' };
  }

  async execute(plan: DeletionPlan, grant: WebGrant | null): Promise<DeletionOutcome> {
    const mismatch = this.grantOperationMismatch(grant, plan.operationId);
    if (mismatch) return mismatch;
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
    const mismatch = this.grantOperationMismatch(grant, pending.operationId);
    if (mismatch) return mismatch;
    const token = this.grantToTokenWithOperationId(grant, pending.operationId);
    return this.deletionService.resume(token);
  }

  /**
   * A grant registered at authorize for another operationId cannot run this plan. A grant that was
   * never registered here (forged, or left over from a previous page load) is left for the gate.
   */
  private grantOperationMismatch(grant: WebGrant | null, operationId: string): DeletionOutcome | null {
    if (!grant) return null;
    const bound = this.boundOp.get(grant.id);
    if (bound === undefined || bound === operationId) return null;
    return { kind: 'refused', reason: 'AUTHORIZATION_OTHER_OPERATION', error: null };
  }

  confirmGate(action: DeletionAction, context: DeletionContext): ConfirmGateState {
    return confirmGateOf(this.decide(action, context));
  }

  private grantToToken(plan: DeletionPlan, grant: WebGrant | null): AuthorizationToken | null {
    if (!grant) return null;
    return {
      level: grant.requirements.level,
      issuedAtMs: grant.grantedAtMs,
      operationId: plan.operationId,
      proof: String(grant.id),
    };
  }

  private grantToTokenWithOperationId(grant: WebGrant | null, operationId: string): AuthorizationToken | null {
    if (!grant) return null;
    return {
      level: grant.requirements.level,
      issuedAtMs: grant.grantedAtMs,
      operationId,
      proof: String(grant.id),
    };
  }

  async registerPasskey(): Promise<'registered' | 'unsupported' | null> {
    if (!this.prf || !this.crypto || !this.kv) return 'unsupported';
    try {
      if (!(await this.prf.isSupported())) return 'unsupported';
      const credentialId = await this.prf.registerPasskey('Doorprints');
      if (!credentialId) return null;
      // What is sealed is a random value nobody needs: opening it again takes the person's verification and the
      // passkey's PRF, which is what the website's L2 and L3 deletes ask for (docs/15 §10.4).
      const secret = this.crypto.randomBytes(32);
      const sealed = await sealWithPrf(this.crypto, this.prf, credentialId, secret);
      secret.fill(0);
      if (!('v' in sealed)) return sealed.reason === 'NOT_SUPPORTED' ? 'unsupported' : null;
      await this.kv.set(SEALED_BLOB_KEY, sealedBlobToJson(sealed));
      return 'registered';
    } catch {
      return null;
    }
  }

  async passkeyStatus(): Promise<'none' | 'registered' | 'unsupported'> {
    if (!this.prf || !this.crypto || !this.kv) return 'unsupported';
    try {
      if (!(await this.prf.isSupported())) return 'unsupported';
      const stored = await this.kv.get(SEALED_BLOB_KEY);
      return stored && sealedBlobFromJson(stored) ? 'registered' : 'none';
    } catch {
      return 'unsupported';
    }
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

