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

// The checklist criteria and the rating share of the browser's store (docs/11 5.4, slice 2): records of type `criterion`
// and `preference`, read as typed rows and merged with the defaults into the effective scoring, and the saves that
// refuse what the server would refuse (a bad key or label, the 41st criterion). It is separate from LocalStore
// (S4b-BL-168) because the criteria page, the compare and map pages, the house page, the second-viewing prompt and the
// export each read the scoring; it keeps no rows of its own and goes through RecordStore. The only other kind it
// reads is the houses, to refuse deleting a criterion a house still has a score under.
import { LocalDataError } from '../core/local-error';
import type { HouseRecord } from './records';
import type { RecordStore } from './record-store';
import {
  BUILT_IN_KEYS,
  CRITERION_TYPE,
  DEFAULT_RATING_SHARE,
  MAX_CRITERIA,
  MAX_CRITERION_LABEL,
  PREFERENCE_TYPE,
  RATING_SHARE_KEY,
  criterionFromPayload,
  criterionToPayload,
  isBuiltInKey,
  isCustomKey,
  isDefaultCriterion,
  newCustomKey,
  ratingShareValue,
  scoringOf,
} from '../shared/scoring';
import type { Criterion, CriterionRow, PreferenceRow, Scoring, Weight } from '../shared/scoring';

export class CriteriaStore {
  /**
   * @param records the record rows every criterion and preference goes through
   * @param houses every house row, deleted ones included (to see which criteria a house has a score under)
   */
  constructor(
    private readonly records: RecordStore,
    private readonly houses: () => Promise<HouseRecord[]>,
  ) {}

  /** The live criterion records (only what differs from the defaults), oldest edit first; a bad key is skipped. */
  async rows(): Promise<CriterionRow[]> {
    const out: CriterionRow[] = [];
    for (const row of await this.records.ofType(CRITERION_TYPE)) {
      const criterion = criterionFromPayload(row.id, row.payload);
      if (criterion) out.push({ key: row.id, updatedAt: row.updatedAt ?? null, criterion });
    }
    return out;
  }

  /** The live preference records, oldest edit first; a row without a text value is skipped. */
  async preferenceRows(): Promise<PreferenceRow[]> {
    const out: PreferenceRow[] = [];
    for (const row of await this.records.ofType(PREFERENCE_TYPE)) {
      const value = row.payload['value'];
      if (typeof value === 'string') out.push({ key: row.id, value, updatedAt: row.updatedAt ?? null });
    }
    return out;
  }

  /** The effective scoring: the defaults merged with the criterion records and the rating-share preference. */
  async scoring(): Promise<Scoring> {
    return scoringOf(await this.rows(), await this.preferenceRows());
  }

  /**
   * Saves a criterion, writing only what differs from the defaults: a built-in that equals its default has its record
   * deleted, any other is written. A custom criterion needs a label (at most 60 characters); a new one is refused
   * when there are already {@link MAX_CRITERIA} criteria (the ten built-ins included).
   *
   * @throws LocalDataError `error.badRecord` for a key that is neither built-in nor custom, or a bad label;
   *   `criteria.max` when the cap is reached.
   */
  async save(criterion: Criterion, now: number = Date.now()): Promise<void> {
    const builtIn = isBuiltInKey(criterion.key);
    if (!builtIn && !isCustomKey(criterion.key)) throw new LocalDataError('error.badRecord');
    if (builtIn && isDefaultCriterion(criterion)) {
      await this.records.delete(CRITERION_TYPE, criterion.key, now);
      return;
    }
    if (!builtIn) {
      const label = criterion.label?.trim() ?? '';
      if (label === '' || label.length > MAX_CRITERION_LABEL) throw new LocalDataError('error.badRecord');
      if (!(await this.records.get(CRITERION_TYPE, criterion.key)) && (await this.count()) >= MAX_CRITERIA) {
        throw new LocalDataError('criteria.max');
      }
    }
    await this.records.save(CRITERION_TYPE, criterion.key, criterionToPayload({ ...criterion, label: criterion.label?.trim() }), now);
  }

  /** Saves several criteria (a re-ordering, a weight change on each): each as {@link save}. */
  async saveMany(list: readonly Criterion[], now: number = Date.now()): Promise<void> {
    for (const criterion of list) await this.save(criterion, now);
  }

  /**
   * Adds a custom criterion at the end of the list: a new key `c_` and 8 lowercase hex characters (a key that clashes
   * with any record, a deleted one included, is drawn again), weight Medium unless given, not a must-have.
   * `newKey` is a seam for tests.
   *
   * @throws LocalDataError `error.badRecord` for a blank or too long label; `criteria.max` at 40 criteria.
   */
  async add(label: string, weight: Weight = 2, now: number = Date.now(), newKey: () => string = newCustomKey): Promise<Criterion> {
    const text = label.trim();
    if (text === '' || text.length > MAX_CRITERION_LABEL) throw new LocalDataError('error.badRecord');
    if ((await this.count()) >= MAX_CRITERIA) throw new LocalDataError('criteria.max');
    const key = await this.records.freshId(CRITERION_TYPE, newKey);
    const sort = (await this.scoring()).criteria.reduce((max, c) => Math.max(max, c.sort), -1) + 1;
    const criterion: Criterion = { key, label: text, weight, mustHave: false, minScore: 3, sort };
    await this.save(criterion, now);
    return criterion;
  }

  /**
   * Deletes a custom criterion (a tombstone). Refused while any live house has a score under its key, so no score is
   * left without a name; archive it instead. Built-ins can only be archived.
   *
   * @throws LocalDataError `error.badRecord` for a built-in or unknown key; `criteria.inUse` when a house scored it.
   */
  async delete(key: string, now: number = Date.now()): Promise<void> {
    if (!isCustomKey(key)) throw new LocalDataError('error.badRecord');
    if (await this.inUse(key)) throw new LocalDataError('criteria.inUse');
    await this.records.delete(CRITERION_TYPE, key, now);
  }

  /** True when a live house has a checklist score under `key`. */
  async inUse(key: string): Promise<boolean> {
    return (await this.houses()).some((h) => !h.deleted && typeof h.checklist?.[key] === 'number');
  }

  /** Stores the rating share (0..1); the default 0.5 needs no record, so it deletes it. */
  async setRatingShare(share: number, now: number = Date.now()): Promise<void> {
    const value = ratingShareValue(Math.min(1, Math.max(0, share)));
    if (Number(value) === DEFAULT_RATING_SHARE) await this.records.delete(PREFERENCE_TYPE, RATING_SHARE_KEY, now);
    else await this.records.save(PREFERENCE_TYPE, RATING_SHARE_KEY, { value }, now);
  }

  /** "Reset to defaults": deletes every criterion and preference record (tombstones, so other devices follow). */
  async reset(now: number = Date.now()): Promise<void> {
    for (const row of await this.records.ofType(CRITERION_TYPE)) await this.records.delete(CRITERION_TYPE, row.id, now);
    for (const row of await this.records.ofType(PREFERENCE_TYPE)) await this.records.delete(PREFERENCE_TYPE, row.id, now);
  }

  /** The ten built-ins plus the live custom records: what the cap of 40 counts (archived ones included). */
  private async count(): Promise<number> {
    const custom = (await this.records.ofType(CRITERION_TYPE)).filter((r) => !isBuiltInKey(r.id)).length;
    return BUILT_IN_KEYS.length + custom;
  }
}
