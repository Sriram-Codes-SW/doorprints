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

import { hex, utf8 } from '../crypto/bytes';
import type { CryptoProvider } from '../crypto/crypto-provider';
import { sha256Of } from '../crypto/crypto-provider';
import { Dpx, DpxError } from '../crypto/dpx';
import type { DpxHeader } from '../crypto/dpx';
import { KeysError } from '../crypto/keys-file';
import type { OpenedKeys } from '../crypto/keys-file';
import { revokedEpochRule } from '../crypto/keys-guard';
import { sha256Hex } from '../../export/sha256';
import { DRIVE_LAYOUT, DriveError } from './drive-client';
import type { DriveClient, DriveFile } from './drive-client';
import { isHeld as _isHeld, planMerge, takesIncoming } from './drive-merge';
import { downloadVerified, ensureFolder, listAll, markComplete, uploadBytes } from './drive-ops';
import { refOfRow, withRef } from './drive-photo-seams';
import type { PhotoRef, PhotoRefs } from './drive-photo-seams';
import {
  EMPTY_SYNC_STATE, KIND_SYNC, PROP_SEQ, SYNC_INNER, syncDeviceId,
} from './drive-sync-seams';
import type {
  DriveSyncState, FolderSession, LocalRows, SkippedFile, SkipReason, SyncPassResult, SyncPeer, SyncReport, SyncStateStore,
} from './drive-sync-seams';
import {
  encodeSyncFile, isDeviceId, parseSyncFile, SYNC_EARLIEST_MS, SYNC_KINDS, SYNC_MAX_BYTES, SYNC_MAX_SEQ, SyncFileError, syncFile,
} from './sync-file';
import type { SyncFile, SyncKind, SyncRow } from './sync-file';

void _isHeld;

/** Candidates read per device in one pass. */
export const MAX_CANDIDATES = 4;
/** The largest ciphertext read: the plaintext cap plus the `dpx/1` overhead. */
export const MAX_FILE_BYTES = SYNC_MAX_BYTES + 1024 * 1024;

interface Verified {
  readonly file: SyncFile;
  readonly checksum: string;
  readonly plainSha: Uint8Array;
}

class Skip extends Error {
  constructor(readonly reason: SkipReason, readonly detail: string | null = null) {
    super(reason);
  }
}

class NeedFreshKeys extends Error {}

interface Ctx {
  keys: OpenedKeys;
  refreshed: boolean;
}

const rowId = (kind: SyncKind, key: string) => `${kind}\u0000${key}`;

/**
 * Sync through Google Drive (S4b-BL-118; docs/15 §1.3, §5.1, §7 phase 5, §9.9) over a signed-in `DriveClient` and an
 * opened `FolderSession`: the twin of Kotlin's `DriveSyncEngine`, same rules, same names (read its comment for the pass:
 * keys reopened through the pin, one listing, per-device best authenticated `seq`, rollback ignored, the merge plan with
 * the 24 h hold and the shrink guard, this device's whole state written as `partial` then `complete` and read back
 * before success, own superseded files to the bin only after that). A file that fails any check is skipped and
 * reported; nothing is deleted because of what is read; no keys or Drive error triggers a revoke, re-key, wipe or
 * re-creation.
 */
export class DriveSyncEngine {
  private readonly dpx: Dpx;
  private readonly staged = new Map<string, SyncRow>();
  private ownCache: { checksum: string; verified: Verified } | null = null;

  constructor(
    private readonly drive: DriveClient,
    private readonly p: CryptoProvider,
    private readonly session: FolderSession,
    private readonly state: SyncStateStore,
    private readonly local: LocalRows,
    private readonly clock: () => number,
    /** True while a deletion is pending or the folder was deleted: no pass then (docs/15 §3.3, §3.4). */
    private readonly paused: () => Promise<boolean> = async () => false,
    /** Where the photos' Drive files are remembered (S4b-BL-128): written into the photo rows, learned from the others' files. */
    private readonly photos: PhotoRefs | null = null,
  ) {
    this.dpx = new Dpx(p);
  }

  /** Adds a row to the next written file that the local rows no longer hold (a deleted photo's tombstone). */
  stage(row: SyncRow): void {
    const id = rowId(row.kind, row.key);
    const had = this.staged.get(id);
    if (!had || takesIncoming(had.stamp, row.stamp)) this.staged.set(id, row);
  }

