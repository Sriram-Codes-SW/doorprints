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

import { Injectable, Inject, InjectionToken, Optional, signal } from '@angular/core';
import type { BackupSource } from '../backup/drive-backup-seams';
import { b64, hex, unb64, unhex } from '../../crypto/bytes';
import type { RecoveryKey } from '../../crypto/recovery-key';
import { RecoveryKey as RecoveryKeyClass } from '../../crypto/recovery-key';
import type { DriveBackupAdapter } from './backup-adapter';
import type { DriveSyncAdapter } from './sync-adapter';
import type { DriveDeletionAdapter } from './deletion-adapter';
import type { GoogleConfig } from './google-token-provider';
import { GoogleTokenProvider } from './google-token-provider';
import { GOOGLE_CONFIG } from './drive-connect.providers';
import type { DeletionAction } from '../drive-deletion-rules';
import type { DeletionPlan } from '../drive-deletion';
import type { WebGrant } from '../../device-auth/web-authorizer';
import type { ReadyFolder, DriveBackup, DriveConnection } from '../backup/drive-backup-results';
import { msgOfThrown, problemToMsg } from '../backup/drive-backup-results';
import type { TKey } from '../../../i18n/en';
import type { SyncAdapterStatus } from './sync-adapter';
import type { PhotoSettings, OneOffGrant } from '../photo-network-policy';
import type { DeletionContext } from '../../device-auth/delete-policy';
import type { PolicyDeletionAction } from './deletion-adapter';
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
  /** A dictionary key; the card translates it. Never English, never String(err). */
  readonly error?: TKey;
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

/** One device listed in the opened folder, for the devices card. */
export interface ListedDevice {
  readonly kidHex: string;
  readonly name: string;
  readonly platform: string;
  readonly self: boolean;
}

/** A delete that ran: `finished` is false when files are still left (`left` of `total`). */
export type DeleteRun =
  | { readonly ok: true; readonly finished: boolean; readonly left: number; readonly total: number }
  | { readonly ok: false; readonly reason: string };

/**
 * Orchestrates Google Drive connection, backup, import, sync, photos, and deletion for the Connect page.
 * Provides a single, typed API delegating to adapters with proper error handling and state management.
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
const AUTO_BACKUP_KEY = 'doorprints.drive.autoBackup';
/** A passkey setup in this browser returned no PRF output, and its step list (no secrets): kept so a reload does not forget it. */
const NO_PRF_KEY = 'doorprints.drive.passkeyNoPrf';
const PASSKEY_DETAILS_KEY = 'doorprints.drive.passkeyDetails';

/** The few per-device preferences of the Drive card (a plain on/off, nothing secret). */
export interface DrivePrefs {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}
export const DRIVE_PREFS = new InjectionToken<DrivePrefs>('DRIVE_PREFS');

function browserPrefs(): DrivePrefs {
  try {
    return globalThis.localStorage;
  } catch {
    return { getItem: () => null, setItem: () => undefined };
  }
}

/** Makes the Full backup ZIP of the app's data for one Drive backup run. */
export const DRIVE_BACKUP_SOURCE = new InjectionToken<BackupSource>('DRIVE_BACKUP_SOURCE');

@Injectable()
export class DriveConnectService {
  private readonly state = signal<ConnectState>('Unavailable');
  /** Set when this device was revoked, so the card does not use the new-browser sentence. */
  private readonly blockedNotice = signal<TKey | null>(null);
  /** This browser pinned its own upload, and the recovery key was never painted. Not the key. */
  private readonly keyUnshown = signal(false);
  private recoveryKeyShown = false;
  private readonly isConfigured: boolean;
  private readyFolder: ReadyFolder | null = null;
  private lastSyncResult: SyncAdapterStatus | null = null;
  private lastBackupId: string | null = null;
  private memoryPref = false;
  /** Whether a passkey setup returned 'no-prf' (set true) or a successful registration (set false). */
  private passkeyNoPrfMemory = false;

