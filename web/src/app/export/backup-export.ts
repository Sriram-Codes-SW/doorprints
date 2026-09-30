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

import { COST_FIELDS } from '../core/models';
import type { HouseAnswer, HouseCost, HouseRoom, MoveIn } from '../core/models';
import { cleanAnswers, cleanCost, cleanMoveIn, cleanRooms, photoMetaOf } from '../data/records';
import type { PhotoRecord } from '../data/records';
import { hasMeta } from '../shared/photo-tags';
import { sortedChecklist } from './export-model';
import type { ExportBroker, ExportBundle, ExportHouse as BundleHouse } from './export-model';
import { brokerToPayload } from '../shared/broker';
import { criterionToPayload } from '../shared/scoring';
import type { CriterionRow, PreferenceRow } from '../shared/scoring';
import { questionToPayload } from '../shared/question';
import type { QuestionRow } from '../shared/question';
import { viewingToPayload } from '../shared/viewing';
import type { ViewingRow } from '../shared/viewing';
import { areaNoteToPayload, areaToPayload, placeToPayload } from '../shared/area';
import type { AreaNoteRow, AreaRow, PlaceRow } from '../shared/area';
import { htmlCopyName, isoUtc } from './deterministic';
import { photoEntry, photoFileName } from './photo-names';
import { sha256Hex } from './sha256';
import { utf8, zip } from './zip';
import type { ZipEntry } from './zip';

/**
 * The exact, re-importable JSON backup (docs/11 §5.2, S4-03; imported by S4-04).
 *
 * **This file is one half of a cross-platform contract.** The other half is
 * `android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt` and `ExportModel.kt`: the same
 * `format` id, the same entry names, the same property names, the same order. A backup written on a phone must
 * import in a browser and the other way round, so nothing here may be renamed on one side only.
 *
 * Differences from the API DTOs, all deliberate and shared with Android:
 *  - timestamps are **epoch milliseconds**, because a backup is a copy of the local store, not an API payload;
 *  - `deleted` and `syncVersion` are not in the file: tombstones are never exported and sync state is local;
 *  - a photo row carries `fileName` (`<id>.jpg`), the name inside the ZIP's `photos/` folder;
 *  - null optional fields are **left out** rather than written as `null` (Kotlin's `explicitNulls = false`), and
 *    the JSON is compact, not pretty-printed, so the output is byte-stable.
 */

/**
 * Written into `manifest.json` and `data.json` of a copy with no list `/1` lacks. Kotlin: `BackupFormat.ID`. Slice 1b
 * of the Sprint 4b data model wrote the first new list (docs/11 5.30 item 3): a copy with brokers is
 * {@link BACKUP_FORMAT_V2}, every other copy stays `/1`.
 */
export const BACKUP_FORMAT = 'doorprints-backup/1';
/**
 * The number a copy with brokers is written as (slice 1b, docs/schemas/README.md §1.1: the lowest number that holds
 * everything): a `brokers` list after `photos`, `brokerId` on the houses and `counts.brokers`. A copy with no broker, room,
 * criterion (`criteria` list, slice 2), preference (`preferences` list), question (`questions` list, slice 3a), viewing (`viewings` list, slice 3b-1), area, place or area note (`areas`, `places`, `areaNotes`, slice 4a), house with answers, house with status TAKEN or NOT_CHOSEN, house with `moveIn` or photo with any meta (slice 5) stays `/1`. Kotlin: `BackupFormat.ID_V2`.
 */
export const BACKUP_FORMAT_V2 = 'doorprints-backup/2';
/**
 * The rule of S4b-BL-72 (docs/schemas/README.md): a new entity list means a new format number, a reader accepts
 * every number up to the one it knows and refuses a newer file with "update the app" rather than dropping its
 * lists in silence. The web has no reader yet (S4b-BL-75); when it lands it accepts exactly these. Kotlin:
 * `BackupFormat.READABLE`.
 */
export const BACKUP_FORMATS_READ: readonly string[] = ['doorprints-backup/1', 'doorprints-backup/2'];
export const MANIFEST_ENTRY = 'manifest.json';
export const DATA_ENTRY = 'data.json';

