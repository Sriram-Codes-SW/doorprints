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

import { KeysError } from './keys-file';
import type { KeysBody } from './keys-file';
import { equalBytes } from './bytes';

/** The highest `keys.json` epoch and revision a device has accepted for one Drive folder. */
export interface KeysWatermark {
  epoch: number;
  revision: number;
}

/** Where a device keeps its watermark (on the website: IndexedDB, with S4b-BL-126). */
export interface KeysWatermarkStore {
  load(): Promise<KeysWatermark | null>;
  save(w: KeysWatermark): Promise<void>;
}

/** The rollback rule, the twin of Kotlin's `KeysGuard` (docs/15 §9.3, docs/02 T-T19). */
export class KeysGuard {
  constructor(private readonly store: KeysWatermarkStore) {}

  async check(epoch: number, revision: number): Promise<void> {
    const seen = await this.store.load();
    if (!seen) return;
    if (revision < seen.revision || epoch < seen.epoch) {
      throw new KeysError('ROLLED_BACK', `revision ${revision} epoch ${epoch} is older than revision ${seen.revision} epoch ${seen.epoch}`);
    }
    if (revision === seen.revision && epoch !== seen.epoch) throw new KeysError('WATERMARK_CONFLICT', `revision ${revision} with another epoch`);
  }

  async accept(epoch: number, revision: number): Promise<void> {
    await this.check(epoch, revision);
    const seen = await this.store.load();
    if (!seen || revision > seen.revision || epoch > seen.epoch) await this.store.save({ epoch, revision });
  }
}

export type RevokedVerdict = 'ACCEPT' | 'SKIP_REVOKED_WRITER' | 'SKIP_OLD_EPOCH_AFTER_REVOKE' | 'SKIP_UNKNOWN_WRITER' | 'NEWER_EPOCH';

/**
 * Which files to skip after a revoke (docs/15 §9.5 iv), the twin of Kotlin's `RevokedEpochRule`. `writtenAt` is not
 * authenticated (Drive's `modifiedTime`): see the residual risk in docs/02 T-T19.
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