  /** One pass; `confirmShrink` is the person's yes to the shrink guard; `force` ignores the backoff. */
  async run(confirmShrink = false, force = false): Promise<SyncPassResult> {
    if ((await this.session.guard.watermark()) === null) throw new KeysError('NOT_PINNED', 'no pin for this folder: nothing is opened');
    if (await this.paused()) return { kind: 'Paused' };
    const st = (await this.state.load()) ?? EMPTY_SYNC_STATE;
    const now = this.clock();
    if (!force && now < st.notBefore) return { kind: 'Waiting', notBefore: st.notBefore };
    try {
      return await this.pass(st, now, confirmShrink);
    } catch (e) {
      if (e instanceof DriveError) {
        const cur = await this.state.load();
        const failures = cur.failures + 1;
        const wait = e.retryAfterMs ?? this.drive.retry.backoffMs(Math.min(failures, 30));
        await this.state.save({ ...cur, failures, notBefore: this.clock() + wait });
      }
      throw e;
    }
  }

  /**
   * Whether Drive has lost what this device wrote: it remembers a confirmed file and Drive has no complete file of this
   * device with an authenticated `seq` at least that high. One `files.get` of the last file; Drive's checksum is a
   * hint that spares the download, never the answer. An error is unknown, so false.
   */
  async isBehind(): Promise<boolean> {
    if ((await this.session.guard.watermark()) === null) return false;
    const st = await this.state.load();
    if (st.confirmedSeq === 0 || st.lastFileId === null) return false;
    try {
      let hint: DriveFile | null = null;
      try {
        hint = await this.drive.getFile(st.lastFileId);
      } catch (e) {
        if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
      }
      if (hint && this.isOwnIntact(hint, st)) return false;
      const ctx: Ctx = { keys: await this.session.refresh(), refreshed: false };
      if (st.syncFolderId === null) return true;
      const own = this.ownCandidates(await listAll(this.drive, { parentId: st.syncFolderId, appProperties: { [DRIVE_LAYOUT.kind]: KIND_SYNC } }));
      let best = 0;
      for (const f of own) {
        try {
          best = Math.max(best, (await this.verify(ctx, f, this.session.deviceId)).file.seq);
        } catch (e) {
          if (!(e instanceof Skip)) throw e;
        }
      }
      return best < st.confirmedSeq;
    } catch {
      return false;
    }
  }

  // ---- The pass ---------------------------------------------------------------------------------------------------