/**
 * Limits an importer checks before unpacking anything (zip bombs, nonsense files). Mirrors Kotlin `BackupFormat`
 * value for value: `MAX_ENTRIES`, `MAX_UNCOMPRESSED_BYTES` (1 GiB), `MAX_COMPRESSION_RATIO` and
 * `MAX_DATA_JSON_BYTES` (**16 MiB**, the cap docs/01 SEC-041 and docs/02 T-T8 state). The web has no importer yet
 * (S4-04, Sprint 4b), so nothing reads these today; they are kept equal to Kotlin so the web reader, when it lands,
 * refuses exactly what Android refuses (`exporters.spec.ts` pins them). The server's own limit on
 * `POST /api/import` is its request body cap (`app.limits.max-import-bytes`), which the Backend team owns.
 */
export const BACKUP_LIMITS = {
  maxEntries: 5_000,
  maxUncompressedBytes: 1_073_741_824,
  maxCompressionRatio: 100,
  maxDataJsonBytes: 16 * 1024 * 1024,
} as const;

/** The app name written into the manifest, so a reader can say where a file came from. */
export const BACKUP_APP = 'Doorprints';
/** Bumped with the web app's package version; never used to gate an import. */
export const BACKUP_APP_VERSION = '1.0.0';

export interface BackupHouse {
  id: string;
  label: string;
  address?: string;
  street?: string;
  locality?: string;
  lat: number;
  lon: number;
  status: string;
  price?: number;
  priceType?: string;
  bedrooms?: number;
  rating?: number;
  contactName?: string;
  contactPhone?: string;
  listingUrl?: string;
  notes?: string;
  /** Slice 1a (docs/11 5.30 item 1): the three house values, in this order, between `notes` and `checklist`. */
  areaSqft?: number;
  locationSource?: string;
  cost?: BackupCost;
  /** Slice 1c (docs/11 5.6): at most 30 rooms, right after `cost` and before `brokerId`. */
  rooms?: BackupRoom[];
  /** Slice 3a (docs/11 5.5): at most 60 answers, right after `rooms` and before `brokerId`. */
  answers?: BackupAnswer[];
  /** Slice 5 (docs/11 5.24): moving in, right after `answers` and before `brokerId`. */
  moveIn?: BackupMoveIn;
  /** Slice 1b: the record id of the house's broker, right after `cost`. */
  brokerId?: string;
  checklist: Record<string, number>;
  createdAt: number;
  updatedAt: number;
}

/** The eleven cost fields, only the set ones, in `COST_FIELDS` order; the object itself is left out when empty. */
export type BackupCost = { [K in keyof HouseCost]?: NonNullable<HouseCost[K]> };

/**
 * A room in the backup, with only the set fields. The array is left out when it is empty or null.
 * Fields are in the order: id, type, name, lengthCm, widthCm, condition, notes, sort.
 */
export type BackupRoom = { [K in keyof HouseRoom]?: Exclude<HouseRoom[K], null> };

/** An answer in the backup, with only the set fields, in the order id, questionId, text, answer, status, sort. */
export type BackupAnswer = { [K in keyof HouseAnswer]?: Exclude<HouseAnswer[K], null> };


/** Moving in in the backup: `date`, `notes` and `items` (id, text, `done` only when true, sort), only what is set. */
export type BackupMoveIn = { [K in keyof MoveIn]?: NonNullable<MoveIn[K]> };

export interface BackupVisit {
  id: string;
  houseId?: string;
  lat: number;
  lon: number;
  street?: string;
  arrivedAt: number;
  leftAt?: number;
  source: string;
  updatedAt: number;
}

export interface BackupPhoto {
  id: string;
  houseId: string;
  fileName: string;
  createdAt: number;
  /** Slice 5 (docs/11 5.7): the room, tags and caption, each only when set; `metaUpdatedAt` only when > 0. */
  roomId?: string;
  tags?: string[];
  caption?: string;
  metaUpdatedAt?: number;
}

/** A broker in a `/2` copy: the record id, the payload keys that are set (in this order) and the last edit. */
export interface BackupBroker {
  id: string;
  name: string;
  phone?: string;
  agency?: string;
  feeTerms?: string;
  notes?: string;
  rating?: number;
  updatedAt: number;
}

/**
 * A criterion in a `/2` copy (slice 2): the record id as `key`, the payload keys that are set (`label` for a custom
 * one only, then weight, mustHave, minScore, sort, `archived` only when true) and the last edit. Only records that
 * exist are written: a built-in with no record uses the defaults and is not listed.
 */
