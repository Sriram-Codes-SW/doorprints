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
import { buildBackupData } from './backup-export';
import { collect } from './export-model';
import { GOLDEN_BACKUP_DATA_JSON } from './golden/backup.golden';
import { FIXTURE_EXPORTED_AT, FIXTURE_HOUSES, FIXTURE_OPTIONS, FIXTURE_PHOTOS, FIXTURE_VISITS } from './golden/fixture';

/**
 * Completeness of the backup writer against the format (readiness review 2026-09-29, docs/14 §8 finding 4): the keys
 * the web writes for a full house, visit and photo are exactly the keys of `docs/schemas/backup-sample.json` (its
 * byte copy `GOLDEN_BACKUP_DATA_JSON`), in order. Android (`BackupFieldsTest`) and the server (`BackupParityTest`)
 * check their models against the same sample, so a new field (Sprint 4c) lands in all four together.
 */
describe('backup fields', () => {
  const sample = JSON.parse(GOLDEN_BACKUP_DATA_JSON) as {
    houses: object[];
    visits: object[];
    photos: object[];
  };
  const written = buildBackupData(
    collect({
      houses: FIXTURE_HOUSES,
      visits: FIXTURE_VISITS,
      photos: FIXTURE_PHOTOS,
      exportedAt: FIXTURE_EXPORTED_AT,
      options: FIXTURE_OPTIONS,
    }),
  );

  it('writes exactly the format\'s house keys', () =>
    expect(Object.keys(written.houses[0])).toEqual(Object.keys(sample.houses[0])));
  it('writes exactly the format\'s visit keys', () =>
    expect(Object.keys(written.visits[0])).toEqual(Object.keys(sample.visits[0])));
  it('writes exactly the format\'s photo keys', () =>
    expect(Object.keys(written.photos[0])).toEqual(Object.keys(sample.photos[0])));
});
