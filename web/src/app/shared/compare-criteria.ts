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

import type { TKey } from '../i18n/en';
import { capabilitiesOf } from '../core/kind-capabilities';
import type { KindFields } from '../core/kind-fields';
import type { KindLabels } from '../core/kind-labels';
import type { HouseDto } from '../core/models';
import { criterionName } from './criterion-name';
import { kindValues } from './kind-values';
import { DEFAULT_WEIGHT, compareRanked, defaultCriterion, evaluateScore } from './scoring';
import type { Criterion, Scoring } from './scoring';
import { isBaselineKind } from '../core/kind-capabilities';

/**
 * What Compare scores for a kind (kinds PR 27, docs/03 ADR-36 §18.21): the kind file's `capabilities.compare.criteria`,
 * resolved to a label and a direction. A criterion is one of the kind's checklist items (scored 0 to 5, higher is
 * better) or one of its typed extras (a number or an amount, where the extra's own `better` says which way is better;
 * an extra with none is shown and not marked). The house's criteria are its ten checklist keys, which is what Compare
 * scored before (`compare-house-pins.spec.ts`). The vectors are `docs/schemas/kinds/compare-vectors.json`; the phones'
 * `CompareCriteria` answers the same.
 */
export type Better = 'higher' | 'lower';

export interface CompareCriterion {
  readonly id: string;
  readonly source: 'check' | 'extra';
  /** The translation key of the criterion's name, from the kind's labels. */
  readonly label: TKey;
  /** Which way is better; `null`: neither, so no value is marked. */
  readonly better: Better | null;
}

/** The part of a built-in kind that Compare reads. */
export interface CompareKind {
  readonly id: string;
  readonly labels: KindLabels;
  readonly fields: KindFields;
}

/**
 * The kind's criteria in the kind file's order. An id the kind has neither as a checklist item nor as a visible extra is
 * left out (a kind file cannot make Compare read what the kind does not hold); a kind with no `compare` capability has none.
 */
export function compareCriteria(kind: CompareKind): CompareCriterion[] {
  return resolveCompareCriteria(capabilitiesOf(kind.id).compare?.criteria ?? [], kind);
}

/** {@link compareCriteria} for a given list of ids (the vectors' `resolve` cases try ids a kind file might name wrongly). */
export function resolveCompareCriteria(ids: readonly string[], kind: CompareKind): CompareCriterion[] {
  const out: CompareCriterion[] = [];
  for (const id of ids) {
    const check = kind.labels.check(id);
    if (check) {
      out.push({ id, source: 'check', label: check, better: 'higher' });
      continue;
    }
    const extra = kind.fields.extras.find((f) => f.id === id && !f.was);
    if (!extra || !kind.labels.isVisible(`field.${id}`)) continue;
    const numeric = extra.type === 'number' || extra.type === 'money';
    out.push({ id, source: 'extra', label: kind.fields.label(extra), better: numeric ? (extra.better === 'lower' ? 'lower' : extra.better === 'higher' ? 'higher' : null) : null });
  }
  return out;
}

/**
 * The scoring a kind's candidates are ranked under. The house's is the person's own (their weights, must-haves and custom
 * criteria, as before: the same object comes back). Another kind scores its checklist in the kind file's order, each item
 * Medium and counted when Compare names it and ignored (weight 0) when it does not, under the person's rating share; the
 * person's criteria are the house's and never apply to another kind's items.
 */
export function scoringForKind(kind: CompareKind, base: Scoring): Scoring {
  if (isBaselineKind(kind.id)) return base;
  const counted = new Set(compareCriteria(kind).filter((c) => c.source === 'check').map((c) => c.id));
  const criteria = kind.labels.checklistKeys().map((key, index): Criterion => ({ ...defaultCriterion(key, index), weight: counted.has(key) ? DEFAULT_WEIGHT : 0 }));
  return { criteria, ratingShare: base.ratingShare };
}

