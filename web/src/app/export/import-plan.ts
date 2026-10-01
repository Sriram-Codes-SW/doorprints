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

import { incomingWins } from '../shared/photo-tags';
import { BUILT_IN_KEYS, MAX_CRITERIA } from '../shared/scoring';
import { MAX_QUESTIONS } from '../shared/question';
import type { BackupBroker, BackupCriterion, BackupData, BackupHouse, BackupPhoto, BackupQuestion, BackupVisit, BackupViewing } from './backup-export';
import { photoEntry } from './photo-names';
import { MAX_FLOOR, MIN_FLOOR } from '../data/records';

/**
 * The pure part of importing a backup on the website (S4b-BL-75): the TypeScript port of Kotlin's `ImportPlan`
 * (`android/shared/.../export/ImportPlan.kt`), so a file previews and merges the same in a browser as on a phone. It
 * never touches IndexedDB, a file or a clock. **The preview promises exactly what the plan writes**: every filter of
 * {@link plan} has its counterpart in {@link preview}. Merge by id, the newest `updatedAt` wins, equal writes nothing,
 * an import never deletes, except an update file's deletions applied by an update import (S4b-BL-82).
 * `docs/schemas/import-vectors.json` holds the cases both stacks run.
 */
export type ImportMode = 'MERGE' | 'COPY';

/** What is already in this browser, tombstones included (`updatedAt` in epoch ms by id). */
export interface LocalVersions {
  houses: ReadonlyMap<string, number>;
  visits: ReadonlyMap<string, number>;
  photoIds: ReadonlySet<string>;
  deletedHouseIds: ReadonlySet<string>;
  scoredHouseIds: ReadonlySet<string>;
  unlinkedVisitIds: ReadonlySet<string>;
  /** Tombstones the server has (pushed or pulled): their photos come back under fresh ids. `null`: cannot tell, all of them. */
  syncedDeletedHouseIds: ReadonlySet<string> | null;
  brokers: ReadonlyMap<string, number>;
  criteria: ReadonlyMap<string, number>;
  preferences: ReadonlyMap<string, number>;
  questions: ReadonlyMap<string, number>;
  viewings: ReadonlyMap<string, number>;
  areas: ReadonlyMap<string, number>;
  places: ReadonlyMap<string, number>;
  areaNotes: ReadonlyMap<string, number>;
  photoMeta: ReadonlyMap<string, number>;
  liveQuestions: ReadonlySet<string>;
  liveCriteria: ReadonlySet<string>;
}

export const EMPTY_LOCAL: LocalVersions = {
  houses: new Map(), visits: new Map(), photoIds: new Set(), deletedHouseIds: new Set(), scoredHouseIds: new Set(),
  unlinkedVisitIds: new Set(), syncedDeletedHouseIds: null, brokers: new Map(), criteria: new Map(), preferences: new Map(),
  questions: new Map(), viewings: new Map(), areas: new Map(), places: new Map(), areaNotes: new Map(), photoMeta: new Map(),
  liveQuestions: new Set(), liveCriteria: new Set(),
};

export interface ImportFlags {
  mode: ImportMode;
  /** MERGE: bring back the houses deleted here that the file has. */
  restoreDeleted?: boolean;
  /** MERGE: *Keep mine, add only what's new*. */
  skipUpdates?: boolean;
  /** An update import (`isUpdate(manifest)`): the file's `deleted` list applies (S4b-BL-82). */
  applyDeletions?: boolean;
}

