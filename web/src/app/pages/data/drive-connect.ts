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

import { Component, computed, inject, signal, ChangeDetectionStrategy, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { DriveJoinComponent } from './drive/drive-join';
import { DriveEnrolCard } from './drive/drive-enrol';
import { DriveBackupsCard } from './drive/drive-backups';
import { DriveSyncCard } from './drive/drive-sync';
import { DrivePasskeyComponent } from './drive/drive-passkey';
import { DriveDeleteCard } from './drive/drive-delete';

/**
 * Google Drive on Your data: Unavailable / Connect / NeedsRecoveryKey (shown once) / NeedsEnrolment / Ready.
 * Cards talk to DriveConnectService; an imported backup Blob is handed to *Import a backup*.
 */
@Component({
  selector: 'app-drive-connect',
  standalone: true,
  imports: [
    CommonModule,
    TPipe,
    DriveJoinComponent,
    DriveEnrolCard,
    DriveBackupsCard,
    DriveSyncCard,
    DrivePasskeyComponent,
    DriveDeleteCard,
  ],
  templateUrl: './drive-connect.html',
  styleUrl: './drive-connect.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveConnectComponent {
  private readonly service = inject(DriveConnectService, { optional: true });
  protected readonly i18n = inject(TranslationService);

  readonly importFile = output<Blob>();

  protected readonly state = computed(() => this.service?.getState() ?? 'Unavailable');
  protected readonly busy = signal(false);
  protected readonly recoveryKey = signal<string | null>(null);
  protected readonly recoveryKeySaved = signal(false);
  protected readonly keyCopied = signal(false);
  protected readonly error = signal<string | null>(null);

  protected onImportFromDrive(file: Blob): void {
    this.importFile.emit(file);
  }

  async onConnect(): Promise<void> {
    if (!this.service) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const result = await this.service.connect();
      if (result.state === 'Disconnected' && !result.error) {
        const created = await this.service.createFolder();
        this.recoveryKey.set(created.recoveryKey || null);
        this.recoveryKeySaved.set(false);
      } else if (result.recoveryKey) {
        this.recoveryKey.set(result.recoveryKey);
        this.recoveryKeySaved.set(false);
      } else if (result.error) {
        this.error.set(result.error);
      }
    } catch (err) {
      this.error.set(String(err));
    } finally {
      this.busy.set(false);
    }
  }

  async copyRecoveryKey(key: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(key);
      this.keyCopied.set(true);
      setTimeout(() => this.keyCopied.set(false), 2000);
    } catch (err) {
      console.error('Failed to copy recovery key:', err);
    }
  }

  toggleRecoverySaved(): void {
    this.recoveryKeySaved.set(!this.recoveryKeySaved());
  }

  continueFromRecoveryKey(): void {
    if (!this.service) return;
    this.service.confirmRecoveryKeySaved();
    this.recoveryKey.set(null);
    this.recoveryKeySaved.set(false);
  }

  skipRecoveryKey(): void {
    if (!this.service) return;
    if (confirm(this.i18n.t('driveConnect.recoveryKeyWarning'))) {
      this.service.skipRecoveryKeyWithWarning();
      this.recoveryKey.set(null);
      this.recoveryKeySaved.set(false);
    }
  }

  async onDisconnect(): Promise<void> {
    if (!this.service) return;
    if (confirm(this.i18n.t('driveConnect.disconnect'))) {
      this.busy.set(true);
      try {
        await this.service.disconnect();
      } finally {
        this.busy.set(false);
      }
    }
  }
}
