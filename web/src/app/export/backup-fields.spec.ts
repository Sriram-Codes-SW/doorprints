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
import { FIXTURE_BROKERS, FIXTURE_CRITERIA, FIXTURE_PREFERENCES, FIXTURE_QUESTIONS, FIXTURE_VIEWINGS, FIXTURE_EXPORTED_AT, FIXTURE_HOUSES, FIXTURE_OPTIONS, FIXTURE_PHOTOS, FIXTURE_VISITS } from './golden/fixture';

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
    brokers: object[];
    criteria: object[];
    preferences: object[];
    questions: object[];
    viewings: object[];
  };
  const written = buildBackupData(
    collect({
      houses: FIXTURE_HOUSES,
      visits: FIXTURE_VISITS,
      photos: FIXTURE_PHOTOS,
      brokers: FIXTURE_BROKERS,
      criteria: FIXTURE_CRITERIA,
      preferences: FIXTURE_PREFERENCES,
      questions: FIXTURE_QUESTIONS,
      viewings: FIXTURE_VIEWINGS,
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
  it('writes exactly the format\'s broker keys, for the full broker and the sparse one', () => {
    expect(Object.keys(written.brokers?.[1] ?? {})).toEqual(Object.keys(sample.brokers[1]));
    expect(Object.keys(written.brokers?.[0] ?? {})).toEqual(Object.keys(sample.brokers[0]));
  });
  it('writes exactly the format\'s criterion keys: the archived built-in, the custom one with its label, the must-have', () => {
    const criteria = written.criteria ?? [];
    expect(criteria.map((c) => c.key)).toEqual(['noise', 'c_1a2b3c4d', 'water']);
    for (let i = 0; i < 3; i++) expect(Object.keys(criteria[i])).toEqual(Object.keys(sample.criteria[i]));
  });
  it('writes exactly the format\'s preference keys', () => {
    expect(Object.keys(written.preferences?.[0] ?? {})).toEqual(Object.keys(sample.preferences[0]));
  });
  it('writes exactly the format\'s question keys: the union over the rows, since only the archived one has `archived`', () => {
    const rows = written.questions ?? [];
    expect(rows.map((q) => q.id)).toEqual(['qd_deposit', 'qd_maintenance', 'q_9f8e7d6c']);
    rows.forEach((row, i) => expect(Object.keys(row)).toEqual(Object.keys(sample.questions[i])));
    const union = new Set(rows.flatMap((q) => Object.keys(q)));
    expect([...union].sort()).toEqual([...new Set(sample.questions.flatMap((q) => Object.keys(q)))].sort());
  });
  it('writes exactly the format\'s viewing keys: the union over the rows, since huntReminder, withWhom, notes and visitId are optional', () => {
    const rows = written.viewings ?? [];
    expect(rows.map((v) => v.id)).toEqual(['v_3c4d5e6f', 'v_a1b2c3d4']);
    rows.forEach((row, i) => expect(Object.keys(row)).toEqual(Object.keys(sample.viewings[i])));
    const union = new Set(rows.flatMap((v) => Object.keys(v)));
    expect([...union].sort()).toEqual([...new Set(sample.viewings.flatMap((v) => Object.keys(v)))].sort());
    for (const key of ['huntReminder', 'withWhom', 'notes', 'visitId']) expect(union.has(key), key).toBe(true);
    expect(written.viewings?.map((v) => v.updatedAt)).toEqual((sample.viewings as { updatedAt: number }[]).map((v) => v.updatedAt));
  });
  it('writes exactly the format\'s answer keys, for the full answer and the ad hoc one, right after rooms', () => {
    const answers = written.houses[0].answers ?? [];
    const sampleAnswers = (sample.houses[0] as unknown as { answers: object[] }).answers;
    expect(Object.keys(answers[0])).toEqual(Object.keys(sampleAnswers[0]));
    expect(Object.keys(answers[1])).toEqual(Object.keys(sampleAnswers[1]));
    const keys = Object.keys(written.houses[0]);
    expect(keys.indexOf('answers')).toBe(keys.indexOf('rooms') + 1);
    expect(keys.indexOf('brokerId')).toBe(keys.indexOf('answers') + 1);
  });
  it('writes the sample\'s format id and top-level keys', () => {
    expect(written.format).toBe((sample as unknown as { format: string }).format);
    expect(Object.keys(written)).toEqual(Object.keys(sample));
  });
});
