import { ANSWER_STATUSES } from '../core/models';
import { MAX_ANSWERS, MAX_ANSWER, MAX_ANSWER_TEXT, MAX_MOVE_IN_ITEMS, MAX_MOVE_IN_NOTES, MAX_MOVE_IN_TEXT, MAX_ROOMS, MAX_ROOM_DIMENSION, MAX_ROOM_NAME, MAX_ROOM_NOTES } from '../data/records';
import { MAX_AREAS, MAX_AREA_NAME, MAX_AREA_NOTES, MAX_AREA_ID, MAX_NOTE_TEXT, MAX_PLACES, MAX_PLACE_NAME, MAX_RADIUS_M, MAX_STREET, MIN_RADIUS_M } from '../shared/area';
import { MAX_BROKER_AGENCY, MAX_BROKER_FEE_TERMS, MAX_BROKER_NAME, MAX_BROKER_NOTES, MAX_BROKER_PHONE } from '../shared/broker';
import { MAX_CAPTION, MAX_PHOTO_ROOM_ID, validate as validateTags } from '../shared/photo-tags';
import { MAX_QUESTIONS, MAX_QUESTION_TEXT } from '../shared/question';
import { BUILT_IN_KEYS, MAX_CRITERIA, MAX_CRITERION_LABEL, MAX_PREFERENCE_VALUE } from '../shared/scoring';
import { MAX_DURATION_MIN, MAX_ID_LENGTH, MAX_VIEWINGS, MAX_VIEWING_NOTES, MAX_WITH_WHOM, MIN_DURATION_MIN, REMIND_OPTIONS, VIEWING_KINDS, VIEWING_STATUSES } from '../shared/viewing';
import { BACKUP_FORMATS_READ } from './backup-export';
import type { BackupData } from './backup-export';

/**
 * The checks of a `data.json` before anything is written (docs/schemas/README.md section 6 rule 1; S4b-BL-75): the
 * website's copy of Kotlin's `BackupValidation.checkData` and of the strict decoding in front of it, rule for rule, so a
 * file one app refuses the other refuses too. `docs/schemas/import-vectors.json` holds the shared cases
 * (`backup-import.spec.ts` here, `ImportVectorsTest` in `android/shared`).
 *
 * The shape first, as kotlinx's decoder does: a field that must be there and is not, a `null` where none is allowed, or a
 * value of the wrong type is `BROKEN_DATA`. Then the format (`UNSUPPORTED_VERSION` above the newest number this reader
 * knows), then the rows: ids, repeats, ranges and caps. An unknown key is ignored; an unknown deletion kind too.
 */
export type BackupProblem =
  | 'NOT_A_BACKUP'
  | 'UNSUPPORTED_VERSION'
  | 'TOO_MANY_ENTRIES'
  | 'TOO_LARGE'
  | 'SUSPICIOUS_PATH'
  | 'CHECKSUM_MISMATCH'
  | 'BROKEN_DATA'
  | 'READ_FAILED'
  | 'WRITE_FAILED';

export type CheckedData = { ok: true; data: BackupData } | { ok: false; problem: BackupProblem };

/** Thrown inside the decoder: the shape is not one any writer of the format produces. */
class Broken extends Error {}

type Obj = Record<string, unknown>;

/** A house, visit, photo or broker id (Kotlin `BackupValidation.isValidId`): no dot, unlike a record's. */
const ROW_ID = /^[A-Za-z0-9_-]{1,64}$/;
/** A record's, a room's or an answer's id (Kotlin `RecordRules.isValidId`): dots allowed, but not `.` or `..`. */
const RECORD_ID = /^[A-Za-z0-9._-]{1,64}$/;
const INT_MIN = -2_147_483_648;
const INT_MAX = 2_147_483_647;

export const isRowId = (id: unknown): boolean => typeof id === 'string' && ROW_ID.test(id);
export const isRecordId = (id: unknown): boolean => typeof id === 'string' && RECORD_ID.test(id) && id !== '.' && id !== '..';

function object(value: unknown): Obj {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Broken();
  return value as Obj;
}

/** A field that must be present and not null. */
function need(o: Obj, key: string): unknown {
  const v = o[key];
  if (v === undefined || v === null) throw new Broken();
  return v;
}

function str(o: Obj, key: string): string {
  const v = need(o, key);
  if (typeof v !== 'string') throw new Broken();
  return v;
}

/** An optional value: absent and `null` are the same (docs/schemas section 4.1). */
function opt<T>(o: Obj, key: string, read: (o: Obj, key: string) => T): T | undefined {
  return o[key] === undefined || o[key] === null ? undefined : read(o, key);
}

