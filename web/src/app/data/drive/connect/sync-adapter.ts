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

import { WebCryptoProvider } from '../../crypto/crypto-provider';
import { DriveSyncEngine } from '../drive-sync-engine';
import { DriveSyncBackend } from '../drive-sync-backend';
import { DrivePhotos } from '../drive-photos';
import {
  DEFAULT_PHOTO_CONFIG,
  EMPTY_PHOTO_STATE,
} from '../drive-photo-seams';
import type { PhotoConfig, PhotoState, PhotoStateStore } from '../drive-photo-seams';
import type { PhotoSettings } from '../photo-network-policy';
import { DEFAULT_PHOTO_SETTINGS, PhotoUploadGate, webNetworkState } from '../photo-network-policy';
import type { OneOffGrant } from '../photo-network-policy';
import { DriveSyncNotYet } from '../drive-sync-seams';
import type { DriveSyncState, FolderSession, LocalRows, SkipReason, SyncPassResult } from '../drive-sync-seams';
import { EMPTY_SYNC_STATE, syncDeviceId } from '../drive-sync-seams';
import type { DriveClient } from '../drive-client';

/**
 * UI-friendly status returned by syncNow; maps the low-level SyncPassResult to user-facing states
 * (S4b-BL-131; docs/15 §9.4).
 */
export interface SyncAdapterStatus {
  readonly state: 'synced' | 'waiting-wifi' | 'offline' | 'needs-confirmation' | 'skipped-files' | 'error';
  readonly skipped: SkipReason[];
  readonly lastSyncAt: number | null;
  readonly error?: string;
}

/**
 * Adapts Google Drive sync and backup for the Connect page UI: wraps DriveSyncEngine, DriveSyncBackend,
 * and photos over a FolderSession to present a single, UI-friendly interface.
 * S4b-BL-131; docs/15 §9.4.
 * Plain class, injectable dependencies only (no Angular DI). Kotlin twin: `DriveSyncAdapter.kt`.
 */
export class DriveSyncAdapter {
  private readonly photoSettings: PhotoSettings;
  private readonly photoState: PhotoState;
  private readonly syncState: DriveSyncState;
  private lastSyncAt: number | null = null;
  private lastResult: SyncPassResult | null = null;
  private readonly engine: DriveSyncEngine;
  private readonly backend: DriveSyncBackend;
  private readonly photoGate: PhotoUploadGate;
  private readonly photos: DrivePhotos | null;
  private readonly local: LocalRows;
  private readonly photoStore: PhotoStateStore;

  constructor(
    private readonly session: FolderSession,
    private readonly drive: DriveClient,
    private readonly clock: () => number = () => Date.now(),
    photoStateStore?: PhotoStateStore,
    photoSettings?: PhotoSettings,
    photoConfig?: PhotoConfig,
  ) {
    this.photoSettings = photoSettings ?? DEFAULT_PHOTO_SETTINGS;
    this.photoState = { ...EMPTY_PHOTO_STATE };
    this.syncState = { ...EMPTY_SYNC_STATE };
    this.photoConfig = photoConfig ?? DEFAULT_PHOTO_CONFIG;

    this.photoStore = photoStateStore || {
      load: async () => this.photoState,
      save: async (s) => {
        Object.assign(this.photoState, s);
      },
    };

    const syncStateStore = {
      load: async () => this.syncState,
      save: async (s: DriveSyncState) => {
        Object.assign(this.syncState, s);
      },
    };

    // Minimal local rows implementation; in real app this comes from IndexedDB
    this.local = {
      all: async () => [],
      photo: async () => null,
    };

    // Initialize engine
    this.engine = new DriveSyncEngine(
      this.drive,
      new WebCryptoProvider(),
      this.session,
      syncStateStore,
      this.local,
      this.clock,
    );

    // Initialize photos service
    this.photos = new DrivePhotos(
      this.drive,
      new WebCryptoProvider(),
      this.session,
      this.photoStore,
      this.clock,
      this.photoConfig,
    );

    // Initialize backend with engine and photos
    this.backend = new DriveSyncBackend(
      this.engine,
      this.local,
      this.clock,
      this.session.deviceId,
      this.photos,
    );

    // Initialize photo upload gate with web network state
    // Web treats UNKNOWN network (no type on desktop) as ALLOWED (S4b-BL-131, docs/15 §11)
    this.photoGate = new PhotoUploadGate(
      webNetworkState,
      () => this.photoSettings,
      this.clock,
      true, // webUnknownAllowed: on web, UNKNOWN is treated as unmetered
    );
  }

