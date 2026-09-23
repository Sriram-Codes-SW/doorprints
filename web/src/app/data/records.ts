import { LocalDataError } from '../core/local-error';
import type { HouseDto, HouseStatus, PriceType, VisitDto, VisitSource } from '../core/models';

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

/** One row of the `settings` store: sync cursors, first-run flags and user preferences. */
export interface SettingRecord {
  key: string;
  value: string;
}

export const SETTING_KEYS = {
  houseCursor: 'cursor.house',
  visitCursor: 'cursor.visit',
  photoCursor: 'cursor.photo',
  /** Set once the "download my houses to this browser" migration has run or been dismissed. */
  migration: 'migration.state',
  /** Remembers that navigator.storage.persist() was already requested, so we ask the browser only once. */
  persistAsked: 'storage.persist-asked',
  /** Remembers the export options the user last chose. */
  exportOptions: 'export.options',
  /**
   * The normalized server address the three cursors belong to. When the user connects a different server the
   * cursors go back to 0 and the migration question is asked again (Android: Settings.saveServer).
   */
  syncServer: 'sync.server',
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

/** The wire form of a stored house: the local flags are stripped. */
export function houseToDto(record: HouseRecord): HouseDto {
  const { dirty: _dirty, ...dto } = record;
  return dto;
}

export function visitToDto(record: VisitRecord): VisitDto {
  const { dirty: _dirty, ...dto } = record;
  return dto;
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

function finite(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
