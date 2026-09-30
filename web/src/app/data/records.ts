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

import { LocalDataError } from '../core/local-error';
import { ANSWER_STATUSES, COST_FIELDS, LOCATION_SOURCES, ROOM_TYPES } from '../core/models';
import type { AnswerStatus, HouseAnswer, HouseCost, HouseDto, HouseRoom, HouseStatus, PriceType, RecordDto, RoomType, VisitDto, VisitSource } from '../core/models';

/**
 * What the browser stores locally (IndexedDB). Doorprints is local-first (docs/11 §5.1, D-01): every record below
 * lives in this browser and is complete on its own. A server is optional; when one is configured the sync engine
 * (sync.service.ts) exchanges exactly these fields with it.
 *
 * Field names are deliberately identical to the wire DTOs and to the Android Room entities
 * (android/shared .../api/ApiModels.kt, android/app .../data/Models.kt), so the same JSON round-trips through
 * Android, the web app and the server. The only additions are the local bookkeeping flags `dirty` and `uploaded`,
 * which are never sent to the server.
 */

/** A house as stored here: the wire DTO plus the local `dirty` flag. `distanceMeters` is never stored. */
export interface HouseRecord extends Omit<HouseDto, 'distanceMeters'> {
  /** True while this row has local changes the server has not seen yet (Android: HouseEntity.dirty). */
  dirty: boolean;
}

/** A visit as stored here: the wire DTO plus the local `dirty` flag. */
export interface VisitRecord extends VisitDto {
  dirty: boolean;
}

/**
 * A photo as stored here. The bytes are a `Blob` in IndexedDB (Android keeps a file path instead); everything else
 * matches `PhotoChangeDto`, the row `GET /api/photos?since=` returns.
 */
export interface PhotoRecord {
  id: string;
  houseId: string;
  /** JPEG bytes. Null only for a photo the server has that this browser has not downloaded yet. */
  blob: Blob | null;
  contentType: string;
  sizeBytes: number;
  createdAt: string | null;
  updatedAt: string | null;
  deleted: boolean;
  syncVersion: number;
  /** True once the server has these bytes (Android: PhotoEntity.uploaded). */
  uploaded: boolean;
}

/**
 * One row of the `records` store (docs/11 5.30 item 2): the wire envelope plus the local `dirty` flag, keyed by
 * (`type`, `id`). Criteria, viewings, brokers and the rest of the Sprint 4b entities are all rows of this shape;
 * the typed accessors of each slice read and write `payload`.
 */
export interface RecordRecord extends RecordDto {
  dirty: boolean;
}

/** A record's `type`: a lower-case word, as the server's `RecordType` pattern. */
export const RECORD_TYPE_PATTERN = /^[a-z][a-zA-Z0-9]{0,39}$/;
/** A record's `id`: a UUID, or another safe key such as a photo's or a house's id. */
export const RECORD_ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;
/** The server's cap on a serialised `payload` (docs/11 5.30: "payload at most 64 KB"); a larger row is untrusted. */
export const MAX_RECORD_PAYLOAD_BYTES = 65_536;

/** One row of the `settings` store: sync cursors, first-run flags and user preferences. */
export interface SettingRecord {
  key: string;
  value: string;
}

export const SETTING_KEYS = {
  houseCursor: 'cursor.house',
  visitCursor: 'cursor.visit',
  photoCursor: 'cursor.photo',
  recordCursor: 'cursor.record',
  /** Set once the "download my houses to this browser" migration has run or been dismissed. */
  migration: 'migration.state',
  /** Remembers that navigator.storage.persist() was already requested, so we ask the browser only once. */
  persistAsked: 'storage.persist-asked',
  /** Remembers the export options the user last chose. */
  exportOptions: 'export.options',
  /**
   * The normalized server address the four cursors belong to. When the user connects a different server the
   * cursors go back to 0 and the migration question is asked again (Android: Settings.saveServer).
   */
  syncServer: 'sync.server',
  /** Set once the contacts of the houses have been turned into brokers (slice 1b), so an unlink is never undone. */
  brokersMigrated: 'brokers.migrated',
  /** Length unit preference for rooms: 'FT' (default) or 'M' (local only, not synced). */
  lengthUnit: 'units.length',
  /** Set once the question bank has been seeded on this install, so a question the person deleted stays deleted (slice 3a; not synced). */
  questionsSeeded: 'questions.seeded',
  /** Remind me about viewings (slice 3b-2; default on, stored as '0' when off; local only, not synced). */
  viewingsRemind: 'viewings.remind',
} as const;