/** "*a* new, *b* newer in file, *c* newer here", per kind: Kotlin `ImportPreview`, field for field. */
export interface ImportPreview {
  mode: ImportMode;
  newHouses: number;
  updatedHouses: number;
  newerHereHouses: number;
  unchangedHouses: number;
  newVisits: number;
  updatedVisits: number;
  newerHereVisits: number;
  unchangedVisits: number;
  newPhotos: number;
  skippedPhotos: number;
  photosMissingFromFile: number;
  checklistsCleared: number;
  deletedHereHouses: number;
  deletedHereVisits: number;
  deletedHerePhotos: number;
  restoredHouses: number;
  keptMineHouses: number;
  keptMineVisits: number;
  relinkedVisits: number;
  newBrokers: number;
  updatedBrokers: number;
  newCriteria: number;
  updatedCriteria: number;
  newPreferences: number;
  updatedPreferences: number;
  newQuestions: number;
  updatedQuestions: number;
  newViewings: number;
  updatedViewings: number;
  newAreas: number;
  updatedAreas: number;
  newPlaces: number;
  updatedPlaces: number;
  newAreaNotes: number;
  updatedAreaNotes: number;
  updatedPhotoMeta: number;
  removedHouses: number;
  /**
   * Houses the import writes whose `floor` is outside -5..200 (S4b-BL-104 d): the store reads such a floor as unknown,
   * so the house lands with its floor blank and the preview warns. The server refuses the file; a device stays tolerant.
   * A note only: not part of `isEmpty` or `overwrites`.
   */
  floorsLeftBlank: number;
  /** True when the import would change nothing. */
  isEmpty: boolean;
  /** Rows that would be replaced. */
  overwrites: number;
}

/** The rows to write, already merged or given new ids: Kotlin `ImportActions`. */
export interface ImportActions {
  mode: ImportMode;
  houses: BackupHouse[];
  visits: BackupVisit[];
  photos: BackupPhoto[];
  /** A photo row's (new) id to the ZIP entry its bytes come from. */
  photoSources: Map<string, string>;
  updatedHouseIds: Set<string>;
  updatedVisitIds: Set<string>;
  restoredHouseIds: Set<string>;
  relinkedVisitIds: Set<string>;
  brokers: BackupBroker[];
  updatedBrokerIds: Set<string>;
  criteria: BackupCriterion[];
  preferences: NonNullable<BackupData['preferences']>;
  questions: BackupQuestion[];
  viewings: BackupViewing[];
  areas: NonNullable<BackupData['areas']>;
  places: NonNullable<BackupData['places']>;
  areaNotes: NonNullable<BackupData['areaNotes']>;
  photoMeta: BackupPhoto[];
  removedHouseIds: string[];
}

type Verdict = 'NEW' | 'INCOMING_NEWER' | 'LOCAL_NEWER' | 'SAME';
type HouseOutcome = 'NEW' | 'UPDATE' | 'RESTORE' | 'DELETED_HERE' | 'KEPT_MINE' | 'NEWER_HERE' | 'SAME';
type VisitOutcome = 'NEW' | 'UPDATE' | 'RELINK' | 'KEPT_MINE' | 'NEWER_HERE' | 'SAME';

const WRITES: ReadonlySet<HouseOutcome> = new Set<HouseOutcome>(['NEW', 'UPDATE', 'RESTORE']);

function compare(local: number | undefined, incoming: number): Verdict {
  if (local === undefined) return 'NEW';
  if (incoming > local) return 'INCOMING_NEWER';
  return incoming === local ? 'SAME' : 'LOCAL_NEWER';
}

function houseOutcome(h: BackupHouse, local: LocalVersions, restore: boolean, skip: boolean): HouseOutcome {
  const verdict = compare(local.houses.get(h.id), h.updatedAt);
  const deletedHere = local.deletedHouseIds.has(h.id);
  if (verdict === 'NEW') return 'NEW';
  if (verdict === 'INCOMING_NEWER' && !skip) return 'UPDATE';
  if (deletedHere && restore) return 'RESTORE';
  if (deletedHere) return 'DELETED_HERE';
  if (verdict === 'INCOMING_NEWER') return 'KEPT_MINE';
  return verdict === 'LOCAL_NEWER' ? 'NEWER_HERE' : 'SAME';
}

function visitOutcome(v: BackupVisit, local: LocalVersions, overTombstone: ReadonlySet<string>, skip: boolean): VisitOutcome {
  const verdict = compare(local.visits.get(v.id), v.updatedAt);
  if (verdict === 'NEW') return 'NEW';
  if (verdict === 'INCOMING_NEWER' && !skip) return 'UPDATE';
  if (v.houseId != null && overTombstone.has(v.houseId) && local.unlinkedVisitIds.has(v.id)) return 'RELINK';
  if (verdict === 'INCOMING_NEWER') return 'KEPT_MINE';
  return verdict === 'LOCAL_NEWER' ? 'NEWER_HERE' : 'SAME';
}