export interface BackupCriterion {
  key: string;
  label?: string;
  weight: number;
  mustHave: boolean;
  minScore: number;
  sort: number;
  archived?: boolean;
  updatedAt: number;
}

/** A preference in a `/2` copy: its key (`score.ratingShare`), the text value and the last edit. */
export interface BackupPreference {
  key: string;
  value: string;
  updatedAt: number;
}

/**
 * A question in a `/2` copy (slice 3a): the record id, the payload keys (text, category, appliesTo, defaultOn, sort,
 * `archived` only when true) and the last edit.
 */
export interface BackupQuestion {
  id: string;
  text: string;
  category: string;
  appliesTo: string;
  defaultOn: boolean;
  sort: number;
  archived?: boolean;
  updatedAt: number;
}

/**
 * A viewing in a `/2` copy (slice 3b-1): the record id, the payload keys that are set (houseId, startsAt, durationMin,
 * kind, status, remindMin, `huntReminder` only when true, `withWhom`, `notes`, `visitId` only when set) and the last edit.
 */
export interface BackupViewing {
  id: string;
  houseId: string;
  startsAt: number;
  durationMin: number;
  kind: string;
  status: string;
  remindMin: number;
  huntReminder?: boolean;
  withWhom?: string;
  notes?: string;
  visitId?: string;
  updatedAt: number;
}

/** An area in a `/2` copy (slice 4a): id, name, point, radius, `enabled` only when false, and the last edit. */
export interface BackupArea {
  id: string;
  name: string;
  lat: number;
  lon: number;
  radiusM: number;
  enabled?: boolean;
  updatedAt: number;
}

/** A place in a `/2` copy (slice 4a). */
export interface BackupPlace {
  id: string;
  name: string;
  lat: number;
  lon: number;
  updatedAt: number;
}

/** An area note in a `/2` copy (slice 4a): exactly one of `areaId` and `street`, then the text. */
export interface BackupAreaNote {
  id: string;
  areaId?: string;
  street?: string;
  text: string;
  updatedAt: number;
}

export interface BackupData {
  format: string;
  exportedAt: number;
  houses: BackupHouse[];
  visits: BackupVisit[];
  photos: BackupPhoto[];
  /** Only in a `/2` copy, and then never empty. */
  brokers?: BackupBroker[];
  /** Only in a `/2` copy, after `brokers`, and then never empty (slice 2). */
  criteria?: BackupCriterion[];
  /** Only in a `/2` copy, after `criteria`, and then never empty (slice 2). */
  preferences?: BackupPreference[];
  /** Only in a `/2` copy, after `preferences`, and then never empty (slice 3a). */
  questions?: BackupQuestion[];
  /** Only in a `/2` copy, after `questions`, and then never empty (slice 3b-1). */
  viewings?: BackupViewing[];
  /** Only in a `/2` copy, after `viewings`, and then never empty (slice 4a). */
  areas?: BackupArea[];
  places?: BackupPlace[];
  areaNotes?: BackupAreaNote[];
}

export interface BackupCounts {
  houses: number;
  visits: number;
  photos: number;
  /** Only in a `/2` copy. */
  brokers?: number;
  criteria?: number;
  preferences?: number;
  questions?: number;
  viewings?: number;
  areas?: number;
  places?: number;
  areaNotes?: number;
}

export interface BackupFile {
  path: string;
  sizeBytes: number;
  sha256: string;
}

export interface BackupManifest {
  format: string;
  app: string;
  appVersion: string;
  /** ISO-8601 instant, the same form the API uses. */
  createdAt: string;
  language: string;
  /** `ALL`, `SHORTLISTED` or `SELECTED` — the Kotlin enum names. */
  scope: string;
  includeRejected: boolean;
  /** `ALL`, `SHORTLISTED` or `NONE`. */
  photoScope: string;
  includeContacts: boolean;
  counts: BackupCounts;
  files: BackupFile[];
}