  private async pass(initial: DriveSyncState, now: number, confirmShrink: boolean): Promise<SyncPassResult> {
    let st = initial;
    const me = this.session.deviceId;
    const ctx: Ctx = { keys: await this.session.refresh(), refreshed: false };
    const skipped: SkippedFile[] = [];

    const current = new Map<string, SyncRow>();
    for (const r of await this.local.all()) putIfNewer(current, r);
    for (const r of this.staged.values()) putIfNewer(current, r);
    let liveHouses = 0;
    for (const r of current.values()) if (r.kind === 'houses' && !r.stamp.deleted) liveHouses++;

    const sync = (await ensureFolder(this.drive, DRIVE_LAYOUT.sync, this.session.rootId, true, st.syncFolderId))!;
    const syncId = sync.id;
    st = { ...st, syncFolderId: syncId };

    const files = new Map<string, DriveFile>();
    for (const f of await listAll(this.drive, { parentId: syncId, appProperties: { [DRIVE_LAYOUT.kind]: KIND_SYNC } })) files.set(f.id, f);
    // A listing may lag behind a write: this device's last file is asked for by id.
    if (st.lastFileId !== null && !files.has(st.lastFileId)) {
      try {
        const f = await this.drive.getFile(st.lastFileId);
        if (!f.trashed && f.parents.includes(syncId) && f.appProperties[DRIVE_LAYOUT.kind] === KIND_SYNC) files.set(f.id, f);
      } catch (e) {
        if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
      }
    }

    // This device's own previous file: the sequence number it reached, and the photo tombstones it carries.
    let ownSeen = 0;
    let ownIntact = false;
    const last = st.lastFileId !== null ? files.get(st.lastFileId) : undefined;
    if (last && this.isOwnIntact(last, st)) {
      ownIntact = true;
      let v: Verified | null = this.ownCache && this.ownCache.checksum === st.lastChecksum ? this.ownCache.verified : null;
      if (!v) {
        try {
          v = await this.verify(ctx, last, me);
          this.ownCache = { checksum: v.checksum, verified: v };
        } catch (e) {
          if (!(e instanceof Skip)) throw e;
        }
      }
      if (v) {
        ownSeen = v.file.seq;
        carryTombstones(current, v.file);
      }
    } else {
      for (const f of this.ownCandidates([...files.values()])) {
        try {
          const v = await this.verify(ctx, f, me);
          if (v.file.seq > ownSeen) {
            ownSeen = v.file.seq;
            carryTombstones(current, v.file);
          }
        } catch (e) {
          if (!(e instanceof Skip)) throw e;
          skipped.push({ deviceId: me, fileId: f.id, reason: e.reason, detail: e.detail });
        }
      }
    }

    // The other devices' files.
    const knownDevices = (): Set<string> => {
      const k = new Set<string>();
      for (const d of ctx.keys.body.devices) k.add(syncDeviceId(d.kid));
      for (const r of ctx.keys.body.revoked) if (!r.isRecovery) k.add(syncDeviceId(r.kid));
      return k;
    };
    let known = knownDevices();
    // A device that joined after this one opened `keys.json` is unlisted only in our stale copy: re-read the list once
    // (through the pin, so a forged list is still refused) before calling its file unlisted.
    if (!ctx.refreshed) {
      const unknownWriter = [...files.values()].some((f) => {
        const dev = f.appProperties[DRIVE_LAYOUT.device];
        return f.appProperties[DRIVE_LAYOUT.state] === DRIVE_LAYOUT.stateComplete && !f.trashed && dev !== me && isDeviceId(dev) && !known.has(dev);
      });
      if (unknownWriter) {
        ctx.refreshed = true;
        ctx.keys = await this.session.refresh();
        known = knownDevices();
      }
    }
    const groups = new Map<string, DriveFile[]>();
    for (const f of files.values()) {
      if (f.appProperties[DRIVE_LAYOUT.state] !== DRIVE_LAYOUT.stateComplete || f.trashed) continue;
      const dev = f.appProperties[DRIVE_LAYOUT.device];
      if (dev === me) continue;
      if (dev === undefined || !known.has(dev)) {
        skipped.push({ deviceId: isDeviceId(dev) ? dev : null, fileId: f.id, reason: 'UNLISTED_DEVICE' });
        continue;
      }
      const list = groups.get(dev) ?? [];
      list.push(f);
      groups.set(dev, list);
    }

    const stamps = (kind: SyncKind, key: string) => current.get(rowId(kind, key))?.stamp;
    const take = new Map<string, SyncRow>();
    const deferredKeys = new Set<string>();
    const deferredFrom: string[] = [];
    let held = 0;
    let peersRead = 0;
    const peers: Record<string, SyncPeer> = { ...st.peers };
    const learned: Record<string, PhotoRef> = {};
    for (const [dev, group] of groups) {
      const peer = peers[dev] ?? { highestSeq: 0, mergedChecksum: null };
      const candidates = group
        .filter((f) => f.sha256Checksum === null || f.sha256Checksum.toLowerCase() !== (peer.mergedChecksum ?? '').toLowerCase())
        .filter((f) => { const h = hintSeq(f); return h === null || h >= peer.highestSeq; })
        .sort(byHint);
      if (candidates.length > MAX_CANDIDATES) skipped.push({ deviceId: dev, fileId: null, reason: 'TOO_MANY_FILES' });
      let best: Verified | null = null;
      let bestFile: DriveFile | null = null;
      for (const f of candidates.slice(0, MAX_CANDIDATES)) {
        try {
          const v = await this.verify(ctx, f, dev);
          if (!best || v.file.seq > best.file.seq) {
            best = v;
            bestFile = f;
          }
        } catch (e) {
          if (!(e instanceof Skip)) throw e;
          skipped.push({ deviceId: dev, fileId: f.id, reason: e.reason, detail: e.detail });
        }
      }
      if (!best) continue;
      peersRead++;
      // The Drive file of each live photo the authenticated file names, for downloads (and for our own next file).
      for (const row of best.file.rows.photos) {
        if (row.stamp.deleted || row.key in learned) continue;
        const ref = refOfRow(row);
        if (ref) learned[row.key] = ref;
      }
      const plan = planMerge(best.file, { now, highestSeq: peer.highestSeq > 0 ? peer.highestSeq : null, liveHouses, confirmShrink }, stamps);
      if (plan.stale) {
        skipped.push({ deviceId: dev, fileId: bestFile!.id, reason: 'ROLLED_BACK' });
        continue;
      }
      for (const r of plan.take) {
        const id = rowId(r.kind, r.key);
        const had = take.get(id);
        if (!had || takesIncoming(had.stamp, r.stamp)) take.set(id, r);
      }
      held += plan.held.length;
      if (plan.deferred.length > 0) {
        for (const r of plan.deferred) deferredKeys.add(rowId(r.kind, r.key));
        deferredFrom.push(dev);
      }
      const settled = plan.take.length === 0 && plan.held.length === 0 && plan.deferred.length === 0;
      peers[dev] = { highestSeq: Math.max(peer.highestSeq, best.file.seq), mergedChecksum: settled ? best.checksum : peer.mergedChecksum };
    }
    for (const r of take.values()) putIfNewer(current, r);
    if (this.photos) {
      await this.photos.learn(learned);
      // Every photo row of our file carries the Drive file of its bytes, once there is one.
      const refs = await this.photos.refs();
      for (const [id, row] of [...current.entries()]) {
        if (row.kind !== 'photos' || refOfRow(row)) continue;
        const ref = refs[row.key];
        if (ref) current.set(id, withRef(row, ref));
      }
    }

    // This device's own file.
    let wrote = false;
    let seq: number | null = null;
    const rowsFile = fileOf(me, 1, SYNC_EARLIEST_MS, current);
    const rowsHash = this.hashRows(rowsFile);
    if (rowsHash !== st.lastRowsHash || !ownIntact) {
      st = await this.write(ctx, st, rowsFile, rowsHash, ownSeen, syncId, [...files.values()], now);
      wrote = true;
      seq = st.confirmedSeq;
    }

    const delivered = [...take.values()].sort((a, b) => SYNC_KINDS.indexOf(a.kind) - SYNC_KINDS.indexOf(b.kind) || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));