/** Epoch milliseconds of an ISO-8601 instant; 0 when it is missing or unparseable. Mirrors IsoTime.parseMillis. */
export function millis(iso: string | null | undefined): number {
  if (!iso) return 0;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? 0 : ms;
}

/** The ISO-8601 form the API uses (UTC, milliseconds), same as kotlin.time.Instant.toString(). */
export function isoNow(now: number = Date.now()): string {
  return new Date(now).toISOString();
}

const STATUSES: readonly HouseStatus[] = ['NEW', 'SHORTLISTED', 'REJECTED'];
const PRICE_TYPES: readonly PriceType[] = ['RENT', 'SALE'];
const SOURCES: readonly VisitSource[] = ['AUTO', 'MANUAL'];

/**
 * A usable primary key: a non-empty string.
 *
 * The id is the one field that may not be coerced. `String(undefined)` is the literal `"undefined"`, which would
 * become a real house on the map, and two malformed rows would then collide on that one key. The server refuses
 * a blank id too (`BackupValidation.checkData`), and S4-04's import runs on these same mappers, so this is the
 * single place both paths are held to it.
 */
export function isRecordId(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== '';
}

/**
 * Accepts a house from the network or from an import file and makes it safe to store: unknown enum values and
 * non-numeric checklist scores are dropped rather than trusted (NFR-025 null semantics, threat model F-16).
 *
 * @throws LocalDataError when the row has no usable id. Callers that are reading untrusted wire data should use
 *   {@link tryHouseFromDto} and skip the row instead, so one bad row does not fail a whole sync.
 */
export function houseFromDto(dto: HouseDto, dirty = false): HouseRecord {
  const record = tryHouseFromDto(dto, dirty);
  if (!record) throw new LocalDataError('error.badRecord');
  return record;
}

/** {@link houseFromDto} for untrusted input: `null` instead of a throw when the row cannot be trusted. */
export function tryHouseFromDto(dto: HouseDto | null | undefined, dirty = false): HouseRecord | null {
  if (!dto || typeof dto !== 'object' || !isRecordId(dto.id)) return null;
  return {
    id: dto.id,
    label: typeof dto.label === 'string' ? dto.label : '',
    address: nullable(dto.address),
    street: nullable(dto.street),
    locality: nullable(dto.locality),
    lat: finite(dto.lat) ?? 0,
    lon: finite(dto.lon) ?? 0,
    status: STATUSES.includes(dto.status) ? dto.status : 'NEW',
    price: finite(dto.price),
    priceType: dto.priceType && PRICE_TYPES.includes(dto.priceType) ? dto.priceType : null,
    bedrooms: finite(dto.bedrooms),
    rating: finite(dto.rating),
    contactName: nullable(dto.contactName),
    contactPhone: nullable(dto.contactPhone),
    listingUrl: nullable(dto.listingUrl),
    notes: nullable(dto.notes),
    areaSqft: whole(dto.areaSqft, 1, MAX_AREA_SQFT),
    locationSource: dto.locationSource && LOCATION_SOURCES.includes(dto.locationSource) ? dto.locationSource : null,
    cost: cleanCost(dto.cost),
    rooms: cleanRooms(dto.rooms),
    answers: cleanAnswers(dto.answers),
    brokerId: cleanBrokerId(dto.brokerId),
    checklist: cleanChecklist(dto.checklist),
    createdAt: nullable(dto.createdAt),
    updatedAt: nullable(dto.updatedAt),
    deleted: dto.deleted === true,
    syncVersion: finite(dto.syncVersion) ?? 0,
    dirty,
  };
}