export function buildBackupData(bundle: ExportBundle): BackupData {
  const brokers = bundle.brokers.length > 0 ? bundle.brokers.map(backupBroker) : undefined;
  const hasRooms = bundle.houses.some((h) => h.house.rooms && h.house.rooms.length > 0);
  const hasAnswers = bundle.houses.some((h) => cleanAnswers(h.house.answers) !== null);
  // Slice 5: a house that is TAKEN or NOT_CHOSEN, one with moveIn, or a photo with any meta makes the copy `/2`.
  const hasStatus = bundle.houses.some((h) => h.house.status === 'TAKEN' || h.house.status === 'NOT_CHOSEN');
  const hasMoveIn = bundle.houses.some((h) => cleanMoveIn(h.house.moveIn) !== null);
  const hasPhotoMeta = bundle.houses.some((h) => h.photos.some((p) => hasMeta(photoMetaOf(p))));
  // Criteria and preferences are not contacts: a copy made without contact details keeps them (slice 2).
  const criteria = bundle.criteria.length > 0 ? bundle.criteria.map(backupCriterion) : undefined;
  const preferences = bundle.preferences.length > 0 ? bundle.preferences.map(backupPreference) : undefined;
  // Questions are not contacts either. The answers stay whole too: a copy is the person's own data (slice 3a).
  const questions = bundle.questions.length > 0 ? bundle.questions.map(backupQuestion) : undefined;
  // Viewings (slice 3b-1): `withWhom` was already removed by `collect` for a copy without contact details.
  const viewings = bundle.viewings.length > 0 ? bundle.viewings.map(backupViewing) : undefined;
  // Areas, places and area notes (slice 4a) are the person's own data, not contacts: a copy without contact details keeps them.
  const areas = bundle.areas.length > 0 ? bundle.areas.map(backupArea) : undefined;
  const places = bundle.places.length > 0 ? bundle.places.map(backupPlace) : undefined;
  const areaNotes = bundle.areaNotes.length > 0 ? bundle.areaNotes.map(backupAreaNote) : undefined;
  return {
    format:
      brokers || hasRooms || hasAnswers || hasStatus || hasMoveIn || hasPhotoMeta || criteria || preferences || questions || viewings || areas || places || areaNotes
        ? BACKUP_FORMAT_V2
        : BACKUP_FORMAT,
    exportedAt: millisOf(bundle.exportedAt),
    houses: bundle.houses.map((entry) => backupHouse(entry)),
    visits: bundle.houses.flatMap((entry) =>
      entry.visits.map((visit) => ({
        id: visit.id,
        houseId: visit.houseId ?? undefined,
        lat: visit.lat,
        lon: visit.lon,
        street: visit.street ?? undefined,
        arrivedAt: millisOf(visit.arrivedAt),
        leftAt: visit.leftAt ? millisOf(visit.leftAt) : undefined,
        source: visit.source,
        updatedAt: millisOf(visit.updatedAt),
      })),
    ),
    photos: bundle.houses.flatMap((entry) =>
      entry.photos.map((photo) => backupPhoto(photo)),
    ),
    ...(brokers ? { brokers } : {}),
    ...(criteria ? { criteria } : {}),
    ...(preferences ? { preferences } : {}),
    ...(questions ? { questions } : {}),
    ...(viewings ? { viewings } : {}),
    ...(areas ? { areas } : {}),
    ...(places ? { places } : {}),
    ...(areaNotes ? { areaNotes } : {}),
  };
}

/** `id, houseId, fileName, createdAt`, then the meta keys that are set: `roomId, tags, caption, metaUpdatedAt`. */
function backupPhoto(photo: PhotoRecord): BackupPhoto {
  const meta = photoMetaOf(photo);
  return {
    id: photo.id,
    houseId: photo.houseId,
    fileName: photoFileName(photo.id),
    createdAt: millisOf(photo.createdAt),
    ...(meta.roomId ? { roomId: meta.roomId } : {}),
    ...(meta.tags.length > 0 ? { tags: meta.tags } : {}),
    ...(meta.caption ? { caption: meta.caption } : {}),
    ...(meta.metaUpdatedAt > 0 ? { metaUpdatedAt: meta.metaUpdatedAt } : {}),
  };
}

/** `id`, then the payload keys in the contract's order (`areaToPayload`), then `updatedAt`. */
function backupArea(row: AreaRow): BackupArea {
  return { id: row.id, ...(areaToPayload(row.area) as Omit<BackupArea, 'id' | 'updatedAt'>), updatedAt: millisOf(row.updatedAt) };
}