    // Apply remote rows BEFORE saving state: if this throws, peer cursors are not advanced and the next pass retries.
    if (delivered.length > 0 && this.local.applyRemote) {
      await this.local.applyRemote(delivered);
    }

    st = { ...st, peers, generation: delivered.length === 0 ? st.generation : st.generation + 1, failures: 0, notBefore: 0 };
    await this.state.save(st);
    this.staged.clear();
    const report: SyncReport = { take: delivered, held, deferred: deferredKeys.size, skipped, wrote, seq, peersRead, generation: st.generation };
    return deferredKeys.size > 0
      ? { kind: 'NeedsConfirmation', report, housesToDelete: deferredKeys.size, liveHouses, fromDevices: [...new Set(deferredFrom)] }
      : { kind: 'Done', report };
  }

  // ---- Writing ----------------------------------------------------------------------------------------------------

  private async write(
    ctx: Ctx, before: DriveSyncState, rowsFile: SyncFile, rowsHash: string, ownSeen: number, syncId: string,
    listed: readonly DriveFile[], now: number,
  ): Promise<DriveSyncState> {
    const me = this.session.deviceId;
    const seq = Math.max(before.lastSeq, ownSeen) + 1;
    if (seq > SYNC_MAX_SEQ) throw new DriveError('BAD_REQUEST', 0, null, 'seqExhausted');
    // Reserved first: a pass that stops half way never reuses it.
    let st: DriveSyncState = { ...before, lastSeq: seq };
    await this.state.save(st);
    const text = encodeSyncFile(syncFile(me, seq, Math.max(now, SYNC_EARLIEST_MS), rowsFile.rows));
    const keys = ctx.keys;
    const wrap = await keys.contentWrapAes();
    if (!wrap) throw new DriveError('BAD_REQUEST', 0, null, 'keys');
    const out = await this.dpx.encryptBytesWithAes(wrap, keys.epoch, this.session.deviceKid, SYNC_INNER, utf8(text));
    const bytes = out.file;
    const written = out.result;
    const sha = hex(written.ciphertextSha256);
    const partial = await uploadBytes(
      this.drive,
      {
        kind: 'new',
        file: {
          name: `partial-sync-${seq}.dpx`,
          mimeType: 'application/octet-stream',
          parents: [syncId],
          appProperties: {
            [DRIVE_LAYOUT.kind]: KIND_SYNC,
            [DRIVE_LAYOUT.device]: me,
            [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.statePartial,
            [DRIVE_LAYOUT.createdAt]: String(now),
            [PROP_SEQ]: String(seq),
          },
        },
      },
      bytes,
    );
    const done = await markComplete(this.drive, partial.id, sha, `sync-${me}-${seq}.dpx`, partial);
    // Read back: what Drive holds is the file written, by its content and not by its say-so.
    const back = await this.drive.getFile(done.id);
    if (back.trashed || back.appProperties[DRIVE_LAYOUT.state] !== DRIVE_LAYOUT.stateComplete ||
      back.appProperties[DRIVE_LAYOUT.device] !== me || (back.sha256Checksum ?? '').toLowerCase() !== sha) {
      throw new DriveError('CORRUPT', 0, null, 'readBack');
    }
    const got = await downloadVerified(this.drive, done.id, sha);
    let v: Verified;
    try {
      v = await this.open(ctx, got, back, me);
    } catch (e) {
      if (e instanceof Skip) throw new DriveError('CORRUPT', 0, null, `readBack:${e.reason}`);
      throw e;
    }
    if (v.file.seq !== seq || hex(v.plainSha) !== hex(written.plaintextSha256) || this.hashRows(v.file) !== rowsHash) {
      throw new DriveError('CORRUPT', 0, null, 'readBackContent');
    }
    st = { ...st, confirmedSeq: seq, lastFileId: done.id, lastChecksum: sha, lastRowsHash: rowsHash };
    await this.state.save(st);
    this.ownCache = { checksum: sha, verified: v };
    // Only now the superseded files of this device (and its partial leftovers) go to the bin.
    for (const f of listed) {
      if (f.id !== done.id && f.appProperties[DRIVE_LAYOUT.device] === me) {
        try {
          await this.drive.trash(f.id);
        } catch (e) {
          if (!(e instanceof DriveError)) throw e;
        }
      }
    }
    return st;
  }

  private hashRows(file: SyncFile): string {
    return hex(sha256Of(this.p, utf8(encodeSyncFile(syncFile(file.deviceId, 1, SYNC_EARLIEST_MS, file.rows)))));
  }

  // ---- Reading ----------------------------------------------------------------------------------------------------

  private ownCandidates(files: readonly DriveFile[]): DriveFile[] {
    return files
      .filter((f) => f.appProperties[DRIVE_LAYOUT.device] === this.session.deviceId && f.appProperties[DRIVE_LAYOUT.state] === DRIVE_LAYOUT.stateComplete && !f.trashed)
      .sort(byHint)
      .slice(0, MAX_CANDIDATES);
  }

  private isOwnIntact(f: DriveFile, st: DriveSyncState): boolean {
    return !f.trashed && f.appProperties[DRIVE_LAYOUT.kind] === KIND_SYNC && f.appProperties[DRIVE_LAYOUT.device] === this.session.deviceId &&
      f.appProperties[DRIVE_LAYOUT.state] === DRIVE_LAYOUT.stateComplete && st.lastChecksum !== null &&
      (f.sha256Checksum ?? '').toLowerCase() === st.lastChecksum.toLowerCase();
  }

  /** Downloads, verifies and parses one file claimed by `claimed`. Throws {@link Skip} for a file to leave out. */
  private async verify(ctx: Ctx, f: DriveFile, claimed: string): Promise<Verified> {
    if (f.size !== null && f.size > MAX_FILE_BYTES) throw new Skip('TOO_LARGE');
    let bytes: Uint8Array;
    try {
      bytes = await downloadVerified(this.drive, f.id);
    } catch (e) {
      if (e instanceof DriveError) {
        if (e.kind === 'CORRUPT') throw new Skip('DOWNLOAD_CORRUPT');
        if (e.kind === 'NOT_FOUND' || e.kind === 'FORBIDDEN') throw new Skip('UNREADABLE', e.kind);
      }
      throw e;
    }
    return this.open(ctx, bytes, f, claimed);
  }

  private async open(ctx: Ctx, bytes: Uint8Array, f: DriveFile, claimed: string): Promise<Verified> {
    if (bytes.length > MAX_FILE_BYTES) throw new Skip('TOO_LARGE');
    for (;;) {
      try {
        return await this.openWith(ctx.keys, bytes, f, claimed);
      } catch (e) {
        if (!(e instanceof NeedFreshKeys)) throw e;
        if (ctx.refreshed) throw new Skip('NEWER_EPOCH');
        ctx.refreshed = true;
        ctx.keys = await this.session.refresh();
      }
    }
  }

  private async openWith(keys: OpenedKeys, bytes: Uint8Array, f: DriveFile, claimed: string): Promise<Verified> {
    const headerCheck = (h: DpxHeader): void => {
      switch (revokedEpochRule(keys.body, h.epoch, h.kid, f.modifiedTime)) {
        case 'ACCEPT': break;
        case 'NEWER_EPOCH': throw new NeedFreshKeys();
        case 'SKIP_REVOKED_WRITER': throw new Skip('REVOKED_WRITER');
        case 'SKIP_OLD_EPOCH_AFTER_REVOKE': throw new Skip('OLD_EPOCH_AFTER_REVOKE');
        case 'SKIP_UNKNOWN_WRITER': throw new Skip('UNKNOWN_WRITER');
      }
      if (syncDeviceId(h.kid) !== claimed) throw new Skip('WRONG_DEVICE', 'kid');
    };
    let plain: Uint8Array;
    let read;
    try {
      const r = await this.dpx.decryptBytes(keys, SYNC_INNER, bytes, { maxPlaintext: SYNC_MAX_BYTES, headerCheck });
      plain = r.plaintext;
      read = r.result;
    } catch (e) {
      if (e instanceof DpxError) {
        if (e.kind === 'NOT_DPX') throw new Skip('NOT_ENCRYPTED');
        if (e.kind === 'UNSUPPORTED_VERSION' || e.kind === 'UNSUPPORTED_ALGORITHM') throw new Skip('UNSUPPORTED_VERSION', e.kind);
        if (e.kind === 'TOO_LARGE') throw new Skip('TOO_LARGE');
        throw new Skip('BAD_ENVELOPE', e.kind);
      }
      if (e instanceof KeysError) throw new Skip('BAD_ENVELOPE', e.kind);
      throw e;
    }
    let text: string;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(plain);
    } catch {
      throw new Skip('BAD_FILE', 'utf8');
    }
    let file: SyncFile;
    try {
      file = parseSyncFile(text, claimed);
    } catch (e) {
      if (!(e instanceof SyncFileError)) throw e;
      if (e.problem === 'UNSUPPORTED_VERSION') throw new Skip('UNSUPPORTED_VERSION', e.problem);
      if (e.problem === 'WRONG_DEVICE') throw new Skip('WRONG_DEVICE', e.problem);
      if (e.problem === 'TOO_LARGE') throw new Skip('TOO_LARGE', e.problem);
      throw new Skip('BAD_FILE', e.problem);
    }
    return { file, checksum: sha256Hex(bytes), plainSha: read.plaintextSha256 };
  }
}

