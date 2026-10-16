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

import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../../../docs/schemas/backup-vectors.json';
import {
  RETENTION_DAILY, RETENTION_MONTHLY, RETENTION_WEEKLY, selectRetention,
} from './backup-retention';
import type { RetentionEntry, ShrinkHold } from './backup-retention';
import { CLOCK_SKEW_MS, DAILY_MS, decideBackup, QUOTA_RETRY_MS, RETRY_MS, VERIFY_MS } from './backup-schedule';
import type { ScheduleFailure, ScheduleReason } from './backup-schedule';

/**
 * `docs/schemas/backup-vectors.json`, written by an independent implementation (Python) and read by Kotlin's
 * `BackupVectorsTest` too: retention with the shrink guard, and the automatic schedule (S4b-BL-116).
 */
interface RetentionCase {
  name: string;
  utcOffsetMinutes: number;
  confirmedDrops: string[];
  backups: RetentionEntry[];
  keep: string[];
  prune: string[];
  hold: ShrinkHold | null;
}
interface ScheduleCase {
  name: string;
  input: {
    now: number; enabled: boolean; ready: boolean; manual?: boolean; lastSuccessAt?: number | null; lastAttemptAt?: number | null;
    lastFailure?: ScheduleFailure | null; lastVerifyAt?: number | null;
  };
  expect: { backup: boolean; reason: ScheduleReason; nextAt: number | null; verify: boolean };
}
const vectors = vectorsJson as unknown as {
  constants: Record<string, number>;
  retention: RetentionCase[];
  schedule: ScheduleCase[];
};

describe('backup-vectors.json', () => {
  it('has the constants the code uses', () => {
    const c = vectors.constants;
    expect(c['daily']).toBe(RETENTION_DAILY);
    expect(c['weekly']).toBe(RETENTION_WEEKLY);
    expect(c['monthly']).toBe(RETENTION_MONTHLY);
    expect(c['dailyMs']).toBe(DAILY_MS);
    expect(c['verifyMs']).toBe(VERIFY_MS);
    expect(c['retryMs']).toBe(RETRY_MS);
    expect(c['quotaRetryMs']).toBe(QUOTA_RETRY_MS);
    expect(c['clockSkewMs']).toBe(CLOCK_SKEW_MS);
  });

  it('retention: every case', () => {
    expect(vectors.retention.length).toBe(19);
    for (const v of vectors.retention) {
      const r = selectRetention(v.backups, v.utcOffsetMinutes, new Set(v.confirmedDrops));
      expect(r.keep, v.name).toEqual(v.keep);
      expect(r.prune, v.name).toEqual(v.prune);
      expect(r.hold, v.name).toEqual(v.hold);
    }
  });

  it('schedule: every case', () => {
    expect(vectors.schedule.length).toBe(18);
    for (const v of vectors.schedule) {
      const d = decideBackup(v.input);
      expect(d, v.name).toEqual(v.expect);
    }
  });
});
