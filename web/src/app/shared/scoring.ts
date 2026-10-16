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
 * Custom weighted criteria and the ranking (docs/11 5.4, slice 2 of the Sprint 4b data model). ONE implementation of
 * the arithmetic for the web app; the twin of Kotlin `Scoring`/`HouseScore`/`Ranking` in android/shared, pinned by the
 * same eight vectors V1..V8 (`scoring.spec.ts`, `HouseScoreTest`).
 *
 * A criterion is a record of type `criterion` whose id is the criterion's key; the rating share is a record of type
 * `preference` (`score.ratingShare`). A built-in criterion with no record uses the default, so only what differs from
 * the defaults is ever stored.
 */

/** The `type` of a criterion's / a preference's record envelope. */
export const CRITERION_TYPE = 'criterion';
export const PREFERENCE_TYPE = 'preference';
/** The only preference of this slice: the share of the star rating in the overall score, "0".."1". */
export const RATING_SHARE_KEY = 'score.ratingShare';
export const DEFAULT_RATING_SHARE = 0.5;

/** The ten built-in criteria, in default order. Kept equal to `CHECKLIST` in core/models.ts (`scoring.spec.ts`). */
export const BUILT_IN_KEYS = [
  'water',
  'power',
  'parking',
  'sunlight',
  'ventilation',
  'noise',
  'security',
  'maintenance',
  'neighbourhood',
  'commute',
] as const;

export const MAX_CRITERIA = 40;
export const MAX_CRITERION_LABEL = 60;
export const MAX_PREFERENCE_VALUE = 500;
/** 0 Ignore, 1 Low, 2 Medium, 3 High. */
export type Weight = 0 | 1 | 2 | 3;
export const WEIGHTS: readonly Weight[] = [0, 1, 2, 3];
export const DEFAULT_WEIGHT: Weight = 2;
export const DEFAULT_MIN_SCORE = 3;

/** A record id / key: the same pattern the records store accepts. */
const KEY_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;
/** A custom criterion's key: `c_` and 8 lowercase hex characters. */
const CUSTOM_KEY_PATTERN = /^c_[0-9a-f]{8}$/;

/**
 * One thing the checklist scores a house on: a built-in criterion or a custom one with its own label, how much it
 * counts, and whether it is a must-have.
 */
export interface Criterion {
  key: string;
  /** Custom criteria only (at most 60 characters); a built-in's translated name stays in the apps. */
  label?: string;
  weight: Weight;
  mustHave: boolean;
  /** 1..5: a must-have fails below this score. */
  minScore: number;
  /** Integer >= 0. */
  sort: number;
  /** Written only when true; an archived criterion is hidden and does not count. */
  archived?: boolean;
}

/** The effective scoring: every criterion (defaults merged with records) sorted by `sort` then key, and the share. */
export interface Scoring {
  criteria: readonly Criterion[];
  ratingShare: number;
}

/** A stored criterion record, as read. */
export interface CriterionRow {
  key: string;
  updatedAt: string | null;
  criterion: Criterion;
}

/** A stored preference record, as read. */
export interface PreferenceRow {
  key: string;
  value: string;
  updatedAt: string | null;
}

/** The default of a built-in criterion: Medium, not a must-have, minimum 3, in its position, not archived. */
export function defaultCriterion(key: string, index: number): Criterion {
  return { key, weight: DEFAULT_WEIGHT, mustHave: false, minScore: DEFAULT_MIN_SCORE, sort: index };
}

const DEFAULT_CRITERIA: readonly Criterion[] = BUILT_IN_KEYS.map((key, index) => defaultCriterion(key, index));

export const DEFAULT_SCORING: Scoring = { criteria: DEFAULT_CRITERIA, ratingShare: DEFAULT_RATING_SHARE };

/** Whether the key is one of the ten built-in criteria. */
export function isBuiltInKey(key: string): boolean {
  return (BUILT_IN_KEYS as readonly string[]).includes(key);
}

/** Whether the key has the shape of a custom criterion's key (`c_` and 8 hex characters). */
export function isCustomKey(key: string): boolean {
  return CUSTOM_KEY_PATTERN.test(key);
}

