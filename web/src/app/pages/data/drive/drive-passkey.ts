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

import { Component, ChangeDetectionStrategy, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TPipe } from '../../../i18n/t.pipe';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';

/**
 * Passkey setup for deletion authorization on this device.
 * Shown when state is 'Ready'.
 * Displays passkey status and allows registration if not yet set up.
 */
@Component({
  selector: 'app-drive-passkey',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-passkey.html',
  styleUrl: './drive-passkey.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DrivePasskeyComponent implements OnInit {
  private readonly service = inject(DriveConnectService);
  protected readonly i18n = inject(TranslationService);

  protected readonly status = signal<'none' | 'registered' | 'unsupported' | 'loading'>('loading');
  protected readonly busy = signal(false);
  protected readonly errorMessage = signal<TKey | null>(null);

  async ngOnInit(): Promise<void> {
    await this.loadStatus();
  }

  private async loadStatus(): Promise<void> {
    try {
      const result = await this.service.passkeyStatus();
      this.status.set(result || 'unsupported');
    } catch {
      this.errorMessage.set('driveConnect.failed');
      this.status.set('unsupported');
    }
  }

  async onRegisterPasskey(): Promise<void> {
    if (this.busy()) return;

    this.busy.set(true);
    this.errorMessage.set(null);

    try {
      const result = await this.service.registerPasskey();
      if (result === 'registered') {
        this.status.set('registered');
      } else if (result === null || result === 'unsupported') {
        this.status.set('unsupported');
      }
      // Refresh status after registration
      await this.loadStatus();
    } catch {
      this.errorMessage.set('driveConnect.failed');
    } finally {
      this.busy.set(false);
    }
  }
}