function keptHouseIds(data: BackupData, local: LocalVersions, restore: boolean, skip: boolean): Set<string> {
  const kept = new Set([...local.houses.keys()].filter((id) => !local.deletedHouseIds.has(id)));
  for (const h of data.houses) if (WRITES.has(houseOutcome(h, local, restore, skip))) kept.add(h.id);
  return kept;
}

function overTombstoneIds(data: BackupData, local: LocalVersions, restore: boolean, skip: boolean): Set<string> {
  return new Set(
    data.houses
      .filter((h) => local.deletedHouseIds.has(h.id))
      .filter((h) => {
        const o = houseOutcome(h, local, restore, skip);
        return o === 'RESTORE' || o === 'UPDATE';
      })
      .map((h) => h.id),
  );
}

function settingWrites(key: string, at: number, local: ReadonlyMap<string, number>, skip: boolean): boolean {
  const v = compare(local.get(key), at);
  return v === 'NEW' || (v === 'INCOMING_NEWER' && !skip);
}

function withinCap<T>(rows: readonly T[], id: (r: T) => string, live: ReadonlySet<string>, max: number, start = live.size): T[] {
  let count = start;
  const added = new Set<string>();
  return rows.filter((r) => {
    const k = id(r);
    if (live.has(k) || added.has(k)) return true;
    if (count < max) {
      count++;
      added.add(k);
      return true;
    }
    return false;
  });
}

function questionWrites(data: BackupData, local: LocalVersions, skip: boolean): BackupQuestion[] {
  const rows = (data.questions ?? []).filter((q) => settingWrites(q.id, q.updatedAt, local.questions, skip));
  return withinCap(rows, (q) => q.id, local.liveQuestions, MAX_QUESTIONS);
}

function criteriaWrites(data: BackupData, local: LocalVersions, skip: boolean): BackupCriterion[] {
  const rows = (data.criteria ?? []).filter((c) => settingWrites(c.key, c.updatedAt, local.criteria, skip));
  const builtIn: readonly string[] = BUILT_IN_KEYS;
  const custom = [...local.liveCriteria].filter((k) => !builtIn.includes(k));
  return withinCap(rows, (c) => c.key, new Set([...custom, ...builtIn]), MAX_CRITERIA, builtIn.length + custom.length);
}

function counts(keys: readonly string[], local: ReadonlyMap<string, number>): [number, number] {
  const updated = keys.filter((k) => local.has(k)).length;
  return [keys.length - updated, updated];
}

function settingsCounts(rows: readonly { key: string; at: number }[], local: ReadonlyMap<string, number>, skip: boolean): [number, number] {
  let fresh = 0;
  let updated = 0;
  for (const { key, at } of rows) {
    if (!settingWrites(key, at, local, skip)) continue;
    if (local.has(key)) updated++;
    else fresh++;
  }
  return [fresh, updated];
}

const keyed = <T extends { id: string; updatedAt: number }>(rows: readonly T[] | undefined) => (rows ?? []).map((r) => ({ key: r.id, at: r.updatedAt }));

function photoMetaUpdates(data: BackupData, local: LocalVersions, skip: boolean): BackupPhoto[] {
  if (skip) return [];
  return data.photos.filter((p) => local.photoIds.has(p.id) && incomingWins(local.photoMeta.get(p.id) ?? 0, p.metaUpdatedAt ?? 0));
}

/** The live houses here an update import deletes (S4b-BL-82): Kotlin `ImportPlan.removals`. */
function removals(data: BackupData, local: LocalVersions, flags: ImportFlags): string[] {
  if (!flags.applyDeletions || flags.mode === 'COPY' || flags.skipUpdates) return [];
  return (data.deleted ?? [])
    .filter((d) => d.kind === 'house' && !local.deletedHouseIds.has(d.id))
    .filter((d) => {
      const here = local.houses.get(d.id);
      return here !== undefined && here < d.updatedAt;
    })
    .map((d) => d.id);
}

/** Houses a COPY would put here a second time: houses in the file whose id is a live house here. */
export function copyDuplicates(data: BackupData, local: LocalVersions): number {
  return data.houses.filter((h) => local.houses.has(h.id) && !local.deletedHouseIds.has(h.id)).length;
}

