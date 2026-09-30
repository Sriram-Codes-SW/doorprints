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

import type { HouseAnswer, HouseRoom } from '../core/models';
import type { Lang } from '../i18n/languages';
import type { HouseRecord, PhotoRecord, VisitRecord } from '../data/records';
import type { Broker, BrokerRow } from '../shared/broker';
import type { QuestionRow } from '../shared/question';
import { viewingsForCopy } from '../shared/viewing';
import type { Viewing, ViewingRow } from '../shared/viewing';
import { compareRanked, evaluateScore, scoringOf } from '../shared/scoring';
import type { CriterionRow, PreferenceRow, ScoreResult, Scoring } from '../shared/scoring';
import type { LengthUnit } from '../shared/room-sizes';

/** The six deterministic formats of docs/11 §5.2. */
export type ExportFormat = 'html' | 'pdf' | 'csv' | 'xlsx' | 'markdown' | 'backup';

export interface ExportOptions {
  /** Which houses go in: everything, only the shortlist, or the ids the user ticked. */
  scope: 'all' | 'shortlisted' | 'selected';
  /** Only read when `scope` is `selected`. */
  selectedIds: readonly string[];
  /** Rejected houses are useful ("we saw it, it was bad"), but the user can leave them out. */
  includeRejected: boolean;
  photos: 'all' | 'shortlisted' | 'none';
  /** Off means phone numbers and names of owners and brokers are left out of the file entirely. */
  includeContacts: boolean;
  /** The language the file is written in; independent of the language the app is being used in. */
  lang: Lang;
  /** Length unit for room dimensions in readable copies: 'FT' (feet+inches) or 'M' (metres). */
  lengthUnit?: 'FT' | 'M';
}

export const DEFAULT_EXPORT_OPTIONS: ExportOptions = {
  scope: 'all',
  selectedIds: [],
  includeRejected: true,
  photos: 'all',
  includeContacts: true,
  lang: 'en',
};

/** One house with everything that belongs to it, already filtered and ordered. */
export interface ExportHouse {
  house: HouseRecord;
  /** 0–5 overall score, or null when nothing has been scored (same rule as the app: `evaluateScore`). */
  score: number | null;
  /** The whole result under the copy's scoring: coverage and the must-haves the house misses (slice 2). */
  result: ScoreResult;
  visits: readonly VisitRecord[];
  photos: readonly PhotoRecord[];
  rooms: readonly HouseRoom[];
  /** The questions asked about the house, as stored (slice 3a); readable copies list them with `ordered`. */
  answers: readonly HouseAnswer[];
  /**
   * The viewings of the house (slice 3b-1) in the order a house page lists them: the upcoming PLANNED ones (from the
   * copy's own `exportedAt`, so the copy never reads a clock) soonest first, then the rest newest first.
   */
  viewings: readonly Viewing[];
}

/** A broker in the copy, with the houses of the copy that use it (slice 1b). */
export interface ExportBroker {
  id: string;
  /** The last edit, ISO-8601. */
  updatedAt: string | null;
  broker: Broker;
  /** The live houses in this copy linked to the broker, in the copy's house order. */
  houses: readonly { id: string; label: string }[];
}

/** Everything an exporter needs. Built once, then handed to each format writer. */
export interface ExportBundle {
  /** The one timestamp that appears inside a file (cover and manifest); everything else is data. */
  exportedAt: string;
  options: ExportOptions;
  houses: readonly ExportHouse[];
  /** Houses ordered best first, for the ranking table: `compareRanked` (slice 2, docs/11 5.4). */
  ranking: readonly ExportHouse[];
  /** The effective scoring the scores were computed with: the defaults merged with `criteria` and `preferences`. */
  scoring: Scoring;
  /**
   * The criterion records that exist (only what differs from the defaults), oldest edit first then key (the backup's
   * order). Criteria are not contacts, so a copy without contact details keeps them.
   */
  criteria: readonly CriterionRow[];
  /** The preference records that exist (the rating share), in the same order. */
  preferences: readonly PreferenceRow[];
  /**
   * The question bank records that exist, oldest edit first then id (the backup's order). Questions are not contacts,
   * so a copy without contact details keeps them (slice 3a).
   */
  questions: readonly QuestionRow[];
  /**
   * The viewing records of the copy, oldest edit first then id (the backup's order): every live viewing for a copy of
   * every house (`scope: 'all'`, a viewing of a house that is gone included), else those of the houses in the copy.
   * `withWhom` is contact data, so it is removed from every row when the copy has no contact details (slice 3b-1).
   */
  viewings: readonly ViewingRow[];
  /**
   * The brokers in the copy, oldest edit first then id (the backup's order). Empty with no contact details. A copy of
   * every house (`scope: 'all'`) carries every live broker; a partial copy only the brokers its houses use.
   */
  brokers: readonly ExportBroker[];
  counts: { houses: number; visits: number; photos: number };
  /**
   * The length unit of the device that makes the copy (slice 1c): the rooms' sizes are written in it. A local
   * preference, so it is not one of the {@link ExportOptions} that are remembered with the export choices.
   */
  lengthUnit: LengthUnit;
}