function num(o: Obj, key: string): number {
  const v = need(o, key);
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new Broken();
  return v;
}

/** A Kotlin `Int`: a whole number in 32 bits. */
function int(o: Obj, key: string): number {
  const v = num(o, key);
  if (!Number.isInteger(v) || v < INT_MIN || v > INT_MAX) throw new Broken();
  return v;
}

/** A Kotlin `Long`: a whole number (JavaScript keeps it exact up to 2^53, far past any time or rupee amount). */
function long(o: Obj, key: string): number {
  const v = num(o, key);
  if (!Number.isSafeInteger(v)) throw new Broken();
  return v;
}

function bool(o: Obj, key: string): boolean {
  const v = need(o, key);
  if (typeof v !== 'boolean') throw new Broken();
  return v;
}

/** A field with a default in Kotlin: absent reads as the default, `null` is refused (no `coerceInputValues`). */
function dflt<T>(o: Obj, key: string, read: (o: Obj, key: string) => T, fallback: T): T {
  if (o[key] === undefined) return fallback;
  return read(o, key);
}

function list(o: Obj, key: string, required = false): unknown[] | undefined {
  const v = o[key];
  if (v === undefined) {
    if (required) throw new Broken();
    return undefined;
  }
  if (v === null) return undefined;
  if (!Array.isArray(v)) throw new Broken();
  return v;
}

const len = (s: string | undefined | null): number => (s ? s.length : 0);
const blank = (s: string): boolean => s.trim() === '';
const unique = (ids: readonly string[]): boolean => new Set(ids).size === ids.length;
const validPoint = (lat: number, lon: number): boolean => lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;

/** Checks a parsed `data.json` the way every reader does; on success the rows are the file's own objects. */
export function checkData(raw: unknown): CheckedData {
  try {
    return check(object(raw));
  } catch (e) {
    if (e instanceof Broken) return { ok: false, problem: 'BROKEN_DATA' };
    throw e;
  }
}

function check(root: Obj): CheckedData {
  const format = str(root, 'format');
  long(root, 'exportedAt');
  const houses = (list(root, 'houses') ?? []).map(object);
  const visits = (list(root, 'visits') ?? []).map(object);
  const photos = (list(root, 'photos') ?? []).map(object);
  const brokers = (list(root, 'brokers') ?? []).map(object);
  const criteria = (list(root, 'criteria') ?? []).map(object);
  const preferences = (list(root, 'preferences') ?? []).map(object);
  const questions = (list(root, 'questions') ?? []).map(object);
  const viewings = (list(root, 'viewings') ?? []).map(object);
  const areas = (list(root, 'areas') ?? []).map(object);
  const places = (list(root, 'places') ?? []).map(object);
  const areaNotes = (list(root, 'areaNotes') ?? []).map(object);
  const deleted = (list(root, 'deleted') ?? []).map(object);
  // The shape of every row, as decoding it would.
  houses.forEach(shapeHouse);
  visits.forEach(shapeVisit);
  photos.forEach(shapePhoto);
  brokers.forEach(shapeBroker);
  criteria.forEach((c) => (str(c, 'key'), opt(c, 'label', str), int(c, 'weight'), bool(c, 'mustHave'), int(c, 'minScore'), int(c, 'sort'), opt(c, 'archived', bool), long(c, 'updatedAt')));
  preferences.forEach((p) => (str(p, 'key'), str(p, 'value'), long(p, 'updatedAt')));
  questions.forEach((q) => (str(q, 'id'), str(q, 'text'), str(q, 'category'), str(q, 'appliesTo'), bool(q, 'defaultOn'), int(q, 'sort'), opt(q, 'archived', bool), long(q, 'updatedAt')));
  viewings.forEach(shapeViewing);
  areas.forEach((a) => (str(a, 'id'), dflt(a, 'name', str, ''), opt(a, 'lat', num), opt(a, 'lon', num), opt(a, 'radiusM', int), opt(a, 'enabled', bool), long(a, 'updatedAt')));
  places.forEach((p) => (str(p, 'id'), dflt(p, 'name', str, ''), opt(p, 'lat', num), opt(p, 'lon', num), long(p, 'updatedAt')));
  areaNotes.forEach((n) => (str(n, 'id'), opt(n, 'areaId', str), opt(n, 'street', str), dflt(n, 'text', str, ''), long(n, 'updatedAt')));
  deleted.forEach((d) => (str(d, 'kind'), str(d, 'id'), long(d, 'updatedAt')));

  if (!BACKUP_FORMATS_READ.includes(format)) return { ok: false, problem: 'UNSUPPORTED_VERSION' };
  const broken = (): CheckedData => ({ ok: false, problem: 'BROKEN_DATA' });
  if (!houses.every((h) => isRowId(h['id'])) || !visits.every((v) => isRowId(v['id']))) return broken();
  if (!visits.every((v) => v['houseId'] == null || isRowId(v['houseId']))) return broken();
  if (!photos.every((p) => isRowId(p['id']) && isRowId(p['houseId']))) return broken();
  if (!unique(houses.map((h) => h['id'] as string)) || !unique(visits.map((v) => v['id'] as string)) || !unique(photos.map((p) => p['id'] as string))) return broken();
  if (!brokers.every((b) => isRowId(b['id']) && brokerValid(b)) || !unique(brokers.map((b) => b['id'] as string))) return broken();
  if (!houses.every((h) => h['brokerId'] == null || isRowId(h['brokerId']))) return broken();
  if (!houses.every((h) => roomsValid(h['rooms']))) return broken();
  if (criteria.length > MAX_CRITERIA || !criteria.every(criterionValid) || !unique(criteria.map((c) => c['key'] as string))) return broken();
  if (!preferences.every((p) => isRecordId(p['key']) && len(p['value'] as string) <= MAX_PREFERENCE_VALUE) || !unique(preferences.map((p) => p['key'] as string))) return broken();
  if (questions.length > MAX_QUESTIONS || !questions.every(questionValid) || !unique(questions.map((q) => q['id'] as string))) return broken();
  if (!houses.every((h) => answersValid(h['answers']))) return broken();
  if (viewings.length > MAX_VIEWINGS || !viewings.every(viewingValid) || !unique(viewings.map((v) => v['id'] as string))) return broken();
  if (!recordsValid(areas, MAX_AREAS, areaValid) || !recordsValid(places, MAX_PLACES, placeValid) || !recordsValid(areaNotes, MAX_AREA_NOTES, noteValid)) return broken();
  if (!houses.every((h) => h['moveIn'] == null || moveInValid(h['moveIn'] as Obj))) return broken();
  if (!photos.every(photoMetaValid)) return broken();
  if (!deletionsValid(deleted, houses)) return broken();
  return { ok: true, data: root as unknown as BackupData };
}