function finish(p: Omit<ImportPreview, 'isEmpty' | 'overwrites'>): ImportPreview {
  const isEmpty =
    p.newHouses === 0 && p.updatedHouses === 0 && p.newVisits === 0 && p.updatedVisits === 0 && p.newPhotos === 0 &&
    p.restoredHouses === 0 && p.newBrokers === 0 && p.updatedBrokers === 0 && p.newCriteria === 0 && p.updatedCriteria === 0 &&
    p.newPreferences === 0 && p.updatedPreferences === 0 && p.newQuestions === 0 && p.updatedQuestions === 0 &&
    p.newViewings === 0 && p.updatedViewings === 0 && p.newAreas === 0 && p.updatedAreas === 0 && p.newPlaces === 0 &&
    p.updatedPlaces === 0 && p.newAreaNotes === 0 && p.updatedAreaNotes === 0 && p.updatedPhotoMeta === 0 && p.removedHouses === 0;
  const overwrites = p.updatedHouses + p.updatedVisits + p.updatedBrokers + p.updatedCriteria + p.updatedPreferences +
    p.updatedQuestions + p.updatedViewings + p.updatedAreas + p.updatedPlaces + p.updatedAreaNotes;
  return { ...p, isEmpty, overwrites };
}

/** What an import would do; nothing is written. */
export function preview(data: BackupData, photoEntries: ReadonlySet<string>, local: LocalVersions, flags: ImportFlags): ImportPreview {
  const { mode } = flags;
  const restore = mode === 'MERGE' && !!flags.restoreDeleted;
  const skip = mode === 'MERGE' && !!flags.skipUpdates;
  const [newCriteria, updatedCriteria] = counts(criteriaWrites(data, local, skip).map((c) => c.key), local.criteria);
  const [newPreferences, updatedPreferences] = settingsCounts((data.preferences ?? []).map((p) => ({ key: p.key, at: p.updatedAt })), local.preferences, skip);
  const [newQuestions, updatedQuestions] = counts(questionWrites(data, local, skip).map((q) => q.id), local.questions);
  const [newViewings, updatedViewings] = mode === 'COPY' ? [(data.viewings ?? []).length, 0] : settingsCounts(keyed(data.viewings), local.viewings, skip);
  const [newAreas, updatedAreas] = settingsCounts(keyed(data.areas), local.areas, skip);
  const [newPlaces, updatedPlaces] = settingsCounts(keyed(data.places), local.places, skip);
  const [newAreaNotes, updatedAreaNotes] = settingsCounts(keyed(data.areaNotes), local.areaNotes, skip);
  const zero = {
    mode, newHouses: 0, updatedHouses: 0, newerHereHouses: 0, unchangedHouses: 0, newVisits: 0, updatedVisits: 0, newerHereVisits: 0,
    unchangedVisits: 0, newPhotos: 0, skippedPhotos: 0, photosMissingFromFile: 0, checklistsCleared: 0, deletedHereHouses: 0,
    deletedHereVisits: 0, deletedHerePhotos: 0, restoredHouses: 0, keptMineHouses: 0, keptMineVisits: 0, relinkedVisits: 0,
    newBrokers: 0, updatedBrokers: 0, newCriteria, updatedCriteria, newPreferences, updatedPreferences, newQuestions, updatedQuestions,
    newViewings, updatedViewings, newAreas, updatedAreas, newPlaces, updatedPlaces, newAreaNotes, updatedAreaNotes, updatedPhotoMeta: 0,
    removedHouses: 0, floorsLeftBlank: 0,
  };
  if (mode === 'COPY') {
    const fileHouses = new Set(data.houses.map((h) => h.id));
    let withFiles = 0;
    let orphaned = 0;
    let missing = 0;
    for (const p of data.photos) {
      if (!photoEntries.has(photoEntry(p.fileName))) missing++;
      else if (!fileHouses.has(p.houseId)) orphaned++;
      else withFiles++;
    }
    return finish({
      ...zero, newHouses: data.houses.length, newVisits: data.visits.filter((v) => v.houseId == null || fileHouses.has(v.houseId)).length,
      newPhotos: withFiles, skippedPhotos: orphaned, photosMissingFromFile: missing, newBrokers: (data.brokers ?? []).length,
      floorsLeftBlank: data.houses.filter(floorOutOfRange).length,
    });
  }
  const out = { ...zero };
  for (const h of data.houses) {
    const outcome = houseOutcome(h, local, restore, skip);
    // Only a house the import writes lands with its floor blank.
    if (floorOutOfRange(h) && (outcome === 'NEW' || outcome === 'UPDATE' || outcome === 'RESTORE')) out.floorsLeftBlank++;
    switch (outcome) {
      case 'NEW': out.newHouses++; break;
      case 'UPDATE':
        out.updatedHouses++;
        if (Object.keys(h.checklist ?? {}).length === 0 && local.scoredHouseIds.has(h.id)) out.checklistsCleared++;
        break;
      case 'RESTORE': out.restoredHouses++; break;
      case 'DELETED_HERE': out.deletedHereHouses++; break;
      case 'KEPT_MINE': out.keptMineHouses++; break;
      case 'NEWER_HERE': out.newerHereHouses++; break;
      case 'SAME': out.unchangedHouses++; break;
    }
  }
  const kept = keptHouseIds(data, local, restore, skip);
  const over = overTombstoneIds(data, local, restore, skip);
  for (const v of data.visits) {
    if (v.houseId != null && !kept.has(v.houseId)) {
      if (local.deletedHouseIds.has(v.houseId)) out.deletedHereVisits++;
      continue;
    }
    switch (visitOutcome(v, local, over, skip)) {
      case 'NEW': out.newVisits++; break;
      case 'RELINK': out.newVisits++; out.relinkedVisits++; break;
      case 'UPDATE': out.updatedVisits++; break;
      case 'KEPT_MINE': out.keptMineVisits++; break;
      case 'NEWER_HERE': out.newerHereVisits++; break;
      case 'SAME': out.unchangedVisits++; break;
    }
  }
  for (const p of data.photos) {
    if (!photoEntries.has(photoEntry(p.fileName))) out.photosMissingFromFile++;
    else if (!kept.has(p.houseId)) {
      out.skippedPhotos++;
      if (local.deletedHouseIds.has(p.houseId)) out.deletedHerePhotos++;
    } else if (local.photoIds.has(p.id)) out.skippedPhotos++;
    else out.newPhotos++;
  }
  for (const b of data.brokers ?? []) {
    const v = compare(local.brokers.get(b.id), b.updatedAt);
    if (v === 'NEW') out.newBrokers++;
    else if (v === 'INCOMING_NEWER' && !skip) out.updatedBrokers++;
  }
  out.updatedPhotoMeta = photoMetaUpdates(data, local, skip).length;
  out.removedHouses = removals(data, local, flags).length;
  return finish(out);
}

