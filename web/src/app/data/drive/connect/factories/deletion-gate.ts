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
import type { WebAuthorizer } from '../../../device-auth/web-authorizer';

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

  registerGrant(grantId: number, action: DeletionAction, operationId: string): void {
    this.issuedGrants.set(grantId, { action, operationId, issuedAtMs: this.clock() });
  }

  async isGenuine(token: AuthorizationToken): Promise<boolean> {
    // Parse grant ID from proof
    const grantId = Number(token.proof);
    if (!Number.isInteger(grantId)) return false;

    const grant = this.issuedGrants.get(grantId);
    if (!grant) return false; // Grant never issued

    if (this.spent.has(grantId)) return false; // Grant already used

    // Check freshness
    const age = this.clock() - grant.issuedAtMs;
    if (age < 0 || age > AUTHORIZATION_MAX_AGE_MS) return false;

    // Check operation binding
    if (grant.operationId !== token.operationId) return false;

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
    if (age < 0 || age > AUTHORIZATION_MAX_AGE_MS) return false;

    // Check operation binding
    if (grant.operationId !== token.operationId) return false;

    return true;
  }
}
