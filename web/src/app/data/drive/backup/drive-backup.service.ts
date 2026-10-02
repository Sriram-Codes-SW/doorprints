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

import { equalBytes, hex } from '../../crypto/bytes';
import type { CryptoProvider } from '../../crypto/crypto-provider';
import { Dpx } from '../../crypto/dpx';
import { kidOf } from '../../crypto/folder-key';
import { KeysError, KeysFile, KeysGuard } from '../../crypto/keys-file';
import type { OpenedKeys } from '../../crypto/keys-file';
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
 * No keys.json or Drive error kind triggers a revoke, re-key, wipe or re-create.
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
        return st.creatingRootId === root.id ? { kind: 'NO_FOLDER' } : { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
      }
      const bytes = await downloadVerified(this.drive, keys.id);
      const guard = new KeysGuard(this.p, this.trust.keys(root.id));
      if ((await guard.watermark()) == null) {
        // Never adopted silently (docs/15 §9.3): this device's own unfinished create starts again, anything else is joined.
        return st.creatingRootId === root.id ? { kind: 'NO_FOLDER' } : { kind: 'NEEDS_ENROLMENT', recoveryAvailable: this.recoveryHint(bytes) };
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
    });
  }

  /**
   * The first connect to a Drive with no Doorprints folder, or *Start again* after `FOLDER_GONE`: makes `Doorprints/`,
   * the key set, `doorprints.json` and `Backups/`, then pins this device to it. `withRecoveryKey` false is the
   * person's *Skip* after the warning. The recovery key comes back to be shown **once**; it is never stored. The pin is
   * the last step, so a run that stops half way leaves nothing this device trusts, and the next run starts again.
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

        // If a folder exists and it's not one we're currently creating, check if it's already pinned by us.
        // This can happen when a second tab calls createFolder while the first tab is creating;
        // after the first tab finishes, the second tab (holding the lock) will find the folder pinned.
        if (existing && st.creatingRootId !== existing.id) {
          const guard = new KeysGuard(this.p, this.trust.keys(existing.id));
          if ((await guard.watermark()) != null) {
            // We're already pinned to this folder (created in this tab or another).
            // Use the connect() method to properly open and return the ready state.
            const keys = await this.findKind(existing.id, KIND_KEYS, st.keysId);
            if (!keys) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
            const bytes = await downloadVerified(this.drive, keys.id);
            const opened = await this.keysFile.open(bytes, this.device.key, guard);
            return this.ready(existing.id, keys.id, opened);
          }
          // Folder exists but we're not pinned to it; reject
          return { kind: 'ERROR', problem: new DriveProblem('FOLDER_EXISTS') };
        }

        const root = existing ?? (await this.drive.createFile({ name: DRIVE_LAYOUT.root.name, mimeType: FOLDER_MIME, parents: [], appProperties: DRIVE_LAYOUT.root.appProperties }));
        await this.state.save({ ...st, rootId: root.id, creatingRootId: root.id, keysId: null, controlId: null, backupsId: null });
        const guard = new KeysGuard(this.p, this.trust.keys(root.id));
        if ((await guard.watermark()) != null) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_EXISTS') };
        for (const f of await listAll(this.drive, { parentId: root.id })) {
          const kind = f.appProperties[DRIVE_LAYOUT.kind];
          if (kind === KIND_KEYS || kind === KIND_CONTROL) await this.trashQuietly(f.id);
        }
        const now = this.clock();
        const key = withRecoveryKey ? RecoveryKey.generate(this.p) : null;
        const written = await this.keysFile.createFirstDevice({ publicKey: this.device.key.publicKey, name: this.device.name, platform: this.device.platform }, key, now);
        const keysId = await this.uploadSmall(root.id, KEYS_NAME, KIND_KEYS, written.bytes);
        const control = await this.controlFile.create(written.opened, now);
        const controlId = await this.uploadSmall(root.id, CONTROL_NAME, KIND_CONTROL, control.bytes);
        const backups = (await ensureFolder(this.drive, DRIVE_LAYOUT.backups, root.id, true))!;
        await this.controlFile.accept(control.body, this.trust.control(root.id));
        await guard.pinCreated(written);
        await this.state.save({
          ...(await this.state.load()),
          rootId: root.id, keysId, controlId, backupsId: backups.id, creatingRootId: null,
          deviceId: st.deviceId ?? this.newDeviceId(), newestSeenAt: null, lastBackupId: null,
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
        const key = folder.keys.currentFolderKey();
        let mac: Uint8Array;
        try {
          let written;
          try {
            written = await new Dpx(this.p).encrypt(key, folder.keys.epoch, this.myKid, payload.format, payload.source, (b) => file.write(b), MAX_BACKUP_PLAINTEXT);
          } finally {
            payload.close?.();
          }
          meta = new BackupMeta(now, payload.houses, folder.keys.epoch, this.myKid, written.ciphertextSha256);
          mac = await meta.mac(this.p, key);
        } finally {
          key.fill(0);
        }
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
    await this.state.save({
      ...(await this.state.load()),
      rootId, keysId, controlId, backupsId, creatingRootId: null, deviceId: st.deviceId ?? this.newDeviceId(),
    });
    return { kind: 'READY', folder: { rootId, keysId, controlId, backupsId, keys: opened, control: body } };
  }

  private async locateKeys(): Promise<{ rootId: string; keys: DriveFile; bytes: Uint8Array } | null> {
    const st = await this.state.load();
    const root = await ensureFolder(this.drive, DRIVE_LAYOUT.root, null, false, st.rootId);
    if (!root) return null;
    const keys = await this.findKind(root.id, KIND_KEYS, root.id === st.rootId ? st.keysId : null);
    if (!keys) throw new FolderWithoutKeys();
    return { rootId: root.id, keys, bytes: await downloadVerified(this.drive, keys.id) };
  }

  /** A file of `kind` in the folder: the remembered id first (a listing may lag), else the oldest listed. */
  private async findKind(rootId: string, kind: string, knownId: string | null): Promise<DriveFile | null> {
    if (knownId != null) {
      let f: DriveFile | null = null;
      try {
        f = await this.drive.getFile(knownId);
      } catch (e) {
        if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
      }
      if (f && !f.trashed && f.parents.includes(rootId) && f.appProperties[DRIVE_LAYOUT.kind] === kind) return f;
    }
    const page = await this.drive.list({ parentId: rootId, appProperties: { [DRIVE_LAYOUT.kind]: kind } });
    return page.files.find((f) => f.mimeType !== FOLDER_MIME) ?? null;
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

  /** Runs `block`, turning every failure into an `ERROR` connection. */
  private async connection(block: () => Promise<DriveConnection>): Promise<DriveConnection> {
    try {
      return await block();
    } catch (e) {
      if (e instanceof FolderWithoutKeys) return { kind: 'ERROR', problem: new DriveProblem('FOLDER_WITHOUT_KEYS') };
      return { kind: 'ERROR', problem: DriveProblem.of(e) };
    }
  }
}
