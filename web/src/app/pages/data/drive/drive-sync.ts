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
  OnDestroy,
  OnInit,
  ChangeDetectionStrategy,
  effect,
  inject,
  signal,
  computed,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { LocalStore } from '../../../data/local-store.service';
import { TranslationService } from '../../../i18n/translation.service';
import { TPipe } from '../../../i18n/t.pipe';

/** docs/15 §1.3: sync two minutes after the last local change, while the page is open. */
export const DRIVE_SYNC_AFTER_CHANGE_MS = 2 * 60 * 1000;

/**
 * Drive sync status and controls: status line, sync now, shrink confirmation, Wi-Fi photo rule.
 */
@Component({
  selector: 'app-drive-sync',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-sync.html',
  styleUrl: './drive-sync.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveSyncCard implements OnInit, OnDestroy {
  private readonly service = inject(DriveConnectService);
  protected readonly i18n = inject(TranslationService);
  private alive = true;
  private afterChangeTimer: number | null = null;

  protected readonly busy = signal(false);
  protected readonly syncState = signal<'synced' | 'waiting-wifi' | 'offline' | 'error' | 'syncing' | 'needs-confirmation'>('synced');
  protected readonly lastSyncAt = signal<number | null>(null);
  protected readonly skippedCount = signal(0);
  protected readonly photosWifiOnly = signal(true);
  protected readonly pendingPhotoBytes = signal<number | null>(null);
  protected readonly showPhotoError = signal(false);
  protected readonly needsConfirmation = signal(false);

  protected readonly statusMessage = computed(() => {
    const state = this.syncState();
    const at = this.lastSyncAt();
    if (state === 'syncing') return this.i18n.t('driveSync.statusSyncing');
    if (state === 'waiting-wifi') return this.i18n.t('driveSync.statusWaitingWifi');
    if (state === 'offline') return this.i18n.t('driveSync.statusOffline');
    if (state === 'error') return this.i18n.t('driveSync.statusError');
    if (state === 'synced' && at) {
      return this.i18n.t('driveSync.statusUpToDateAt', { time: this.i18n.dateTime(new Date(at).toISOString()) });
    }
    return this.i18n.t('driveSync.statusUpToDate');
  });

  protected readonly photoLabel = computed(() => {
    const bytes = this.pendingPhotoBytes();
    if (!bytes || bytes <= 0) return null;
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${Math.round((bytes / (1024 * 1024)) * 10) / 10} MB`;
  });

  private syncIntervalId: number | null = null;
  private onlineHandler: (() => void) | null = null;
  private offlineHandler: (() => void) | null = null;
  private visibilityHandler: (() => void) | null = null;

  constructor() {
    const store = inject(LocalStore);
    let previous = store.revision();
    effect((onCleanup) => {
      const rev = store.revision();
      if (rev === previous) return;
      previous = rev;
      if (this.afterChangeTimer !== null) window.clearTimeout(this.afterChangeTimer);
      const timer = window.setTimeout(() => {
        this.afterChangeTimer = null;
        if (this.alive) void this.performSync();
      }, DRIVE_SYNC_AFTER_CHANGE_MS);
      this.afterChangeTimer = timer;
      onCleanup(() => window.clearTimeout(timer));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.performSync();
    this.onlineHandler = () => void this.performSync();
    this.offlineHandler = () => this.syncState.set('offline');
    window.addEventListener('online', this.onlineHandler);
    window.addEventListener('offline', this.offlineHandler);
    this.visibilityHandler = () => {
      if (document.visibilityState === 'hidden') void this.performSync();
    };
    document.addEventListener('visibilitychange', this.visibilityHandler);
    this.syncIntervalId = window.setInterval(() => void this.performSync(), 30 * 60 * 1000);
    await this.loadPhotoSettings();
  }

  ngOnDestroy(): void {
    this.alive = false;
    if (this.afterChangeTimer !== null) window.clearTimeout(this.afterChangeTimer);
    if (this.syncIntervalId !== null) clearInterval(this.syncIntervalId);
    if (this.onlineHandler) window.removeEventListener('online', this.onlineHandler);
    if (this.offlineHandler) window.removeEventListener('offline', this.offlineHandler);
    if (this.visibilityHandler) document.removeEventListener('visibilitychange', this.visibilityHandler);
  }

  protected async performSync(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.syncState.set('syncing');
    try {
      const result = await this.service.syncNow();
      if (result.needsConfirmation) {
        this.needsConfirmation.set(true);
        this.syncState.set('needs-confirmation');
      } else {
        this.needsConfirmation.set(false);
        const mapped =
          result.state === 'synced' || result.state === 'skipped-files'
            ? 'synced'
            : result.state === 'waiting-wifi'
              ? 'waiting-wifi'
              : result.state === 'offline'
                ? 'offline'
                : result.state === 'needs-confirmation'
                  ? 'needs-confirmation'
                  : 'error';
        this.syncState.set(mapped);
        this.lastSyncAt.set(result.lastSyncAt ?? Date.now());
        this.skippedCount.set(result.skipped?.length ?? 0);
      }
      await this.loadPhotoSettings();
    } catch {
      this.syncState.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  protected async confirmShrinkDeletion(): Promise<void> {
    this.busy.set(true);
    try {
      await this.service.syncNow({ confirmShrink: true });
      this.needsConfirmation.set(false);
      this.syncState.set('synced');
      this.lastSyncAt.set(Date.now());
    } catch {
      this.syncState.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  protected cancelConfirmation(): void {
    this.needsConfirmation.set(false);
    this.syncState.set('synced');
  }

  protected async togglePhotosWifiOnly(event: Event): Promise<void> {
    const checked = (event.target as HTMLInputElement).checked;
    this.busy.set(true);
    try {
      await this.service.setPhotosWifiOnly(checked);
      this.photosWifiOnly.set(checked);
      this.showPhotoError.set(false);
    } catch {
      this.showPhotoError.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  protected async uploadPhotosNowOverMobile(): Promise<void> {
    this.busy.set(true);
    try {
      await this.service.uploadPhotosNowOverMobile();
      this.pendingPhotoBytes.set(0);
      this.showPhotoError.set(false);
    } catch {
      this.showPhotoError.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  private async loadPhotoSettings(): Promise<void> {
    try {
      const settings = await this.service.photoSettings();
      this.photosWifiOnly.set(!settings.uploadOnMobileData);
      this.pendingPhotoBytes.set(await this.service.pendingPhotoBytes());
    } catch {
      /* photo settings are not required for the status line */
    }
  }
}
