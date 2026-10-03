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

import { Component, OnInit, ChangeDetectionStrategy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DriveConnectService, type ListedDevice } from '../../../data/drive/connect/drive-connect.service';
import { TPipe } from '../../../i18n/t.pipe';
import type { TKey } from '../../../i18n/en';

type Phase = 'list' | 'passkey' | 'recovery';

/**
 * Devices on this Google Drive folder: who is enrolled, revoke (L2, new recovery key shown once),
 * and disconnect on all devices (L2). docs/15 §3.5, §9.5 iv, §10.4, §10.6.
 */
@Component({
  selector: 'app-drive-devices',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-devices.html',
  styleUrl: './drive-devices.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveDevicesCard implements OnInit {
  private readonly service = inject(DriveConnectService);

  protected readonly busy = signal(false);
  protected readonly phase = signal<Phase>('list');
  protected readonly devices = signal<readonly ListedDevice[]>([]);
  protected readonly email = signal<string | null>(null);
  protected readonly error = signal<TKey | null>(null);
  protected readonly recoveryKey = signal<string | null>(null);
  protected readonly recoverySaved = signal(false);

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  protected async revoke(kidHex: string): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const out = await this.service.revokeListedDevice(kidHex);
      if (!out.ok) {
        this.showRefused(out.reason);
        return;
      }
      this.recoveryKey.set(out.recoveryKey);
      this.recoverySaved.set(false);
      this.phase.set('recovery');
      await this.reload();
    } catch {
      this.error.set('driveConnect.failed');
    } finally {
      this.busy.set(false);
    }
  }

  protected async disconnectAll(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const out = await this.service.disconnectAll();
      if (!out.ok) this.showRefused(out.reason);
    } catch {
      this.error.set('driveConnect.failed');
    } finally {
      this.busy.set(false);
    }
  }

  protected toggleSaved(): void {
    this.recoverySaved.set(!this.recoverySaved());
  }

  protected dismissRecovery(): void {
    if (!this.recoverySaved()) return;
    this.recoveryKey.set(null);
    this.phase.set('list');
  }

  protected cancel(): void {
    this.phase.set('list');
    this.error.set(null);
  }

  private async reload(): Promise<void> {
    try {
      this.devices.set(await this.service.listedDevices());
      this.email.set(await this.service.accountEmail());
    } catch {
      this.devices.set([]);
    }
  }

  private showRefused(reason: string): void {
    if (reason === 'USE_PHONE') {
      this.phase.set('passkey');
      return;
    }
    this.error.set(reason.startsWith('drive') ? (reason as TKey) : 'driveConnect.failed');
  }
}
