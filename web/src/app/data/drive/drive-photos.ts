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

import { hex, unhex } from '../crypto/bytes';
import type { CryptoProvider } from '../crypto/crypto-provider';
import { sha256Of } from '../crypto/crypto-provider';
import { Dpx, DpxError, PHOTO } from '../crypto/dpx';
import type { DpxHeader } from '../crypto/dpx';
import { KeysError } from '../crypto/keys-file';
import type { OpenedKeys } from '../crypto/keys-file';
import { revokedEpochRule } from '../crypto/keys-guard';
import { DRIVE_LAYOUT, DriveError, MULTIPART_LIMIT } from './drive-client';
import type { DriveClient, DriveFile, UploadSession, UploadTarget } from './drive-client';
import { downloadVerified, ensureFolder, listAll, markComplete, uploadResumable } from './drive-ops';
import { DEFAULT_PHOTO_CONFIG, KIND_PHOTO, maxPhotoFileBytes, PROP_PHOTO_ID } from './drive-photo-seams';
import type {
  PhotoBad, PhotoConfig, PhotoRef, PhotoRefs, PhotoSkipReason, PhotoStateStore, PhotoUploadResult, SkippedPhoto,
} from './drive-photo-seams';
import type { FolderSession } from './drive-sync-seams';
import { isSyncId } from './sync-file';

interface Pending {
  readonly photoId: string;
  readonly plainSha: string;
  readonly cipher: Uint8Array;
  readonly cipherSha: string;
  readonly token: string;
  session: UploadSession | null;
  uploaded: DriveFile | null;
}

class Reject extends Error {
  constructor(readonly reason: PhotoSkipReason, readonly detail: string | null = null) {
    super(reason);
  }
}

class NeedFreshKeys extends Error {}

/**
 * Photos over Google Drive (S4b-BL-128; docs/15 §5.1, §9, §11): the twin of Kotlin's `DrivePhotos`, same rules, same
 * names (read its comment). One photo is one `photo/1` file (`dpx/1`, a fresh content key) in the `Photos` folder, named
 * `p-<random>.dpx`, `appProperties kind=photo`, `photoId`, `state`, `device`, `createdAt` (no house, no address),
 * written as `partial-p-…` then checked against Drive's checksum of the ciphertext and completed. The plaintext SHA-256
 * goes into the photo's row of the sync file and is what every download is held to (`CHECKSUM_REQUIRED`). A download
 * that fails any check is skipped and reported (`skipped`), never thrown and never deleted; a network failure is thrown.
 */
export class DrivePhotos implements PhotoRefs {
  private readonly dpx: Dpx;
  private pending: Pending | null = null;
  private keysAt: number | null = null;
  private skips: SkippedPhoto[] = [];

  constructor(
    private readonly drive: DriveClient,
    private readonly p: CryptoProvider,
    private readonly session: FolderSession,
    private readonly store: PhotoStateStore,
    private readonly clock: () => number,
    private readonly config: PhotoConfig = DEFAULT_PHOTO_CONFIG,
  ) {
    this.dpx = new Dpx(p);
  }

  /** What was skipped since the last {@link drainSkipped}. */
  get skipped(): readonly SkippedPhoto[] {
    return [...this.skips];
  }

  drainSkipped(): SkippedPhoto[] {
    const out = this.skips;
    this.skips = [];
    return out;
  }

  async refs(): Promise<Readonly<Record<string, PhotoRef>>> {
    return (await this.store.load()).refs;
  }

  async learn(found: Readonly<Record<string, PhotoRef>>): Promise<void> {
    const ids = Object.keys(found);
    if (ids.length === 0) return;
    const st = await this.store.load();
    let refs = st.refs;
    let bad = st.bad;
    let changed = false;
    for (const id of ids) {
      const have = refs[id];
      if (!have || (have.driveFileId !== found[id].driveFileId && bad[id]?.fileId === have.driveFileId)) {
        refs = { ...refs, [id]: found[id] };
        const { [id]: _drop, ...rest } = bad;
        void _drop;
        bad = rest;
        changed = true;
      }
    }
    if (changed) await this.store.save({ ...st, refs, bad });
  }