function hintSeq(f: DriveFile): number | null {
  const v = f.appProperties[PROP_SEQ];
  if (v === undefined || !/^[0-9]{1,16}$/.test(v)) return null;
  return Number(v);
}

function byHint(a: DriveFile, b: DriveFile): number {
  const ha = hintSeq(a) ?? Number.MAX_SAFE_INTEGER;
  const hb = hintSeq(b) ?? Number.MAX_SAFE_INTEGER;
  if (ha !== hb) return hb - ha;
  if (a.createdTime !== b.createdTime) return b.createdTime - a.createdTime;
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

function putIfNewer(map: Map<string, SyncRow>, row: SyncRow): void {
  const id = rowId(row.kind, row.key);
  const had = map.get(id);
  if (!had || takesIncoming(had.stamp, row.stamp)) map.set(id, row);
}

/** The deleted photo rows of this device's own file that no local row covers: tombstones are kept for ever. */
function carryTombstones(current: Map<string, SyncRow>, file: SyncFile): void {
  for (const r of file.rows.photos) if (r.stamp.deleted) putIfNewer(current, r);
}

function fileOf(deviceId: string, seq: number, writtenAt: number, rows: Map<string, SyncRow>): SyncFile {
  const by: Partial<Record<SyncKind, SyncRow[]>> = {};
  for (const r of rows.values()) (by[r.kind] ??= []).push(r);
  return syncFile(deviceId, seq, writtenAt, by);
}
