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

import { b64, concat, constantTimeEquals, unb64, utf8 } from '../../crypto/bytes';
import { CanonicalJson, exactObj, int, MAX_SAFE, parseJson, str } from '../../crypto/canonical-json';
import { sha256Of } from '../../crypto/crypto-provider';
import type { CryptoProvider } from '../../crypto/crypto-provider';
import type { FolderKeys } from '../../crypto/folder-key';
import type { OpenedKeys } from '../../crypto/keys-file';
import { controlKey } from './backup-meta';

/** Why `doorprints.json` was refused (the kinds of Kotlin's `ControlException`). */
export type ControlErrorKind =
  | 'MALFORMED'
  | 'NOT_CANONICAL'
  | 'UNSUPPORTED_FORMAT'
  | 'UNSUPPORTED_ENCRYPTION'
  | 'UNKNOWN_EPOCH'
  | 'MAC_INVALID'
  | 'ROLLED_BACK'
  | 'FORK_DETECTED'
  | 'CONCURRENT_UPDATE';

/** Why `doorprints.json` was refused; like `KeysError`, no kind may trigger anything destructive. */
export class ControlError extends Error {
  constructor(
    readonly kind: ControlErrorKind,
    message: string,
  ) {
    super(`control ${kind}: ${message}`);
    this.name = 'ControlError';
  }
}

/** The body of `doorprints.json` (docs/15 §5.1, §9.6). Times are epoch milliseconds. */
export interface ControlBody {
  /** Raised by every write; the rollback watermark goes by it. */
  readonly revision: number;
  /** The epoch of the folder key whose derived key MACs it. */
  readonly epoch: number;
  readonly createdAt: number;
  /** Always `dpx/1`. */
  readonly encryption: string;
  /** *Delete all backups* sets it; a backup whose `createdAt` is not after it is never listed again. */
  readonly backupsDeletedAt: number | null;
}

/** The canonical bytes of the control body: the exact input to its MAC. */
export function controlBodyJson(b: ControlBody): Uint8Array {
  return new CanonicalJson()
    .raw('{"revision":').number(b.revision)
    .raw(',"epoch":').number(b.epoch)
    .raw(',"createdAt":').number(b.createdAt)
    .raw(',"encryption":').string(b.encryption)
    .raw(',"backupsDeletedAt":').raw(b.backupsDeletedAt == null ? 'null' : String(b.backupsDeletedAt))
    .raw('}')
    .bytes();
}

/** What a device accepted of one folder's `doorprints.json`. */
export interface ControlWatermark {
  readonly revision: number;
  readonly bodyHash: Uint8Array;
  readonly backupsDeletedAt: number | null;
}

/**
 * Whether two control watermarks are equal (both absent counts as equal); used by the atomic update of the stored watermark.
 */
export function sameControlWatermark(a: ControlWatermark | null, b: ControlWatermark | null): boolean {
  if (!a || !b) return a === b;
  return a.revision === b.revision && a.backupsDeletedAt === b.backupsDeletedAt && constantTimeEquals(a.bodyHash, b.bodyHash);
}

/** Where a device keeps its `ControlWatermark` for one folder (sealed on the device); `compareAndSet` is atomic. */
export interface ControlWatermarkStore {
  load(): Promise<ControlWatermark | null>;
  compareAndSet(expected: ControlWatermark | null, next: ControlWatermark): Promise<boolean>;
}

/** A freshly written `doorprints.json`: the bytes to upload and the body they encode. */
export interface WrittenControl {
  readonly bytes: Uint8Array;
  readonly body: ControlBody;
}

/** The format tag of `doorprints.json`. */
export const CONTROL_FORMAT = 'doorprints-control/1';
/** The encryption format backups in this folder use. */
export const CONTROL_ENCRYPTION = 'dpx/1';
/** The largest `doorprints.json` a reader accepts, in bytes. */
export const CONTROL_MAX_FILE = 4096;
const MAX_TRIES = 4;

/**
 * `doorprints.json`, the folder's control file (docs/15 §5.1, §9.6, §9.9); the twin of Kotlin's `ControlFile`:
 *
 * ```
 * {"format":"doorprints-control/1",
 *  "body":{"revision":R,"epoch":E,"createdAt":ms,"encryption":"dpx/1","backupsDeletedAt":ms|null},
 *  "mac":B64(HMAC-SHA-256(HKDF(folder key of E, "doorprints/dpx1/control"),
 *                         "doorprints-control/1" ‖ 0x00 ‖ canonical body))}
 * ```
 *
 * Canonical JSON as `keys.json`. Read only after `keys.json` passed the pin; rollback refused by its own watermark,
 * ordered by revision, the same revision with another body a fork.
 */
export class ControlFile {
  constructor(private readonly p: CryptoProvider) {}

  /** The first control file of a folder this device just made (revision 1, the current epoch). */
  create(opened: OpenedKeys, now: number): Promise<WrittenControl> {
    return this.write({ revision: 1, epoch: opened.epoch, createdAt: now, encryption: CONTROL_ENCRYPTION, backupsDeletedAt: null }, opened);
  }