/** The rows to write. `newId` gives fresh ids for a COPY (and for the photos of a house written over a synced tombstone). */
export function plan(data: BackupData, photoEntries: ReadonlySet<string>, local: LocalVersions, flags: ImportFlags, newId: () => string): ImportActions {
  const { mode } = flags;
  const restore = mode === 'MERGE' && !!flags.restoreDeleted;
  const skip = mode === 'MERGE' && !!flags.skipUpdates;
  const criteria = criteriaWrites(data, local, skip);
  const preferences = (data.preferences ?? []).filter((p) => settingWrites(p.key, p.updatedAt, local.preferences, skip));
  const questions = questionWrites(data, local, skip);
  const mergedViewings = (data.viewings ?? []).filter((v) => settingWrites(v.id, v.updatedAt, local.viewings, skip));
  const areas = (data.areas ?? []).filter((a) => settingWrites(a.id, a.updatedAt, local.areas, skip));
  const places = (data.places ?? []).filter((p) => settingWrites(p.id, p.updatedAt, local.places, skip));
  const areaNotes = (data.areaNotes ?? []).filter((n) => settingWrites(n.id, n.updatedAt, local.areaNotes, skip));
  const empty = {
    updatedHouseIds: new Set<string>(), updatedVisitIds: new Set<string>(), restoredHouseIds: new Set<string>(),
    relinkedVisitIds: new Set<string>(), updatedBrokerIds: new Set<string>(), photoMeta: [] as BackupPhoto[], removedHouseIds: [] as string[],
  };
  if (mode === 'COPY') {
    const houseIds = new Map(data.houses.map((h) => [h.id, newId()]));
    const visitIds = new Map<string, string>();
    const visits = data.visits
      .filter((v) => v.houseId == null || houseIds.has(v.houseId))
      .map((v) => {
        const id = newId();
        visitIds.set(v.id, id);
        return { ...v, id, houseId: v.houseId == null ? v.houseId : houseIds.get(v.houseId) };
      });
    const photoSources = new Map<string, string>();
    const photos: BackupPhoto[] = [];
    for (const p of data.photos) {
      const entry = photoEntry(p.fileName);
      const houseId = houseIds.get(p.houseId);
      if (!photoEntries.has(entry) || houseId === undefined) continue;
      const id = newId();
      photoSources.set(id, entry);
      photos.push({ ...p, id, houseId });
    }
    const brokerIds = new Map((data.brokers ?? []).map((b) => [b.id, newId()]));
    const houses = data.houses.map((h) => ({
      ...h,
      id: houseIds.get(h.id)!,
      ...(h.brokerId != null ? { brokerId: brokerIds.get(h.brokerId) ?? h.brokerId } : {}),
    }));
    const brokers = (data.brokers ?? []).map((b) => ({ ...b, id: brokerIds.get(b.id)! }));
    const viewings = (data.viewings ?? []).map((v) => ({
      ...v,
      id: newId(),
      houseId: houseIds.get(v.houseId) ?? v.houseId,
      ...(v.visitId != null ? { visitId: visitIds.get(v.visitId) ?? v.visitId } : {}),
    }));
    return { mode, houses, visits, photos, photoSources, brokers, criteria, preferences, questions, viewings, areas, places, areaNotes, ...empty };
  }
  const actions: ImportActions = {
    mode, houses: [], visits: [], photos: [], photoSources: new Map(), brokers: [], criteria, preferences, questions,
    viewings: mergedViewings, areas, places, areaNotes, ...empty,
  };
  for (const h of data.houses) {
    const o = houseOutcome(h, local, restore, skip);
    if (o === 'UPDATE') actions.updatedHouseIds.add(h.id);
    if (o === 'RESTORE') actions.restoredHouseIds.add(h.id);
    if (WRITES.has(o)) actions.houses.push(h);
  }
  const kept = keptHouseIds(data, local, restore, skip);
  const over = overTombstoneIds(data, local, restore, skip);
  for (const v of data.visits) {
    if (v.houseId != null && !kept.has(v.houseId)) continue;
    const o = visitOutcome(v, local, over, skip);
    if (o === 'RELINK') actions.relinkedVisitIds.add(v.id);
    if (o === 'UPDATE') actions.updatedVisitIds.add(v.id);
    if (o === 'NEW' || o === 'RELINK' || o === 'UPDATE') actions.visits.push(v);
  }
  for (const p of data.photos) {
    const entry = photoEntry(p.fileName);
    if (!photoEntries.has(entry) || local.photoIds.has(p.id) || !kept.has(p.houseId)) continue;
    // The server never takes a tombstoned photo id back: a photo of a house it purged lands under a fresh id.
    const purged = over.has(p.houseId) && (local.syncedDeletedHouseIds === null || local.syncedDeletedHouseIds.has(p.houseId));
    const id = purged ? newId() : p.id;
    actions.photoSources.set(id, entry);
    actions.photos.push(id === p.id ? p : { ...p, id });
  }
  for (const b of data.brokers ?? []) {
    const v = compare(local.brokers.get(b.id), b.updatedAt);
    if (v === 'NEW') actions.brokers.push(b);
    else if (v === 'INCOMING_NEWER' && !skip) {
      actions.updatedBrokerIds.add(b.id);
      actions.brokers.push(b);
    }
  }
  actions.photoMeta = photoMetaUpdates(data, local, skip);
  actions.removedHouseIds = removals(data, local, flags);
  return actions;
}

/** A floor in the file that the store reads as unknown (S4b-BL-104 d; Kotlin `ImportPlan.floorOutOfRange`). */
function floorOutOfRange(h: BackupHouse): boolean {
  return typeof h.floor === 'number' && (h.floor < MIN_FLOOR || h.floor > MAX_FLOOR);
}