/** @throws LocalDataError when the row has no usable id; see {@link tryVisitFromDto}. */
export function visitFromDto(dto: VisitDto, dirty = false): VisitRecord {
  const record = tryVisitFromDto(dto, dirty);
  if (!record) throw new LocalDataError('error.badRecord');
  return record;
}

/** {@link visitFromDto} for untrusted input: `null` instead of a throw when the row cannot be trusted. */
export function tryVisitFromDto(dto: VisitDto | null | undefined, dirty = false): VisitRecord | null {
  if (!dto || typeof dto !== 'object' || !isRecordId(dto.id)) return null;
  return {
    id: dto.id,
    houseId: nullable(dto.houseId),
    lat: finite(dto.lat) ?? 0,
    lon: finite(dto.lon) ?? 0,
    street: nullable(dto.street),
    arrivedAt: typeof dto.arrivedAt === 'string' ? dto.arrivedAt : isoNow(0),
    leftAt: nullable(dto.leftAt),
    source: SOURCES.includes(dto.source) ? dto.source : 'MANUAL',
    updatedAt: nullable(dto.updatedAt),
    deleted: dto.deleted === true,
    syncVersion: finite(dto.syncVersion) ?? 0,
    dirty,
  };
}

/**
 * {@link tryRecordFromDto}'s strict form for local writes.
 *
 * @throws LocalDataError when the type or id is not usable, or the payload is too large.
 */
export function recordFromDto(dto: RecordDto, dirty = false): RecordRecord {
  const record = tryRecordFromDto(dto, dirty);
  if (!record) throw new LocalDataError('error.badRecord');
  return record;
}

/**
 * Accepts a record envelope from the network or an import file: `null` when the row cannot be trusted (a type or
 * id outside the server's patterns, a payload over {@link MAX_RECORD_PAYLOAD_BYTES}). The payload is kept as it
 * is, or replaced by `{}` when it is not a plain object: the server never reads it and each slice's typed
 * accessor validates its own fields.
 */
export function tryRecordFromDto(dto: RecordDto | null | undefined, dirty = false): RecordRecord | null {
  if (!dto || typeof dto !== 'object') return null;
  if (typeof dto.type !== 'string' || !RECORD_TYPE_PATTERN.test(dto.type)) return null;
  // `.` and `..` fit the pattern but are path segments in `/api/records/{type}/{id}`; the phone refuses them too.
  if (typeof dto.id !== 'string' || !RECORD_ID_PATTERN.test(dto.id) || dto.id === '.' || dto.id === '..') return null;
  const payload = isPlainObject(dto.payload) ? dto.payload : {};
  if (payloadBytes(payload) > MAX_RECORD_PAYLOAD_BYTES) return null;
  return {
    type: dto.type,
    id: dto.id,
    payload,
    updatedAt: nullable(dto.updatedAt),
    deleted: dto.deleted === true,
    syncVersion: finite(dto.syncVersion) ?? 0,
    dirty,
  };
}