function shapeHouse(h: Obj): void {
  str(h, 'id');
  str(h, 'label');
  for (const k of ['address', 'street', 'locality', 'priceType', 'contactName', 'contactPhone', 'listingUrl', 'notes', 'locationSource', 'brokerId']) opt(h, k, str);
  num(h, 'lat');
  num(h, 'lon');
  str(h, 'status');
  opt(h, 'price', long);
  for (const k of ['bedrooms', 'rating', 'areaSqft', 'floor']) opt(h, k, int);
  if (h['cost'] != null) {
    const c = object(h['cost']);
    for (const k of ['deposit', 'maintenance', 'brokerage', 'myOffer', 'agreedPrice']) opt(c, k, long);
    for (const k of ['depositMonths', 'brokerageMonths', 'lockInMonths', 'noticeMonths']) opt(c, k, int);
    opt(c, 'maintenanceIncluded', bool);
    opt(c, 'availableFrom', str);
  }
  (list(h, 'rooms') ?? []).map(object).forEach((r) => {
    str(r, 'id');
    dflt(r, 'type', str, 'OTHER');
    opt(r, 'name', str);
    for (const k of ['lengthCm', 'widthCm', 'condition']) opt(r, k, int);
    opt(r, 'notes', str);
    dflt(r, 'sort', int, 0);
  });
  (list(h, 'answers') ?? []).map(object).forEach((a) => {
    str(a, 'id');
    opt(a, 'questionId', str);
    dflt(a, 'text', str, '');
    opt(a, 'answer', str);
    dflt(a, 'status', str, 'OPEN');
    dflt(a, 'sort', int, 0);
  });
  if (h['moveIn'] != null) {
    const m = object(h['moveIn']);
    opt(m, 'date', long);
    opt(m, 'notes', str);
    (list(m, 'items') ?? []).map(object).forEach((i) => (str(i, 'id'), dflt(i, 'text', str, ''), opt(i, 'done', bool), dflt(i, 'sort', int, 0)));
  }
  // The one lenient always-present field: absent or null is "no scores" (section 4.4); a value must be a map of whole numbers.
  if (h['checklist'] != null) {
    const c = object(h['checklist']);
    for (const k of Object.keys(c)) int(c, k);
  }
  long(h, 'createdAt');
  long(h, 'updatedAt');
}

