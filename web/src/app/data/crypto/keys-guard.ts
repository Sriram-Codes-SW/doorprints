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

import { constantTimeEquals, equalBytes } from './bytes';
import { sha256Of } from './crypto-provider';
import type { CryptoProvider } from './crypto-provider';
import { keyId } from './folder-key';
import { bodyJson, KeysError } from './keys-file';
import type { KeysBody, OpenedKeys, WrittenKeys } from './keys-file';

/**
 * What a device has accepted of one folder's `keys.json` (Kotlin `KeysWatermark`): the highest (epoch, revision),
 * the pin of that epoch's folder key (`keyId`, HKDF "doorprints/dpx1/key-id") and the SHA-256 of that revision's body.
 */
export interface KeysWatermark {
  epoch: number;
  revision: number;
  keyId: Uint8Array;
  bodyHash: Uint8Array;
}

/** Where a device keeps its watermark (on the website: IndexedDB, with S4b-BL-126). `compareAndSet` must be atomic. */
export interface KeysWatermarkStore {
  load(): Promise<KeysWatermark | null>;
  compareAndSet(expected: KeysWatermark | null, next: KeysWatermark): Promise<boolean>;
}

export function sameWatermark(a: KeysWatermark | null, b: KeysWatermark | null): boolean {
  if (!a || !b) return a === b;
  return a.epoch === b.epoch && a.revision === b.revision && equalBytes(a.keyId, b.keyId) && equalBytes(a.bodyHash, b.bodyHash);
}

/** @internal How a list was proven: by the pin, or by one of the three named first-pin paths. */
export type KeysTrust = 'PINNED' | 'FIRST_PIN' | 'RECOVERY_ANCHOR' | 'CREATED';
export type KeysOrder = 'LOWER' | 'SAME_EPOCH' | 'HIGHER_EPOCH';

/** (epoch, revision) ordered lexicographically: a higher epoch always wins, a lower one never does. */
export function keysOrder(seenEpoch: number, seenRevision: number, epoch: number, revision: number): KeysOrder {
  if (epoch < seenEpoch) return 'LOWER';
  if (epoch > seenEpoch) return 'HIGHER_EPOCH';
  return revision < seenRevision ? 'LOWER' : 'SAME_EPOCH';
}

function higher(next: KeysWatermark, seen: KeysWatermark): boolean {
  return next.epoch > seen.epoch || (next.epoch === seen.epoch && next.revision > seen.revision);
}

const MAX_TRIES = 4;

/**
 * The trust anchor of a device in one folder, the twin of Kotlin's `KeysGuard` (docs/15 §9.9): a list is accepted
 * only if it leads to the pinned folder key (same epoch: same key id, and the same revision means the same body;
 * higher epoch: the whole chain down to the pinned epoch ends at the pinned key; lower epoch: rolled back). No pin:
 * only `KeysFile.openFirstPin`, `KeysFile.openWithRecovery` and `pinCreated` may make one.
 */
export class KeysGuard {
  constructor(
    private readonly p: CryptoProvider,
    private readonly store: KeysWatermarkStore,
  ) {}

  watermark(): Promise<KeysWatermark | null> {
    return this.store.load();
  }

  /** After this device's own write was confirmed by Drive. */
  acceptWritten(written: WrittenKeys): Promise<void> {
    return this.accept(written.opened, 'PINNED');
  }

  /** After `createFirstDevice`'s list was confirmed by Drive: the first pin, only if there is none. */
  pinCreated(written: WrittenKeys): Promise<void> {
    return this.accept(written.opened, 'CREATED');
  }

  /** @internal */
  async accept(opened: OpenedKeys, trust: KeysTrust): Promise<void> {
    const next = await watermarkOf(this.p, opened);
    for (let i = 0; i < MAX_TRIES; i++) {
      const seen = await this.store.load();
      if (!seen) {
        if (trust === 'PINNED') throw new KeysError('NOT_PINNED', 'no pin for this folder yet');
      } else {
        await this.verify(seen, opened, next);
        if (!higher(next, seen)) return;
      }
      if (await this.store.compareAndSet(seen, next)) return;
    }
    throw new KeysError('CONCURRENT_UPDATE', 'the watermark kept changing');
  }

  private async verify(seen: KeysWatermark, opened: OpenedKeys, next: KeysWatermark): Promise<void> {
    const order = keysOrder(seen.epoch, seen.revision, next.epoch, next.revision);
    if (order === 'LOWER') {
      throw new KeysError('ROLLED_BACK', `epoch ${next.epoch} revision ${next.revision} is older than epoch ${seen.epoch} revision ${seen.revision}`);
    }
    if (order === 'SAME_EPOCH') {
      if (!constantTimeEquals(next.keyId, seen.keyId)) throw new KeysError('FORK_DETECTED', `another folder key for epoch ${next.epoch}`);
      if (next.revision === seen.revision && !equalBytes(next.bodyHash, seen.bodyHash)) throw new KeysError('FORK_DETECTED', `another list at revision ${next.revision}`);
      return;
    }
    let pinned: Uint8Array | null = null;
    try {
      pinned = await opened.folderKey(seen.epoch);
    } catch (e) {
      if (!(e instanceof KeysError)) throw e;
    }
    if (!pinned) throw new KeysError('PIN_MISMATCH', `the chain does not reach epoch ${seen.epoch}`);
    const id = await keyId(this.p, pinned);
    pinned.fill(0);
    if (!constantTimeEquals(id, seen.keyId)) throw new KeysError('PIN_MISMATCH', 'the chain does not end at the pinned key');
  }
}

async function watermarkOf(p: CryptoProvider, opened: OpenedKeys): Promise<KeysWatermark> {
  const key = opened.currentFolderKey();
  const id = await keyId(p, key);
  key.fill(0);
  return { epoch: opened.epoch, revision: opened.revision, keyId: id, bodyHash: sha256Of(p, bodyJson(opened.body)) };
}

export type RevokedVerdict = 'ACCEPT' | 'SKIP_REVOKED_WRITER' | 'SKIP_OLD_EPOCH_AFTER_REVOKE' | 'SKIP_UNKNOWN_WRITER' | 'NEWER_EPOCH';

/**
 * Which files to skip after a revoke (docs/15 §9.5 iv), the twin of Kotlin's `RevokedEpochRule`. `writtenAt` is not
 * authenticated (Drive's `modifiedTime`): see the residual risk in docs/02 RR-26. The recovery key's kid is not a
 * writer: a file naming it is `SKIP_UNKNOWN_WRITER`.
 */
export function revokedEpochRule(body: KeysBody, fileEpoch: number, writerKid: Uint8Array, writtenAt: number): RevokedVerdict {
  if (fileEpoch > body.epoch) return 'NEWER_EPOCH';
  const revokedWriter = body.revoked.find((r) => equalBytes(r.kid, writerKid));
  if (revokedWriter) {
    if (fileEpoch >= revokedWriter.revokedAtEpoch || writtenAt > revokedWriter.revokedAt) return 'SKIP_REVOKED_WRITER';
  } else if (!body.devices.some((d) => equalBytes(d.kid, writerKid))) {
    return 'SKIP_UNKNOWN_WRITER';
  }
  for (const r of body.revoked) if (fileEpoch < r.revokedAtEpoch && writtenAt > r.revokedAt) return 'SKIP_OLD_EPOCH_AFTER_REVOKE';
  return 'ACCEPT';
}