  /**
   * Run one sync pass: connect, fetch peer files, merge, push this device's state.
   * Returns a UI-friendly status; confirmShrink asks to apply house deletions that were held.
   */
  async syncNow(opts: { confirmShrink?: boolean } = {}): Promise<SyncAdapterStatus> {
    if (opts.confirmShrink) {
      this.backend.confirmShrink();
    }

    try {
      const result = await this.backend.commitPushes().toPromise();
      this.lastResult = this.backend.lastResult;

      if (!this.lastResult) {
        return { state: 'error', skipped: [], lastSyncAt: this.lastSyncAt, error: 'No result from sync' };
      }

      this.lastSyncAt = this.clock();

      if (this.lastResult.kind === 'Waiting') {
        return {
          state: 'waiting-wifi',
          skipped: [],
          lastSyncAt: this.lastSyncAt,
        };
      }

      if (this.lastResult.kind === 'Paused') {
        return {
          state: 'offline',
          skipped: [],
          lastSyncAt: this.lastSyncAt,
        };
      }

      const skipped = this.lastResult.report.skipped.map((s) => s.reason);

      if (this.lastResult.kind === 'NeedsConfirmation') {
        return {
          state: 'needs-confirmation',
          skipped,
          lastSyncAt: this.lastSyncAt,
        };
      }

      // kind === 'Done'
      if (skipped.length > 0) {
        return {
          state: 'skipped-files',
          skipped,
          lastSyncAt: this.lastSyncAt,
        };
      }

      return {
        state: 'synced',
        skipped: [],
        lastSyncAt: this.lastSyncAt,
      };
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : String(err);
      if (err instanceof DriveSyncNotYet) {
        return {
          state: 'error',
          skipped: [],
          lastSyncAt: this.lastSyncAt,
          error: `${err.feature} not yet built (${err.ticket})`,
        };
      }
      return {
        state: 'error',
        skipped: [],
        lastSyncAt: this.lastSyncAt,
        error: errMsg,
      };
    }
  }

  /**
   * Check if this device is behind peers: unread files from other devices, or a difference in generation.
   */
  async isBehind(): Promise<boolean> {
    try {
      return await this.engine.isBehind();
    } catch (err) {
      // On error, assume not behind to avoid blocking the UI
      return false;
    }
  }

  /**
   * Get the current photo settings (Wi-Fi-only upload, mobile data allowed).
   */
  getPhotoSettings(): Readonly<PhotoSettings> {
    return { ...this.photoSettings };
  }

  /**
   * Update the "upload photos only on Wi-Fi" setting (default: true).
   */
  setPhotosWifiOnly(wifiOnly: boolean): void {
    (this.photoSettings as any).uploadOnMobileData = !wifiOnly;
  }

  /**
   * Update the "allow photos on mobile data" setting (default: false).
   */
  setUploadOnMobile(allowed: boolean): void {
    (this.photoSettings as any).uploadOnMobileData = allowed;
  }

  /**
   * Grant a one-time exception to upload photos over mobile data for the next 30 minutes.
   * Useful for the "Upload now over mobile" button in the UI.
   */
  uploadPhotosNowOverMobile(): OneOffGrant {
    return this.photoGate.grantOneOff();
  }

  /**
   * Get the number of bytes waiting to be uploaded (for the "Upload now" button label).
   * Sums sizeBytes of all photos known locally but not yet uploaded to Drive.
   */
  async pendingPhotoBytes(): Promise<number> {
    try {
      if (!this.photos) return 0;
      // Get all local rows (photos and other data)
      const allRows = await this.local.all();
      // Get all uploaded photo refs
      const refs = await this.photos.refs();
      // Sum sizeBytes of photos not yet uploaded (live photos without a ref)
      let pending = 0;
      for (const row of allRows) {
        if (row.kind === 'photos' && !row.stamp.deleted) {
          const photoId = row.key;
          const sizeBytes = (row.json['sizeBytes'] as number) ?? 0;
          if (!(photoId in refs)) {
            pending += sizeBytes;
          }
        }
      }
      return pending;
    } catch {
      return 0;
    }
  }

  /**
   * Decide whether a backup should run now based on network state and user settings.
   * Returns true if the network and settings allow backup (on unmetered network or if forced).
   */
  decideBackup(force: boolean = false): boolean {
    if (force) return true;
    const decision = this.photoGate.decision();
    // Allow backup on unmetered networks or with the mobile data setting on
    return decision.reason === 'UNMETERED' || decision.reason === 'MOBILE_DATA_SETTING';
  }

  private readonly photoConfig: PhotoConfig;
}

/**
 * The public interface for DriveSyncAdapter (properties and methods that external code can use).
 * This is what service injection looks like when not using Angular DI.
 */
export interface IDriveSyncAdapter {
  syncNow(opts?: { confirmShrink?: boolean }): Promise<SyncAdapterStatus>;
  isBehind(): Promise<boolean>;
  getPhotoSettings(): Readonly<PhotoSettings>;
  setPhotosWifiOnly(wifiOnly: boolean): void;
  setUploadOnMobile(allowed: boolean): void;
  uploadPhotosNowOverMobile(): OneOffGrant;
  pendingPhotoBytes(): Promise<number>;
  decideBackup(force?: boolean): boolean;
}
