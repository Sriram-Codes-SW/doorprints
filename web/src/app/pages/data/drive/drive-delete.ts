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

import { Component, Input, OnInit, ChangeDetectionStrategy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import type { DeletionAction } from '../../../data/drive/drive-deletion-rules';
import type { DeletionPlan } from '../../../data/drive/drive-deletion';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { TPipe } from '../../../i18n/t.pipe';

type Phase = 'menu' | 'plan' | 'confirm' | 'passkey-error' | 'running' | 'done' | 'error';

/**
 * Drive deletion: L1 without a passkey; L2/L3 only with a PRF-sealed passkey (docs/15 §10.4).
 * Confirmation tick box; no delay on the website (owner).
 */
@Component({
  selector: 'app-drive-delete',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-delete.html',
  styleUrl: './drive-delete.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveDeleteCard implements OnInit {
  @Input() oneBackupId?: string;

  private readonly service = inject(DriveConnectService);

  protected readonly busy = signal(false);
  protected readonly phase = signal<Phase>('menu');
  protected readonly tickBoxRequired = signal(false);
  protected readonly ticked = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly passkeyStatus = signal<'none' | 'registered' | 'unsupported'>('none');
  protected readonly result = signal<string | null>(null);

  private currentAction: DeletionAction | null = null;
  private currentPlan: DeletionPlan | null = null;

  async ngOnInit(): Promise<void> {
    try {
      this.passkeyStatus.set(await this.service.passkeyStatus());
    } catch {
      this.passkeyStatus.set('none');
    }
  }

  protected async startDeletion(action: DeletionAction): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.currentAction = action;
    try {
      const preflight = await this.service.deletePlan(action);
      if (!preflight.ok) {
        this.error.set(preflight.reason);
        this.phase.set(preflight.reason === 'USE_PHONE' ? 'passkey-error' : 'error');
        return;
      }
      this.currentPlan = preflight.plan;
      this.phase.set('plan');
    } catch {
      this.error.set('Failed to plan deletion');
      this.phase.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  protected async proceedToConfirm(): Promise<void> {
    if (!this.currentAction) return;
    this.busy.set(true);
    try {
      const info = await this.service.deleteConfirmInfo(this.currentAction);
      if (!info.ok) {
        this.error.set(info.reason);
        this.phase.set(info.reason === 'USE_PHONE' ? 'passkey-error' : 'error');
        return;
      }
      this.tickBoxRequired.set(info.tickBoxRequired);
      this.ticked.set(!info.tickBoxRequired);
      this.phase.set('confirm');
    } catch {
      this.error.set('Failed to get confirmation info');
      this.phase.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  protected toggleTickBox(): void {
    if (!this.tickBoxRequired()) return;
    this.ticked.set(!this.ticked());
  }

  protected async registerPasskey(): Promise<void> {
    this.busy.set(true);
    try {
      const result = await this.service.registerPasskey();
      if (result === 'registered') {
        this.passkeyStatus.set('registered');
        this.phase.set('menu');
      }
    } catch {
      this.error.set('Failed to register passkey');
    } finally {
      this.busy.set(false);
    }
  }

  protected async confirmDelete(): Promise<void> {
    if (!this.currentAction || !this.currentPlan) return;
    if (this.tickBoxRequired() && !this.ticked()) return;
    this.busy.set(true);
    this.phase.set('running');
    try {
      const info = await this.service.deleteConfirmInfo(this.currentAction);
      let grant = null;
      if (info.ok) {
        const auth = await this.service.authorizeDelete(this.currentAction);
        if (!auth.ok) {
          this.error.set(auth.reason);
          this.phase.set(auth.reason === 'USE_PHONE' ? 'passkey-error' : 'error');
          return;
        }
        grant = auth.grant;
      }
      if (!this.currentPlan) {
        this.error.set('Failed to plan deletion');
        this.phase.set('error');
        return;
      }
      const result = await this.service.executeDelete(this.currentPlan, grant);
      if (!result.ok) {
        this.error.set(result.reason);
        this.phase.set(result.reason === 'USE_PHONE' ? 'passkey-error' : 'error');
        return;
      }
      this.result.set('ok');
      this.phase.set('done');
    } catch {
      this.error.set('Deletion failed');
      this.phase.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  protected cancel(): void {
    this.phase.set('menu');
    this.ticked.set(false);
    this.error.set(null);
    this.result.set(null);
    this.currentAction = null;
    this.currentPlan = null;
  }
}
