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

import { b64, concat, constantTimeEquals, i2osp, u32, unb64, unhex, utf8 } from '../../crypto/bytes';
import type { CryptoProvider } from '../../crypto/crypto-provider';
import type { FolderKeys } from '../../crypto/folder-key';
import { Hkdf } from '../../crypto/hpke';
import { DRIVE_LAYOUT } from '../drive-client';
import type { DriveFile } from '../drive-client';

/**
 * The authenticated metadata of one backup in Drive (S4b-BL-116; docs/15 §1.4, §5.8), kept in its `appProperties` so a
 * listing tells the backups apart without downloading them; the twin of Kotlin's `BackupMeta`:
 *
 * ```
 * kind=backup  state=partial|complete  device=<random id>        (not authenticated)
 * createdAt=<ms>  houses=<n>  epoch=<E>  kid=B64(16)               (authenticated)
 * bm=B64(HMAC-SHA-256(HKDF(folder key of E, "doorprints/dpx1/backup-meta"),
 *        "doorprints-backup-meta/1" ‖ 0x00 ‖ u64 createdAt ‖ u32 houses ‖ u32 E ‖ kid(16) ‖ SHA-256 of the file))
 * ```
 *
 * The SHA-256 is of the `dpx/1` file as Drive stores it, compared with Drive's own `sha256Checksum`, so the metadata
 * only verifies on the exact bytes it was made for. Values are canonical decimal and base64 with padding; anything
 * else is not a backup.
 */

/** The two folder-key uses this ticket adds, each its own HKDF-SHA-256 key (empty salt); the folder key is never an HMAC key. */
export const BACKUP_KEY_INFO = {
  control: 'doorprints/dpx1/control',
  backupMeta: 'doorprints/dpx1/backup-meta',
} as const;

async function derive(p: CryptoProvider, folderKey: Uint8Array, info: string): Promise<Uint8Array> {
  if (folderKey.length !== 32) throw new RangeError('a folder key is 32 bytes');
  return new Hkdf(p).derive(new Uint8Array(0), folderKey, utf8(info), 32);
}

/** The key that MACs `doorprints.json`, derived from the folder key (the folder key itself is never a MAC key). */
export const controlKey = (p: CryptoProvider, folderKey: Uint8Array) => derive(p, folderKey, BACKUP_KEY_INFO.control);
/** The key that MACs a backup's metadata, derived from the folder key. */
export const backupMetaKey = (p: CryptoProvider, folderKey: Uint8Array) => derive(p, folderKey, BACKUP_KEY_INFO.backupMeta);

/** The names of the `appProperties` of a backup, the MAC label, and the limits a reader enforces on its values. */
export const BACKUP_META = {
  label: 'doorprints-backup-meta/1',
  kindBackup: 'backup',
  houses: 'houses',
  epoch: 'epoch',
  kid: 'kid',
  mac: 'bm',
  kidSize: 16,
  /** 2⁵³ − 1. */
  maxTime: Number.MAX_SAFE_INTEGER,
  maxHouses: 10_000_000,
} as const;

const DECIMAL = /^(0|[1-9][0-9]{0,15})$/;
const HEX64 = /^[0-9a-f]{64}$/;

function decimal(text: string | undefined, max: number): number | null {
  if (text === undefined || !DECIMAL.test(text)) return null;
  const n = Number(text);
  return Number.isSafeInteger(n) && n <= max ? n : null;
}

/**
 * The authenticated facts of one backup (when, how many houses, which key epoch and writer, which file bytes), so a listing can trust them without downloading.
 * The constructor rejects out-of-range values; `macInput` is what the MAC covers. Drive's own times and the file name are never trusted instead.
 */
export class BackupMeta {
  readonly writerKid: Uint8Array;
  readonly ciphertextSha256: Uint8Array;

