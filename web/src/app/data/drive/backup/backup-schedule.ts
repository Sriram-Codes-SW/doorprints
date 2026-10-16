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

/**
 * When the automatic Drive backup is due (S4b-BL-116; docs/15 §1.3, §1.4 item 3), as one pure function with the clock
 * injected: the twin of Kotlin's `BackupSchedule`; vectors: docs/schemas/backup-vectors.json `schedule`. The website's
 * timer (while a tab is open) asks it and runs `DriveBackupService.backUp` when `backup` is true.
 */

/** The daily backup: 24 hours after the last one that finished. */
export const DAILY_MS = 24 * 60 * 60 * 1000;
/** Once a week the newest backup is downloaded and opened. */
export const VERIFY_MS = 7 * DAILY_MS;
/** After a failure that can pass, the next automatic try waits this long. */
export const RETRY_MS = 30 * 60 * 1000;
/** A full Drive is tried again once a day. */
export const QUOTA_RETRY_MS = DAILY_MS;
/** A last time more than this ahead of the clock means the clock moved back. */
export const CLOCK_SKEW_MS = 5 * 60 * 1000;

/**
 * Why the last run failed, as the schedule needs it: RETRYABLE (offline, rate limit, 5xx), QUOTA (Drive is full),
 * UNAUTHORIZED (connect again) and BLOCKED (only a manual run tries again).
 */
export type ScheduleFailure = 'RETRYABLE' | 'QUOTA' | 'UNAUTHORIZED' | 'BLOCKED';

/** What `decideBackup` needs: the clock, the person's switches, and the outcome of the last runs. */
export interface ScheduleInput {
  readonly now: number;
  /** *Automatic backup and sync* is on. */
  readonly enabled: boolean;
  /** The folder is open on this device. */
  readonly ready: boolean;
  /** The person tapped *Back up to Google Drive now*. */
  readonly manual?: boolean;
  readonly lastSuccessAt?: number | null;
  readonly lastAttemptAt?: number | null;
  readonly lastFailure?: ScheduleFailure | null;
  readonly lastVerifyAt?: number | null;
}

/** Why `decideBackup` answered as it did; shown in logs and tests, never to the person verbatim. */
export type ScheduleReason =
  | 'NOT_READY' | 'MANUAL' | 'DISABLED' | 'NEEDS_CONNECT' | 'BLOCKED' | 'WAIT_QUOTA' | 'WAIT_RETRY' | 'FIRST'
  | 'CLOCK_CHANGED' | 'DAILY' | 'NOT_DUE';

/** `backup`: run now. `nextAt`: when to ask again (null: only after something changes). `verify`: open the newest backup. */
export interface ScheduleDecision {
  readonly backup: boolean;
  readonly reason: ScheduleReason;
  readonly nextAt: number | null;
  readonly verify: boolean;
}

/**
 * Decides whether a backup is due, as a pure function of the clock and the last results.
 * A manual run always goes. Otherwise: nothing when automatic backup is off, back-off after a failure (30 minutes, a day when the Drive is full, never for a lost sign-in or a blocked folder), then a backup if there is none yet, the clock moved back, or a day has passed. `verify` asks separately for the newest backup to be opened and checked.
 */
export function decideBackup(input: ScheduleInput): ScheduleDecision {
  const now = input.now;
  const lastSuccessAt = input.lastSuccessAt ?? null;
  const lastVerifyAt = input.lastVerifyAt ?? null;
  const lastFailure = input.lastFailure ?? null;
  const verify =
    input.ready && lastSuccessAt != null && lastFailure == null &&
    (lastVerifyAt == null || now - lastVerifyAt >= VERIFY_MS || lastVerifyAt > now + CLOCK_SKEW_MS);
  const no = (reason: ScheduleReason, nextAt: number | null): ScheduleDecision => ({ backup: false, reason, nextAt, verify });
  if (!input.ready) return { backup: false, reason: 'NOT_READY', nextAt: null, verify: false };
  if (input.manual) return { backup: true, reason: 'MANUAL', nextAt: null, verify };
  if (!input.enabled) return no('DISABLED', null);
  const lastAttempt = input.lastAttemptAt ?? null;
  const attempt = lastAttempt != null && lastAttempt <= now + CLOCK_SKEW_MS ? lastAttempt : null;
  switch (lastFailure) {
    case 'UNAUTHORIZED':
      return no('NEEDS_CONNECT', null);
    case 'BLOCKED':
      return no('BLOCKED', null);
    case 'QUOTA':
      if (attempt != null && now - attempt < QUOTA_RETRY_MS) return no('WAIT_QUOTA', attempt + QUOTA_RETRY_MS);
      break;
    case 'RETRYABLE':
      if (attempt != null && now - attempt < RETRY_MS) return no('WAIT_RETRY', attempt + RETRY_MS);
      break;
    default:
      break;
  }
  if (lastSuccessAt == null) return { backup: true, reason: 'FIRST', nextAt: null, verify };
  if (lastSuccessAt > now + CLOCK_SKEW_MS) return { backup: true, reason: 'CLOCK_CHANGED', nextAt: null, verify };
  if (now - lastSuccessAt >= DAILY_MS) return { backup: true, reason: 'DAILY', nextAt: null, verify };
  return no('NOT_DUE', lastSuccessAt + DAILY_MS);
}