  constructor(
    @Inject(DRIVE_BACKUP_ADAPTER) private readonly backupAdapter: DriveBackupAdapter,
    @Inject(DRIVE_SYNC_ADAPTER) private readonly syncAdapter: DriveSyncAdapter,
    @Inject(DRIVE_DELETION_ADAPTER) private readonly deletionAdapter: DriveDeletionAdapter,
    @Inject(GOOGLE_CONFIG) private readonly googleConfig: GoogleConfig,
    /** Produces the Full backup ZIP for one run (the same one 'Save a copy' makes); without it Back up now is refused. */
    @Optional() @Inject(DRIVE_BACKUP_SOURCE) private readonly backupSource: BackupSource | null = null,
    /** Where the *Automatic backup* preference lives (tests pass an in-memory one). */
    @Optional() @Inject(DRIVE_PREFS) private readonly prefs: DrivePrefs = browserPrefs(),
    /** Present on the Data page so *Disconnect on all devices* can revoke the in-memory Google token. */
    @Optional() @Inject(GoogleTokenProvider) private readonly tokens: GoogleTokenProvider | null = null,
  ) {
    this.isConfigured = !!googleConfig.clientId;
    this.state.set(this.isConfigured ? 'Disconnected' : 'Unavailable');
  }

  getState(): ConnectState {
    return this.state();
  }

  /** The revoked-device sentence, or null when this card is a new browser. */
  enrolmentNotice(): TKey | null {
    return this.blockedNotice();
  }

  /** True when this connect pinned a list whose recovery key was never on screen. */
  recoveryKeyWasNotShown(): boolean {
    return this.keyUnshown();
  }

  /** The card has said that once. Does not store or reveal a recovery key. */
  acknowledgeRecoveryKeyUnshown(): Promise<void> {
    this.keyUnshown.set(false);
    return this.backupAdapter.clearRecoveryKeyUnshown();
  }

  // ==================== Connection Flow ====================

  async connect(): Promise<ConnectResult> {
    if (!this.isConfigured) {
      return { state: 'Unavailable', error: 'driveConnect.notConfigured' };
    }

    this.state.set('Connecting');
    try {
      const connection = await this.backupAdapter.connect();
      return this.handleConnection(connection);
    } catch (err) {
      return this.fail(err);
    }
  }

  async refresh(): Promise<ConnectResult> {
    return this.connect();
  }

  async createFolder(): Promise<ConnectResult> {
    try {
      const outcome = await this.backupAdapter.createFolder();
      this.recoveryKeyShown = false;

      if (outcome.recoveryKey) {
        this.updateReadyFolderFromConnection(outcome.connection);
        // The heading is only true once the key is in this result. Setting the state earlier paints
        // "Save your recovery key" while the key is still absent.
        this.state.set('FirstConnectShowRecoveryKey');
        return {
          state: 'FirstConnectShowRecoveryKey',
          recoveryKey: outcome.recoveryKey.display,
        };
      }

      return this.handleConnection(outcome.connection);
    } catch (err) {
      return this.fail(err);
    }
  }

  async openWithRecoveryKey(recoveryKeyText: string): Promise<ConnectResult> {
    try {
      // The typed text is parsed strictly (check character, confusable letters, case, hyphens): a typo is refused here.
      let recoveryKey: RecoveryKey;
      try {
        recoveryKey = RecoveryKeyClass.parse(recoveryKeyText);
      } catch {
        return { state: this.getState(), error: 'driveJoin.errorInvalidFormat' };
      }
      const connection = await this.backupAdapter.openWithRecoveryKey(recoveryKey);
      return this.handleConnection(connection);
    } catch (err) {
      return this.fail(err);
    }
  }

  confirmRecoveryKeySaved(): void {
    this.recoveryKeyShown = true;
    this.state.set(this.readyFolder ? 'Ready' : 'NeedsEnrolment');
  }

  skipRecoveryKeyWithWarning(): void {
    this.recoveryKeyShown = true;
    this.state.set(this.readyFolder ? 'Ready' : 'NeedsEnrolment');
  }

