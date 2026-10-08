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
 * The `sync/1` inner format (S4b-BL-130, docs/15 §5.1; schema `docs/schemas/sync-1.schema.json`, vectors
 * `docs/schemas/sync-vectors.json`; Kotlin: `SyncFiles` in `android/shared`, `app.doorprints.shared.sync`). One file per
 * device holds that device's whole state, tombstones included. Every file read from Drive is untrusted input
 * (docs/schemas §6): {@link parseSyncFile} is strict about everything the merge relies on and refuses the whole file on
 * the first problem; the rest of a row is the sync loop's to validate (`tryHouseFromDto` and the others), as for the
 * server's rows.
 */

/** The four lists, in the order they are written and merged. A row's key is its `id`; a record's is `type/id`. */
export type SyncKind = 'houses' | 'visits' | 'records' | 'photos';
/** The row kinds of a sync file, in write and merge order. */
export const SYNC_KINDS: readonly SyncKind[] = ['houses', 'visits', 'records', 'photos'];

/** The most rows each list may hold (tombstones included, they are kept for ever). */
export const SYNC_MAX_ROWS_OF: Readonly<Record<SyncKind, number>> = { houses: 20_000, visits: 50_000, records: 50_000, photos: 50_000 };

/** The format tag of a sync file, version 1. */
export const SYNC_FORMAT = 'doorprints-sync/1';
/**
 * The tag every sync format version starts with: a higher version is refused as made by a newer Doorprints, anything else as not a sync file.
 */
export const SYNC_FORMAT_PREFIX = 'doorprints-sync/';
/** The newest sync format version this app reads. */
export const SYNC_MAX_VERSION = 1;
/** The cap of the decompressed JSON, in UTF-8 bytes: the backup's `data.json` cap (docs/schemas §7). */
export const SYNC_MAX_BYTES = 16 * 1024 * 1024;
/** All four lists together. */
export const SYNC_MAX_ROWS = 100_000;
/** The deepest nesting of objects and arrays (root 1, a row 3), checked before parsing as Kotlin must. */
export const SYNC_MAX_DEPTH = 64;
/** The highest `seq`: `Number.MAX_SAFE_INTEGER`, which Kotlin holds exactly too. */
export const SYNC_MAX_SEQ = Number.MAX_SAFE_INTEGER;
/** 2000-01-01T00:00:00Z, the server's `ClientClock.EARLIEST`: an earlier stamp is a bug or tampering. */
export const SYNC_EARLIEST_MS = 946_684_800_000;

/** Why a sync file was refused; the same codes in Kotlin (`SyncFileProblem`). */
export type SyncFileProblem =
  | 'NOT_JSON'
  | 'NOT_A_SYNC_FILE'
  | 'UNSUPPORTED_VERSION'
  | 'WRONG_DEVICE'
  | 'TOO_LARGE'
  | 'BAD_ROW'
  | 'DUPLICATE_ROW';

/** A sync file this app will not read, refused whole: nothing of it is merged. */
export class SyncFileError extends Error {
  constructor(readonly problem: SyncFileProblem, message: string) {
    super(message);
    this.name = 'SyncFileError';
  }
}

/**
 * The version of a row the merge compares (Kotlin: `SyncStamp`): when it was made (epoch ms), by which device, and
 * whether it is a tombstone. Ordered by `updatedAt`, then `by` (code units), then a tombstone above a live row
 * ({@link compareStamps}).
 */
export interface SyncStamp {
  readonly updatedAt: number;
  readonly by: string;
  readonly deleted: boolean;
}

/** The total order of {@link SyncStamp}: negative, 0 or positive, as Kotlin's `compareTo`. */
export function compareStamps(a: SyncStamp, b: SyncStamp): number {
  if (a.updatedAt !== b.updatedAt) return a.updatedAt < b.updatedAt ? -1 : 1;
  if (a.by !== b.by) return a.by < b.by ? -1 : 1;
  if (a.deleted !== b.deleted) return a.deleted ? 1 : -1;
  return 0;
}

