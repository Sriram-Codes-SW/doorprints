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

import { equalBytes } from './bytes';
import type { KeysBody } from './keys-file';

/** The guard lives in keys-file.ts (so only that module can pin); re-exported here under its old name. */
export { KeysGuard, keysOrder, MAX_REVISION_STEP, sameWatermark } from './keys-file';
export type { KeysOrder, KeysWatermark, KeysWatermarkStore } from './keys-file';

/** What to do with one file after a revoke: accept it, or skip it for the named reason. */
export type RevokedVerdict = 'ACCEPT' | 'SKIP_REVOKED_WRITER' | 'SKIP_OLD_EPOCH_AFTER_REVOKE' | 'SKIP_UNKNOWN_WRITER' | 'NEWER_EPOCH';

/**
 * Which files to skip after a revoke (docs/15 §9.5 iv), the twin of Kotlin's `RevokedEpochRule`. `writtenAt` is not
 * authenticated (Drive's `modifiedTime`): see the residual risk in docs/02 RR-26. The recovery key's kid is not a
 * writer: a file naming it is `SKIP_UNKNOWN_WRITER`.
 */
export function revokedEpochRule(body: KeysBody, fileEpoch: number, writerKid: Uint8Array, writtenAt: number): RevokedVerdict {
  if (fileEpoch > body.epoch) return 'NEWER_EPOCH';
  const revokedWriter = body.revoked.find((r) => equalBytes(r.kid, writerKid));
  if (revokedWriter?.isRecovery) return 'SKIP_UNKNOWN_WRITER';
  if (revokedWriter) {
    if (fileEpoch >= revokedWriter.revokedAtEpoch || writtenAt > revokedWriter.revokedAt) return 'SKIP_REVOKED_WRITER';
  } else if (!body.devices.some((d) => equalBytes(d.kid, writerKid))) {
    return 'SKIP_UNKNOWN_WRITER';
  }
  for (const r of body.revoked) if (!r.isRecovery && fileEpoch < r.revokedAtEpoch && writtenAt > r.revokedAt) return 'SKIP_OLD_EPOCH_AFTER_REVOKE';
  return 'ACCEPT';
}