  constructor(
    /** When the writer made the backup (epoch milliseconds): the order of backups and retention's clock. */
    readonly createdAt: number,
    /** Live houses in it, for the shrink guard. */
    readonly houses: number,
    /** The epoch of the folder key the file (and this MAC) is under. */
    readonly epoch: number,
    writerKid: Uint8Array,
    ciphertextSha256: Uint8Array,
  ) {
    if (!(Number.isSafeInteger(createdAt) && createdAt >= 0 && createdAt <= BACKUP_META.maxTime)) throw new RangeError('createdAt');
    if (!(Number.isSafeInteger(houses) && houses >= 0 && houses <= BACKUP_META.maxHouses)) throw new RangeError('houses');
    if (!(Number.isSafeInteger(epoch) && epoch >= 1)) throw new RangeError('epoch');
    if (writerKid.length !== BACKUP_META.kidSize || ciphertextSha256.length !== 32) throw new RangeError('kid or hash');
    this.writerKid = writerKid.slice();
    this.ciphertextSha256 = ciphertextSha256.slice();
  }

  macInput(): Uint8Array {
    return concat(utf8(BACKUP_META.label), new Uint8Array([0]), i2osp(this.createdAt, 8), u32(this.houses), u32(this.epoch), this.writerKid, this.ciphertextSha256);
  }

  /** The MAC under the folder key of `epoch`. */
  async mac(p: CryptoProvider, folderKey: Uint8Array): Promise<Uint8Array> {
    const k = await backupMetaKey(p, folderKey);
    try {
      return await p.hmacSha256(k, this.macInput());
    } finally {
      k.fill(0);
    }
  }

  /** The `appProperties` of a new upload: `state=partial` until the checksum check (docs/15 §1.4 item 2). */
  appProperties(mac: Uint8Array, deviceId: string): Record<string, string> {
    return {
      [DRIVE_LAYOUT.kind]: BACKUP_META.kindBackup,
      [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.statePartial,
      [DRIVE_LAYOUT.device]: deviceId,
      [DRIVE_LAYOUT.createdAt]: String(this.createdAt),
      [BACKUP_META.houses]: String(this.houses),
      [BACKUP_META.epoch]: String(this.epoch),
      [BACKUP_META.kid]: b64(this.writerKid),
      [BACKUP_META.mac]: b64(mac),
    };
  }

  /** The unverified fields of `file` and its MAC, or null when they are missing or not canonical. */
  static read(file: DriveFile): { meta: BackupMeta; mac: Uint8Array } | null {
    const props = file.appProperties;
    if (props[DRIVE_LAYOUT.kind] !== BACKUP_META.kindBackup) return null;
    const createdAt = decimal(props[DRIVE_LAYOUT.createdAt], BACKUP_META.maxTime);
    const houses = decimal(props[BACKUP_META.houses], BACKUP_META.maxHouses);
    const epoch = decimal(props[BACKUP_META.epoch], 2147483647);
    if (createdAt == null || houses == null || epoch == null || epoch < 1) return null;
    const kidText = props[BACKUP_META.kid];
    const macText = props[BACKUP_META.mac];
    const kid = kidText === undefined ? null : unb64(kidText, BACKUP_META.kidSize);
    const mac = macText === undefined ? null : unb64(macText, 32);
    const sumText = file.sha256Checksum?.toLowerCase();
    if (!kid || !mac || !sumText || !HEX64.test(sumText)) return null;
    return { meta: new BackupMeta(createdAt, houses, epoch, kid, unhex(sumText)), mac };
  }

  /** Whether `mac` is the MAC of `meta` under the folder key of its epoch; false when that epoch is unknown. */
  static async verify(p: CryptoProvider, keys: FolderKeys, meta: BackupMeta, mac: Uint8Array): Promise<boolean> {
    let key: Uint8Array | null;
    try {
      key = await keys.folderKey(meta.epoch);
    } catch {
      key = null;
    }
    if (!key) return false;
    try {
      return constantTimeEquals(await meta.mac(p, key), mac);
    } finally {
      key.fill(0);
    }
  }
}