  hasShownRecoveryKey(): boolean {
    return this.recoveryKeyShown;
  }

  // ==================== State Management ====================

  private fail(err: unknown): ConnectResult {
    this.state.set('Error');
    return { state: 'Error', error: msgOfThrown(err) };
  }

  private handleConnection(connection: DriveConnection): ConnectResult {
    this.updateReadyFolderFromConnection(connection);
    switch (connection.kind) {
      case 'READY':
        this.blockedNotice.set(null);
        this.keyUnshown.set(connection.recoveryKeyUnshown === true);
        this.state.set('Ready');
        return { state: 'Ready' };
      case 'NEEDS_RECOVERY_KEY':
        this.state.set('NeedsRecoveryKey');
        this.blockedNotice.set(connection.reason === 'REVOKED' ? 'driveProblem.DEVICE_REVOKED' : null);
        return { state: 'NeedsRecoveryKey' };
      case 'NEEDS_ENROLMENT':
        this.blockedNotice.set(null);
        this.state.set('NeedsEnrolment');
        return { state: 'NeedsEnrolment' };
      case 'FOLDER_GONE':
        this.blockedNotice.set(null);
        this.state.set('Disconnected');
        return { state: 'Disconnected', error: 'driveConnect.folderGone' };
      case 'NO_FOLDER':
        this.blockedNotice.set(null);
        this.state.set('Disconnected');
        return { state: 'Disconnected' };
      case 'ERROR': {
        const key = problemToMsg(connection.problem.kind);
        // A wrong key is said on the join form. Moving to Error destroys that form and the parent
        // then shows "Something went wrong."
        const onJoin = this.state() === 'NeedsEnrolment' || this.state() === 'NeedsRecoveryKey';
        // A wrong key stays on the join form. No recovery key does too: the sentence says to join
        // from a device that is already connected, and that card is the enrol card beside the form.
        if (onJoin && (connection.problem.kind === 'WRONG_RECOVERY_KEY' || connection.problem.kind === 'NO_RECOVERY_KEY')) {
          return { state: this.getState(), error: key };
        }
        this.blockedNotice.set(null);
        this.state.set('Error');
        return { state: 'Error', error: key };
      }
      default:
        this.blockedNotice.set(null);
        this.state.set('Error');
        return { state: 'Error', error: 'driveConnect.failed' };
    }
  }

  private updateReadyFolderFromConnection(connection: DriveConnection): void {
    if (connection.kind === 'READY' && connection.folder) {
      this.readyFolder = connection.folder;
    } else {
      this.readyFolder = null;
    }
  }

  // ==================== Backup & Listing ====================