/** One row of a sync file: the sync DTO's fields plus `by`, as it travels ({@link json}), with what the merge reads. */
export interface SyncRow {
  readonly kind: SyncKind;
  readonly key: string;
  readonly stamp: SyncStamp;
  readonly json: Readonly<Record<string, unknown>>;
}

/** A parsed `sync/1` file; each list sorted by key. */
export interface SyncFile {
  readonly deviceId: string;
  readonly seq: number;
  readonly writtenAt: number;
  readonly rows: Readonly<Record<SyncKind, readonly SyncRow[]>>;
}

const DEVICE_ID = /^[A-Za-z0-9_-]{8,64}$/;
const DRIVE_FILE_ID = /^[A-Za-z0-9_-]{1,128}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const VERSION = /^[1-9][0-9]{0,8}$/;
const ID = /^[A-Za-z0-9._-]{1,64}$/;
const TYPE = /^[a-z][a-zA-Z0-9]{0,39}$/;
const ISO = /^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.([0-9]{1,9}))?Z$/;

/**
 * Whether the value looks like a device id (8 to 64 URL-safe characters); device ids come from other devices' files, so they are checked before use.
 */
export function isDeviceId(value: unknown): value is string {
  return typeof value === 'string' && DEVICE_ID.test(value);
}

/** Kotlin's `RecordRules.isValidId`: what can go into a URL path, not `.` or `..`. */
export function isSyncId(value: unknown): value is string {
  return typeof value === 'string' && ID.test(value) && value !== '.' && value !== '..';
}

/**
 * Epoch milliseconds of an ISO-8601 UTC instant `YYYY-MM-DDTHH:MM:SS[.f]Z` (1 to 9 fraction digits, truncated to
 * milliseconds; a real calendar date, no offset, no leap second), or null. Hand-written like Kotlin's `SyncTime`, not
 * `Date.parse`, whose leniency differs between engines.
 */
