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

import type { AuthorizationGate } from '../../drive-deletion';
import type { AuthorizationToken, DeletionAction } from '../../drive-deletion-rules';
import { AUTHORIZATION_MAX_AGE_MS } from '../../drive-deletion-rules';
import type { WebAuthorizer, WebGrant } from '../../../device-auth/web-authorizer';
import { toPolicyAction } from '../deletion-adapter';

/**
 * Real authorization gate that validates tokens through WebAuthorizer.
 * Tracks issued grants and enforces one-time use, freshness, and operation binding.
 *
 * S4b-BL-127, docs/15 §10.3.
 */
export class RealAuthorizationGate implements AuthorizationGate {
  private readonly issuedGrants = new Map<number, { action: DeletionAction; operationId: string; issuedAtMs: number }>();
  private readonly spent = new Set<number>();

  constructor(
    private readonly webAuthorizer: WebAuthorizer,
    private readonly clock: () => number,
  ) {}

  /** `issuedAtMs` is when the person passed the check (the grant's own time), not when the delete starts. */
  registerGrant(grantId: number, action: DeletionAction, operationId: string, issuedAtMs: number): void {
    this.issuedGrants.set(grantId, { action, operationId, issuedAtMs });
  }

  async isGenuine(token: AuthorizationToken): Promise<boolean> {
    if (isHmacProof(token.proof)) {
      if (token.level === 'L1') return false;
      if (!(await this.webAuthorizer.verifyProof(token.operationId, token.issuedAtMs, token.proof))) return false;
      const grantId = this.grantIdMatching(token.operationId, token.issuedAtMs);
      if (grantId === null) return false;
      return this.redeemIssued(grantId, token);
    }
    if (token.level !== 'L1') return false;
    const grantId = Number(token.proof);
    if (!Number.isInteger(grantId)) return false;
    return this.redeemIssued(grantId, token);
  }

  async stillHolds(token: AuthorizationToken, action: DeletionAction): Promise<boolean> {
    void action;
    if (isHmacProof(token.proof)) {
      if (!(await this.webAuthorizer.verifyProof(token.operationId, token.issuedAtMs, token.proof))) return false;
      const grantId = this.grantIdMatching(token.operationId, token.issuedAtMs);
      if (grantId === null) return false;
      return this.fresh(this.issuedGrants.get(grantId)!, token.operationId);
    }
    const grantId = Number(token.proof);
    if (!Number.isInteger(grantId)) return false;
    const grant = this.issuedGrants.get(grantId);
    if (!grant) return false;
    return this.fresh(grant, token.operationId);
  }

  private grantIdMatching(operationId: string, issuedAtMs: number): number | null {
    for (const [id, grant] of this.issuedGrants) {
      if (grant.operationId === operationId && grant.issuedAtMs === issuedAtMs) return id;
    }
    return null;
  }

  private fresh(grant: { operationId: string; issuedAtMs: number }, operationId: string): boolean {
    const age = this.clock() - grant.issuedAtMs;
    if (age < 0 || age > AUTHORIZATION_MAX_AGE_MS) return false;
    return grant.operationId === operationId;
  }

  /** Redeem the grant WebAuthorizer issued. A grant only registered in-page is NOT_ISSUED. */
  private redeemIssued(grantId: number, token: AuthorizationToken): boolean {
    const grant = this.issuedGrants.get(grantId);
    if (!grant) return false;
    if (this.spent.has(grantId)) return false;
    if (!this.fresh(grant, token.operationId)) return false;
    const policyAction = toPolicyAction(grant.action);
    const stub: WebGrant = {
      id: grantId,
      action: policyAction,
      requirements: {
        level: token.level,
        factor: 'NONE',
        tickBox: false,
        delaySeconds: 0,
        pairing: 'NONE',
        authValidMs: 0,
      },
      grantedAtMs: grant.issuedAtMs,
    };
    if (this.webAuthorizer.redeem(stub, policyAction) !== 'VALID') return false;
    this.spent.add(grantId);
    return true;
  }
}

function isHmacProof(proof: string): boolean {
  return /^[0-9a-f]{64}$/.test(proof);
}