/** The contact fields; they are blanked rather than removed so every export has the same shape. */
const CONTACT_FIELDS = ['contactName', 'contactPhone', 'brokerId'] as const;

export interface CollectInput {
  houses: readonly HouseRecord[];
  visits: readonly VisitRecord[];
  photos: readonly PhotoRecord[];
  /** The live brokers of the store (slice 1b); leave out for none. */
  brokers?: readonly BrokerRow[];
  /** The criterion records of the store (slice 2); leave out for the defaults. */
  criteria?: readonly CriterionRow[];
  /** The preference records of the store (slice 2). */
  preferences?: readonly PreferenceRow[];
  /** The question records of the store (slice 3a). */
  questions?: readonly QuestionRow[];
  /** The viewing records of the store (slice 3b-1). */
  viewings?: readonly ViewingRow[];
  /** The length preference of this device; feet when left out. */
  lengthUnit?: LengthUnit;
  exportedAt: string;
  options: ExportOptions;
}

/**
 * Turns the raw store contents into the bundle, applying scope, contacts and photo options.
 *
 * Ordering is fixed at every level — houses and photos by `createdAt` then `id`, visits by `arrivedAt` then `id`,
 * checklist keys alphabetically — so two exports of the same data are byte-for-byte identical (docs/11 §5.2).
 * Tombstones are never exported.
 */
export function collect(input: CollectInput): ExportBundle {
  const { options } = input;
  const live = input.houses.filter((h) => !h.deleted);
  const chosen = live
    .filter((h) => (options.scope === 'shortlisted' ? h.status === 'SHORTLISTED' : true))
    .filter((h) => (options.scope === 'selected' ? options.selectedIds.includes(h.id) : true))
    .filter((h) => options.includeRejected || h.status !== 'REJECTED')
    .map(stripContacts(options.includeContacts))
    .sort(byCreatedThenId);

  const visitsByHouse = new Map<string, VisitRecord[]>();
  for (const visit of input.visits) {
    if (visit.deleted || !visit.houseId) continue;
    const list = visitsByHouse.get(visit.houseId) ?? [];
    list.push(visit);
    visitsByHouse.set(visit.houseId, list);
  }

  const photosByHouse = new Map<string, PhotoRecord[]>();
  for (const photo of input.photos) {
    if (photo.deleted) continue;
    const list = photosByHouse.get(photo.houseId) ?? [];
    list.push(photo);
    photosByHouse.set(photo.houseId, list);
  }

  const criteria = sortRows(input.criteria ?? []);
  const preferences = sortRows(input.preferences ?? []);
  const questions = (input.questions ?? [])
    .slice()
    .sort((a, b) => Date.parse(a.updatedAt ?? '') - Date.parse(b.updatedAt ?? '') || compare(a.id, b.id));
  const scoring = scoringOf(criteria, preferences);
  const chosenIds = new Set(chosen.map((h) => h.id));
  const viewings = (input.viewings ?? [])
    .filter((row) => options.scope === 'all' || chosenIds.has(row.viewing.houseId))
    .map((row) => (options.includeContacts ? row : withoutWhom(row)))
    .sort((a, b) => Date.parse(a.updatedAt ?? '') - Date.parse(b.updatedAt ?? '') || compare(a.id, b.id));
  const viewingsByHouse = new Map<string, Viewing[]>();
  for (const row of viewings) {
    viewingsByHouse.set(row.viewing.houseId, [...(viewingsByHouse.get(row.viewing.houseId) ?? []), row.viewing]);
  }
  const copyNow = Date.parse(input.exportedAt) || 0;

  const houses: ExportHouse[] = chosen.map((house) => {
    const wantPhotos =
      options.photos === 'all' || (options.photos === 'shortlisted' && house.status === 'SHORTLISTED');
    const result = evaluateScore(house.checklist, house.rating, scoring);
    return {
      house,
      score: result.overall,
      result,
      visits: (visitsByHouse.get(house.id) ?? [])
        .slice()
        .sort((a, b) => compare(a.arrivedAt, b.arrivedAt) || compare(a.id, b.id)),
      photos: wantPhotos ? (photosByHouse.get(house.id) ?? []).slice().sort(byCreatedThenId) : [],
      rooms: house.rooms ?? [],
      answers: house.answers ?? [],
      viewings: viewingsForCopy(viewingsByHouse.get(house.id) ?? [], copyNow),
    };
  });

  return {
    exportedAt: input.exportedAt,
    options,
    houses,
    ranking: rank(houses),
    scoring,
    criteria,
    preferences,
    questions,
    viewings,
    brokers: options.includeContacts ? collectBrokers(input.brokers ?? [], houses, options.scope === 'all') : [],
    lengthUnit: input.lengthUnit ?? 'FT',
    counts: {
      houses: houses.length,
      visits: houses.reduce((n, h) => n + h.visits.length, 0),
      photos: houses.reduce((n, h) => n + h.photos.length, 0),
    },
  };
}

