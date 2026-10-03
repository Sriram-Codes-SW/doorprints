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

import { Component, computed, inject, signal, ChangeDetectionStrategy, output, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { TKey } from '../../i18n/en';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { DriveJoinComponent } from './drive/drive-join';
import { DriveEnrolCard } from './drive/drive-enrol';
import { DriveBackupsCard } from './drive/drive-backups';
import { DriveSyncCard } from './drive/drive-sync';
import { DrivePasskeyComponent } from './drive/drive-passkey';
import { DriveDeleteCard } from './drive/drive-delete';
import { DriveDevicesCard } from './drive/drive-devices';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';

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
    DriveDevicesCard,
  ],
  templateUrl: './drive-connect.html',
  styleUrl: './drive-connect.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveConnectComponent implements OnDestroy {
  private readonly service = inject(DriveConnectService, { optional: true });
  protected readonly i18n = inject(TranslationService);
  private readonly announcer = inject(Announcer);
  private readonly confirm = inject(ConfirmService);
  private copyTimer: ReturnType<typeof setTimeout> | undefined;

  readonly importFile = output<Blob>();

  protected readonly state = computed(() => this.service?.getState() ?? 'Unavailable');
  protected readonly busy = signal(false);
  /** Connect and folder creation only. Disconnect uses busy and must not say the connection is in progress. */
  private readonly connectWork = signal(false);
  protected readonly recoveryKey = signal<string | null>(null);
  protected readonly recoveryKeySaved = signal(false);
  protected readonly keyCopied = signal(false);
  protected readonly error = signal<TKey | null>(null);

  /** The sentence in the error box: the specific problem when we have one, otherwise the generic line. */
  protected shownError(): TKey {
    return this.error() ?? 'driveConnect.failed';
  }

  /**
   * The gap after sign-in: busy creating a folder, state is no longer Connecting, and the recovery key
   * is not on screen yet. Signing in… already covers Connecting. An error sentence, or the key, replaces this.
   */
  protected readonly connectionInProgress = computed(() => {
    if (!this.connectWork() || this.recoveryKey() || this.error()) return false;
    const current = this.state();
    return current !== 'Connecting' && current !== 'Error' && current !== 'FirstConnectShowRecoveryKey';
  });

  /** The revoked-device sentence, when this browser is no longer allowed. Null on a new browser. */
  protected enrolmentNotice(): TKey | null {
    const notice = this.service?.enrolmentNotice;
    return typeof notice === 'function' ? notice.call(this.service) : null;
  }

  ngOnDestroy(): void {
    if (this.copyTimer !== undefined) clearTimeout(this.copyTimer);
  }

  protected onImportFromDrive(file: Blob): void {
    this.importFile.emit(file);
  }

  async onConnect(): Promise<void> {
    if (!this.service) return;
    this.busy.set(true);
    this.connectWork.set(true);
    this.error.set(null);
    try {
      const result = await this.service.connect();
      // No folder yet, or the one this browser remembers was deleted: create one. FOLDER_GONE is Disconnected
      // plus driveConnect.folderGone. Connect and Try again both come through here, and that sentence promises a
      // new folder. A failure comes back on the result (the service is already Error) and must be copied here.
      // Leaving the signal null shows only Try again, with no sentence.
      const folderMissing = result.state === 'Disconnected'
        && (result.error == null || result.error === 'driveConnect.folderGone');
      if (folderMissing) {
        const created = await this.service.createFolder();
        if (created.error) {
          this.error.set(created.error);
        } else if (created.recoveryKey) {
          this.recoveryKey.set(created.recoveryKey);
          this.recoveryKeySaved.set(false);
        } else if (created.state === 'Error') {
          this.error.set('driveConnect.failed');
        }
      } else if (result.recoveryKey) {
        this.recoveryKey.set(result.recoveryKey);
        this.recoveryKeySaved.set(false);
      } else if (result.error) {
        this.error.set(result.error);
      } else if (result.state === 'Error') {
        this.error.set('driveConnect.failed');
      }
    } catch {
      this.error.set('driveConnect.failed');
    } finally {
      this.busy.set(false);
      this.connectWork.set(false);
    }
  }

  async copyRecoveryKey(key: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(key);
      this.keyCopied.set(true);
      if (this.copyTimer !== undefined) clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => {
        this.keyCopied.set(false);
        this.copyTimer = undefined;
      }, 2000);
    } catch {
      this.announcer.announce({ key: 'driveConnect.copyFailed' });
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

  async skipRecoveryKey(): Promise<void> {
    if (!this.service) return;
    const ok = await this.confirm.ask(
      { key: 'driveConnect.recoveryKeyWarning' },
      { confirmKey: 'common.skip', danger: true },
    );
    if (!ok) return;
    this.service.skipRecoveryKeyWithWarning();
    this.recoveryKey.set(null);
    this.recoveryKeySaved.set(false);
  }

  async onDisconnect(): Promise<void> {
    if (!this.service) return;
    const ok = await this.confirm.ask(
      { key: 'driveConnect.disconnect' },
      { confirmKey: 'driveConnect.disconnect', danger: true },
    );
    if (!ok) return;
    this.busy.set(true);
    try {
      await this.service.disconnect();
    } finally {
      this.busy.set(false);
    }
  }
}
