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

import type { DeletionAction } from '../drive-deletion-rules';
import type { DeletionPlan } from '../drive-deletion';
import type { WebGrant } from '../../device-auth/web-authorizer';
import type { DeletionContext } from '../../device-auth/delete-policy';
import { RecoveryKey as RecoveryKeyClass } from '../../crypto/recovery-key';
import type { RecoveryKey } from '../../crypto/recovery-key';
import { msgOfThrown } from '../backup/drive-backup-results';
import type { DriveDeletionAdapter } from './deletion-adapter';
import type { DeleteRun } from './drive-connect.service';

/** What the delete flow asks of the connect service: the policy context and whether the recovery key is offered. */
export interface DeleteFlowSeams {
  context(): Promise<DeletionContext>;
  recoveryKeyOffered(): Promise<boolean>;
}

/**
 * The deletion half of the Drive card (docs/15 §10): plan, confirm info, which factor, authorisation by passkey or
 * (last resort) recovery key, run and resume. Split out of `DriveConnectService`; the service keeps the same public
 * methods and delegates here.
 */
export class DeleteFlow {
  constructor(
    private readonly adapter: DriveDeletionAdapter,
    private readonly seams: DeleteFlowSeams,
  ) {}

  async deletePlan(
    action: DeletionAction,
  ): Promise<
    | { readonly ok: true; readonly plan: DeletionPlan }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const result = await this.adapter.preflight(action);
      if (result.kind === 'refused') {
        return { ok: false, reason: result.reason };
      }
      return { ok: true, plan: result.plan };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  async deleteConfirmInfo(
    action: DeletionAction,
  ): Promise<
    | {
        readonly ok: true;
        readonly tickBoxRequired: boolean;
        readonly delayMs: number;
      }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const context = await this.seams.context();
      const decision = this.adapter.decide(action, context);
      if (decision.outcome === 'REFUSED') {
        return { ok: false, reason: decision.reason };
      }
      const gate = this.adapter.confirmGate(action, context);
      return {
        ok: true,
        tickBoxRequired: gate.tickBoxRequired,
        delayMs: gate.delayMs,
      };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  async deleteFactor(
    action: DeletionAction,
  ): Promise<'NONE' | 'PASSKEY' | null> {
    try {
      const context = await this.seams.context();
      const decision = this.adapter.decide(action, context);
      if (decision.outcome === 'REFUSED') {
        return null;
      }
      const factor = decision.requirements.factor;
      if (factor === 'NONE' || factor === 'PASSKEY') {
        return factor;
      }
      return null;
    } catch {
      return null;
    }
  }

  async authorizeDelete(
    action: DeletionAction,
    operationId: string,
    recoveryKeyText?: string,
  ): Promise<
    | { readonly ok: true; readonly grant: WebGrant }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const context = await this.seams.context();

      // If no recovery key text given, use passkey path as today
      if (!recoveryKeyText) {
        const result = await this.adapter.authorize(action, context, operationId);
        if (result.kind === 'refused') {
          return { ok: false, reason: result.reason };
        }
        return { ok: true, grant: result.grant };
      }

      // Recovery key text provided: check if it's offered
      if (!(await this.seams.recoveryKeyOffered())) {
        return { ok: false, reason: 'RECOVERY_KEY_NOT_OFFERED' };
      }

      // Parse recovery key
      let recoveryKey: RecoveryKey;
      try {
        recoveryKey = RecoveryKeyClass.parse(recoveryKeyText);
      } catch {
        return { ok: false, reason: 'RECOVERY_KEY_INVALID' };
      }

      // Use adapter's recovery key path
      const result = await this.adapter.authorizeWithRecoveryKey(action, context, operationId, recoveryKey);
      if (result.kind === 'refused') {
        return { ok: false, reason: result.reason };
      }
      return { ok: true, grant: result.grant };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  async executeDelete(plan: DeletionPlan, grant: WebGrant | null): Promise<DeleteRun> {
    try {
      const outcome = await this.adapter.execute(plan, grant);
      if (outcome.kind === 'ran') return this.ran(outcome);
      return { ok: false, reason: outcome.reason };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    } finally {
      this.forgetDeleteProof();
    }
  }

  async resumeDelete(grant: WebGrant | null): Promise<DeleteRun> {
    try {
      const outcome = await this.adapter.resume(grant);
      if (outcome.kind === 'ran') return this.ran(outcome);
      return { ok: false, reason: outcome.reason };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    } finally {
      this.forgetDeleteProof();
    }
  }

  private forgetDeleteProof(): void {
    try {
      this.adapter.forgetProof?.();
    } catch {
      // Swallow errors
    }
  }

  private ran(outcome: { readonly finished: boolean; readonly total: number; readonly report: { readonly left: readonly string[] } }): DeleteRun {
    return { ok: true, finished: outcome.finished, left: outcome.report.left.length, total: outcome.total };
  }
}
