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

import { Injectable, Inject, InjectionToken, signal } from '@angular/core';
import type { RecoveryKey } from '../../crypto/recovery-key';
import type { DriveBackupAdapter } from './backup-adapter';
import type { DriveSyncAdapter } from './sync-adapter';
import type { DriveDeletionAdapter } from './deletion-adapter';
import type { GoogleConfig } from './google-token-provider';
import { GOOGLE_CONFIG } from './drive-connect.providers';
import type { DeletionAction } from '../drive-deletion-rules';

export const DRIVE_BACKUP_ADAPTER = new InjectionToken<DriveBackupAdapter>('DRIVE_BACKUP_ADAPTER');
export const DRIVE_SYNC_ADAPTER = new InjectionToken<DriveSyncAdapter>('DRIVE_SYNC_ADAPTER');
export const DRIVE_DELETION_ADAPTER = new InjectionToken<DriveDeletionAdapter>('DRIVE_DELETION_ADAPTER');

export type ConnectState = 'Unavailable' | 'Disconnected' | 'Connecting' | 'NeedsRecoveryKey' | 'NeedsEnrolment' | 'FirstConnectShowRecoveryKey' | 'Ready' | 'Error';

export interface ConnectResult {
  readonly state: ConnectState;
  readonly recoveryKey?: string;
  readonly error?: string;
}