  async listBackups(): Promise<
    | { readonly ok: true; readonly backups: BackupSummary[]; readonly missingNewer: boolean }
    | { readonly ok: false; readonly reason: TKey }
  > {
    if (!this.readyFolder) {
      return { ok: false, reason: 'driveBackups.error.notConnected' };
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
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  // ==================== Import ====================

  async importFromDrive(
    backupId: string,
  ): Promise<
    | { readonly ok: true; readonly file: Blob }
    | { readonly ok: false; readonly reason: TKey }
  > {
    if (!this.readyFolder) {
      return { ok: false, reason: 'driveBackups.error.notConnected' };
    }

    try {
      const listing = await this.backupAdapter.listBackups(this.readyFolder);
      const backup = listing.backups.find((b) => b.fileId === backupId);

      if (!backup) {
        return { ok: false, reason: 'driveBackups.error.backupNotFound' };
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
        return { ok: false, reason: problemToMsg(result.problem.kind) };
      }

      const blob = sink.blob;
      if (!blob) {
        await sink.discard();
        return { ok: false, reason: 'driveBackups.error.retrieveFailed' };
      }

      return { ok: true, file: blob };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  // ==================== Backup Operations ====================

  async backUpNow(): Promise<
    | { readonly ok: true; readonly backup: BackupSummary; readonly needsShrinkConfirmation: boolean; readonly missingNewer: boolean }
    | { readonly ok: false; readonly reason: TKey }
  > {
    if (!this.readyFolder) return { ok: false, reason: 'driveBackups.error.notConnected' };
    if (!this.backupSource) return { ok: false, reason: 'driveConnect.noBackupSource' };
    try {
      const out = await this.backupAdapter.backUpNow(this.readyFolder, this.backupSource);
      if (out.kind === 'failed') return { ok: false, reason: problemToMsg(out.problem.kind) };
      this.lastBackupId = out.backup.fileId;
      return {
        ok: true,
        backup: { id: out.backup.fileId, createdAt: out.backup.createdAt, houses: out.backup.houses, bytes: out.backup.size, name: out.backup.name },
        // Retention held its pruning because the new backup is much smaller: the person is asked before older ones go.
        needsShrinkConfirmation: out.tidy.hold !== null,
        missingNewer: out.missingNewer,
      };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  confirmShrink(backupId: string): Promise<void> {
    return this.backupAdapter.confirmShrink(backupId);
  }

  async lastBackup(): Promise<
    | { readonly ok: true; readonly backup: BackupSummary }
    | { readonly ok: false; readonly reason: TKey }
  > {
    const result = await this.listBackups();
    if (!result.ok) {
      return result;
    }
    if (result.backups.length === 0) {
      return { ok: false, reason: 'driveConnect.noBackups' };
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
    | { readonly ok: true; readonly plan: DeletionPlan }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const result = await this.deletionAdapter.preflight(action);
      if (result.kind === 'refused') {
        return { ok: false, reason: result.reason };
      }
      return { ok: true, plan: result.plan };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
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
      const context = await this.deletionContext();
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
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  async deleteFactor(
    action: DeletionAction,
  ): Promise<'NONE' | 'PASSKEY' | null> {
    try {
      const context = await this.deletionContext();
      const decision = this.deletionAdapter.decide(action, context);
      if (decision.outcome === 'REFUSED') {
        return null;
      }
      const factor = decision.requirements.factor;
      if (factor === 'NONE' || factor === 'PASSKEY') {
        return factor;
      }
      return null;
    } catch {
      return null;
    }
  }

  async authorizeDelete(
    action: DeletionAction,
    operationId: string,
    recoveryKeyText?: string,
  ): Promise<
    | { readonly ok: true; readonly grant: WebGrant }
    | { readonly ok: false; readonly reason: string }
  > {
    try {
      const context = await this.deletionContext();

      // If no recovery key text given, use passkey path as today
      if (!recoveryKeyText) {
        const result = await this.deletionAdapter.authorize(action, context, operationId);
        if (result.kind === 'refused') {
          return { ok: false, reason: result.reason };
        }
        return { ok: true, grant: result.grant };
      }

      // Recovery key text provided: check if it's offered
      if (!(await this.recoveryKeyOffered())) {
        return { ok: false, reason: 'RECOVERY_KEY_NOT_OFFERED' };
      }

      // Parse recovery key
      let recoveryKey: RecoveryKey;
      try {
        recoveryKey = RecoveryKeyClass.parse(recoveryKeyText);
      } catch {
        return { ok: false, reason: 'RECOVERY_KEY_INVALID' };
      }

      // Use adapter's recovery key path
      const result = await this.deletionAdapter.authorizeWithRecoveryKey(action, context, operationId, recoveryKey);
      if (result.kind === 'refused') {
        return { ok: false, reason: result.reason };
      }
      return { ok: true, grant: result.grant };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  async executeDelete(plan: DeletionPlan, grant: WebGrant | null): Promise<DeleteRun> {
    try {
      const outcome = await this.deletionAdapter.execute(plan, grant);
      if (outcome.kind === 'ran') return this.ran(outcome);
      return { ok: false, reason: outcome.reason };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    } finally {
      this.forgetDeleteProof();
    }
  }

  async resumeDelete(grant: WebGrant | null): Promise<DeleteRun> {
    try {
      const outcome = await this.deletionAdapter.resume(grant);
      if (outcome.kind === 'ran') return this.ran(outcome);
      return { ok: false, reason: outcome.reason };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    } finally {
      this.forgetDeleteProof();
    }
  }

  private forgetDeleteProof(): void {
    try {
      this.deletionAdapter.forgetProof?.();
    } catch {
      // Swallow errors
    }
  }

  private ran(outcome: { readonly finished: boolean; readonly total: number; readonly report: { readonly left: readonly string[] } }): DeleteRun {
    return { ok: true, finished: outcome.finished, left: outcome.report.left.length, total: outcome.total };
  }

  // ==================== Passkey ====================

  async passkeyStatus(): Promise<'none' | 'registered' | 'unsupported'> {
    return this.deletionAdapter.passkeyStatus();
  }

  /** Where the last passkey setup stopped (step names and flags only, never a value), for the card's details. */
  async passkeyDetails(): Promise<string | null> {
    try {
      const fresh = (await this.deletionAdapter.lastPasskeyDetails?.()) ?? null;
      if (fresh !== null) {
        this.remember(PASSKEY_DETAILS_KEY, fresh);
        return fresh;
      }
    } catch {
      /* fall through to the saved text */
    }
    return this.recall(PASSKEY_DETAILS_KEY) || null;
  }

  async registerPasskey(): Promise<'registered' | 'unsupported' | 'failed' | 'no-prf' | null> {
    const result = await this.deletionAdapter.registerPasskey();
    if (result === 'no-prf') {
      this.passkeyNoPrfMemory = true;
      this.remember(NO_PRF_KEY, '1');
    } else if (result === 'registered') {
      this.passkeyNoPrfMemory = false;
      this.remember(NO_PRF_KEY, '0');
    }
    return result;
  }

  private get passkeyNoPrfSeen(): boolean {
    return this.passkeyNoPrfMemory || this.recall(NO_PRF_KEY) === '1';
  }

  private remember(key: string, value: string): void {
    try {
      this.prefs.setItem(key, value);
    } catch {
      /* private window: the in-memory flag still holds until the page closes */
    }
  }

  private recall(key: string): string | null {
    try {
      return this.prefs.getItem(key);
    } catch {
      return null;
    }
  }

  /** Whether this browser supports the WebAuthn PRF extension. */
  async passkeyPrfCapability(): Promise<boolean | null> {
    try {
      return (await this.deletionAdapter.prfCapability?.()) ?? null;
    } catch {
      return null;
    }
  }

  /** Whether a built-in platform authenticator is available. */
  async passkeyBuiltIn(): Promise<boolean | null> {
    try {
      return (await this.deletionAdapter.builtInAuthenticator?.()) ?? null;
    } catch {
      return null;
    }
  }

  // ==================== Backward Compatibility ====================

  /** *Automatic backup and sync* (a per-device preference, kept in the browser; off until the person turns it on). */
  autoBackupEnabled(): boolean {
    try {
      const stored = this.prefs.getItem(AUTO_BACKUP_KEY);
      if (stored === '1') return true;
      if (stored === '0') return false;
    } catch {
      /* private window: the in-memory choice below */
    }
    return this.memoryPref;
  }

  async setAutoBackup(enabled: boolean): Promise<void> {
    try {
      this.prefs.setItem(AUTO_BACKUP_KEY, enabled ? '1' : '0');
    } catch {
      // Storage refused (private window): the choice lasts until the page closes, through the in-memory fallback below.
      this.memoryPref = enabled;
    }
  }

  /**
   * Makes a backup if the schedule says one is due (daily after the last success, with back-off after a failure), when
   * *Automatic backup* is on. The screen calls it when the card opens and now and then while it is open: the website
   * cannot run in the background, so "automatic" means "whenever Doorprints is open and a backup is due".
   */
  async runDueBackup(): Promise<{ readonly ran: boolean; readonly reason: string }> {
    if (!this.readyFolder) return { ran: false, reason: 'NOT_READY' };
    if (!(this.autoBackupEnabled() || this.memoryPref)) return { ran: false, reason: 'DISABLED' };
    try {
      const decision = await this.backupAdapter.schedule(true, true);
      if (!decision.backup) return { ran: false, reason: decision.reason };
      const out = await this.backUpNow();
      return { ran: out.ok, reason: out.ok ? decision.reason : out.reason };
    } catch (err) {
      return { ran: false, reason: msgOfThrown(err) };
    }
  }

  /** Whether the recovery key is offered for this browser (docs/15 §10.4a). */
  async recoveryKeyOffered(): Promise<boolean> {
    // Drive must be connected and enrolled
    if (this.state() !== 'Ready') return false;

    const status = await this.passkeyStatus();
    // Passkey registered -> false
    if (status === 'registered') return false;
    // passkeyNoPrfSeen -> true
    if (this.passkeyNoPrfSeen) return true;

    // Otherwise: PRF capability false AND built-in authenticator true
    const prfCapability = await this.passkeyPrfCapability();
    const builtIn = await this.passkeyBuiltIn();

    // An unknown capability null is NOT enough; a real failed registration is needed.
    // builtIn false or null is NOT enough.
    if (prfCapability === false && builtIn === true) return true;

    return false;
  }

  private async deletionContext(): Promise<DeletionContext> {
    const status = await this.passkeyStatus();
    const webPrf = status === 'registered' || (await this.recoveryKeyOffered());
    return {
      platform: 'WEBSITE',
      deviceLock: false,
      webPrf,
      online: typeof navigator !== 'undefined' ? navigator.onLine : true,
      backupsLeft: null,
    };
  }

  // ==================== Devices, enrolment, disconnect-all ====================

  /** This browser's real device public key, for an 8-digit pairing request. */
  devicePublicKey(): Promise<Uint8Array> {
    return this.backupAdapter.devicePublicKey();
  }

  /** Devices in the opened folder. Empty until the folder is ready. */
  async listedDevices(): Promise<readonly ListedDevice[]> {
    if (!this.readyFolder) return [];
    let mine = '';
    try {
      mine = await this.backupAdapter.deviceKidHex();
    } catch {
      mine = '';
    }
    return this.readyFolder.keys.body.devices.map((d) => ({
      kidHex: hex(d.kid),
      name: d.name,
      platform: d.platform,
      self: hex(d.kid) === mine,
    }));
  }

  /** The Google account from Drive's about, or null when Drive cannot say. */
  accountEmail(): Promise<string | null> {
    return this.backupAdapter.accountEmail();
  }

  /**
   * After the 8-digit codes match: L2, then list the new device and return the HPKE wrap of the current folder key
   * for exactly that public key. The newcomer opens the wrap; it is not a key read from Drive.
   */
  async approveJoinedDevice(
    publicKey: Uint8Array,
    name: string,
  ): Promise<
    | { readonly ok: true; readonly wrapEnc: string; readonly wrapCt: string; readonly epoch: number }
    | { readonly ok: false; readonly reason: string }
  > {
    const auth = await this.authorizePolicyAction('APPROVE_DEVICE');
    if (!auth.ok) return auth;
    try {
      const approved = await this.backupAdapter.approveDevice(publicKey, name, 'web');
      if (approved.kind === 'error') return { ok: false, reason: problemToMsg(approved.problem.kind) };
      this.updateReadyFolderFromConnection(approved.connection);
      this.state.set('Ready');
      return {
        ok: true,
        wrapEnc: b64(approved.wrapEnc),
        wrapCt: b64(approved.wrapCt),
        epoch: approved.epoch,
      };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  /**
   * After the connected browser has the new browser's QR text: L2, then a PSK wrap of the folder key.
   * The base-mode wrap is not returned.
   */
  async approveJoinedDevicePsk(
    publicKey: Uint8Array,
    name: string,
    psk: Uint8Array,
  ): Promise<
    | { readonly ok: true; readonly wrapEnc: string; readonly wrapCt: string; readonly epoch: number }
    | { readonly ok: false; readonly reason: string }
  > {
    const auth = await this.authorizePolicyAction('APPROVE_DEVICE');
    if (!auth.ok) return auth;
    try {
      const approved = await this.backupAdapter.approveDevicePsk(publicKey, name, 'web', psk);
      if (approved.kind === 'error') return { ok: false, reason: problemToMsg(approved.problem.kind) };
      this.updateReadyFolderFromConnection(approved.connection);
      this.state.set('Ready');
      return {
        ok: true,
        wrapEnc: b64(approved.wrapEnc),
        wrapCt: b64(approved.wrapCt),
        epoch: approved.epoch,
      };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  /** Open the wrap from the enrolled browser and pin this device. */
  async joinFromWrap(wrapEnc: string, wrapCt: string, epoch: number): Promise<ConnectResult> {
    const enc = unb64(wrapEnc);
    const ct = unb64(wrapCt);
    if (!enc || !ct) return { state: this.getState(), error: 'driveEnrol.badMessage' };
    try {
      const connection = await this.backupAdapter.joinFromWrap(enc, ct, epoch);
      return this.handleConnection(connection);
    } catch (err) {
      return this.fail(err);
    }
  }

  /** Open the PSK wrap from the connected browser and pin this device. */
  async joinFromPsk(wrapEnc: string, wrapCt: string, epoch: number, psk: Uint8Array): Promise<ConnectResult> {
    const enc = unb64(wrapEnc);
    const ct = unb64(wrapCt);
    if (!enc || !ct) return { state: this.getState(), error: 'driveEnrol.badMessage' };
    try {
      const connection = await this.backupAdapter.joinFromPsk(enc, ct, epoch, psk);
      return this.handleConnection(connection);
    } catch (err) {
      return this.fail(err);
    }
  }

  /**
   * Revoke one listed device (L2): a new epoch and a new recovery key, shown once by the caller.
   * Does not sign that device out of Google.
   */
  async revokeListedDevice(
    kidHex: string,
  ): Promise<
    | { readonly ok: true; readonly recoveryKey: string | null }
    | { readonly ok: false; readonly reason: string }
  > {
    const auth = await this.authorizePolicyAction('REVOKE_DEVICE');
    if (!auth.ok) return auth;
    let kid: Uint8Array;
    try {
      kid = unhex(kidHex);
    } catch {
      return { ok: false, reason: 'driveConnect.failed' };
    }
    try {
      const out = await this.backupAdapter.revokeDevice(kid);
      this.updateReadyFolderFromConnection(out.connection);
      if (out.connection.kind !== 'READY' || !out.recoveryKey) {
        const reason = out.connection.kind === 'ERROR' ? problemToMsg(out.connection.problem.kind) : 'driveConnect.failed';
        return { ok: false, reason };
      }
      this.state.set('Ready');
      return { ok: true, recoveryKey: out.recoveryKey.display };
    } catch (err) {
      return { ok: false, reason: msgOfThrown(err) };
    }
  }

  /**
   * *Disconnect on all devices* (L2): revoke Google's grant for the in-memory token, then drop the local session.
   * Files in Drive stay.
   */
  async disconnectAll(): Promise<{ readonly ok: true } | { readonly ok: false; readonly reason: string }> {
    const auth = await this.authorizePolicyAction('DISCONNECT_ALL_DEVICES');
    if (!auth.ok) return auth;
    try {
      await this.tokens?.revokeAccess();
    } catch {
      // The local session still ends. The token is never logged.
    }
    await this.disconnect();
    return { ok: true };
  }

  private async authorizePolicyAction(
    action: PolicyDeletionAction,
  ): Promise<{ readonly ok: true } | { readonly ok: false; readonly reason: string }> {
    const context = await this.deletionContext();
    const result = await this.deletionAdapter.authorizePolicy(action, context);
    if (result.kind === 'refused') return { ok: false, reason: result.reason };
    return { ok: true };
  }

  // ==================== Cleanup ====================

  async disconnect(): Promise<void> {
    this.readyFolder = null;
    this.lastSyncResult = null;
    this.lastBackupId = null;
    this.state.set('Disconnected');
  }
}
