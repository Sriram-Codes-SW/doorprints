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

import { constantTimeEquals, equalBytes, hex } from '../../crypto/bytes';
import { sha256Of } from '../../crypto/crypto-provider';
import type { CryptoProvider } from '../../crypto/crypto-provider';
import { Dpx } from '../../crypto/dpx';
import { HPKE_INFO, WrapAad, kidOf } from '../../crypto/folder-key';
import { QR_PSK_ID, QR_PSK_LEN } from '../../crypto/qr-enrol';
import { Hpke } from '../../crypto/hpke';
import { KeysError, KeysFile, KeysGuard } from '../../crypto/keys-file';
import type { DevicePlatform, KeysWatermarkStore, OpenedKeys } from '../../crypto/keys-file';
import { RecoveryKey } from '../../crypto/recovery-key';
import { BACKUP_FORMATS_READ } from '../../../export/backup-export';
import { DRIVE_LAYOUT, DriveError, FOLDER_MIME, MULTIPART_LIMIT } from '../drive-client';
import type { DriveClient, DriveFile, UploadTarget } from '../drive-client';
import { downloadVerified, ensureFolder, listAll, markComplete, uploadResumable } from '../drive-ops';
import { BackupLister } from './backup-listing';
import { backupName, BACKUP_MIME, BACKUP_PARTIAL_PREFIX } from './backup-names';
import { selectRetention } from './backup-retention';
import type { ShrinkHold } from './backup-retention';
import { decideBackup } from './backup-schedule';
import type { ScheduleDecision } from './backup-schedule';
import { BackupMeta } from './backup-meta';
import { CONTROL_ENCRYPTION, ControlFile } from './control-file';
import type { ControlBody } from './control-file';
import { memoryScratchSpace } from './drive-backup-seams';
import type { BackupSource, DeviceIdentity, DriveDeviceState, DriveStateStore, FolderTrustStores, ScratchSpace } from './drive-backup-seams';
import { DriveProblem } from './drive-backup-results';
import type { BackupListing, BackupOutcome, CreateOutcome, DriveBackup, DriveConnection, ReadyFolder, TidyReport } from './drive-backup-results';
import { DriveImportService } from './drive-import.service';
import type { LockRunner } from './lock-runner';
import { WebLockRunner } from './lock-runner';

export const KIND_KEYS = 'keys';
export const KIND_CONTROL = 'control';
export const KEYS_NAME = 'keys.json';
export const CONTROL_NAME = 'doorprints.json';

/** The enrolled device listed the newcomer and wrapped the folder key for that public key alone. */
export type ApproveDeviceOutcome =
  | {
      readonly kind: 'approved';
      readonly connection: Extract<DriveConnection, { kind: 'READY' }>;
      readonly wrapEnc: Uint8Array;
      readonly wrapCt: Uint8Array;
      readonly epoch: number;
    }
  | { readonly kind: 'error'; readonly problem: DriveProblem };

/** A revoke that finished: the new recovery key is shown once and is not stored. */
export interface RevokeDeviceOutcome {
  readonly connection: DriveConnection;
  readonly recoveryKey: RecoveryKey | null;
}
export const JSON_MIME = 'application/json';
/** A backup ZIP is at most 1 GiB of contents, plus its directory. */
export const MAX_BACKUP_PLAINTEXT = 1024 * 1024 * 1024 + 64 * 1024 * 1024;
/** A partial file that does not verify is put in the bin after a day (docs/15 §1.4 item 2). */
export const STALE_PARTIAL_MS = 24 * 60 * 60 * 1000;
export const MAX_CONFIRMED = 32;

class FolderWithoutKeys extends Error {}

/**
 * Backups in Google Drive (S4b-BL-116; docs/15 §1.4, §5.1, §7 phase 3, §9.3) over a signed-in `DriveClient` and the
 * encryption core; the twin of Kotlin's `DriveBackupService` (the doc comment there is the long form).
 *
 * **The trust rule** (docs/15 §9.9): this device opens nothing from Drive before it is pinned to the folder's key set,
 * and the first pin comes only from `createFolder`, `openWithRecoveryKey` or `openWithFolderKey` (S4b-BL-126's QR
 * enrolment). `connect` never writes (a missing control file excepted), and never adopts a folder it is not pinned to.
 * An empty shell (no key list and no other file, including the bin) is an unfinished first connect: `createFolder`
 * writes the key list there. Any file that is still in Drive is left untouched: no revoke, re-key, wipe, or
 * replacement of a folder that still exists. A remembered folder that Drive no longer has is not that case:
 * `createFolder` drops its pin and makes a new one. A reload after this device's own upload, before the pin,
 * pins that list only when its bytes still match the hash saved before the upload.
 *
 * **A backup** is the app's *Full backup* ZIP, encrypted as `dpx/1`, uploaded `state=partial` with its authenticated
 * metadata under a `partial-` name, then marked complete and renamed only after Drive's `sha256Checksum` equals the one
 * computed here. Then retention prunes to Drive's bin. One run at a time per device (a tab lock).
 */
export class DriveBackupService {
  private readonly keysFile: KeysFile;
  private readonly controlFile: ControlFile;
  private readonly lister: BackupLister;
  private readonly myKid: Uint8Array;
  /** The import side, over the same Drive, crypto and scratch space. */
  readonly imports: DriveImportService;