export function syncTime(text: string): number | null {
  const m = ISO.exec(text);
  if (!m) return null;
  const [year, month, day, hour, minute, second] = m.slice(1, 7).map(Number);
  if (month < 1 || month > 12 || day < 1 || day > daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return null;
  const millis = Number((m[7] ?? '').padEnd(3, '0').slice(0, 3));
  return daysFromCivil(year, month, day) * 86_400_000 + hour * 3_600_000 + minute * 60_000 + second * 1_000 + millis;
}

function daysIn(year: number, month: number): number {
  if (month === 2) return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28;
  return month === 4 || month === 6 || month === 9 || month === 11 ? 30 : 31;
}

/** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's algorithm), for years 0..9999. */
function daysFromCivil(year: number, month: number, day: number): number {
  const y = month <= 2 ? year - 1 : year;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const mp = (month + 9) % 12;
  const doy = Math.floor((153 * mp + 2) / 5) + day - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146_097 + doe - 719_468;
}

function own(o: Readonly<Record<string, unknown>>, key: string): unknown {
  return Object.prototype.hasOwnProperty.call(o, key) ? o[key] : undefined;
}

function has(o: Readonly<Record<string, unknown>>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(o, key);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** [json] as a row of [kind] (Kotlin: `SyncRow.of`); throws {@link SyncFileError} `BAD_ROW` when a merge field is wrong. */
export function syncRow(kind: SyncKind, json: Readonly<Record<string, unknown>>): SyncRow {
  const bad = (what: string): never => {
    throw new SyncFileError('BAD_ROW', `${kind}: ${what}`);
  };
  const id = own(json, 'id');
  if (!isSyncId(id)) return bad('id');
  let key = id;
  if (kind === 'records') {
    const type = own(json, 'type');
    if (typeof type !== 'string' || !TYPE.test(type)) return bad(`type of ${id}`);
    key = `${type}/${id}`;
  }
  const updatedAtText = own(json, 'updatedAt');
  const updatedAt = typeof updatedAtText === 'string' ? syncTime(updatedAtText) : null;
  if (updatedAt === null || updatedAt < SYNC_EARLIEST_MS) return bad(`updatedAt of ${key}`);
  const by = own(json, 'by');
  if (!isDeviceId(by)) return bad(`by of ${key}`);
  const deleted = own(json, 'deleted');
  if (typeof deleted !== 'boolean') return bad(`deleted of ${key}`);
  if (kind === 'photos') {
    if (!isSyncId(own(json, 'houseId'))) bad(`houseId of ${key}`);
    if (has(json, 'driveFileId')) {
      const v = own(json, 'driveFileId');
      if (typeof v !== 'string' || !DRIVE_FILE_ID.test(v)) bad(`driveFileId of ${key}`);
    }
    if (has(json, 'sha256')) {
      const v = own(json, 'sha256');
      if (typeof v !== 'string' || !SHA256.test(v)) bad(`sha256 of ${key}`);
    }
  }
  if (kind === 'visits' && has(json, 'houseId') && own(json, 'houseId') !== null && !isSyncId(own(json, 'houseId'))) {
    bad(`houseId of ${key}`);
  }
  return { kind, key, stamp: { updatedAt, by, deleted }, json };
}

function byKey(a: SyncRow, b: SyncRow): number {
  return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
}

/** A {@link SyncFile} from rows in any order (each must be of its list's kind). */
export function syncFile(deviceId: string, seq: number, writtenAt: number, rows: Partial<Record<SyncKind, readonly SyncRow[]>>): SyncFile {
  const sorted = {} as Record<SyncKind, readonly SyncRow[]>;
  for (const kind of SYNC_KINDS) {
    const list = [...(rows[kind] ?? [])];
    for (const row of list) if (row.kind !== kind) throw new Error(`a ${row.kind} row in ${kind}`);
    sorted[kind] = list.sort(byKey);
  }
  return { deviceId, seq, writtenAt, rows: sorted };
}

/**
 * Reads `text` as the sync file of `expectedDeviceId` (the writer the envelope authenticated, docs/15 §9.6). Unknown
 * fields are ignored, at the top and in rows.
 *
 * @throws SyncFileError with the problem of the first thing wrong.
 */
export function parseSyncFile(text: string, expectedDeviceId: string): SyncFile {
  if (utf8Length(text) > SYNC_MAX_BYTES) throw new SyncFileError('TOO_LARGE', `over ${SYNC_MAX_BYTES} bytes`);
  if (nestingDepth(text) > SYNC_MAX_DEPTH) throw new SyncFileError('TOO_LARGE', `nested deeper than ${SYNC_MAX_DEPTH}`);
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    throw new SyncFileError('NOT_JSON', 'not JSON');
  }
  if (!isObject(root)) throw new SyncFileError('NOT_JSON', 'not a JSON object');
  checkFormat(own(root, 'format'));
  const bad = (what: string): never => {
    throw new SyncFileError('BAD_ROW', what);
  };
  const deviceId = own(root, 'deviceId');
  if (!isDeviceId(deviceId)) return bad('deviceId');
  if (deviceId !== expectedDeviceId) throw new SyncFileError('WRONG_DEVICE', "deviceId is not the writer's");
  const seq = own(root, 'seq');
  if (typeof seq !== 'number' || !Number.isSafeInteger(seq) || seq < 1) return bad('seq');
  const writtenAtText = own(root, 'writtenAt');
  const writtenAt = typeof writtenAtText === 'string' ? syncTime(writtenAtText) : null;
  if (writtenAt === null || writtenAt < SYNC_EARLIEST_MS) return bad('writtenAt');
  const lists = {} as Record<SyncKind, readonly unknown[]>;
  let total = 0;
  for (const kind of SYNC_KINDS) {
    const list = own(root, kind);
    if (!Array.isArray(list)) return bad(kind);
    if (list.length > SYNC_MAX_ROWS_OF[kind]) throw new SyncFileError('TOO_LARGE', `${kind} over ${SYNC_MAX_ROWS_OF[kind]}`);
    lists[kind] = list;
    total += list.length;
  }
  if (total > SYNC_MAX_ROWS) throw new SyncFileError('TOO_LARGE', `over ${SYNC_MAX_ROWS} rows`);
  const rows = {} as Record<SyncKind, readonly SyncRow[]>;
  for (const kind of SYNC_KINDS) {
    const seen = new Set<string>();
    rows[kind] = lists[kind].map((element) => {
      if (!isObject(element)) return bad(`${kind}: a row that is not an object`);
      const row = syncRow(kind, element);
      if (seen.has(row.key)) throw new SyncFileError('DUPLICATE_ROW', `${kind}: ${row.key} twice`);
      seen.add(row.key);
      return row;
    });
  }
  return syncFile(deviceId, seq, writtenAt, rows);
}

/**
 * The canonical text of `file` (Kotlin: `SyncFiles.encode`): the envelope in a fixed order, then the four lists, each
 * sorted by key. Refuses (as {@link SyncFileError}) a file a reader would refuse: over a cap, or two rows with one key.
 */
export function encodeSyncFile(file: SyncFile): string {
  if (!isDeviceId(file.deviceId)) throw new Error('deviceId');
  if (!Number.isSafeInteger(file.seq) || file.seq < 1) throw new Error('seq');
  let total = 0;
  for (const kind of SYNC_KINDS) {
    const list = file.rows[kind];
    if (list.length > SYNC_MAX_ROWS_OF[kind]) throw new SyncFileError('TOO_LARGE', `${kind} over ${SYNC_MAX_ROWS_OF[kind]}`);
    total += list.length;
    for (let i = 1; i < list.length; i++) {
      if (list[i].key === list[i - 1].key) throw new SyncFileError('DUPLICATE_ROW', `${kind}: ${list[i].key} twice`);
    }
  }
  if (total > SYNC_MAX_ROWS) throw new SyncFileError('TOO_LARGE', `over ${SYNC_MAX_ROWS} rows`);
  const out: Record<string, unknown> = {
    format: SYNC_FORMAT,
    deviceId: file.deviceId,
    seq: file.seq,
    writtenAt: new Date(file.writtenAt).toISOString(),
  };
  for (const kind of SYNC_KINDS) out[kind] = file.rows[kind].map((row) => row.json);
  const text = JSON.stringify(out);
  if (utf8Length(text) > SYNC_MAX_BYTES) throw new SyncFileError('TOO_LARGE', `over ${SYNC_MAX_BYTES} bytes`);
  return text;
}

function checkFormat(format: unknown): void {
  if (format === SYNC_FORMAT) return;
  if (typeof format === 'string' && format.startsWith(SYNC_FORMAT_PREFIX)) {
    const number = format.slice(SYNC_FORMAT_PREFIX.length);
    if (VERSION.test(number) && Number(number) > SYNC_MAX_VERSION) {
      throw new SyncFileError('UNSUPPORTED_VERSION', 'made by a newer version of Doorprints');
    }
  }
  throw new SyncFileError('NOT_A_SYNC_FILE', 'not a Doorprints sync file');
}

/** The UTF-8 length of `text` without encoding it (an unpaired surrogate counts as U+FFFD's 3 bytes, as TextEncoder). */
export function utf8Length(text: string): number {
  let n = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.length && (text.charCodeAt(i + 1) & 0xfc00) === 0xdc00) {
      n += 4;
      i++;
    } else n += 3;
  }
  return n;
}

/** The deepest nesting of `{` and `[` outside strings, stopping once it passes {@link SYNC_MAX_DEPTH}. */
export function nestingDepth(text: string): number {
  let depth = 0;
  let deepest = 0;
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (inString) {
      if (c === 0x5c) i++;
      else if (c === 0x22) inString = false;
    } else if (c === 0x22) inString = true;
    else if (c === 0x7b || c === 0x5b) {
      depth++;
      if (depth > deepest) deepest = depth;
      if (deepest > SYNC_MAX_DEPTH) return deepest;
    } else if (c === 0x7d || c === 0x5d) depth--;
  }
  return deepest;
}
