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

import { RESTORE_WINDOW_DAYS } from './photo-restore-rules';
import type { GonePhoto } from './photo-restore-rules';

/*
 * The 30-day clean-up (docs/15 §5.1): which deleted photos' encrypted files leave the person's own Google Drive. The
 * rules only, pure. A deleted photo's file is kept so *Recently deleted* > *Restore* can bring the photo back
 * (S4b-BL-293), and it is taken away only once it can no longer be wanted. Kotlin twin: `PhotoSweep.kt`.
 */

/**
 * How long a deleted photo's file is kept before the clean-up may take it, in days (docs/15 §1.4, §5.1). It is the
 * restore window itself, not a second 30: the clean-up must never take a file before *Recently deleted* > *Restore*
 * stops promising it, so the two are one number by construction rather than by agreement.
 */
export const SWEEP_WINDOW_DAYS = RESTORE_WINDOW_DAYS;
const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The Drive file ids the clean-up may move to Drive's bin: a deleted photo's file leaves Drive only when its tombstone
 * is older than {@link SWEEP_WINDOW_DAYS} **and** no kept backup refers to it.
 *
 * The second half needs no download. A backup never exports tombstones (`backup-export.ts`: "tombstones are never
 * exported"), so a backup holds only the photos live when it was made: a kept backup made **after** a photo's deletion
 * cannot hold that photo's file. Every kept backup was made after the deletion exactly when the tombstone is older than
 * the **oldest** kept backup's `createdAt` — so `oldestKeptBackupAt` (null when nothing is kept, when nothing can
 * refer) is all this needs, and the backups themselves are never read. Pure.
 *
 * A photo with no Drive file named is skipped: there is nothing to take. Each file id is returned once.
 */
export function photoFilesToSweep(input: {
  readonly photos: readonly GonePhoto[];
  /** The authenticated `createdAt` of the oldest kept backup, or null when no backup is kept. */
  readonly oldestKeptBackupAt: number | null;
  readonly now: number;
}): string[] {
  const older = input.now - SWEEP_WINDOW_DAYS * DAY_MS;
  const out = new Set<string>();
  for (const photo of input.photos) {
    const file = photo.ref?.driveFileId;
    if (file == null || photo.deletedAt >= older) continue;
    if (input.oldestKeptBackupAt != null && photo.deletedAt >= input.oldestKeptBackupAt) continue;
    out.add(file);
  }
  return [...out];
}