function backupPlace(row: PlaceRow): BackupPlace {
  return { id: row.id, ...(placeToPayload(row.place) as Omit<BackupPlace, 'id' | 'updatedAt'>), updatedAt: millisOf(row.updatedAt) };
}

function backupAreaNote(row: AreaNoteRow): BackupAreaNote {
  return { id: row.id, ...(areaNoteToPayload(row.note) as Omit<BackupAreaNote, 'id' | 'updatedAt'>), updatedAt: millisOf(row.updatedAt) };
}

/** `id`, then the set payload keys in the contract's order (`viewingToPayload`), then `updatedAt`. */
function backupViewing(row: ViewingRow): BackupViewing {
  return { id: row.id, ...(viewingToPayload(row.viewing) as Omit<BackupViewing, 'id' | 'updatedAt'>), updatedAt: millisOf(row.updatedAt) };
}

/** `id`, then the payload keys in the contract's order (`questionToPayload`), then `updatedAt`. */
function backupQuestion(row: QuestionRow): BackupQuestion {
  return { id: row.id, ...(questionToPayload(row.question) as Omit<BackupQuestion, 'id' | 'updatedAt'>), updatedAt: millisOf(row.updatedAt) };
}

/** `key`, then the set payload keys in the contract's order (`criterionToPayload`), then `updatedAt`. */
function backupCriterion(row: CriterionRow): BackupCriterion {
  return {
    key: row.key,
    ...(criterionToPayload(row.criterion) as Omit<BackupCriterion, 'key' | 'updatedAt'>),
    updatedAt: millisOf(row.updatedAt),
  };
}

function backupPreference(row: PreferenceRow): BackupPreference {
  return { key: row.key, value: row.value, updatedAt: millisOf(row.updatedAt) };
}

/** `id`, then the set payload keys in the contract's order (`brokerToPayload`), then `updatedAt`. */
function backupBroker(entry: ExportBroker): BackupBroker {
  return { id: entry.id, ...(brokerToPayload(entry.broker) as Omit<BackupBroker, 'id' | 'updatedAt'>), updatedAt: millisOf(entry.updatedAt) };
}

function backupHouse({ house }: BundleHouse): BackupHouse {
  return {
    id: house.id,
    label: house.label,
    address: house.address ?? undefined,
    street: house.street ?? undefined,
    locality: house.locality ?? undefined,
    lat: house.lat,
    lon: house.lon,
    status: house.status,
    price: house.price ?? undefined,
    priceType: house.priceType ?? undefined,
    bedrooms: house.bedrooms ?? undefined,
    rating: house.rating ?? undefined,
    contactName: house.contactName ?? undefined,
    contactPhone: house.contactPhone ?? undefined,
    listingUrl: house.listingUrl ?? undefined,
    notes: house.notes ?? undefined,
    areaSqft: house.areaSqft ?? undefined,
    locationSource: house.locationSource ?? undefined,
    cost: backupCost(house.cost),
    rooms: backupRooms(house.rooms),
    answers: backupAnswers(house.answers),
    moveIn: backupMoveIn(house.moveIn),
    brokerId: house.brokerId ?? undefined,
    checklist: sortedChecklist(house.checklist),
    createdAt: millisOf(house.createdAt),
    updatedAt: millisOf(house.updatedAt),
  };
}

/** `cleanCost` keeps only the set fields in the contract's order, so the file never holds a null or an empty `{}`. */
function backupCost(cost: HouseCost | null | undefined): BackupCost | undefined {
  const clean = cleanCost(cost);
  if (!clean) return undefined;
  const out: BackupCost = {};
  for (const field of COST_FIELDS) {
    const value = clean[field];
    if (value !== null && value !== undefined) (out as Record<string, unknown>)[field] = value;
  }
  return out;
}

/**
 * Rooms in the backup: at most 30, only the set fields in the spec's order (id, type, name, lengthCm, widthCm, condition, notes, sort).
 * The array itself is left out when there are no rooms.
 */
