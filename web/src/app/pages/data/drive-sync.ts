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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, inject, signal, computed, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { TPipe } from '../../i18n/t.pipe';

/**
 * Drive sync status and controls: shows sync state, initiates sync on demand and on schedule,
 * manages photo upload settings and Wi-Fi/mobile data preferences. S4b-BL-117.
 */
@Component({
  selector: 'app-drive-sync',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-sync.html',
  styleUrls: ['./drive-sync.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppDriveSync implements OnInit, OnDestroy {
  private readonly service = inject(DriveConnectService);

  readonly busy = signal(false);
  readonly syncState = signal<'up-to-date' | 'waiting-wifi' | 'offline' | 'error' | 'syncing'>('up-to-date');
  readonly lastSyncTime = signal<Date | null>(null);
  readonly skippedCount = signal(0);
  readonly photosWifiOnly = signal(false);
  readonly pendingPhotoBytes = signal<number | null>(null);
  readonly showPhotoError = signal(false);
  readonly needsShrinkConfirmation = signal(false);
  readonly shrinkMessage = signal('');

  readonly statusMessage = computed(() => {
    const state = this.syncState();
    const lastSync = this.lastSyncTime();

    if (state === 'up-to-date' && lastSync) {
      return `Up to date, last synced ${lastSync.toLocaleTimeString()}`;
    }
    if (state === 'up-to-date') return 'Up to date';
    if (state === 'waiting-wifi') return 'Waiting for Wi-Fi to send photos';
    if (state === 'offline') return 'Offline: will sync when you are back online';
    if (state === 'error') return 'Sync failed';
    return 'Syncing…';
  });

  readonly photoLabel = computed(() => {
    const bytes = this.pendingPhotoBytes();
    return bytes && bytes > 0 ? this.formatBytes(bytes) : null;
  });

  private syncIntervalId: number | null = null;
  private onlineHandler: (() => void) | null = null;
  private offlineHandler: (() => void) | null = null;

  constructor() {
    effect(() => {
      // Ensure skipped notice disappears when resetting state
      void this.skippedCount();
    });
  }

  async ngOnInit(): Promise<void> {
    // Initial sync
    await this.performSync();

    // Set up event listeners for online/offline
    this.onlineHandler = () => this.performSync();
    this.offlineHandler = () => this.syncState.set('offline');
    window.addEventListener('online', this.onlineHandler);
    window.addEventListener('offline', this.offlineHandler);

    // Set up 5-minute interval
    this.syncIntervalId = window.setInterval(() => this.performSync(), 5 * 60 * 1000);

    // Load photo settings
    await this.loadPhotoSettings();
  }

  ngOnDestroy(): void {
    if (this.syncIntervalId !== null) {
      clearInterval(this.syncIntervalId);
    }
    if (this.onlineHandler) window.removeEventListener('online', this.onlineHandler);
    if (this.offlineHandler) window.removeEventListener('offline', this.offlineHandler);
  }

  async performSync(): Promise<void> {
    this.busy.set(true);
    this.syncState.set('syncing');
    try {
      const result = await this.service.syncNow();

      if (result.needsConfirmation) {
        this.needsShrinkConfirmation.set(true);
        this.shrinkMessage.set('Another device deleted backups. Apply these deletions on this device?');
      } else {
        this.needsShrinkConfirmation.set(false);
        this.syncState.set(result.state as 'up-to-date' | 'waiting-wifi' | 'offline' | 'error');
        this.lastSyncTime.set(new Date());

        if (result.skipped?.length > 0) {
          this.skippedCount.set(result.skipped.length);
        }
      }

      await this.loadPhotoSettings();
    } catch (_err) {
      this.syncState.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  async confirmShrinkDeletion(): Promise<void> {
    this.busy.set(true);
    try {
      await this.service.syncNow({ confirmShrink: true });
      this.needsShrinkConfirmation.set(false);
      this.syncState.set('up-to-date');
      this.lastSyncTime.set(new Date());
    } catch (_err) {
      this.syncState.set('error');
    } finally {
      this.busy.set(false);
    }
  }

  cancelShrinkConfirmation(): void {
    this.needsShrinkConfirmation.set(false);
    this.syncState.set('up-to-date');
  }

  async togglePhotosWifiOnly(checked: boolean): Promise<void> {
    this.busy.set(true);
    try {
      await this.service.setPhotosWifiOnly(checked);
      this.photosWifiOnly.set(checked);
      this.showPhotoError.set(false);
    } catch (_err) {
      this.showPhotoError.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  async uploadPhotosNowOverMobile(): Promise<void> {
    this.busy.set(true);
    try {
      await this.service.uploadPhotosNowOverMobile();
      this.pendingPhotoBytes.set(0);
      this.showPhotoError.set(false);
    } catch (_err) {
      this.showPhotoError.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  private async loadPhotoSettings(): Promise<void> {
    try {
      const settings = await this.service.photoSettings();
      this.photosWifiOnly.set(settings.wifiOnly ?? false);

      const bytes = await this.service.pendingPhotoBytes();
      this.pendingPhotoBytes.set(bytes);
    } catch (_err) {
      // Silently fail; photo settings are not critical
    }
  }

  private formatBytes(bytes: number): string {
    const units = ['B', 'KB', 'MB', 'GB'];
    let size = bytes;
    let unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
      size /= 1024;
      unitIndex++;
    }
    return `${Math.round(size * 10) / 10} ${units[unitIndex]}`;
  }
}
