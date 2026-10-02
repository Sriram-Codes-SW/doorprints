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

import { Component, Input, OnInit, ChangeDetectionStrategy, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import type { DeletionAction } from '../../data/drive/drive-deletion-rules';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { TPipe } from '../../i18n/t.pipe';

type Phase = 'menu' | 'plan' | 'confirm' | 'passkey-error' | 'running' | 'done' | 'error';

/**
 * Drive deletion UI: shows options to delete older backups, all backups, or everything,
 * with full authorization flow including passkey setup for level 2/3. S4b-BL-117.
 */
@Component({
  selector: 'app-drive-delete',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-delete.html',
  styleUrls: ['./drive-delete.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppDriveDelete implements OnInit {
  @Input() oneBackupId?: string;

  private readonly service = inject(DriveConnectService);

  readonly busy = signal(false);
  readonly phase = signal<Phase>('menu');
  readonly plan = signal<any>(null);
  readonly tickBoxRequired = signal(false);
  readonly delayMs = signal(0);
  readonly tickedAt = signal<number | null>(null);
  readonly confirmEnabled = signal(false);
  readonly error = signal<string | null>(null);
  readonly passkeyStatus = signal<'none' | 'registered' | 'unsupported'>('none');
  readonly result = signal<string | null>(null);

  readonly timeRemaining = computed(() => {
    const ticked = this.tickedAt();
    const delayMs = this.delayMs();
    if (!ticked || delayMs === 0) return null;

    const elapsed = Date.now() - ticked;
    const remaining = Math.max(0, delayMs - elapsed);
    if (remaining === 0) return null;
    return Math.ceil(remaining / 1000);
  });

  private countdownInterval: number | null = null;
  private currentAction: DeletionAction | null = null;
  private currentGrant: any = null;

  async ngOnInit(): Promise<void> {
    await this.checkPasskeyStatus();
  }

  private async checkPasskeyStatus(): Promise<void> {
    try {
      const status = await this.service.passkeyStatus();
      this.passkeyStatus.set(status);
    } catch (_err) {
      this.passkeyStatus.set('none');
    }
  }

  async startDeletion(action: DeletionAction): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.currentAction = action;

    try {
      const preflight = await this.service.deletePlan(action);
      if (!preflight.ok) {
        this.error.set(preflight.reason);
        this.phase.set('error');
        return;
      }

      this.plan.set(preflight.plan);
      this.phase.set('plan');
    } catch (_err) {
      this.error.set('Failed to plan deletion');
      this.phase.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  async proceedToConfirm(): Promise<void> {
    if (!this.currentAction) return;

    this.busy.set(true);
    try {
      const info = await this.service.deleteConfirmInfo(this.currentAction);
      if (!info.ok) {
        this.error.set(info.reason);
        this.phase.set('passkey-error');
        return;
      }

      this.tickBoxRequired.set(info.tickBoxRequired);
      this.delayMs.set(info.delayMs);
      this.phase.set('confirm');
    } catch (_err) {
      this.error.set('Failed to get confirmation info');
      this.phase.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  toggleTickBox(): void {
    if (this.tickBoxRequired()) {
      this.tickedAt.set(this.tickedAt() ? null : Date.now());
      this.startCountdown();
      this.updateConfirmEnabled();
    }
  }

  private startCountdown(): void {
    if (this.countdownInterval !== null) clearInterval(this.countdownInterval);

    const ticked = this.tickedAt();
    if (!ticked || this.delayMs() === 0) return;

    this.countdownInterval = window.setInterval(() => {
      this.updateConfirmEnabled();
    }, 100);
  }

  private updateConfirmEnabled(): void {
    const ticked = this.tickedAt();
    const delayMs = this.delayMs();
    const tickRequired = this.tickBoxRequired();

    if (!tickRequired) {
      this.confirmEnabled.set(true);
      return;
    }

    if (!ticked) {
      this.confirmEnabled.set(false);
      return;
    }

    const elapsed = Date.now() - ticked;
    this.confirmEnabled.set(elapsed >= delayMs);
  }

  async registerPasskey(): Promise<void> {
    this.busy.set(true);
    try {
      const result = await this.service.registerPasskey();
      if (result === 'registered') {
        await this.checkPasskeyStatus();
        this.phase.set('confirm');
      } else if (result === 'unsupported') {
        this.error.set('Passkeys are not supported on this device');
      }
      // null means cancelled
    } catch (_err) {
      this.error.set('Failed to register passkey');
    } finally {
      this.busy.set(false);
    }
  }

  async confirmDelete(): Promise<void> {
    if (!this.currentAction || !this.plan()) return;

    this.busy.set(true);
    this.phase.set('running');

    try {
      // For level 1 without a grant, skip authorization
      let grant = this.currentGrant;
      const needsAuth = this.tickBoxRequired() || this.delayMs() > 0;

      if (needsAuth && !grant) {
        const authResult = await this.service.authorizeDelete(this.currentAction);
        if (!authResult.ok) {
          this.error.set(authResult.reason);
          this.phase.set('passkey-error');
          return;
        }
        grant = authResult.grant;
      }

      const result = await this.service.executeDelete(this.plan(), grant);
      if (!result.ok) {
        this.error.set(result.reason);
        this.phase.set('error');
        return;
      }

      this.result.set('Deleted from Google Drive for all devices.');
      this.phase.set('done');
      this.clearCountdown();
    } catch (_err) {
      this.error.set('Deletion failed');
      this.phase.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  async resumeDelete(): Promise<void> {
    if (!this.currentGrant) return;

    this.busy.set(true);
    this.phase.set('running');

    try {
      const result = await this.service.resumeDelete(this.currentGrant);
      if (!result.ok) {
        this.error.set(result.reason);
        this.phase.set('error');
        return;
      }

      this.result.set('Deleted from Google Drive for all devices.');
      this.phase.set('done');
    } catch (_err) {
      this.error.set('Resume failed');
      this.phase.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  cancel(): void {
    this.clearCountdown();
    this.phase.set('menu');
    this.plan.set(null);
    this.tickedAt.set(null);
    this.confirmEnabled.set(false);
    this.error.set(null);
    this.result.set(null);
    this.currentAction = null;
    this.currentGrant = null;
  }

  private clearCountdown(): void {
    if (this.countdownInterval !== null) {
      clearInterval(this.countdownInterval);
      this.countdownInterval = null;
    }
  }
}