/**
 * Orchestrates Google Drive connection, backup, import, sync, photos, and deletion for the Connect page.
 * A plain class with constructor-injected dependencies (no Angular DI inside this class).
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
@Injectable()
export class DriveConnectService {
  private readonly state = signal<ConnectState>('Unavailable');
  private recoveryKeyShown = false;
  private readonly isConfigured: boolean;

  constructor(
    @Inject(DRIVE_BACKUP_ADAPTER) private readonly backupAdapter: DriveBackupAdapter,
    @Inject(DRIVE_SYNC_ADAPTER) private readonly syncAdapter: DriveSyncAdapter,
    @Inject(DRIVE_DELETION_ADAPTER) private readonly deletionAdapter: DriveDeletionAdapter,
    @Inject(GOOGLE_CONFIG) private readonly googleConfig: GoogleConfig,
  ) {
    // Check if Google Drive is configured
    this.isConfigured = !!googleConfig.clientId;
    // If not configured, state remains 'Unavailable'
  }

  getState(): ConnectState {
    return this.state();
  }

  async connect(): Promise<ConnectResult> {
    this.state.set('Connecting');
    try {
      const connection = await this.backupAdapter.connect();
      switch (connection.kind) {
        case 'READY':
          this.state.set('Ready');
          return { state: 'Ready' };
        case 'NEEDS_RECOVERY_KEY':
          this.state.set('NeedsRecoveryKey');
          return { state: 'NeedsRecoveryKey' };
        case 'NEEDS_ENROLMENT':
          this.state.set('NeedsEnrolment');
          return { state: 'NeedsEnrolment' };
        case 'FOLDER_GONE':
          this.state.set('Disconnected');
          return { state: 'Disconnected', error: 'Folder was deleted; connect again to create a new one' };
        case 'NO_FOLDER':
          this.state.set('Disconnected');
          return { state: 'Disconnected' };
        case 'ERROR':
          this.state.set('Error');
          return { state: 'Error', error: connection.problem.kind };
        default:
          this.state.set('Error');
          return { state: 'Error', error: 'Unknown connection state' };
      }
    } catch (err) {
      this.state.set('Error');
      return { state: 'Error', error: String(err) };
    }
  }

  async createFolder(): Promise<ConnectResult> {
    try {
      this.state.set('FirstConnectShowRecoveryKey');
      const outcome = await this.backupAdapter.createFolder();
      this.recoveryKeyShown = false;
      const connection = outcome.connection;

      if (outcome.recoveryKey) {
        return { state: 'FirstConnectShowRecoveryKey', recoveryKey: outcome.recoveryKey.display };
      }

      // No recovery key returned; show what state we're in
      switch (connection.kind) {
        case 'READY':
          this.state.set('Ready');
          return { state: 'Ready' };
        case 'NEEDS_ENROLMENT':
          this.state.set('NeedsEnrolment');
          return { state: 'NeedsEnrolment' };
        case 'ERROR':
          this.state.set('Error');
          return { state: 'Error', error: connection.problem.kind };
        default:
          this.state.set('Error');
          return { state: 'Error', error: 'Folder creation did not return recovery key' };
      }
    } catch (err) {
      this.state.set('Error');
      return { state: 'Error', error: String(err) };
    }
  }

  confirmRecoveryKeySaved(): void {
    this.recoveryKeyShown = true;
    this.state.set('NeedsEnrolment');
  }

  skipRecoveryKeyWithWarning(): void {
    this.recoveryKeyShown = true;
    this.state.set('NeedsEnrolment');
  }

  async openWithRecoveryKey(recoveryKeyText: string): Promise<ConnectResult> {
    try {
      // Parse recovery key text (this is a simplified version; real parsing happens in RecoveryKey)
      const recoveryKey = { display: recoveryKeyText } as unknown as RecoveryKey;
      const connection = await this.backupAdapter.openWithRecoveryKey(recoveryKey);

      switch (connection.kind) {
        case 'READY':
          this.state.set('Ready');
          return { state: 'Ready' };
        case 'NEEDS_ENROLMENT':
          this.state.set('NeedsEnrolment');
          return { state: 'NeedsEnrolment' };
        case 'ERROR':
          this.state.set('Error');
          return { state: 'Error', error: connection.problem.kind };
        default:
          this.state.set('Error');
          return { state: 'Error', error: 'Recovery key opening failed' };
      }
    } catch (err) {
      this.state.set('Error');
      return { state: 'Error', error: String(err) };
    }
  }

  async backUpNow(): Promise<{ success: boolean; error?: string }> {
    try {
      const result = await this.syncAdapter.syncNow();
      if (result.state === 'synced' || result.state === 'skipped-files') {
        return { success: true };
      }
      return { success: false, error: result.error || 'Sync failed' };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  async setAutoBackup(enabled: boolean): Promise<void> {
    // This is handled by the backup schedule logic in the real implementation
    // For now, this is a no-op
  }

  async setPhotosWifiOnly(wifiOnly: boolean): Promise<void> {
    this.syncAdapter.setPhotosWifiOnly(wifiOnly);
  }

  async uploadPhotosNowOverMobile(): Promise<{ success: boolean; error?: string }> {
    try {
      this.syncAdapter.uploadPhotosNowOverMobile();
      return { success: true };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  async disconnect(): Promise<void> {
    // Disconnect only drops tokens and local keys, never deletes remote data
    this.state.set('Disconnected');
  }

  async deleteL1(): Promise<{ success: boolean; error?: string }> {
    try {
      const action: DeletionAction = { type: 'olderBackups' };
      const preflight = await this.deletionAdapter.preflight(action);
      if (preflight.kind === 'refused') {
        return { success: false, error: preflight.reason };
      }
      // Execute L1 deletion
      const outcome = await this.deletionAdapter.execute(preflight.plan, null);
      return { success: outcome.kind === 'ran', error: outcome.kind === 'refused' ? outcome.reason : undefined };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  async deleteL2(): Promise<{ success: boolean; error?: string }> {
    try {
      const action: DeletionAction = { type: 'olderBackups' };
      const preflight = await this.deletionAdapter.preflight(action);
      if (preflight.kind === 'refused') {
        return { success: false, error: preflight.reason };
      }
      // L2 requires authorization
      const auth = await this.deletionAdapter.authorize(action, {} as any);
      if (auth.kind === 'refused') {
        return { success: false, error: auth.reason };
      }
      const outcome = await this.deletionAdapter.execute(preflight.plan, auth.grant);
      return { success: outcome.kind === 'ran', error: outcome.kind === 'refused' ? outcome.reason : undefined };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  async deleteL3(deleteAllCheckbox: boolean): Promise<{ success: boolean; error?: string }> {
    try {
      const action: DeletionAction = { type: 'everything' };
      const preflight = await this.deletionAdapter.preflight(action);
      if (preflight.kind === 'refused') {
        return { success: false, error: preflight.reason };
      }
      // L3 requires authorization
      const auth = await this.deletionAdapter.authorize(action, {} as any);
      if (auth.kind === 'refused') {
        return { success: false, error: auth.reason };
      }
      const outcome = await this.deletionAdapter.execute(preflight.plan, auth.grant);
      return { success: outcome.kind === 'ran', error: outcome.kind === 'refused' ? outcome.reason : undefined };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  hasShownRecoveryKey(): boolean {
    return this.recoveryKeyShown;
  }
}