/** A viewing record without the person named in `withWhom`. */
function withoutWhom(row: ViewingRow): ViewingRow {
  if (row.viewing.withWhom === undefined) return row;
  const viewing = { ...row.viewing };
  delete viewing.withWhom;
  return { ...row, viewing };
}

/** The brokers of a copy: all of them for a copy of every house, else those a house of the copy is linked to. */
function collectBrokers(rows: readonly BrokerRow[], houses: readonly ExportHouse[], all: boolean): ExportBroker[] {
  const used = new Map<string, { id: string; label: string }[]>();
  for (const { house } of houses) {
    if (!house.brokerId) continue;
    used.set(house.brokerId, [...(used.get(house.brokerId) ?? []), { id: house.id, label: house.label }]);
  }
  return rows
    .filter((row) => all || used.has(row.id))
    .map((row) => ({ id: row.id, updatedAt: row.updatedAt, broker: row.broker, houses: used.get(row.id) ?? [] }))
    .sort((a, b) => Date.parse(a.updatedAt ?? '') - Date.parse(b.updatedAt ?? '') || compare(a.id, b.id));
}

/** Rows in backup order: oldest edit first, then key. */
function sortRows<T extends { key: string; updatedAt: string | null }>(rows: readonly T[]): T[] {
  return rows
    .slice()
    .sort((a, b) => Date.parse(a.updatedAt ?? '') - Date.parse(b.updatedAt ?? '') || compare(a.key, b.key));
}

/**
 * Best first by `compareRanked` (docs/11 5.4; the twin of `Ranking.compare` in `android/shared`): a house that
 * misses no must-have first, then the overall score, the coverage, the lower price, the newer edit, and finally the
 * id, so the ranking table of a phone copy and of a browser copy of the same data lists the houses in one order.
 */
export function rank(houses: readonly ExportHouse[]): ExportHouse[] {
  const key = (entry: ExportHouse) => ({
    id: entry.house.id,
    result: entry.result,
    price: entry.house.price,
    updatedAt: Date.parse(entry.house.updatedAt ?? entry.house.createdAt ?? '') || 0,
  });
  return houses.slice().sort((a, b) => compareRanked(key(a), key(b)));
}

/**
 * Checklist scores with the keys in alphabetical order. Object key order is what `JSON.stringify` writes, so
 * sorting here is what makes a backup byte-stable no matter what order the store handed the keys back in.
 */
export function sortedChecklist(checklist: Record<string, number>): Record<string, number> {
  const out: Record<string, number> = {};
  for (const key of Object.keys(checklist).sort()) out[key] = checklist[key];
  return out;
}

function stripContacts(include: boolean): (house: HouseRecord) => HouseRecord {
  if (include) return (house) => house;
  return (house) => {
    const copy = { ...house };
    for (const field of CONTACT_FIELDS) copy[field] = null;
    return copy;
  };
}

function byCreatedThenId<T extends { createdAt?: string | null; id: string }>(a: T, b: T): number {
  return compare(a.createdAt ?? '', b.createdAt ?? '') || compare(a.id, b.id);
}

function compare(a: string, b: string): number {
  // Code-unit comparison, not localeCompare: collation differs between browsers and would break determinism.
  return a < b ? -1 : a > b ? 1 : 0;
}