  // ---- Upload -----------------------------------------------------------------------------------------------------

  /** Uploads one photo (see the class). `bytes` is the photo; the caller reads it once. */
  async upload(photoId: string, bytes: Uint8Array): Promise<PhotoUploadResult> {
    await this.requirePinned();
    if (!isSyncId(photoId)) return this.skip(photoId, 'BAD_ID');
    if (bytes.length > this.config.maxPlaintextBytes) return this.skip(photoId, 'TOO_LARGE');
    if (bytes.length === 0) return this.skip(photoId, 'EMPTY');
    const sha = hex(sha256Of(this.p, bytes));
    let st = await this.store.load();
    const folder = (await ensureFolder(this.drive, DRIVE_LAYOUT.photos, this.session.rootId, true, st.photosFolderId))!;
    if (folder.id !== st.photosFolderId) {
      st = { ...st, photosFolderId: folder.id };
      await this.store.save(st);
    }
    const me = this.session.deviceId;

    // 1. Not again: the file this device knows is intact and holds these very bytes.
    const known = st.refs[photoId];
    if (known && known.sha256 === sha && (await this.intact(known.driveFileId, folder.id))) return { kind: 'Unchanged', ref: known };

    // 2. Dedupe by photoId: adopt a file already in Drive only if it decrypts, under a folder key, to exactly these bytes.
    const listed = await listAll(this.drive, { parentId: folder.id, appProperties: { [DRIVE_LAYOUT.kind]: KIND_PHOTO, [PROP_PHOTO_ID]: photoId } });
    const resuming = this.pending && this.pending.photoId === photoId && this.pending.plainSha === sha ? this.pending : null;
    const candidates = resuming?.uploaded
      ? []
      : listed.filter((f) => !f.trashed && f.mimeType !== 'application/vnd.google-apps.folder')
        .sort((a, b) => a.createdTime - b.createdTime || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
        .slice(0, this.config.maxCandidates);
    for (const f of candidates) {
      try {
        await this.readVerified(f, sha);
      } catch (e) {
        if (e instanceof Reject) continue;
        throw e;
      }
      let done = f;
      if (f.appProperties[DRIVE_LAYOUT.state] !== DRIVE_LAYOUT.stateComplete) {
        if (f.sha256Checksum === null) continue;
        done = await markComplete(this.drive, f.id, f.sha256Checksum, `p-${this.token()}.dpx`, f);
      }
      return { kind: 'Adopted', ref: await this.remember(photoId, done.id, sha) };
    }

    // 3. Encrypt (once per photo: a retry reuses the bytes and the session) and send.
    const pend = resuming ?? (this.pending = await this.encrypt(photoId, bytes, sha, await this.keys()));
    if (!pend.uploaded) pend.uploaded = await this.sendPartial(pend, folder.id);
    const partial = pend.uploaded;
    let done: DriveFile;
    try {
      done = await markComplete(this.drive, partial.id, pend.cipherSha, `p-${pend.token}.dpx`, partial);
    } catch (e) {
      if (e instanceof DriveError && e.kind === 'CORRUPT') {
        // What Drive holds is not what was sent: this device's own partial goes to the bin; nothing is trusted.
        this.pending = null;
        await this.tryTrash(partial.id);
      }
      throw e;
    }
    const ref = await this.remember(photoId, done.id, sha);
    this.pending = null;
    // Only now the superseded files of this device for this photo (and its leftovers) go to the bin.
    for (const f of listed) if (f.id !== done.id && !f.trashed && f.appProperties[DRIVE_LAYOUT.device] === me) await this.tryTrash(f.id);
    return { kind: 'Uploaded', ref };
  }

  private async encrypt(photoId: string, plain: Uint8Array, sha: string, keys: OpenedKeys): Promise<Pending> {
    const folderKey = keys.currentFolderKey();
    let out;
    try {
      out = await this.dpx.encryptBytes(folderKey, keys.epoch, this.session.deviceKid, PHOTO, plain);
    } finally {
      folderKey.fill(0);
    }
    if (hex(out.result.plaintextSha256) !== sha) throw new Error('plaintext hash');
    return { photoId, plainSha: sha, cipher: out.file, cipherSha: hex(out.result.ciphertextSha256), token: this.token(), session: null, uploaded: null };
  }

  private async sendPartial(pend: Pending, folderId: string): Promise<DriveFile> {
    const target: UploadTarget = {
      kind: 'new',
      file: {
        name: `partial-p-${pend.token}.dpx`,
        mimeType: 'application/octet-stream',
        parents: [folderId],
        appProperties: {
          [DRIVE_LAYOUT.kind]: KIND_PHOTO,
          [PROP_PHOTO_ID]: pend.photoId,
          [DRIVE_LAYOUT.device]: this.session.deviceId,
          [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.statePartial,
          [DRIVE_LAYOUT.createdAt]: String(this.clock()),
        },
      },
    };
    const cipher = pend.cipher;
    if (cipher.length <= MULTIPART_LIMIT) return this.drive.upload(target, cipher);
    return uploadResumable(this.drive, target, cipher.length, (offset, length) => cipher.slice(offset, offset + length), {
      chunkSize: this.config.chunkSize,
      session: pend.session,
      onSession: (s) => {
        pend.session = s;
      },
    });
  }

  private async remember(photoId: string, fileId: string, sha: string): Promise<PhotoRef> {
    const ref: PhotoRef = { driveFileId: fileId, sha256: sha };
    const st = await this.store.load();
    const { [photoId]: _drop, ...bad } = st.bad;
    void _drop;
    await this.store.save({ ...st, refs: { ...st.refs, [photoId]: ref }, bad });
    return ref;
  }

  private async intact(fileId: string, folderId: string): Promise<boolean> {
    let f: DriveFile;
    try {
      f = await this.drive.getFile(fileId);
    } catch (e) {
      if (e instanceof DriveError && e.kind === 'NOT_FOUND') return false;
      throw e;
    }
    return this.isPhotoFile(f, folderId) && f.sha256Checksum !== null;
  }

  private async tryTrash(fileId: string): Promise<void> {
    try {
      await this.drive.trash(fileId);
    } catch (e) {
      if (!(e instanceof DriveError)) throw e;
    }
  }

  // ---- Download ---------------------------------------------------------------------------------------------------

  /** The photo's bytes, or null when this photo is skipped (reported in {@link skipped}); a network failure is thrown. */
  async download(photoId: string): Promise<Uint8Array | null> {
    await this.requirePinned();
    const st = await this.store.load();
    const ref = st.refs[photoId];
    if (!ref) {
      this.skip(photoId, 'NO_REFERENCE');
      return null;
    }
    const mark = st.bad[photoId];
    const now = this.clock();
    if (mark && mark.fileId === ref.driveFileId && now >= mark.at && now - mark.at < this.config.badRetryMs) {
      this.skip(photoId, mark.reason, 'remembered');
      return null;
    }
    const folder = await ensureFolder(this.drive, DRIVE_LAYOUT.photos, this.session.rootId, false, st.photosFolderId);
    if (!folder) {
      this.skip(photoId, 'NO_FOLDER');
      return null;
    }
    try {
      let f: DriveFile;
      try {
        f = await this.drive.getFile(ref.driveFileId);
      } catch (e) {
        if (e instanceof DriveError && e.kind === 'NOT_FOUND') throw new Reject('UNREADABLE', 'NOT_FOUND');
        throw e;
      }
      if (!this.isPhotoFile(f, folder.id)) throw new Reject('NOT_OURS');
      return await this.readVerified(f, ref.sha256);
    } catch (e) {
      if (!(e instanceof Reject)) throw e;
      const cur = await this.store.load();
      const mark2: PhotoBad = { fileId: ref.driveFileId, reason: e.reason, at: this.clock() };
      await this.store.save({ ...cur, bad: { ...cur.bad, [photoId]: mark2 } });
      this.skip(photoId, e.reason, e.detail);
      return null;
    }
  }

  // ---- Checks -----------------------------------------------------------------------------------------------------

  private isPhotoFile(f: DriveFile, folderId: string): boolean {
    return !f.trashed && f.mimeType !== 'application/vnd.google-apps.folder' && f.parents.includes(folderId) &&
      f.appProperties[DRIVE_LAYOUT.kind] === KIND_PHOTO && f.appProperties[DRIVE_LAYOUT.state] === DRIVE_LAYOUT.stateComplete;
  }

  /** The plaintext of `f` when it is a `photo/1` file that authenticates and decrypts to `expectedSha`; {@link Reject} otherwise. */
  private async readVerified(f: DriveFile, expectedSha: string): Promise<Uint8Array> {
    const max = maxPhotoFileBytes(this.config);
    if (f.size !== null && f.size > max) throw new Reject('TOO_LARGE');
    let bytes: Uint8Array;
    try {
      bytes = await downloadVerified(this.drive, f.id);
    } catch (e) {
      if (e instanceof DriveError) {
        if (e.kind === 'CORRUPT') throw new Reject('DOWNLOAD_CORRUPT');
        if (e.kind === 'NOT_FOUND' || e.kind === 'FORBIDDEN') throw new Reject('UNREADABLE', e.kind);
      }
      throw e;
    }
    if (bytes.length > max) throw new Reject('TOO_LARGE');
    let refreshed = false;
    for (;;) {
      try {
        return await this.open(await this.keys(), bytes, f, expectedSha);
      } catch (e) {
        if (!(e instanceof NeedFreshKeys)) throw e;
        if (refreshed) throw new Reject('NEWER_EPOCH');
        refreshed = true;
        await this.session.refresh();
        this.keysAt = this.clock();
      }
    }
  }

  private async open(keys: OpenedKeys, bytes: Uint8Array, f: DriveFile, expectedSha: string): Promise<Uint8Array> {
    const headerCheck = (h: DpxHeader): void => {
      switch (revokedEpochRule(keys.body, h.epoch, h.kid, f.modifiedTime)) {
        case 'ACCEPT': break;
        case 'NEWER_EPOCH': throw new NeedFreshKeys();
        case 'SKIP_REVOKED_WRITER': throw new Reject('REVOKED_WRITER');
        case 'SKIP_OLD_EPOCH_AFTER_REVOKE': throw new Reject('OLD_EPOCH_AFTER_REVOKE');
        case 'SKIP_UNKNOWN_WRITER': throw new Reject('UNKNOWN_WRITER');
      }
    };
    try {
      const r = await this.dpx.decryptBytes(keys, PHOTO, bytes, {
        maxPlaintext: this.config.maxPlaintextBytes,
        expectedPlaintextSha256: unhex(expectedSha),
        headerCheck,
      });
      return r.plaintext;
    } catch (e) {
      if (e instanceof DpxError) {
        if (e.kind === 'NOT_DPX') throw new Reject('NOT_ENCRYPTED');
        if (e.kind === 'UNSUPPORTED_VERSION' || e.kind === 'UNSUPPORTED_ALGORITHM') throw new Reject('UNSUPPORTED_VERSION', e.kind);
        if (e.kind === 'TOO_LARGE') throw new Reject('TOO_LARGE');
        if (e.kind === 'CHECKSUM_MISMATCH') throw new Reject('CHECKSUM_MISMATCH');
        throw new Reject('BAD_ENVELOPE', e.kind);
      }
      if (e instanceof KeysError) throw new Reject('BAD_ENVELOPE', e.kind);
      throw e;
    }
  }

  // ---- Helpers ----------------------------------------------------------------------------------------------------

  private async requirePinned(): Promise<void> {
    if ((await this.session.guard.watermark()) === null) throw new KeysError('NOT_PINNED', 'no pin for this folder: nothing is opened');
  }

  /** The opened key list, read again through the pin when it is older than `keysFreshMs` (a revoke is seen). */
  private async keys(): Promise<OpenedKeys> {
    const now = this.clock();
    const at = this.keysAt;
    if (at === null || now < at || now - at > this.config.keysFreshMs) {
      await this.session.refresh();
      this.keysAt = now;
    }
    return this.session.keys;
  }

  private skip(photoId: string, reason: PhotoSkipReason, detail: string | null = null): PhotoUploadResult {
    this.skips.push({ photoId, reason, detail });
    return { kind: 'Skipped', reason };
  }

  private token(): string {
    return hex(this.p.randomBytes(12));
  }
}
