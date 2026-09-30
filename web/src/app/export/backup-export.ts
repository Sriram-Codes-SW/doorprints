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
import type { HouseCost } from '../core/models';
import { cleanCost } from '../data/records';
import { sortedChecklist } from './export-model';
import type { ExportBroker, ExportBundle, ExportHouse as BundleHouse } from './export-model';
import { brokerToPayload } from '../shared/broker';
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
 * everything): a `brokers` list after `photos`, `brokerId` on the houses and `counts.brokers`. A copy with no broker
 * stays `/1`. Kotlin: `BackupFormat.ID_V2`.
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
  /** Slice 1b: the record id of the house's broker, right after `cost`. */
  brokerId?: string;
  checklist: Record<string, number>;
  createdAt: number;
  updatedAt: number;
}

/** The eleven cost fields, only the set ones, in `COST_FIELDS` order; the object itself is left out when empty. */
export type BackupCost = { [K in keyof HouseCost]?: NonNullable<HouseCost[K]> };

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

export interface BackupData {
  format: string;
  exportedAt: number;
  houses: BackupHouse[];
  visits: BackupVisit[];
  photos: BackupPhoto[];
  /** Only in a `/2` copy, and then never empty. */
  brokers?: BackupBroker[];
}

export interface BackupCounts {
  houses: number;
  visits: number;
  photos: number;
  /** Only in a `/2` copy. */
  brokers?: number;
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
  return {
    format: brokers ? BACKUP_FORMAT_V2 : BACKUP_FORMAT,
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
    ...(brokers ? { brokers } : {}),
  };
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
