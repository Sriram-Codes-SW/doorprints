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

import {
  Component,
  ChangeDetectionStrategy,
  OnInit,
  OnDestroy,
  inject,
  signal,
  output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DriveConnectService, type BackupSummary } from '../../../data/drive/connect/drive-connect.service';
import { TranslationService, type Msg } from '../../../i18n/translation.service';
import { TPipe } from '../../../i18n/t.pipe';
import type { TKey } from '../../../i18n/en';
import { Announcer } from '../../../core/announcer.service';

type ListState = 'loading' | 'ready' | 'error';

/**
 * Drive backups card on Your data page (S4b-BL-73): list backups from Google Drive,
 * back up now, import a backup, and enable automatic backups.
 */
@Component({
  selector: 'app-drive-backups',
  standalone: true,
  imports: [CommonModule, FormsModule, TPipe],
  templateUrl: './drive-backups.html',
  styleUrl: './drive-backups.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveBackupsCard implements OnInit, OnDestroy {
  private readonly service = inject(DriveConnectService);
  protected readonly i18n = inject(TranslationService);
  private readonly announcer = inject(Announcer);

  // Outputs for parent component (data-page)
  readonly importFile = output<Blob>();

  // State signals
  protected readonly backups = signal<BackupSummary[]>([]);
  protected readonly listState = signal<ListState>('loading');
  protected readonly listError = signal<TKey | null>(null);

  protected readonly backupBusy = signal(false);
  protected readonly backupMessage = signal<Msg | null>(null);
  protected readonly backupError = signal<TKey | null>(null);
  protected readonly shrinkBackupId = signal<string | null>(null);

  protected readonly autoBackupEnabled = signal(false);
  protected readonly importBusy = signal<string | null>(null);
  protected readonly importError = signal<TKey | null>(null);

  private autoBackupInterval: number | null = null;
  private visibilityListener: (() => void) | null = null;

  /** Lists the backups and starts the automatic-backup checks. */
  ngOnInit(): void {
    this.loadBackups();
    this.loadAutoBackupState();
    this.setupAutoBackupInterval();
    this.setupVisibilityListener();
  }

  ngOnDestroy(): void {
    this.cleanupAutoBackupInterval();
    this.cleanupVisibilityListener();
  }

  /**
   * Makes a backup in Drive now and says how many houses it holds. If the service asks for shrink confirmation, the
   * person is asked to confirm.
   */
  protected async backupNow(): Promise<void> {
    if (this.backupBusy()) return;

    this.backupBusy.set(true);
    this.backupMessage.set(null);
    this.backupError.set(null);
    this.shrinkBackupId.set(null);

    const result = await this.service.backUpNow();

    if (!result.ok) {
      this.backupError.set(result.reason);
      this.backupBusy.set(false);
      return;
    }

    const backup = result.backup;
    const timeStr = this.i18n.dateTime(new Date(backup.createdAt).toISOString());
    this.backupMessage.set({
      key: 'driveBackups.housesBackedUp' as const,
      params: { n: backup.houses, time: timeStr },
    });
    this.announcer.announce({
      key: 'driveBackups.housesBackedUp' as const,
      params: { n: backup.houses, time: timeStr },
    });

    if (result.needsShrinkConfirmation) {
      this.shrinkBackupId.set(backup.id);
    }
    // Clear busy so the shrink confirm button is enabled; confirmShrink sets it again.
    this.backupBusy.set(false);

    await this.loadBackups();
  }

  /** The person confirms the smaller backup. */
  protected async confirmShrink(): Promise<void> {
    const backupId = this.shrinkBackupId();
    if (!backupId) return;

    this.backupBusy.set(true);
    try {
      await this.service.confirmShrink(backupId);
      this.shrinkBackupId.set(null);
      this.announcer.announce({ key: 'driveBackups.shrinkConfirmed' as const });
      await this.loadBackups();
    } catch (err) {
      this.backupError.set('driveConnect.failed');
    } finally {
      this.backupBusy.set(false);
    }
  }

  protected cancelShrink(): void {
    this.shrinkBackupId.set(null);
    this.backupBusy.set(false);
  }

  /**
   * Downloads a backup from Drive and hands it to the *Import a backup* card as a file; nothing is imported until the
   * person confirms there.
   */
  protected async importBackup(backupId: string): Promise<void> {
    if (this.importBusy()) return;

    this.importBusy.set(backupId);
    this.importError.set(null);

    const result = await this.service.importFromDrive(backupId);

    if (!result.ok) {
      this.importError.set(result.reason);
      this.importBusy.set(null);
      return;
    }

    this.importFile.emit(result.file);
    this.importBusy.set(null);
  }

  protected async retryLoadBackups(): Promise<void> {
    await this.loadBackups();
  }

  /** Turns automatic backups on or off. */
  protected toggleAutoBackup(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.autoBackupEnabled.set(checked);
    void this.service.setAutoBackup(checked);
  }

  /** Reads the backup list; a newer backup missing from Drive is announced. */
  private async loadBackups(): Promise<void> {
    this.listState.set('loading');
    this.listError.set(null);

    const result = await this.service.listBackups();

    if (!result.ok) {
      this.listError.set(result.reason);
      this.listState.set('error');
      return;
    }

    this.backups.set(result.backups);
    this.listState.set('ready');

    if (result.missingNewer) {
      this.announcer.announce({ key: 'driveBackups.missingNewer' as const });
    }
  }

  private async loadAutoBackupState(): Promise<void> {
    this.autoBackupEnabled.set(this.service.autoBackupEnabled());
  }

  /** While the page is open, checks every 15 minutes whether an automatic backup is due. */
  private setupAutoBackupInterval(): void {
    this.autoBackupInterval = window.setInterval(async () => {
      if (!this.autoBackupEnabled()) return;
      const result = await this.service.runDueBackup();
      if (result.ran) {
        await this.loadBackups();
      }
    }, 15 * 60 * 1000);
  }

  private cleanupAutoBackupInterval(): void {
    if (this.autoBackupInterval !== null) {
      clearInterval(this.autoBackupInterval);
      this.autoBackupInterval = null;
    }
  }

  /** Checks for a due automatic backup when the page becomes visible. */
  private setupVisibilityListener(): void {
    this.visibilityListener = () => {
      if (document.visibilityState === 'visible' && this.autoBackupEnabled()) {
        void this.runDueBackupSilent();
      }
    };
    document.addEventListener('visibilitychange', this.visibilityListener);
  }

  private cleanupVisibilityListener(): void {
    if (this.visibilityListener) {
      document.removeEventListener('visibilitychange', this.visibilityListener);
      this.visibilityListener = null;
    }
  }

  /** Runs a backup that is due, with no message, and refreshes the list if one ran. */
  private async runDueBackupSilent(): Promise<void> {
    const result = await this.service.runDueBackup();
    if (result.ran) {
      await this.loadBackups();
    }
  }

  protected formatSize(bytes: number | null): string {
    if (bytes === null) return this.i18n.t('driveBackups.sizeUnknown');
    const kb = bytes / 1024;
    if (kb < 1024) {
      return Math.round(kb) + ' KB';
    }
    const mb = kb / 1024;
    return Math.round(mb * 10) / 10 + ' MB';
  }
}