function backupRooms(rooms: HouseRoom[] | null | undefined): BackupRoom[] | undefined {
  const clean = cleanRooms(rooms);
  if (!clean) return undefined;
  return clean.map((room) => {
    const out: Record<string, unknown> & BackupRoom = { id: room.id, type: room.type };
    if (room.name) out.name = room.name;
    if (room.lengthCm !== undefined && room.lengthCm !== null) out.lengthCm = room.lengthCm;
    if (room.widthCm !== undefined && room.widthCm !== null) out.widthCm = room.widthCm;
    if (room.condition !== undefined && room.condition !== null) out.condition = room.condition;
    if (room.notes) out.notes = room.notes;
    if (room.sort !== undefined && room.sort !== null) out.sort = room.sort;
    return out as BackupRoom;
  });
}

/** Answers in the backup: at most 60 (`cleanAnswers`), the array left out when there are none. */
function backupAnswers(answers: HouseAnswer[] | null | undefined): BackupAnswer[] | undefined {
  return cleanAnswers(answers)?.map((a) => ({
    id: a.id,
    ...(a.questionId ? { questionId: a.questionId } : {}),
    text: a.text,
    ...(a.answer ? { answer: a.answer } : {}),
    status: a.status,
    sort: a.sort,
  }));
}

/** Moving in in the backup (`cleanMoveIn`): date, notes, items; the object is left out when it has none of them. */
function backupMoveIn(moveIn: MoveIn | null | undefined): BackupMoveIn | undefined {
  return cleanMoveIn(moveIn) ?? undefined;
}

/**
 * Builds the whole backup ZIP.
 *
 * Entry order matches the Android writer: `data.json`, the readable HTML copy, the photos, then `manifest.json`
 * last, because the manifest lists a SHA-256 for every earlier entry. ZIP puts no meaning on entry order, and a
 * reader looks the manifest up in the central directory, so being last costs nothing.
 */
export function buildBackupZip(
  bundle: ExportBundle,
  photoBytes: ReadonlyMap<string, Uint8Array>,
  html: string,
  modifiedAt: Date,
): Uint8Array {
  const data = buildBackupData(bundle);
  // `Doorprints-copy-<date>.html`, the same name as the HTML download. Only the readers' *view* of a backup: no
  // importer reads this entry (Android's `BackupValidation` only checks that its path is safe), so the rename from
  // `Doorprints-<date>.html` cannot break an import either way; Android's `ExportFormat.HTML` follows it (handover).
  const htmlName = htmlCopyName(bundle.exportedAt);

  const contents: ZipEntry[] = [
    { path: DATA_ENTRY, data: utf8(backupJson(data)) },
    { path: htmlName, data: utf8(html) },
  ];
  // Photos in the bundle's own order; a photo whose bytes this browser does not have is simply not written, and
  // the importer treats a row with no file as metadata only.
  for (const photo of data.photos) {
    const bytes = photoBytes.get(photo.id);
    if (bytes) contents.push({ path: photoEntry(photo.fileName), data: bytes });
  }

  const manifest: BackupManifest = {
    format: data.format,
    app: BACKUP_APP,
    appVersion: BACKUP_APP_VERSION,
    createdAt: isoUtc(bundle.exportedAt),
    language: bundle.options.lang,
    scope: bundle.options.scope.toUpperCase(),
    includeRejected: bundle.options.includeRejected,
    photoScope: bundle.options.photos.toUpperCase(),
    includeContacts: bundle.options.includeContacts,
    counts: {
      houses: data.houses.length,
      visits: data.visits.length,
      photos: data.photos.length,
      brokers: data.brokers?.length,
      criteria: data.criteria?.length,
      preferences: data.preferences?.length,
      questions: data.questions?.length,
      viewings: data.viewings?.length,
      areas: data.areas?.length,
      places: data.places?.length,
      areaNotes: data.areaNotes?.length,
    },
    files: contents.map((entry) => ({
      path: entry.path,
      sizeBytes: entry.data.length,
      sha256: sha256Hex(entry.data),
    })),
  };

  return zip([...contents, { path: MANIFEST_ENTRY, data: utf8(backupJson(manifest)) }], modifiedAt);
}

/**
 * Compact JSON with the keys in declaration order and every `undefined` left out — exactly what kotlinx's
 * `Json { encodeDefaults = true; explicitNulls = false }` writes, and what the golden test pins.
 * `JSON.stringify` already drops `undefined` properties and keeps string keys in insertion order.
 */
export function backupJson(value: unknown): string {
  return JSON.stringify(value);
}

function millisOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? 0 : ms;
}