  constructor(
    private readonly drive: DriveClient,
    private readonly p: CryptoProvider,
    private readonly device: DeviceIdentity,
    private readonly state: DriveStateStore,
    private readonly trust: FolderTrustStores,
    private readonly clock: () => number,
    /** The device's offset from UTC now, in minutes (file names and retention's days). */
    private readonly utcOffsetMinutes: () => number,
    private readonly scratch: ScratchSpace = memoryScratchSpace,
    private readonly locks: LockRunner = new WebLockRunner(),
  ) {
    this.keysFile = new KeysFile(p);
    this.controlFile = new ControlFile(p);
    this.lister = new BackupLister(drive, p);
    this.myKid = kidOf(p, device.key.publicKey);
    this.imports = new DriveImportService(drive, p, scratch);
  }

  // ---- Where the folder stands ----------------------------------------------------------------------------------

  /** Reads where the folder stands for this device. Writes nothing in Drive (a missing control file excepted). */
  connect(): Promise<DriveConnection> {
    return this.connection(async () => {
      const st = await this.state.load();
      const root = await ensureFolder(this.drive, DRIVE_LAYOUT.root, null, false, st.rootId);
      if (!root) return st.rootId != null && st.creatingRootId == null ? { kind: 'FOLDER_GONE' } : { kind: 'NO_FOLDER' };
      if (root.id !== st.rootId) await this.state.save({ ...st, rootId: root.id, keysId: null, controlId: null, backupsId: null });
      const keys = await this.findKind(root.id, KIND_KEYS, root.id === st.rootId ? st.keysId : null);
      if (!keys) {
        // Only an empty shell starts again. A creating mark does not override a file that is still there.
        if (await this.folderIsEmptyShell(root.id)) return { kind: 'NO_FOLDER' };
        return { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
      }
      const bytes = await downloadVerified(this.drive, keys.id);
      const guard = new KeysGuard(this.p, this.trust.keys(root.id));
      if ((await guard.watermark()) == null) {
        // Never adopted silently (docs/15 §9.3). A key list already in the folder is not wiped to start again.
        // This browser's own upload, reloaded before the pin, is finished here. Someone else's list is a join.
        if (st.creatingRootId === root.id) {
          const finished = await this.finishInterruptedCreate(root.id, keys.id, bytes, guard, st.creatingKeysHash);
          if (finished) return finished;
          return { kind: 'ERROR', problem: new DriveProblem('FOLDER_EXISTS') };
        }
        // A file named keys.json is not a key list until it parses. Do not say another device's backups are here.
        try {
          this.keysFile.parse(bytes);
        } catch (e) {
          if (e instanceof KeysError) return { kind: 'ERROR', problem: DriveProblem.of(e) };
          throw e;
        }
        return { kind: 'NEEDS_ENROLMENT', recoveryAvailable: this.recoveryHint(bytes) };
      }
      let opened: OpenedKeys;
      try {
        opened = await this.keysFile.open(bytes, this.device.key, guard);
      } catch (e) {
        if (e instanceof KeysError && (e.kind === 'NOT_ENROLLED' || e.kind === 'REVOKED' || e.kind === 'UNWRAP_FAILED')) {
          return { kind: 'NEEDS_RECOVERY_KEY', recoveryAvailable: this.recoveryHint(bytes), reason: e.kind };
        }
        throw e;
      }
      return this.ready(root.id, keys.id, opened);
    }, true);
  }

  /**
   * The first connect to a Drive with no Doorprints folder, or *Start again* after `FOLDER_GONE`: makes `Doorprints/`,
   * the key set, `doorprints.json` and `Backups/`, then pins this device to it. When the remembered folder is gone,
   * its pin is dropped first; a folder that is still there is not replaced, and nothing else in Drive is deleted.
   * `withRecoveryKey` false is the person's *Skip* after the warning. The recovery key comes back to be shown
   * **once**; it is never stored. The pin is the last step, so a run that stops before a key list is in the folder
   * leaves nothing this device trusts.
   * A folder with no key list and no other file is that unfinished create: this writes the key list into it.
   * A folder that still holds any file, including a key list, is not replaced, and nothing in it is binned.
   *
   * Wraps folder creation in an exclusive Web Lock to prevent two tabs from creating duplicate root folders.
   * The second tab, after acquiring the lock, re-reads Drive state and finds the folder the first tab created.
   */
  async createFolder(withRecoveryKey: boolean): Promise<CreateOutcome> {
    let recovery = null as RecoveryKey | null;
    const connection = await this.locks.request('doorprints-drive-create', async () =>
      this.connection(async () => {
        // Re-read state after lock acquire: the first tab may have created the folder while we waited.
        const st = await this.state.load();
        const existing = await ensureFolder(this.drive, DRIVE_LAYOUT.root, null, false, st.rootId);
        // Remembered, and Drive has no Doorprints folder. Drop that pin before a new folder is made.
        // A folder that is still here takes the branches below and is not replaced.
        if (!existing) await this.forgetGoneFolder(st);

        // If a folder exists and it's not one we're currently creating, check if it's already pinned by us.
        // This can happen when a second tab calls createFolder while the first tab is creating;
        // after the first tab finishes, the second tab (holding the lock) will find the folder pinned.
        // An empty shell (no key list, no other file) is finished here even when the local creating mark was lost.
        let finishEmptyShell = false;
        if (existing && st.creatingRootId !== existing.id) {
          const guard = new KeysGuard(this.p, this.trust.keys(existing.id));
          const keys = await this.findKind(existing.id, KIND_KEYS, st.keysId);
          if ((await guard.watermark()) != null && keys) {
            // We're already pinned to this folder (created in this tab or another).
            const bytes = await downloadVerified(this.drive, keys.id);
            const opened = await this.keysFile.open(bytes, this.device.key, guard);
            return this.ready(existing.id, keys.id, opened);
          }
          if (keys) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_EXISTS') };
          // No key list. Finish only a folder that holds nothing else; never trash what might be a backup.
          if (!(await this.folderIsEmptyShell(existing.id))) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
          finishEmptyShell = true;
        }

        const root = existing ?? (await this.drive.createFile({ name: DRIVE_LAYOUT.root.name, mimeType: FOLDER_MIME, parents: [], appProperties: DRIVE_LAYOUT.root.appProperties }));
        if (finishEmptyShell) {
          // Still inside the lock, before any pin is dropped and before creatingRootId is saved.
          if (!(await this.folderIsEmptyShell(root.id))) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
          if (!(await this.releaseShellPins(root.id))) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
        }
        // The creating mark used to bin this device's own keys and write a new list. A file that is already
        // there, including one this device uploaded, stays. Only an empty shell is written.
        if (existing && !finishEmptyShell && !(await this.folderIsEmptyShell(root.id))) return this.refuseOccupied(root.id, st.keysId);
        if (existing && !finishEmptyShell && (await new KeysGuard(this.p, this.trust.keys(root.id)).watermark()) != null) {
          // Drop a pin from a key list that is gone before the guard below is built, and only while the shell is empty.
          if (!(await this.folderIsEmptyShell(root.id)) || !(await this.releaseShellPins(root.id))) {
            return this.refuseOccupied(root.id, st.keysId);
          }
        }
        // Leave creatingKeysHash until the new list is about to be uploaded. Clearing it here would forget
        // the bytes of an interrupted create when this call then refuses a folder that still holds a file.
        await this.state.save({ ...st, rootId: root.id, creatingRootId: root.id, keysId: null, controlId: null, backupsId: null });
        const guard = new KeysGuard(this.p, this.trust.keys(root.id));
        if (!finishEmptyShell && (await guard.watermark()) != null) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_EXISTS') };
        const now = this.clock();
        const key = withRecoveryKey ? RecoveryKey.generate(this.p) : null;
        const written = await this.keysFile.createFirstDevice({ publicKey: this.device.key.publicKey, name: this.device.name, platform: this.device.platform }, key, now);
        // Key generation takes long enough for another device to put a file in the shell.
        if (existing && !(await this.folderIsEmptyShell(root.id))) return this.refuseOccupied(root.id, null);
        // Saved before the upload, so a later reload can tell this list from one wrapped to the same public key.
        const creatingKeysHash = sha256Of(this.p, written.bytes);
        await this.state.save({ ...(await this.state.load()), creatingKeysHash });
        const keysId = await this.uploadSmall(root.id, KEYS_NAME, KIND_KEYS, written.bytes);
        const control = await this.controlFile.create(written.opened, now);
        const controlId = await this.uploadSmall(root.id, CONTROL_NAME, KIND_CONTROL, control.bytes);
        const backups = (await ensureFolder(this.drive, DRIVE_LAYOUT.backups, root.id, true))!;
        await this.controlFile.accept(control.body, this.trust.control(root.id));
        await guard.pinCreated(written);
        await this.state.save({
          ...(await this.state.load()),
          rootId: root.id, keysId, controlId, backupsId: backups.id, creatingRootId: null, creatingKeysHash: null,
          deviceId: st.deviceId ?? this.newDeviceId(), newestSeenAt: null, lastBackupId: null,
          recoveryKeyUnshown: false,
        });
        recovery = key;
        return { kind: 'READY', folder: { rootId: root.id, keysId, controlId, backupsId: backups.id, keys: written.opened, control: control.body } };
      }));
    return { connection, recoveryKey: connection.kind === 'READY' ? recovery : null };
  }

  /**
   * Opens the folder with the typed recovery key (docs/15 §9.4, §9.5 iii): the recovery anchor makes the first pin (or
   * checks the one there is), then this device lists itself in `keys.json` (approved by the recovery key, so its
   * backups are accepted by the others) unless it is listed already.
   */
  openWithRecoveryKey(recoveryKey: RecoveryKey): Promise<DriveConnection> {
    return this.connection(async () => {
      const located = await this.locateKeys();
      if (!located) return { kind: 'NO_FOLDER' };
      const { rootId, keys, bytes } = located;
      const guard = new KeysGuard(this.p, this.trust.keys(rootId));
      let opened = await this.keysFile.openWithRecovery(bytes, recoveryKey, guard);
      if (!opened.body.devices.some((d) => hex(d.kid) === hex(this.myKid))) {
        const approver = opened.body.recovery!.kid;
        const written = await this.keysFile.addDevice(opened, approver, { publicKey: this.device.key.publicKey, name: this.device.name, platform: this.device.platform }, this.clock());
        await this.writeKeys(keys.id, written.bytes);
        await guard.acceptWritten(written);
        opened = written.opened;
      }
      return this.ready(rootId, keys.id, opened);
    });
  }

  /** This device's public key, the one a pairing request must carry (not a key made up on the screen). */
  devicePublicKey(): Uint8Array {
    return this.device.key.publicKey;
  }

  /** This device's key id, so a screen can mark its own row. */
  deviceKid(): Uint8Array {
    return this.myKid;
  }

  /**
   * After the 8-digit codes match (docs/15 §9.5 i): re-read `keys.json`, list the new device, and return the wrap of
   * the current folder key made for exactly that public key. The newcomer opens that wrap (it came over the pairing
   * messages, not from a file they chose in Drive) and pins with it. A QR enrolment uses {@link approveDevicePsk}.
   */
  async approveDevice(publicKey: Uint8Array, name: string, platform: DevicePlatform): Promise<ApproveDeviceOutcome> {
    try {
      const located = await this.locateKeys();
      if (!located) return { kind: 'error', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
      const guard = new KeysGuard(this.p, this.trust.keys(located.rootId));
      const opened = await this.keysFile.open(located.bytes, this.device.key, guard);
      const written = await this.keysFile.addDevice(
        opened,
        this.myKid,
        { publicKey, name, platform },
        this.clock(),
      );
      await this.writeKeys(located.keys.id, written.bytes);
      await guard.acceptWritten(written);
      const kid = kidOf(this.p, publicKey);
      const entry = written.opened.body.devices.find((d) => equalBytes(d.kid, kid));
      if (!entry) return { kind: 'error', problem: new DriveProblem('KEYS_UNREADABLE') };
      const connection = await this.ready(located.rootId, located.keys.id, written.opened);
      if (connection.kind !== 'READY') {
        return { kind: 'error', problem: connection.kind === 'ERROR' ? connection.problem : new DriveProblem('DRIVE') };
      }
      return {
        kind: 'approved',
        connection,
        wrapEnc: entry.wrap.enc,
        wrapCt: entry.wrap.ct,
        epoch: written.opened.epoch,
      };
    } catch (e) {
      return { kind: 'error', problem: DriveProblem.of(e) };
    }
  }

  /**
   * After the connected browser has seen `pk_new ‖ s` (the QR or the pasted code): list the device as {@link approveDevice}
   * does, then return a second wrap of the same folder key in HPKE PSK mode. The newcomer accepts only that wrap.
   * The base-mode wrap stays in `keys.json` and is not handed back.
   */
  async approveDevicePsk(publicKey: Uint8Array, name: string, platform: DevicePlatform, psk: Uint8Array): Promise<ApproveDeviceOutcome> {
    if (psk.length !== QR_PSK_LEN) return { kind: 'error', problem: new DriveProblem('KEYS_UNREADABLE') };
    const approved = await this.approveDevice(publicKey, name, platform);
    if (approved.kind !== 'approved') return approved;
    const kid = kidOf(this.p, publicKey);
    const folderKey = approved.connection.folder.keys.currentFolderKey();
    try {
      const sealed = await new Hpke(this.p).sealPsk(
        publicKey,
        HPKE_INFO,
        WrapAad.folderKey(approved.epoch, kid),
        folderKey,
        psk,
        QR_PSK_ID,
      );
      return { kind: 'approved', connection: approved.connection, wrapEnc: sealed.enc, wrapCt: sealed.ciphertext, epoch: approved.epoch };
    } catch (e) {
      return { kind: 'error', problem: DriveProblem.of(e) };
    } finally {
      folderKey.fill(0);
    }
  }

  /**
   * The newcomer's first pin from the wrap the enrolled device handed over after the codes matched. The wrap is opened
   * here; the folder key is never taken from a Drive download alone.
   */
  async joinFromWrap(enc: Uint8Array, ct: Uint8Array, epoch: number): Promise<DriveConnection> {
    const hpke = new Hpke(this.p);
    let folderKey: Uint8Array;
    try {
      folderKey = await hpke.open(enc, this.device.key, HPKE_INFO, WrapAad.folderKey(epoch, this.myKid), ct);
    } catch (e) {
      return { kind: 'ERROR', problem: DriveProblem.of(e) };
    }
    try {
      return await this.openWithFolderKey(folderKey);
    } finally {
      folderKey.fill(0);
    }
  }

  /**
   * The newcomer's first pin from the PSK wrap. A wrong PSK, or a wrap made for another public key, does not open.
   */
  async joinFromPsk(enc: Uint8Array, ct: Uint8Array, epoch: number, psk: Uint8Array): Promise<DriveConnection> {
    if (psk.length !== QR_PSK_LEN) return { kind: 'ERROR', problem: new DriveProblem('KEYS_UNREADABLE') };
    const hpke = new Hpke(this.p);
    let folderKey: Uint8Array;
    try {
      folderKey = await hpke.openPsk(enc, this.device.key, HPKE_INFO, WrapAad.folderKey(epoch, this.myKid), ct, psk, QR_PSK_ID);
    } catch (e) {
      return { kind: 'ERROR', problem: DriveProblem.of(e) };
    }
    try {
      return await this.openWithFolderKey(folderKey);
    } finally {
      folderKey.fill(0);
    }
  }

  /**
   * Revoke one listed device (docs/15 §9.5 iv): a new epoch and a new recovery key, shown once. Does not revoke
   * Google's grant. A keys error is reported; it does not revoke, re-key or wipe by itself.
   */
  async revokeDevice(kid: Uint8Array): Promise<RevokeDeviceOutcome> {
    const recovery = RecoveryKey.generate(this.p);
    const connection = await this.connection(async () => {
      const located = await this.locateKeys();
      if (!located) return { kind: 'NO_FOLDER' };
      const guard = new KeysGuard(this.p, this.trust.keys(located.rootId));
      const opened = await this.keysFile.open(located.bytes, this.device.key, guard);
      const written = await this.keysFile.newEpoch(opened, this.clock(), { revokeKid: kid, newRecovery: recovery });
      await this.writeKeys(located.keys.id, written.bytes);
      await guard.acceptWritten(written);
      return this.ready(located.rootId, located.keys.id, written.opened);
    });
    return { connection, recoveryKey: connection.kind === 'READY' ? recovery : null };
  }

  /**
   * The first pin from a folder key received over an authenticated channel (S4b-BL-126's QR enrolment). Never call it
   * with a key read from Drive.
   */
  openWithFolderKey(trustedFolderKey: Uint8Array): Promise<DriveConnection> {
    return this.connection(async () => {
      const located = await this.locateKeys();
      if (!located) return { kind: 'NO_FOLDER' };
      const opened = await this.keysFile.openFirstPin(located.bytes, this.device.key, new KeysGuard(this.p, this.trust.keys(located.rootId)), trustedFolderKey);
      return this.ready(located.rootId, located.keys.id, opened);
    });
  }

  // ---- Backups ---------------------------------------------------------------------------------------------------

  /**
   * One backup (*Back up to Google Drive now*, or the schedule's daily run): encrypt, upload `partial`, check Drive's
   * checksum, mark `complete`, then tidy (heal unfinished uploads, bin stale partial files, retention).
   */
  async backUp(folder: ReadyFolder, source: BackupSource): Promise<BackupOutcome> {
    const now = this.clock();
    let st: DriveDeviceState = await this.state.load();
    st = { ...st, deviceId: st.deviceId ?? this.newDeviceId(), lastAttemptAt: now };
    await this.state.save(st);
    let done: DriveFile;
    let backupsId: string;
    let meta: BackupMeta;
    try {
      backupsId = (await ensureFolder(this.drive, DRIVE_LAYOUT.backups, folder.rootId, true, st.backupsId ?? folder.backupsId))!.id;
      let payload;
      try {
        payload = await source();
      } catch {
        return await this.failed(new DriveProblem('SOURCE_FAILED'));
      }
      if (!BACKUP_FORMATS_READ.includes(payload.format)) {
        payload.close?.();
        return await this.failed(new DriveProblem('SOURCE_FAILED'));
      }
      const file = this.scratch.create();
      try {
        const wrap = await folder.keys.contentWrapAes();
        if (!wrap) return await this.failed(new DriveProblem('KEYS_UNREADABLE'));
        let mac: Uint8Array;
        let written;
        try {
          written = await new Dpx(this.p).encryptWithAes(wrap, folder.keys.epoch, this.myKid, payload.format, payload.source, (b) => file.write(b), MAX_BACKUP_PLAINTEXT);
        } finally {
          payload.close?.();
        }
        meta = new BackupMeta(now, payload.houses, folder.keys.epoch, this.myKid, written.ciphertextSha256);
        mac = await folder.keys.hmacUnder(folder.keys.epoch, 'doorprints/dpx1/backup-meta', meta.macInput());
        const name = backupName(now, this.utcOffsetMinutes());
        const target: UploadTarget = {
          kind: 'new',
          file: { name: BACKUP_PARTIAL_PREFIX + name, mimeType: BACKUP_MIME, parents: [backupsId], appProperties: meta.appProperties(mac, st.deviceId!) },
        };
        const uploaded =
          file.size <= MULTIPART_LIMIT
            ? await this.drive.upload(target, await file.read(0, file.size))
            : await uploadResumable(this.drive, target, file.size, (offset, length) => file.read(offset, length));
        try {
          done = await markComplete(this.drive, uploaded.id, hex(meta.ciphertextSha256), name, uploaded);
        } catch (e) {
          // Drive holds other bytes than were sent: never a backup; put it in the bin now (else the clean-up does).
          if (e instanceof DriveError && e.kind === 'CORRUPT') await this.trashQuietly(uploaded.id);
          throw e;
        }
      } finally {
        await file.delete();
      }
    } catch (e) {
      return this.failed(DriveProblem.of(e));
    }
    st = {
      ...(await this.state.load()),
      backupsId, lastBackupId: done.id, lastSuccessAt: now, lastFailure: null, newestSeenAt: Math.max(st.newestSeenAt ?? 0, now),
    };
    await this.state.save(st);
    const backup: DriveBackup = {
      fileId: done.id, name: done.name, createdAt: now, houses: meta.houses, epoch: meta.epoch, writerKid: this.myKid,
      sha256: hex(meta.ciphertextSha256), size: done.size,
    };
    const { report, missing } = await this.tidy(folder, backupsId, st);
    return { kind: 'done', backup, tidy: report, missingNewer: missing };
  }

  /** The backups in the folder, newest first, each checked (*Backups in Google Drive*, *Import a backup*). */
  async listBackups(folder: ReadyFolder): Promise<BackupListing> {
    const st = await this.state.load();
    const backupsId = st.backupsId ?? folder.backupsId ?? (await ensureFolder(this.drive, DRIVE_LAYOUT.backups, folder.rootId, false))?.id ?? null;
    const listing = await this.lister.list(folder, backupsId, st.lastBackupId ? [st.lastBackupId] : [], st.newestSeenAt);
    const newest = listing.backups[0]?.createdAt;
    if (newest != null && newest > (st.newestSeenAt ?? -Infinity)) await this.state.save({ ...(await this.state.load()), newestSeenAt: newest, backupsId });
    return listing;
  }

  /** The person confirmed the drop the shrink guard held on `backupId`; pruning goes on at the next run. */
  async confirmShrink(backupId: string): Promise<void> {
    const st = await this.state.load();
    const confirmedDrops = [...new Set([...st.confirmedDrops, backupId])].slice(-MAX_CONFIRMED);
    await this.state.save({ ...st, confirmedDrops });
  }

  /** Downloads and opens the newest backup (once a week, `ScheduleDecision.verify`); null when it reads. */
  async verifyNewest(folder: ReadyFolder): Promise<DriveProblem | null> {
    let newest: DriveBackup | undefined;
    try {
      newest = (await this.listBackups(folder)).backups[0];
    } catch (e) {
      return DriveProblem.of(e);
    }
    if (!newest) return null;
    const r = await this.imports.download(folder, newest, { write: () => undefined, discard: () => undefined });
    if (r.kind === 'verified') {
      await this.state.save({ ...(await this.state.load()), lastVerifyAt: this.clock() });
      return null;
    }
    return r.problem;
  }

  /** The schedule's decision for now, from this device's state. */
  async schedule(enabled: boolean, ready: boolean, manual = false): Promise<ScheduleDecision> {
    const st = await this.state.load();
    return decideBackup({
      now: this.clock(), enabled, ready, manual, lastSuccessAt: st.lastSuccessAt, lastAttemptAt: st.lastAttemptAt,
      lastFailure: st.lastFailure, lastVerifyAt: st.lastVerifyAt,
    });
  }

  // ---- Inside ----------------------------------------------------------------------------------------------------

  private async failed(problem: DriveProblem): Promise<BackupOutcome> {
    await this.state.save({ ...(await this.state.load()), lastFailure: problem.scheduleFailure });
    return { kind: 'failed', problem };
  }

  /** Heals unfinished uploads, bins stale partial files and duplicates, then retention. Its failure is reported only. */
  private async tidy(folder: ReadyFolder, backupsId: string, st: DriveDeviceState): Promise<{ report: TidyReport; missing: boolean }> {
    const trashed: string[] = [];
    const completed: string[] = [];
    let hold: ShrinkHold | null = null;
    let missing = false;
    let problem: DriveProblem | null = null;
    try {
      const listing = await this.lister.list(folder, backupsId, st.lastBackupId ? [st.lastBackupId] : [], st.newestSeenAt);
      missing = listing.missingNewer;
      const backups = [...listing.backups];
      for (const u of listing.unfinished) {
        // Verified over Drive's checksum, so it is exactly what its writer uploaded: finish its last step.
        await this.drive.updateMetadata(u.fileId, {
          name: backupName(u.createdAt, this.utcOffsetMinutes()),
          appProperties: { [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete },
        });
        completed.push(u.fileId);
        backups.push(u);
      }
      const now = this.clock();
      for (const j of listing.junk) if (now - j.createdTime >= STALE_PARTIAL_MS && (await this.trashQuietly(j.id))) trashed.push(j.id);
      for (const d of listing.duplicates) if (await this.trashQuietly(d)) trashed.push(d);
      const result = selectRetention(backups.map((b) => ({ id: b.fileId, createdAt: b.createdAt, houses: b.houses })), this.utcOffsetMinutes(), new Set(st.confirmedDrops));
      hold = result.hold;
      for (const id of result.prune) if (await this.trashQuietly(id)) trashed.push(id);
    } catch (e) {
      problem = DriveProblem.of(e);
    }
    return { report: { trashed, completed, hold, problem }, missing };
  }

  /** Moves `fileId` to Drive's bin; one already gone counts as done. Other failures propagate. */
  private async trashQuietly(fileId: string): Promise<boolean> {
    try {
      await this.drive.trash(fileId);
    } catch (e) {
      if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
    }
    return true;
  }

  private async ready(rootId: string, keysId: string, opened: OpenedKeys): Promise<DriveConnection> {
    const st = await this.state.load();
    const known = st.rootId === rootId ? st.controlId : null;
    const controlStore = this.trust.control(rootId);
    const controlMeta = await this.findKind(rootId, KIND_CONTROL, known);
    let controlId: string;
    let body: ControlBody;
    if (!controlMeta) {
      // Deleted by hand (or never written): this pinned device writes it again, keeping what it knew of it.
      const seen = await controlStore.load();
      const base: ControlBody = { revision: seen?.revision ?? 0, epoch: opened.epoch, createdAt: this.clock(), encryption: CONTROL_ENCRYPTION, backupsDeletedAt: seen?.backupsDeletedAt ?? null };
      const written = await this.controlFile.next(opened, base);
      controlId = await this.uploadSmall(rootId, CONTROL_NAME, KIND_CONTROL, written.bytes);
      await this.controlFile.accept(written.body, controlStore);
      body = written.body;
    } else {
      controlId = controlMeta.id;
      body = await this.controlFile.open(await downloadVerified(this.drive, controlMeta.id), opened, controlStore);
    }
    const backupsId = (st.rootId === rootId ? st.backupsId : null) ?? (await ensureFolder(this.drive, DRIVE_LAYOUT.backups, rootId, false))?.id ?? null;
    const loaded = await this.state.load();
    const recoveryKeyUnshown = loaded.recoveryKeyUnshown === true;
    await this.state.save({
      ...loaded,
      rootId, keysId, controlId, backupsId, creatingRootId: null, creatingKeysHash: null, deviceId: st.deviceId ?? this.newDeviceId(),
    });
    return {
      kind: 'READY',
      folder: { rootId, keysId, controlId, backupsId, keys: opened, control: body },
      ...(recoveryKeyUnshown ? { recoveryKeyUnshown: true } : {}),
    };
  }

  private async locateKeys(): Promise<{ rootId: string; keys: DriveFile; bytes: Uint8Array } | null> {
    const st = await this.state.load();
    const root = await ensureFolder(this.drive, DRIVE_LAYOUT.root, null, false, st.rootId);
    if (!root) return null;
    const keys = await this.findKind(root.id, KIND_KEYS, root.id === st.rootId ? st.keysId : null);
    if (!keys) throw new FolderWithoutKeys();
    return { rootId: root.id, keys, bytes: await downloadVerified(this.drive, keys.id) };
  }

  /** Verify a recovery key against Drive's keys.json without changing anything (docs/15 §10.4a). */
  async verifyRecoveryKey(recoveryKey: RecoveryKey): Promise<boolean> {
    let located;
    try {
      located = await this.locateKeys();
    } catch (e) {
      if (e instanceof FolderWithoutKeys) return false;
      throw e;
    }
    if (!located) return false;
    const pinned = this.trust.keys(located.rootId);
    // A read-only check must not create the first pin, so without one there is nothing to check against.
    if ((await pinned.load()) === null) return false;
    // Nor may it move one: another device may have written a newer list since this browser pinned, and a normal open would
    // advance the pin to it (a verified forward move). This guard reads the pin and verifies against it, and its
    // compare-and-set pretends to succeed without writing, so the pin stays exactly as it was.
    const readOnly: KeysWatermarkStore = { load: () => pinned.load(), compareAndSet: async () => true };
    try {
      await this.keysFile.openWithRecovery(located.bytes, recoveryKey, new KeysGuard(this.p, readOnly));
      return true;
    } catch {
      return false;
    }
  }

  /** A file of `kind` in the folder: the remembered id first (a listing may lag), else the oldest listed. */
  private async findKind(rootId: string, kind: string, knownId: string | null): Promise<DriveFile | null> {
    if (knownId != null) {
      const known = await this.fileIfPresent(knownId);
      if (known && this.isKindFile(known, rootId, kind)) return known;
    }
    const page = await this.drive.list({ parentId: rootId, appProperties: { [DRIVE_LAYOUT.kind]: kind } });
    const listed = page.files.find((f) => f.mimeType !== FOLDER_MIME);
    if (listed) return listed;
    // An appProperties query can lag behind the parent listing (docs/15 §7.1). A name match is only used to
    // refuse a folder, never to trust its bytes: the opener still checks the MAC.
    const name = kind === KIND_KEYS ? KEYS_NAME : kind === KIND_CONTROL ? CONTROL_NAME : null;
    if (name != null) {
      for (const f of await listAll(this.drive, { parentId: rootId })) {
        if (this.isKindFile(f, rootId, kind)) return f;
      }
    }
    if (page.incompleteSearch) throw new DriveError('SERVER', 0, null, 'incompleteSearch');
    return null;
  }

  private async fileIfPresent(fileId: string): Promise<DriveFile | null> {
    try {
      return await this.drive.getFile(fileId);
    } catch (e) {
      if (e instanceof DriveError && e.kind === 'NOT_FOUND') return null;
      throw e;
    }
  }

  /** The kind marker, or the canonical name. Empty `parents` still counts: some answers omit them. A file in another folder does not. */
  private isKindFile(f: DriveFile, rootId: string, kind: string): boolean {
    if (f.trashed || f.mimeType === FOLDER_MIME) return false;
    const name = kind === KIND_KEYS ? KEYS_NAME : kind === KIND_CONTROL ? CONTROL_NAME : null;
    const marked = f.appProperties[DRIVE_LAYOUT.kind] === kind || (name != null && f.name === name);
    if (!marked) return false;
    return f.parents.length === 0 || f.parents.includes(rootId);
  }

  /**
   * A folder that is not an empty shell: a key list is `FOLDER_EXISTS` (it is not missing); any other file is
   * `FOLDER_WITHOUT_KEYS`. Neither path bins or overwrites a file.
   */
  private async refuseOccupied(rootId: string, knownKeysId: string | null): Promise<DriveConnection> {
    const keys = await this.findKind(rootId, KIND_KEYS, knownKeysId);
    return { kind: 'ERROR', problem: new DriveProblem(keys ? 'FOLDER_EXISTS' : 'FOLDER_WITHOUT_KEYS') };
  }

  /**
   * No key list and no other file, including the bin and files inside subfolders. An incomplete listing is not empty.
   * More than a shallow empty tree is not empty either: this must not miss a backup.
   */
  private async folderIsEmptyShell(rootId: string): Promise<boolean> {
    const pending = [rootId];
    const seenIds = new Set<string>();
    let seen = 0;
    while (pending.length > 0) {
      const id = pending.pop()!;
      if (seenIds.has(id)) continue;
      seenIds.add(id);
      for (const f of await this.listEveryChild(id)) {
        seen++;
        if (seen > 40) return false;
        if (f.mimeType !== FOLDER_MIME) return false;
        pending.push(f.id);
      }
    }
    return true;
  }

  /**
   * The id this device remembers is not a live Doorprints folder. Drop those pins so the new folder can be pinned.
   * Does not touch Drive. Called only after `ensureFolder` found nothing.
   */
  private async forgetGoneFolder(st: DriveDeviceState): Promise<void> {
    const ids = new Set<string>();
    if (st.rootId != null) ids.add(st.rootId);
    if (st.creatingRootId != null) ids.add(st.creatingRootId);
    for (const id of ids) await this.trust.forgetShell?.(id);
  }

  /**
   * `keys.json` is already in the folder this browser was creating, and the pin was never saved.
   * Pin and finish only when `committed` is the hash saved before the upload and Drive still has those bytes.
   * A missing control file is written. A file that is already there is not replaced.
   * A list this browser is not in is a join, and is not pinned. Anything else keeps the old refusal.
   */
  private async finishInterruptedCreate(
    rootId: string,
    keysId: string,
    bytes: Uint8Array,
    guard: KeysGuard,
    committed: Uint8Array | null,
  ): Promise<DriveConnection | null> {
    if (committed == null || !constantTimeEquals(sha256Of(this.p, bytes), committed)) {
      return this.outcomeWithoutPin(rootId, keysId, bytes, guard);
    }
    try {
      const opened = await this.keysFile.finishOwnCreate(bytes, this.device.key, guard, committed);
      const connected = await this.ready(rootId, keysId, opened);
      if (connected.kind !== 'READY') return connected;
      // The recovery key lived only in the call that died. Remember that it was not shown. Do not store the key.
      const st = await this.state.load();
      await this.state.save({ ...st, recoveryKeyUnshown: true });
      return { ...connected, recoveryKeyUnshown: true };
    } catch (e) {
      if (!(e instanceof KeysError)) throw e;
      if (e.kind === 'NOT_ENROLLED' || e.kind === 'UNWRAP_FAILED') {
        return { kind: 'NEEDS_ENROLMENT', recoveryAvailable: this.recoveryHint(bytes) };
      }
      if (e.kind === 'REVOKED') {
        return { kind: 'NEEDS_RECOVERY_KEY', recoveryAvailable: this.recoveryHint(bytes), reason: 'REVOKED' };
      }
      return null;
    }
  }

  /**
   * Classify a list this device must not pin. `open` uses the existing pin and refuses to make one.
   * Not enrolled: join. Revoked: the recovery key. A list this device could unwrap, or a file that is not a list,
   * returns null so the caller keeps the occupied-folder refusal.
   */
  private async outcomeWithoutPin(
    rootId: string,
    keysId: string,
    bytes: Uint8Array,
    guard: KeysGuard,
  ): Promise<DriveConnection | null> {
    try {
      const opened = await this.keysFile.open(bytes, this.device.key, guard);
      return await this.ready(rootId, keysId, opened);
    } catch (e) {
      if (!(e instanceof KeysError)) throw e;
      if (e.kind === 'NOT_ENROLLED' || e.kind === 'UNWRAP_FAILED') {
        return { kind: 'NEEDS_ENROLMENT', recoveryAvailable: this.recoveryHint(bytes) };
      }
      if (e.kind === 'REVOKED') {
        return { kind: 'NEEDS_RECOVERY_KEY', recoveryAvailable: this.recoveryHint(bytes), reason: 'REVOKED' };
      }
      return null;
    }
  }

  /** Drop pins only after the folder has been proved empty, so `pinCreated` can start a new key set. */
  private async releaseShellPins(rootId: string): Promise<boolean> {
    await this.trust.forgetShell?.(rootId);
    const keysPin = await new KeysGuard(this.p, this.trust.keys(rootId)).watermark();
    const controlPin = await this.trust.control(rootId).load();
    return keysPin == null && controlPin == null;
  }

  private async listEveryChild(parentId: string): Promise<DriveFile[]> {
    const out: DriveFile[] = [];
    let token: string | null = null;
    for (let i = 0; i < 20; i++) {
      const page = await this.drive.list({ parentId, trashed: null }, token, 100);
      if (page.incompleteSearch) throw new DriveError('SERVER', 0, null, 'incompleteSearch');
      out.push(...page.files);
      token = page.nextPageToken;
      if (token == null) return out;
    }
    throw new DriveError('SERVER', 0, null, 'incompleteSearch');
  }

  /** Uploads a small file of `kind` into the folder and reads it back: Drive must hold exactly `bytes`. */
  private async uploadSmall(rootId: string, name: string, kind: string, bytes: Uint8Array): Promise<string> {
    const f = await this.drive.upload({ kind: 'new', file: { name, mimeType: JSON_MIME, parents: [rootId], appProperties: { [DRIVE_LAYOUT.kind]: kind } } }, bytes);
    await this.readBack(f.id, bytes);
    return f.id;
  }

  private async writeKeys(keysId: string, bytes: Uint8Array): Promise<void> {
    await this.drive.upload({ kind: 'existing', fileId: keysId, mimeType: JSON_MIME }, bytes);
    await this.readBack(keysId, bytes);
  }

  private async readBack(fileId: string, bytes: Uint8Array): Promise<void> {
    const got = await downloadVerified(this.drive, fileId);
    if (!equalBytes(got, bytes)) throw new DriveError('CORRUPT', 0, null, 'readBack');
  }

  private recoveryHint(bytes: Uint8Array): boolean {
    try {
      return this.keysFile.parse(bytes).body.recovery != null;
    } catch {
      return false;
    }
  }

  private newDeviceId(): string {
    return hex(this.p.randomBytes(8));
  }

  /** Drops the "key was not shown" mark after the card has said it. Does not write the key, or anything in Drive. */
  async clearRecoveryKeyUnshown(): Promise<void> {
    const st = await this.state.load();
    if (st.recoveryKeyUnshown !== true) return;
    await this.state.save({ ...st, recoveryKeyUnshown: false });
  }

  /**
   * Runs `block`, turning every failure into an `ERROR` connection.
   * `connecting` is `connect()` itself: an unknown throw is a connect failure, not a backup that could not be prepared.
   */
  private async connection(block: () => Promise<DriveConnection>, connecting = false): Promise<DriveConnection> {
    try {
      return await block();
    } catch (e) {
      if (e instanceof FolderWithoutKeys) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
      const problem = DriveProblem.of(e);
      if (connecting && problem.kind === 'SOURCE_FAILED') return { kind: 'ERROR', problem: new DriveProblem('CONNECT_FAILED') };
      return { kind: 'ERROR', problem };
    }
  }
}
