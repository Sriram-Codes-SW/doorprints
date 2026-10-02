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
import type { ReadyFolder, DriveProblem, DriveBackup } from '../backup/drive-backup-results';
import type { SyncAdapterStatus } from './sync-adapter';
import type { PhotoSettings, OneOffGrant } from '../photo-network-policy';
import type { DeletionContext } from '../../device-auth/delete-policy';
import type {
  DeletionPreflightResult,
  AuthorizationResult,
  ConfirmGateState,
  KeyValueStore,
} from './deletion-adapter';
import { MemoryStagingSink } from './local/import-sink';

export const DRIVE_BACKUP_ADAPTER = new InjectionToken<DriveBackupAdapter>('DRIVE_BACKUP_ADAPTER');
export const DRIVE_SYNC_ADAPTER = new InjectionToken<DriveSyncAdapter>('DRIVE_SYNC_ADAPTER');
export const DRIVE_DELETION_ADAPTER = new InjectionToken<DriveDeletionAdapter>('DRIVE_DELETION_ADAPTER');

export type ConnectState = 'Unavailable' | 'Disconnected' | 'Connecting' | 'NeedsRecoveryKey' | 'NeedsEnrolment' | 'FirstConnectShowRecoveryKey' | 'Ready' | 'Error';

export interface ConnectResult {
  readonly state: ConnectState;
  readonly recoveryKey?: string;
  readonly error?: string;
}

export interface BackupSummary {
  readonly id: string;
  readonly createdAt: number;
  readonly houses: number;
  readonly bytes: number | null;
  readonly name: string;
}

export interface SyncInfo {
  readonly state: SyncAdapterStatus['state'];
  readonly lastSyncAt: number | null;
  readonly skipped: string[];
  readonly needsConfirmation?: boolean;
  readonly needsShrinkConfirmation?: string; // backupId if present
}

export interface PhotoPendingInfo {
  readonly bytes: number | null;
  readonly settings: Readonly<PhotoSettings>;
}