  /** The next revision of `current`, under the current epoch; `backupsDeletedAt` changed when given. */
  next(opened: OpenedKeys, current: ControlBody, backupsDeletedAt: number | null = current.backupsDeletedAt): Promise<WrittenControl> {
    if (current.revision >= MAX_SAFE) throw new ControlError('MALFORMED', 'revision limit');
    return this.write(
      { revision: current.revision + 1, epoch: opened.epoch, createdAt: current.createdAt, encryption: CONTROL_ENCRYPTION, backupsDeletedAt },
      opened,
    );
  }

  private async write(body: ControlBody, opened: OpenedKeys): Promise<WrittenControl> {
    const data = concat(utf8(CONTROL_FORMAT), new Uint8Array([0]), controlBodyJson(body));
    return { bytes: this.encode(body, await opened.hmacUnder(opened.epoch, 'doorprints/dpx1/control', data)), body };
  }

  /** Opens `file` with the folder keys of an opened `keys.json`, then moves the watermark in `store`. */
  async open(file: Uint8Array, keys: FolderKeys, store: ControlWatermarkStore): Promise<ControlBody> {
    const { body, mac } = this.parse(file);
    let key: Uint8Array | null;
    try {
      key = await keys.folderKey(body.epoch);
    } catch {
      key = null;
    }
    if (!key) throw new ControlError('UNKNOWN_EPOCH', `epoch ${body.epoch}`);
    try {
      if (!constantTimeEquals(await this.mac(key, body), mac)) throw new ControlError('MAC_INVALID', 'MAC');
    } finally {
      key.fill(0);
    }
    if (body.encryption !== CONTROL_ENCRYPTION) throw new ControlError('UNSUPPORTED_ENCRYPTION', 'encryption');
    await this.accept(body, store);
    return body;
  }

  /** Moves the watermark to `body` (after an `open`, or after this device's own write was read back). */
  async accept(body: ControlBody, store: ControlWatermarkStore): Promise<void> {
    const next: ControlWatermark = { revision: body.revision, bodyHash: sha256Of(this.p, controlBodyJson(body)), backupsDeletedAt: body.backupsDeletedAt };
    for (let i = 0; i < MAX_TRIES; i++) {
      const seen = await store.load();
      if (seen) {
        if (body.revision < seen.revision) throw new ControlError('ROLLED_BACK', `revision ${body.revision} < ${seen.revision}`);
        if (body.revision === seen.revision) {
          if (!constantTimeEquals(seen.bodyHash, next.bodyHash)) throw new ControlError('FORK_DETECTED', `revision ${body.revision}`);
          return;
        }
      }
      if (await store.compareAndSet(seen, next)) return;
    }
    throw new ControlError('CONCURRENT_UPDATE', 'the watermark kept changing');
  }

  private async mac(folderKey: Uint8Array, body: ControlBody): Promise<Uint8Array> {
    const k = await controlKey(this.p, folderKey);
    try {
      return await this.p.hmacSha256(k, concat(utf8(CONTROL_FORMAT), new Uint8Array([0]), controlBodyJson(body)));
    } finally {
      k.fill(0);
    }
  }

  /** Test seam and writer: the exact bytes of a control file (with any MAC). */
  encode(body: ControlBody, mac: Uint8Array): Uint8Array {
    return concat(
      new CanonicalJson().raw('{"format":').string(CONTROL_FORMAT).raw(',"body":').bytes(),
      controlBodyJson(body),
      new CanonicalJson().raw(',"mac":').string(b64(mac)).raw('}').bytes(),
    );
  }

  parse(file: Uint8Array): { body: ControlBody; mac: Uint8Array } {
    const bad = (what: string): never => {
      throw new ControlError('MALFORMED', what);
    };
    if (file.length > CONTROL_MAX_FILE) bad('too large');
    const root = parseJson(file);
    if (root === undefined || root === null || typeof root !== 'object' || Array.isArray(root)) return bad('not a JSON object');
    const format = str((root as Record<string, unknown>)['format']);
    if (format === null) return bad('format');
    if (format !== CONTROL_FORMAT) throw new ControlError('UNSUPPORTED_FORMAT', 'format');
    const r = exactObj(root, 'format', 'body', 'mac') ?? bad('fields');
    const macText = str(r['mac']);
    const mac = (macText === null ? null : unb64(macText, 32)) ?? bad('mac');
    const o = exactObj(r['body'], 'revision', 'epoch', 'createdAt', 'encryption', 'backupsDeletedAt') ?? bad('body');
    const encryption = str(o['encryption']);
    const body: ControlBody = {
      revision: int(o['revision'], 1, MAX_SAFE) ?? bad('revision'),
      epoch: int(o['epoch'], 1, 2147483647) ?? bad('epoch'),
      createdAt: int(o['createdAt'], 0, MAX_SAFE) ?? bad('createdAt'),
      encryption: encryption !== null && encryption.length >= 1 && encryption.length <= 32 ? encryption : bad('encryption'),
      backupsDeletedAt: o['backupsDeletedAt'] === null ? null : (int(o['backupsDeletedAt'], 0, MAX_SAFE) ?? bad('backupsDeletedAt')),
    };
    const again = this.encode(body, mac);
    if (again.length !== file.length || again.some((b, i) => b !== file[i])) throw new ControlError('NOT_CANONICAL', 'not canonical');
    return { body, mac };
  }
}