/**
 * The checklist rows Compare shows, in order: for the house every criterion that is not archived (the person's own, a weight of 0
 * included, as before); for another kind the items its `compare` names (the others are asked on the page and not compared).
 */
export function comparedRows(kind: CompareKind, scoring: Scoring): Criterion[] {
  const isHouse = isBaselineKind(kind.id);
  return scoring.criteria.filter((c) => c.archived !== true && (isHouse || c.weight > 0));
}

/** A criterion's name in the kind's words: its label key when the kind has the item, else the house's rule (a custom label). */
export function kindCriterionName(kind: Pick<CompareKind, 'labels'>, c: Pick<Criterion, 'key' | 'label'>, t: (key: TKey) => string): string {
  const key = kind.labels.check(c.key);
  return key ? t(key) : criterionName(c, t);
}

/** Indices holding the highest score, when at least one exists and not every value ties (a checklist row). */
export function bestOf(values: (number | null)[]): Set<number> {
  const present = values.filter((v): v is number => v !== null);
  if (present.length === 0) return new Set<number>();
  const max = Math.max(...present);
  const best = new Set<number>();
  values.forEach((v, i) => {
    if (v === max) best.add(i);
  });
  return best.size === values.length && values.length > 1 ? new Set<number>() : best;
}

/**
 * The lowest or highest figure of an extra, by the criterion's direction. Marked only when at least two candidates have a
 * value and they do not all tie (one value, or none, is a tie with itself); no direction, no mark.
 */
export function bestOfExtra(values: (number | null)[], better: Better | null): Set<number> {
  if (!better) return new Set<number>();
  const present = values.filter((n): n is number => n !== null);
  const target = better === 'lower' ? Math.min(...present) : Math.max(...present);
  if (present.every((n) => n === target)) return new Set<number>();
  return new Set(values.flatMap((n, i) => (n === target ? [i] : [])));
}

/** A candidate's figure for an extra: a number, or the amount of a money value; nothing otherwise. */
export function extraFigure(fields: KindFields, h: Pick<HouseDto, 'extrasOther'>, id: string): number | null {
  const v = kindValues(fields, h).find((x) => x.field.id === id);
  return v?.type === 'number' ? v.value : v?.type === 'money' ? v.amount : null;
}

/** What Compare shows for some candidates of one kind, as the vectors pin it. */
export interface CompareOutcome {
  /** The candidate ids, best first (the ranking of docs/11 5.4: must-haves, score, coverage, price, edit, id). */
  readonly ranking: string[];
  /** Each candidate's overall score, 0 to 5, or null. */
  readonly overall: Record<string, number | null>;
  /** For each criterion, the ids holding the best value; none when there is no direction, one value or a tie. */
  readonly best: Record<string, string[]>;
}

/** The pure core of the Compare table: the ranking, the scores and the marked cells of `candidates`, in the given order. */
export function compareOutcome(kind: CompareKind, candidates: readonly HouseDto[], base: Scoring): CompareOutcome {
  const scoring = scoringForKind(kind, base);
  const results = candidates.map((h) => evaluateScore(h.checklist, h.rating, scoring));
  const ranking = candidates
    .map((h, i) => ({ id: h.id, result: results[i], price: undefined, updatedAt: 0 }))
    .sort(compareRanked)
    .map((r) => r.id);
  const best: Record<string, string[]> = {};
  for (const c of compareCriteria(kind)) {
    const values = candidates.map((h) => (c.source === 'check' ? (typeof h.checklist?.[c.id] === 'number' ? h.checklist[c.id] : null) : extraFigure(kind.fields, h, c.id)));
    const marked = c.source === 'check' ? bestOf(values) : bestOfExtra(values, c.better);
    best[c.id] = candidates.filter((_, i) => marked.has(i)).map((h) => h.id);
  }
  return { ranking, overall: Object.fromEntries(candidates.map((h, i) => [h.id, results[i].overall])), best };
}