function shapeVisit(v: Obj): void {
  str(v, 'id');
  opt(v, 'houseId', str);
  num(v, 'lat');
  num(v, 'lon');
  opt(v, 'street', str);
  long(v, 'arrivedAt');
  opt(v, 'leftAt', long);
  str(v, 'source');
  long(v, 'updatedAt');
}

function shapePhoto(p: Obj): void {
  str(p, 'id');
  str(p, 'houseId');
  str(p, 'fileName');
  long(p, 'createdAt');
  opt(p, 'roomId', str);
  const tags = list(p, 'tags');
  if (tags && !tags.every((t) => typeof t === 'string')) throw new Broken();
  opt(p, 'caption', str);
  opt(p, 'metaUpdatedAt', long);
}

function shapeBroker(b: Obj): void {
  str(b, 'id');
  str(b, 'name');
  for (const k of ['phone', 'agency', 'feeTerms', 'notes']) opt(b, k, str);
  opt(b, 'rating', int);
  long(b, 'updatedAt');
}

function shapeViewing(v: Obj): void {
  str(v, 'id');
  dflt(v, 'houseId', str, '');
  dflt(v, 'startsAt', long, 0);
  opt(v, 'durationMin', int);
  opt(v, 'kind', str);
  opt(v, 'status', str);
  opt(v, 'remindMin', int);
  opt(v, 'huntReminder', bool);
  for (const k of ['withWhom', 'notes', 'visitId']) opt(v, k, str);
  long(v, 'updatedAt');
}

function brokerValid(b: Obj): boolean {
  const name = b['name'] as string;
  const rating = b['rating'] as number | undefined | null;
  return !blank(name) && name.length <= MAX_BROKER_NAME && len(b['phone'] as string) <= MAX_BROKER_PHONE &&
    len(b['agency'] as string) <= MAX_BROKER_AGENCY && len(b['feeTerms'] as string) <= MAX_BROKER_FEE_TERMS &&
    len(b['notes'] as string) <= MAX_BROKER_NOTES && (rating == null || (rating >= 1 && rating <= 5));
}

const dimension = (v: unknown): boolean => v == null || ((v as number) >= 0 && (v as number) <= MAX_ROOM_DIMENSION);

function roomsValid(raw: unknown): boolean {
  if (raw == null) return true;
  const rooms = raw as Obj[];
  return rooms.length <= MAX_ROOMS && unique(rooms.map((r) => r['id'] as string)) && rooms.every((r) =>
    isRecordId(r['id']) && len(r['name'] as string) <= MAX_ROOM_NAME && dimension(r['lengthCm']) && dimension(r['widthCm']) &&
    (r['condition'] == null || ((r['condition'] as number) >= 1 && (r['condition'] as number) <= 5)) &&
    len(r['notes'] as string) <= MAX_ROOM_NOTES && ((r['sort'] as number | undefined) ?? 0) >= 0);
}

function criterionValid(c: Obj): boolean {
  const key = c['key'] as string;
  const label = c['label'] as string | undefined | null;
  const builtIn = (BUILT_IN_KEYS as readonly string[]).includes(key);
  const weight = c['weight'] as number;
  const min = c['minScore'] as number;
  return isRecordId(key) && weight >= 0 && weight <= 3 && min >= 1 && min <= 5 && (c['sort'] as number) >= 0 &&
    (label == null || (!builtIn && label.length <= MAX_CRITERION_LABEL));
}

function questionValid(q: Obj): boolean {
  const text = q['text'] as string;
  return isRecordId(q['id']) && !blank(text) && text.length <= MAX_QUESTION_TEXT && (q['sort'] as number) >= 0;
}

function answersValid(raw: unknown): boolean {
  if (raw == null) return true;
  const answers = raw as Obj[];
  return answers.length <= MAX_ANSWERS && unique(answers.map((a) => a['id'] as string)) && answers.every((a) => {
    const text = (a['text'] as string | undefined) ?? '';
    const status = (a['status'] as string | undefined) ?? 'OPEN';
    return isRecordId(a['id']) && !blank(text) && text.length <= MAX_ANSWER_TEXT && len(a['answer'] as string) <= MAX_ANSWER &&
      (ANSWER_STATUSES as readonly string[]).includes(status) && ((a['sort'] as number | undefined) ?? 0) >= 0 &&
      (a['questionId'] == null || isRecordId(a['questionId']));
  });
}

