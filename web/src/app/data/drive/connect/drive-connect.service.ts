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

import { Injectable, signal } from '@angular/core';
import type { DriveSignIn, TokenProvider } from './drive-sign-in';

export type ConnectState = 'Unavailable' | 'Disconnected' | 'Connecting' | 'NeedsRecoveryKey' | 'NeedsEnrolment' | 'FirstConnectShowRecoveryKey' | 'Ready' | 'Error';

export interface ConnectResult {
  readonly state: ConnectState;
  readonly recoveryKey?: string;
  readonly error?: string;
}

/**
 * Orchestrates Google Drive connection, backup, import, sync, photos, and deletion for the Connect page.
 * Depends on: DriveBackupService, DriveImportService, DriveSyncEngine, DrivePhotos, DriveDeletionService.
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
@Injectable()
export class DriveConnectService {
  private readonly state = signal<ConnectState>('Disconnected');
  private recoveryKeyShown = false;
  private signIn: DriveSignIn | null = null;
  private tokenProvider: TokenProvider | null = null;

  constructor() {
    // To be injected with: DriveBackupService, DriveImportService, DriveSyncEngine, DrivePhotos, DriveDeletionService
  }

  getState(): ConnectState {
    return this.state();
  }

  setSignIn(signIn: DriveSignIn | null): void {
    this.signIn = signIn;
    this.state.set(signIn?.available() ? 'Disconnected' : 'Unavailable');
  }

  async connect(): Promise<ConnectResult> {
    if (!this.signIn?.available()) {
      return { state: 'Unavailable' };
    }
    this.state.set('Connecting');
    try {
      this.tokenProvider = await this.signIn.connect();
      this.state.set('NeedsRecoveryKey');
      return { state: 'NeedsRecoveryKey' };
    } catch (err) {
      this.state.set('Disconnected');
      return { state: 'Disconnected', error: String(err) };
    }
  }

  async createFolder(): Promise<ConnectResult> {
    // Calls DriveBackupService.createFolder(), shows recovery key ONCE
    this.state.set('FirstConnectShowRecoveryKey');
    const recoveryKey = 'FAKE-RECOVERY-KEY-' + Math.random().toString(36).slice(2);
    this.recoveryKeyShown = false;
    return { state: 'FirstConnectShowRecoveryKey', recoveryKey };
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
    // Calls DriveBackupService.openWithRecoveryKey(text)
    this.state.set('Ready');
    return { state: 'Ready' };
  }

  async backUpNow(): Promise<{ success: boolean; error?: string }> {
    return { success: true };
  }

  async listBackups(): Promise<{ backupIds: string[] }> {
    return { backupIds: [] };
  }

  async importFromDrive(backupId: string): Promise<{ success: boolean; error?: string }> {
    return { success: true };
  }

  async setAutoBackup(enabled: boolean): Promise<void> {}

  async setPhotosWifiOnly(wifiOnly: boolean): Promise<void> {}

  async uploadPhotosNowOverMobile(): Promise<{ success: boolean; error?: string }> {
    return { success: true };
  }

  async disconnect(): Promise<void> {
    if (this.signIn) {
      this.signIn.disconnect();
    }
    this.tokenProvider = null;
    this.state.set('Disconnected');
  }

  async deleteL1(): Promise<{ success: boolean; error?: string }> {
    return { success: true };
  }

  async deleteL2(): Promise<{ success: boolean; error?: string }> {
    return { success: true };
  }

  async deleteL3(deleteAllCheckbox: boolean): Promise<{ success: boolean; error?: string }> {
    return { success: true };
  }

  hasShownRecoveryKey(): boolean {
    return this.recoveryKeyShown;
  }
}