/** True when `key` can be a record id at all. */
export function isCriterionKey(key: string): boolean {
  return KEY_PATTERN.test(key) && key !== '.' && key !== '..';
}

/** The default of a built-in key, or null for any other. */
export function builtInDefault(key: string): Criterion | null {
  const index = (BUILT_IN_KEYS as readonly string[]).indexOf(key);
  return index < 0 ? null : defaultCriterion(key, index);
}

/**
 * Reads a record payload as a criterion. An out-of-range weight, minimum score or sort is unknown and takes the
 * default; a key outside the record-id pattern is untrusted, so the row is `null` and the caller skips it. A
 * built-in never carries a label.
 */
export function criterionFromPayload(key: string, payload: Record<string, unknown> | null | undefined): Criterion | null {
  if (!isCriterionKey(key) || !payload || typeof payload !== 'object') return null;
  const base = builtInDefault(key) ?? defaultCriterion(key, 0);
  const weight = payload['weight'];
  const out: Criterion = {
    key,
    weight: isWeight(weight) ? weight : base.weight,
    mustHave: typeof payload['mustHave'] === 'boolean' ? payload['mustHave'] : base.mustHave,
    minScore: intIn(payload['minScore'], 1, 5) ?? base.minScore,
    sort: intIn(payload['sort'], 0, Number.MAX_SAFE_INTEGER) ?? base.sort,
  };
  const label = payload['label'];
  if (!isBuiltInKey(key) && typeof label === 'string' && label.trim() !== '' && label.length <= MAX_CRITERION_LABEL) {
    out.label = label;
  }
  if (payload['archived'] === true) out.archived = true;
  return out;
}

/** The payload keys in the contract's order: label (custom only), weight, mustHave, minScore, sort, archived (only true). */
export function criterionToPayload(criterion: Criterion): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (!isBuiltInKey(criterion.key) && criterion.label !== undefined && criterion.label.trim() !== '') out['label'] = criterion.label;
  out['weight'] = criterion.weight;
  out['mustHave'] = criterion.mustHave;
  out['minScore'] = criterion.minScore;
  out['sort'] = criterion.sort;
  if (criterion.archived === true) out['archived'] = true;
  return out;
}

/** True when a built-in criterion equals its default, so it needs no record. */
export function isDefaultCriterion(criterion: Criterion): boolean {
  const base = builtInDefault(criterion.key);
  return (
    base !== null &&
    criterion.weight === base.weight &&
    criterion.mustHave === base.mustHave &&
    criterion.minScore === base.minScore &&
    criterion.sort === base.sort &&
    criterion.archived !== true
  );
}

/** The rating share of a preference value: a decimal string "0".."1"; anything else is the default, 0.5. */
export function ratingShareOf(value: string | null | undefined): number {
  if (value === null || value === undefined || value.trim() === '') return DEFAULT_RATING_SHARE;
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 && n <= 1 ? n : DEFAULT_RATING_SHARE;
}

/** The preference value that stores a share ("0.25"). */
export function ratingShareValue(share: number): string {
  return String(Math.round(share * 100) / 100);
}

/**
 * The effective scoring: the defaults for the ten built-ins, replaced by their records, plus the custom records,
 * sorted by `sort` then key. `ratingShare` is the value of the `score.ratingShare` preference.
 */
export function scoringOf(criteria: readonly CriterionRow[], preferences: readonly PreferenceRow[]): Scoring {
  const byKey = new Map<string, Criterion>(DEFAULT_CRITERIA.map((c) => [c.key, c]));
  for (const row of criteria) byKey.set(row.key, row.criterion);
  const share = preferences.find((p) => p.key === RATING_SHARE_KEY);
  return { criteria: sortCriteria([...byKey.values()]), ratingShare: ratingShareOf(share?.value) };
}