/**
 * Orchestrates Google Drive connection, backup, import, sync, photos, and deletion for the Connect page.
 * Provides a single, typed API delegating to adapters with proper error handling and state management.
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
@Injectable()
export class DriveConnectService {
  private readonly state = signal<ConnectState>('Unavailable');
  private recoveryKeyShown = false;
  private readonly isConfigured: boolean;
  private readyFolder: ReadyFolder | null = null;
  private lastSyncResult: SyncAdapterStatus | null = null;
  private lastBackupId: string | null = null;

  constructor(
    @Inject(DRIVE_BACKUP_ADAPTER) private readonly backupAdapter: DriveBackupAdapter,
    @Inject(DRIVE_SYNC_ADAPTER) private readonly syncAdapter: DriveSyncAdapter,
    @Inject(DRIVE_DELETION_ADAPTER) private readonly deletionAdapter: DriveDeletionAdapter,
    @Inject(GOOGLE_CONFIG) private readonly googleConfig: GoogleConfig,
  ) {
    this.isConfigured = !!googleConfig.clientId;
  }

  getState(): ConnectState {
    return this.state();
  }

  // ==================== Connection Flow ====================

  async connect(): Promise<ConnectResult> {
    if (!this.isConfigured) {
      return { state: 'Unavailable', error: 'Google Drive not configured' };
    }

    this.state.set('Connecting');
    try {
      const connection = await this.backupAdapter.connect();
      return this.handleConnection(connection);
    } catch (err) {
      this.state.set('Error');
      return { state: 'Error', error: String(err) };
    }
  }

  async refresh(): Promise<ConnectResult> {
    return this.connect();
  }

  async createFolder(): Promise<ConnectResult> {
    this.state.set('FirstConnectShowRecoveryKey');
    try {
      const outcome = await this.backupAdapter.createFolder();
      this.recoveryKeyShown = false;

      if (outcome.recoveryKey) {
        this.updateReadyFolderFromConnection(outcome.connection);
        return {
          state: 'FirstConnectShowRecoveryKey',
          recoveryKey: outcome.recoveryKey.display,
        };
      }

      const result = this.handleConnection(outcome.connection);
      this.updateReadyFolderFromConnection(outcome.connection);
      return result;
    } catch (err) {
      this.state.set('Error');
      return { state: 'Error', error: String(err) };
    }
  }

  async openWithRecoveryKey(recoveryKeyText: string): Promise<ConnectResult> {
    try {
      const recoveryKey = { display: recoveryKeyText } as unknown as RecoveryKey;
      const connection = await this.backupAdapter.openWithRecoveryKey(recoveryKey);
      this.updateReadyFolderFromConnection(connection);
      return this.handleConnection(connection);
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

  hasShownRecoveryKey(): boolean {
    return this.recoveryKeyShown;
  }

  // ==================== State Management ====================

  private handleConnection(connection: any): ConnectResult {
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
        return {
          state: 'Disconnected',
          error: 'Folder was deleted; connect again to create a new one',
        };
      case 'NO_FOLDER':
        this.state.set('Disconnected');
        return { state: 'Disconnected' };
      case 'ERROR':
        this.state.set('Error');
        return {
          state: 'Error',
          error: connection.problem?.kind || 'Unknown error',
        };
      default:
        this.state.set('Error');
        return { state: 'Error', error: 'Unknown connection state' };
    }
  }

  private updateReadyFolderFromConnection(connection: any): void {
    if (connection.kind === 'READY' && connection.folder) {
      this.readyFolder = connection.folder;
    } else {
      this.readyFolder = null;
    }
  }

  // ==================== Backup & Listing ====================

  async listBackups(): Promise<
    | { readonly ok: true; readonly backups: BackupSummary[]; readonly missingNewer: boolean }
    | { readonly ok: false; readonly reason: string }
  > {
    if (!this.readyFolder) {
      return { ok: false, reason: 'Not connected to folder' };
    }

    try {
      const listing = await this.backupAdapter.listBackups(this.readyFolder);
      const backups: BackupSummary[] = listing.backups.map((b) => ({
        id: b.fileId,
        createdAt: b.createdAt,
        houses: b.houses,
        bytes: b.size,
        name: b.name,
      }));
      return { ok: true, backups, missingNewer: listing.missingNewer };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  // ==================== Import ====================

  async importFromDrive(
    backupId: string,
  ): Promise<
    | { readonly ok: true; readonly file: Blob }
    | { readonly ok: false; readonly reason: string }
  > {
    if (!this.readyFolder) {
      return { ok: false, reason: 'Not connected to folder' };
    }

    try {
      const listing = await this.backupAdapter.listBackups(this.readyFolder);
      const backup = listing.backups.find((b) => b.fileId === backupId);

      if (!backup) {
        return { ok: false, reason: 'Backup not found' };
      }

      const sink = new MemoryStagingSink();
      const result = await this.backupAdapter.importFromDrive(
        this.readyFolder,
        backupId,
        backup,
        sink,
      );

      if (result.kind === 'refused') {
        await sink.discard();
        return { ok: false, reason: result.problem.kind };
      }

      const blob = sink.blob;
      if (!blob) {
        await sink.discard();
        return { ok: false, reason: 'Failed to retrieve backup data' };
      }

      return { ok: true, file: blob };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  // ==================== Backup Operations ====================

  async backUpNow(): Promise<
    | { readonly ok: true; readonly backup?: any; readonly needsShrinkConfirmation?: string }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      // backUpNow is managed through sync adapter in the current implementation
      const status = await this.syncAdapter.syncNow();
      if (status.state === 'synced' || status.state === 'skipped-files') {
        return { ok: true };
      }
      return { ok: false, reason: status.error || 'Backup failed' };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  confirmShrink(backupId: string): Promise<void> {
    return this.backupAdapter.confirmShrink(backupId);
  }

  async lastBackup(): Promise<
    | { readonly ok: true; readonly backup: BackupSummary }
    | { readonly ok: false; readonly reason: string }
  > {
    const result = await this.listBackups();
    if (!result.ok) {
      return result;
    }
    if (result.backups.length === 0) {
      return { ok: false, reason: 'No backups found' };
    }
    return { ok: true, backup: result.backups[0] };
  }

  // ==================== Sync ====================

  async syncNow(
    opts?: { confirmShrink?: boolean },
  ): Promise<
    | SyncInfo & { needsConfirmation: false }
    | (SyncInfo & { needsConfirmation: true; needsShrinkConfirmation: string | undefined })
  > {
    try {
      this.lastSyncResult = await this.syncAdapter.syncNow(opts);
      return {
        state: this.lastSyncResult.state,
        lastSyncAt: this.lastSyncResult.lastSyncAt,
        skipped: this.lastSyncResult.skipped.map((s) => String(s)),
        needsConfirmation: this.lastSyncResult.state === 'needs-confirmation',
        needsShrinkConfirmation: undefined,
      };
    } catch (err) {
      return {
        state: 'error',
        lastSyncAt: this.lastSyncResult?.lastSyncAt || null,
        skipped: [],
        needsConfirmation: false,
      };
    }
  }

  confirmShrinkSync(backupId: string): Promise<void> {
    return this.backupAdapter.confirmShrink(backupId);
  }

  async syncStatus(): Promise<SyncInfo> {
    return {
      state: this.lastSyncResult?.state || 'error',
      lastSyncAt: this.lastSyncResult?.lastSyncAt || null,
      skipped: (this.lastSyncResult?.skipped || []).map((s) => String(s)),
    };
  }

  // ==================== Photos ====================

  async photoSettings(): Promise<Readonly<PhotoSettings>> {
    return this.syncAdapter.getPhotoSettings();
  }

  async pendingPhotoBytes(): Promise<number | null> {
    return this.syncAdapter.pendingPhotoBytes();
  }

  async setPhotosWifiOnly(wifiOnly: boolean): Promise<void> {
    this.syncAdapter.setPhotosWifiOnly(wifiOnly);
  }

  async uploadPhotosNowOverMobile(): Promise<OneOffGrant> {
    return this.syncAdapter.uploadPhotosNowOverMobile();
  }

  // ==================== Deletion ====================

  async deletePlan(
    action: DeletionAction,
  ): Promise<
    | { readonly ok: true; readonly plan: any }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const result = await this.deletionAdapter.preflight(action);
      if (result.kind === 'refused') {
        return { ok: false, reason: result.reason };
      }
      return { ok: true, plan: result.plan };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  async deleteConfirmInfo(
    action: DeletionAction,
  ): Promise<
    | {
        readonly ok: true;
        readonly tickBoxRequired: boolean;
        readonly delayMs: number;
      }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const context: DeletionContext = {
        platform: 'WEBSITE',
        deviceLock: false,
        webPrf: false,
        online: navigator.onLine,
        backupsLeft: null,
      };
      const decision = this.deletionAdapter.decide(action, context);
      if (decision.outcome === 'REFUSED') {
        return { ok: false, reason: decision.reason };
      }
      const gate = this.deletionAdapter.confirmGate(action, context);
      return {
        ok: true,
        tickBoxRequired: gate.tickBoxRequired,
        delayMs: gate.delayMs,
      };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  async authorizeDelete(
    action: DeletionAction,
  ): Promise<
    | { readonly ok: true; readonly grant: any }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const context: DeletionContext = {
        platform: 'WEBSITE',
        deviceLock: false,
        webPrf: false,
        online: navigator.onLine,
        backupsLeft: null,
      };
      const result = await this.deletionAdapter.authorize(action, context);
      if (result.kind === 'refused') {
        return { ok: false, reason: result.reason };
      }
      return { ok: true, grant: result.grant };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  async executeDelete(
    plan: any,
    grant: any,
  ): Promise<
    | { readonly ok: true }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const outcome = await this.deletionAdapter.execute(plan, grant);
      if (outcome.kind === 'ran') {
        return { ok: true };
      }
      return { ok: false, reason: outcome.reason };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  async resumeDelete(
    grant: any,
  ): Promise<
    | { readonly ok: true }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const outcome = await this.deletionAdapter.resume(grant);
      if (outcome.kind === 'ran') {
        return { ok: true };
      }
      return { ok: false, reason: outcome.reason };
    } catch (err) {
      return { ok: false, reason: String(err) };
    }
  }

  // ==================== Passkey ====================

  async passkeyStatus(): Promise<'none' | 'registered' | 'unsupported'> {
    return this.deletionAdapter.passkeyStatus();
  }

  async registerPasskey(): Promise<'registered' | 'unsupported' | null> {
    return this.deletionAdapter.registerPasskey();
  }

  // ==================== Backward Compatibility ====================

  async setAutoBackup(enabled: boolean): Promise<void> {
    // Auto-backup is handled by the schedule logic in the backup service
    // This is a no-op at the service level
  }

  async deleteL1(): Promise<{ success: boolean; error?: string }> {
    try {
      const action: DeletionAction = { type: 'olderBackups' };
      const preflight = await this.deletionAdapter.preflight(action);
      if (preflight.kind === 'refused') {
        return { success: false, error: preflight.reason };
      }
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
      const context: DeletionContext = {
        platform: 'WEBSITE',
        deviceLock: false,
        webPrf: false,
        online: navigator.onLine,
        backupsLeft: null,
      };
      const auth = await this.deletionAdapter.authorize(action, context);
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
      const context: DeletionContext = {
        platform: 'WEBSITE',
        deviceLock: false,
        webPrf: false,
        online: navigator.onLine,
        backupsLeft: null,
      };
      const auth = await this.deletionAdapter.authorize(action, context);
      if (auth.kind === 'refused') {
        return { success: false, error: auth.reason };
      }
      const outcome = await this.deletionAdapter.execute(preflight.plan, auth.grant);
      return { success: outcome.kind === 'ran', error: outcome.kind === 'refused' ? outcome.reason : undefined };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  // ==================== Cleanup ====================

  async disconnect(): Promise<void> {
    this.readyFolder = null;
    this.lastSyncResult = null;
    this.lastBackupId = null;
    this.state.set('Disconnected');
  }
}
