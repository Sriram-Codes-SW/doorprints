import { sortedChecklist } from './export-model';
import type { ExportBundle, ExportHouse as BundleHouse } from './export-model';
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

/** Written into `manifest.json` and `data.json`; a reader refuses anything else. Kotlin: `BackupFormat.ID`. */
export const BACKUP_FORMAT = 'doorprints-backup/1';
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
  checklist: Record<string, number>;
  createdAt: number;
  updatedAt: number;
}

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
}

export interface BackupData {
  format: string;
  exportedAt: number;
  houses: BackupHouse[];
  visits: BackupVisit[];
  photos: BackupPhoto[];
}

export interface BackupCounts {
  houses: number;
  visits: number;
  photos: number;
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
  return {
    format: BACKUP_FORMAT,
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
      entry.photos.map((photo) => ({
        id: photo.id,
        houseId: photo.houseId,
        fileName: photoFileName(photo.id),
        createdAt: millisOf(photo.createdAt),
      })),
    ),
  };
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
    checklist: sortedChecklist(house.checklist),
    createdAt: millisOf(house.createdAt),
    updatedAt: millisOf(house.updatedAt),
  };
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
    format: BACKUP_FORMAT,
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
