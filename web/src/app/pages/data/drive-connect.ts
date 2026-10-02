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

import { Component, computed, inject, signal, OnInit, ChangeDetectionStrategy, Optional } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import { DriveConnectService, type ConnectState } from '../../data/drive/connect/drive-connect.service';

/**
 * Drive Connect page (S4b-BL-117, S4b-BL-73, docs/15 §9.4): connect to Google Drive,
 * manage recovery key, backups, photos policy, and deletion levels (L1/L2/L3 with auth gates).
 */
@Component({
  selector: 'app-drive-connect',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-connect.html',
  styleUrl: './drive-connect.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveConnectComponent implements OnInit {
  private readonly service = inject(DriveConnectService, { optional: true });
  protected readonly i18n = inject(TranslationService);

  protected readonly state = computed(() => this.service?.getState() ?? 'Unavailable');
  protected readonly busy = signal(false);
  protected readonly recoveryKey = signal<string | null>(null);
  protected readonly recoveryKeySaved = signal(false);
  protected readonly keyCopied = signal(false);
  protected readonly lastBackupTime = signal<string | null>(null);
  protected readonly backups = signal<Promise<string[]> | null>(null);
  protected readonly autoBackupEnabled = signal(false);
  protected readonly photosWifiOnly = signal(true);
  protected readonly deleteConfirmLevel = signal<'L1' | 'L2' | 'L3' | null>(null);
  protected readonly deleteL3Confirmed = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    // Initialize component
  }

  async onConnect(): Promise<void> {
    if (!this.service) return;
    this.busy.set(true);
    try {
      const result = await this.service.connect();
      if (result.state === 'NeedsRecoveryKey') {
        const createResult = await this.service.createFolder();
        this.recoveryKey.set(createResult.recoveryKey || null);
        this.recoveryKeySaved.set(false);
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

  async continueFromRecoveryKey(): Promise<void> {
    if (!this.service) return;
    this.service.confirmRecoveryKeySaved();
  }

  async skipRecoveryKey(): Promise<void> {
    if (!this.service) return;
    if (confirm(this.i18n.t('driveConnect.recoveryKeyWarning'))) {
      this.service.skipRecoveryKeyWithWarning();
    }
  }

  async onBackupNow(): Promise<void> {
    if (!this.service) return;
    this.busy.set(true);
    try {
      await this.service.backUpNow();
    } catch (err) {
      this.error.set(String(err));
    } finally {
      this.busy.set(false);
    }
  }

  async onImportFromDrive(): Promise<void> {
    // Navigate to import page or show backup selection
  }

  toggleAutoBackup(event: Event): void {
    if (!this.service) return;
    const checked = (event.target as HTMLInputElement).checked;
    this.autoBackupEnabled.set(checked);
    void this.service.setAutoBackup(checked);
  }

  togglePhotosWifiOnly(event: Event): void {
    if (!this.service) return;
    const checked = (event.target as HTMLInputElement).checked;
    this.photosWifiOnly.set(checked);
    void this.service.setPhotosWifiOnly(checked);
  }

  async onUploadPhotosNow(): Promise<void> {
    if (!this.service) return;
    this.busy.set(true);
    try {
      await this.service.uploadPhotosNowOverMobile();
    } catch (err) {
      this.error.set(String(err));
    } finally {
      this.busy.set(false);
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

  showDeleteMenu(level: 'L1' | 'L2' | 'L3'): void {
    this.deleteConfirmLevel.set(level);
    this.deleteL3Confirmed.set(false);
  }

  toggleDeleteL3(): void {
    this.deleteL3Confirmed.set(!this.deleteL3Confirmed());
  }

  async confirmDelete(): Promise<void> {
    if (!this.service) return;
    const level = this.deleteConfirmLevel();
    if (!level) return;

    this.busy.set(true);
    try {
      let result: { success: boolean; error?: string };
      if (level === 'L1') {
        result = await this.service.deleteL1();
      } else if (level === 'L2') {
        result = await this.service.deleteL2();
      } else {
        result = await this.service.deleteL3(this.deleteL3Confirmed());
      }
      if (!result.success && result.error) {
        this.error.set(result.error);
      }
      this.deleteConfirmLevel.set(null);
    } finally {
      this.busy.set(false);
    }
  }

  cancelDelete(): void {
    this.deleteConfirmLevel.set(null);
    this.deleteL3Confirmed.set(false);
  }
}
