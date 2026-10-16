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

import type { AuthorizationGate } from '../drive-deletion';
import type { AuthorizationToken, DeletionAction } from '../drive-deletion-rules';

/**
 * Fake AuthorizationGate for testing.
 */
export class FakeAuthorizationGate implements AuthorizationGate {
  private genuine = new Set<number>();
  private stillValid = new Set<number>();
  issuedGrants = new Map<number, { action: DeletionAction; operationId: string }>();

  constructor() {
    this.resetAll();
  }

  registerGrant(grantId: number, action: DeletionAction, operationId: string): void {
    this.issuedGrants.set(grantId, { action, operationId });
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

  async stillHolds(token: AuthorizationToken, action: DeletionAction): Promise<boolean> {
    return this.stillValid.has(Number(token.proof));
  }
}