/** By `sort`, then key (code-unit order, so the same on every device). */
export function sortCriteria(list: readonly Criterion[]): Criterion[] {
  return [...list].sort((a, b) => a.sort - b.sort || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
}

/** The criteria that count: not archived, weight above 0. */
export function activeCriteria(scoring: Scoring): Criterion[] {
  return scoring.criteria.filter((c) => c.archived !== true && c.weight > 0);
}

/** The result of scoring a house. */
export interface ScoreResult {
  /** 0..5, or null when the house has neither a checklist score that counts nor a rating. */
  overall: number | null;
  /** 0..1, or null when no criterion counts. */
  coverage: number | null;
  /** Keys of must-haves whose score is below the minimum. */
  failedMustHave: string[];
  /** Keys of must-haves with no score yet: "not checked yet", not a failure. */
  uncheckedMustHave: string[];
  /** How many active criteria have a score, and how many there are (the coverage line "Scored 7 of 10 that matter"). */
  scored: number;
  active: number;
}

/**
 * Scores a house (docs/11 5.4). `wc` = Σ(w·s)/Σ(w) over the active criteria that have a score (a 0 is a real score);
 * `overall` blends it with the star rating by the rating share; scores under keys that are no known criterion are
 * ignored. A must-have counts even at weight 0, but never when archived.
 */
export function evaluateScore(
  checklist: Record<string, number> | null | undefined,
  rating: number | null | undefined,
  scoring: Scoring,
): ScoreResult {
  const scores = checklist ?? {};
  let weighted = 0;
  let weightScored = 0;
  let weightAll = 0;
  let scored = 0;
  let active = 0;
  const failedMustHave: string[] = [];
  const uncheckedMustHave: string[] = [];
  for (const c of scoring.criteria) {
    if (c.archived === true) continue;
    const s = scores[c.key];
    if (c.weight > 0) {
      active += 1;
      weightAll += c.weight;
      if (typeof s === 'number' && Number.isFinite(s)) {
        scored += 1;
        weighted += c.weight * s;
        weightScored += c.weight;
      }
    }
    if (c.mustHave) {
      if (typeof s !== 'number' || !Number.isFinite(s)) uncheckedMustHave.push(c.key);
      else if (s < c.minScore) failedMustHave.push(c.key);
    }
  }
  const wc = weightScored > 0 ? weighted / weightScored : null;
  const stars = typeof rating === 'number' && Number.isFinite(rating) ? rating : null;
  const r = scoring.ratingShare;
  const overall = wc !== null && stars !== null ? (1 - r) * wc + r * stars : (wc ?? stars);
  return {
    overall,
    coverage: weightAll > 0 ? weightScored / weightAll : null,
    failedMustHave,
    uncheckedMustHave,
    scored,
    active,
  };
}

/** What the ranking compares. */
export interface RankedHouse {
  id: string;
  result: Pick<ScoreResult, 'overall' | 'coverage' | 'failedMustHave'>;
  price: number | null | undefined;
  /** Epoch milliseconds of the last edit. */
  updatedAt: number;
}

/**
 * The ranking order (docs/11 5.4): (1) no failed must-have before failed, (2) overall descending, none last,
 * (3) coverage descending (none as 0), (4) price ascending, none last, (5) last edit descending, (6) id. Total and
 * stable, so a list sorted with it does not move between reads.
 */
export function compareRanked(a: RankedHouse, b: RankedHouse): number {
  const failed = Number(a.result.failedMustHave.length > 0) - Number(b.result.failedMustHave.length > 0);
  if (failed !== 0) return failed;
  const overall = descNullLast(a.result.overall, b.result.overall);
  if (overall !== 0) return overall;
  const coverage = (b.result.coverage ?? 0) - (a.result.coverage ?? 0);
  if (coverage !== 0) return coverage;
  const priceA = a.price ?? null;
  const priceB = b.price ?? null;
  if (priceA !== priceB) {
    if (priceA === null) return 1;
    if (priceB === null) return -1;
    return priceA - priceB;
  }
  return b.updatedAt - a.updatedAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

/** Orders larger numbers first and unknown (null) values last. */
function descNullLast(a: number | null, b: number | null): number {
  if (a === b) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  return b - a;
}

/** Eight random lowercase hex characters after `c_`: a custom criterion's key. */
export function newCustomKey(): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return 'c_' + Array.from(bytes, (x) => x.toString(16).padStart(2, '0')).join('');
}

/** Whether the value is one of the four weights. */
function isWeight(value: unknown): value is Weight {
  return value === 0 || value === 1 || value === 2 || value === 3;
}

/** The value if it is a whole number from `min` to `max`, else undefined. */
function intIn(value: unknown, min: number, max: number): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max ? value : undefined;
}