function viewingValid(v: Obj): boolean {
  const houseId = (v['houseId'] as string | undefined) ?? '';
  const duration = (v['durationMin'] as number | undefined | null) ?? 30;
  const kind = (v['kind'] as string | undefined | null) ?? 'FIRST';
  const status = (v['status'] as string | undefined | null) ?? 'PLANNED';
  const remind = (v['remindMin'] as number | undefined | null) ?? 60;
  return (v['updatedAt'] as number) >= 0 && isRecordId(v['id']) && !blank(houseId) && houseId.length <= MAX_ID_LENGTH &&
    ((v['startsAt'] as number | undefined) ?? 0) > 0 && len(v['visitId'] as string) <= MAX_ID_LENGTH &&
    duration >= MIN_DURATION_MIN && duration <= MAX_DURATION_MIN && (VIEWING_KINDS as readonly string[]).includes(kind) &&
    (VIEWING_STATUSES as readonly string[]).includes(status) && REMIND_OPTIONS.includes(remind) &&
    len(v['withWhom'] as string) <= MAX_WITH_WHOM && len(v['notes'] as string) <= MAX_VIEWING_NOTES;
}

function recordsValid(rows: readonly Obj[], max: number, valid: (o: Obj) => boolean): boolean {
  return rows.length <= max && rows.every((r) => (r['updatedAt'] as number) >= 0 && valid(r)) && unique(rows.map((r) => r['id'] as string));
}

function areaValid(a: Obj): boolean {
  const name = (a['name'] as string | undefined) ?? '';
  const lat = a['lat'] as number | undefined | null;
  const lon = a['lon'] as number | undefined | null;
  const radius = (a['radiusM'] as number | undefined | null) ?? 500;
  return lat != null && lon != null && isRecordId(a['id']) && !blank(name) && name.length <= MAX_AREA_NAME && validPoint(lat, lon) &&
    radius >= MIN_RADIUS_M && radius <= MAX_RADIUS_M;
}

function placeValid(p: Obj): boolean {
  const name = (p['name'] as string | undefined) ?? '';
  const lat = p['lat'] as number | undefined | null;
  const lon = p['lon'] as number | undefined | null;
  return lat != null && lon != null && isRecordId(p['id']) && !blank(name) && name.length <= MAX_PLACE_NAME && validPoint(lat, lon);
}

function noteValid(n: Obj): boolean {
  const areaId = n['areaId'] as string | undefined | null;
  const street = n['street'] as string | undefined | null;
  const text = (n['text'] as string | undefined) ?? '';
  return isRecordId(n['id']) && (areaId == null) !== (street == null) &&
    (areaId == null || (!blank(areaId) && areaId.length <= MAX_AREA_ID)) &&
    (street == null || (!blank(street) && street.length <= MAX_STREET)) && !blank(text) && text.length <= MAX_NOTE_TEXT;
}

function moveInValid(m: Obj): boolean {
  const date = m['date'] as number | undefined | null;
  const items = ((m['items'] as Obj[] | undefined | null) ?? []);
  return (date == null || date > 0) && len(m['notes'] as string) <= MAX_MOVE_IN_NOTES && items.length <= MAX_MOVE_IN_ITEMS &&
    items.every((i) => {
      const text = (i['text'] as string | undefined) ?? '';
      return isRecordId(i['id']) && !blank(text) && text.length <= MAX_MOVE_IN_TEXT && ((i['sort'] as number | undefined) ?? 0) >= 0;
    }) && unique(items.map((i) => i['id'] as string));
}

function photoMetaValid(p: Obj): boolean {
  const roomId = p['roomId'] as string | undefined | null;
  const tags = (p['tags'] as string[] | undefined | null) ?? [];
  return (roomId == null || (roomId.length > 0 && roomId.length <= MAX_PHOTO_ROOM_ID)) && validateTags(tags) === null &&
    len(p['caption'] as string) <= MAX_CAPTION && ((p['metaUpdatedAt'] as number | undefined | null) ?? 0) >= 0;
}

/** At most this many deletions in one update file (Kotlin `ExportDeletion.MAX`). */
export const MAX_DELETIONS = 20_000;

function deletionsValid(rows: readonly Obj[], houses: readonly Obj[]): boolean {
  if (rows.length === 0) return true;
  const live = new Set(houses.map((h) => h['id'] as string));
  return rows.length <= MAX_DELETIONS &&
    rows.every((d) => !blank(d['kind'] as string) && (d['kind'] as string).length <= 32 && isRowId(d['id']) && (d['updatedAt'] as number) >= 0) &&
    unique(rows.map((d) => `${d['kind'] as string}\n${d['id'] as string}`)) &&
    !rows.some((d) => d['kind'] === 'house' && live.has(d['id'] as string));
}