/** The serialised size of a payload, as the server measures it (UTF-8 bytes of its JSON). */
export function payloadBytes(payload: Record<string, unknown>): number {
  return new TextEncoder().encode(JSON.stringify(payload)).length;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const proto: unknown = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

/** The wire form of a stored house: the local flags are stripped. */
export function houseToDto(record: HouseRecord): HouseDto {
  const { dirty: _dirty, ...dto } = record;
  return dto;
}

export function visitToDto(record: VisitRecord): VisitDto {
  const { dirty: _dirty, ...dto } = record;
  return dto;
}

export function recordToDto(record: RecordRecord): RecordDto {
  const { dirty: _dirty, ...dto } = record;
  return dto;
}

/** The ranges of the house values (slice 1a): the same numbers the server's `HouseDto` refuses with 400. */
export const MAX_AREA_SQFT = 100_000;
export const MAX_RUPEES = 1_000_000_000_000;
export const MAX_MONTHS = 120;
/** A room's constraints (slice 1c): at most 30 rooms per house. */
export const MAX_ROOMS = 30;
export const MAX_ROOM_NAME = 60;
export const MAX_ROOM_NOTES = 2000;
export const MAX_ROOM_DIMENSION = 5000;
/** A room id pattern (same as the general record id, but not `.`/`..`). */
const ROOM_ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;
/** A calendar date, `YYYY-MM-DD`; the month and day are checked to be real below. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The cost as the store keeps it and the wire and the backup write it: only the fields that are set, in
 * {@link COST_FIELDS} order, or `null` when none is (an empty `{}` from a file reads as no cost). A value out of its
 * range is unknown for that field, like every other coerced house value here.
 */
export function cleanCost(raw: HouseCost | null | undefined): HouseCost | null {
  if (!raw || typeof raw !== 'object') return null;
  const out: HouseCost = {};
  const rupees = (value: number | null | undefined) => whole(value, 0, MAX_RUPEES);
  const months = (value: number | null | undefined) => whole(value, 0, MAX_MONTHS);
  const clean: { [K in keyof HouseCost]-?: HouseCost[K] } = {
    deposit: rupees(raw.deposit),
    depositMonths: months(raw.depositMonths),
    maintenance: rupees(raw.maintenance),
    maintenanceIncluded: typeof raw.maintenanceIncluded === 'boolean' ? raw.maintenanceIncluded : null,
    brokerage: rupees(raw.brokerage),
    brokerageMonths: months(raw.brokerageMonths),
    lockInMonths: months(raw.lockInMonths),
    noticeMonths: months(raw.noticeMonths),
    availableFrom: calendarDate(raw.availableFrom),
    myOffer: rupees(raw.myOffer),
    agreedPrice: rupees(raw.agreedPrice),
  };
  for (const field of COST_FIELDS) {
    const value = clean[field];
    if (value !== null) (out as Record<string, unknown>)[field] = value;
  }
  return Object.keys(out).length === 0 ? null : out;
}

/** A broker's record id (the pattern of every record id, at most 64 characters), or null: a bad id is no broker. */
export function cleanBrokerId(value: string | null | undefined): string | null {
  return typeof value === 'string' && RECORD_ID_PATTERN.test(value) && value !== '.' && value !== '..' ? value : null;
}

/** `YYYY-MM-DD` naming a real day (no 2026-02-30), or null. */
export function calendarDate(value: string | null | undefined): string | null {
  if (typeof value !== 'string' || !DATE_PATTERN.test(value)) return null;
  const [y, m, d] = value.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d ? value : null;
}

/** A whole number within [min, max], or null: a decimal is rounded, anything else is unknown. */
function whole(value: number | null | undefined, min: number, max: number): number | null {
  const n = finite(value);
  if (n === null) return null;
  const rounded = Math.round(n);
  return rounded < min || rounded > max ? null : rounded;
}

/**
 * Rooms as the store keeps them: at most 30, sorted by sort then id, coerced for safe storage. Unknown type or
 * condition coerces to unknown (field absent). Duplicate ids keep only the first; a bad id is skipped. Returns
 * null when the result is empty (no rooms to show).
 */
export function cleanRooms(raw: HouseRoom[] | null | undefined): HouseRoom[] | null {
  if (!raw || !Array.isArray(raw) || raw.length === 0) return null;
  const seenIds = new Set<string>();
  const out: HouseRoom[] = [];
  for (const room of raw) {
    if (!room || typeof room !== 'object') continue;
    const id = roomId(room.id);
    if (!id || seenIds.has(id)) continue;
    seenIds.add(id);
    const type: RoomType = ROOM_TYPES.includes(room.type) ? room.type : 'OTHER';
    const name = text(room.name, MAX_ROOM_NAME) || null;
    const lengthCm = whole(room.lengthCm, 0, MAX_ROOM_DIMENSION);
    const widthCm = whole(room.widthCm, 0, MAX_ROOM_DIMENSION);
    const condition = whole(room.condition, 1, 5);
    const notes = text(room.notes, MAX_ROOM_NOTES) || null;
    const sort = whole(room.sort, 0, 1_000_000) ?? 0;
    const cleaned: HouseRoom = { id, type, sort };
    if (name !== null) cleaned.name = name;
    if (lengthCm !== null) cleaned.lengthCm = lengthCm;
    if (widthCm !== null) cleaned.widthCm = widthCm;
    if (condition !== null) cleaned.condition = condition;
    if (notes !== null) cleaned.notes = notes;
    out.push(cleaned);
  }
  if (out.length === 0) return null;
  // Sort by sort then id, and only then keep the first 30 (Android and the server do the same).
  out.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return out.slice(0, MAX_ROOMS);
}

/** A house's viewing answers (slice 3a): at most 60, one text of at most 300 characters and one answer of 2000. */
export const MAX_ANSWERS = 60;
export const MAX_ANSWER_TEXT = 300;
export const MAX_ANSWER = 2000;

/**
 * Answers as the store keeps them: a bad id is skipped, a duplicate id keeps the first, a blank or over-long text
 * skips the row, an over-long answer is dropped, an unknown status is OPEN, a non-blank answer with status OPEN reads
 * as ANSWERED and ANSWERED without an answer as OPEN. Sorted by sort then id and only then capped at 60. Null when
 * empty. Kotlin: `HouseAnswers.coerced`.
 */
export function cleanAnswers(raw: HouseAnswer[] | null | undefined): HouseAnswer[] | null {
  if (!raw || !Array.isArray(raw) || raw.length === 0) return null;
  const seen = new Set<string>();
  const out: HouseAnswer[] = [];
  for (const row of raw) {
    if (!row || typeof row !== 'object') continue;
    const id = roomId(row.id);
    if (!id || seen.has(id)) continue;
    const asked = text(row.text, MAX_ANSWER_TEXT);
    if (asked === null) continue;
    seen.add(id);
    const answer = text(row.answer, MAX_ANSWER);
    let status: AnswerStatus = ANSWER_STATUSES.includes(row.status) ? row.status : 'OPEN';
    if (answer !== null && status === 'OPEN') status = 'ANSWERED';
    else if (answer === null && status === 'ANSWERED') status = 'OPEN';
    // Keys in the contract's order: id, questionId, text, answer, status, sort.
    const questionId = cleanBrokerId(row.questionId);
    const cleaned: HouseAnswer = {
      id,
      ...(questionId !== null ? { questionId } : {}),
      text: asked,
      ...(answer !== null ? { answer } : {}),
      status,
      sort: whole(row.sort, 0, 1_000_000) ?? 0,
    };
    out.push(cleaned);
  }
  if (out.length === 0) return null;
  out.sort((a, b) => a.sort - b.sort || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return out.slice(0, MAX_ANSWERS);
}

/** A room id: matches the pattern and is not `.`/`..`, or null. */
function roomId(value: unknown): string | null {
  return typeof value === 'string' && ROOM_ID_PATTERN.test(value) && value !== '.' && value !== '..' ? value : null;
}

function cleanChecklist(checklist: Record<string, number> | null | undefined): Record<string, number> {
  const out: Record<string, number> = {};
  if (!checklist || typeof checklist !== 'object') return out;
  for (const key of Object.keys(checklist).sort()) {
    const value = checklist[key];
    if (typeof value === 'number' && Number.isFinite(value)) out[key] = Math.round(value);
  }
  return out;
}

function nullable(value: string | null | undefined): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

function text(value: unknown, max: number): string | null {
  return typeof value === 'string' && value.trim() !== '' && value.length <= max ? value : null;
}

function finite(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
