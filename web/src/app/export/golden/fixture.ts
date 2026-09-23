import type { HouseRecord, PhotoRecord, VisitRecord } from '../../data/records';
import { DEFAULT_EXPORT_OPTIONS, collect } from '../export-model';
import type { ExportBundle, ExportOptions } from '../export-model';

/**
 * The fixture behind the golden-file tests (docs/06 test plan; docs/11 §5.2 "deterministic").
 *
 * It is deliberately awkward: a house whose name starts with `=` (spreadsheet formula injection), Markdown and
 * HTML control characters in the notes, a Tamil label, an unknown checklist key from a newer app version, a house
 * with nothing filled in, a rejected house, a visit that has not ended, and a photo. If the exporters survive
 * this unchanged, they survive real data.
 *
 * **Two rows interleave on purpose** (`docs/schemas/README.md` v1.1 §5, handover S4-00/b): house 3's visit
 * `…aaa3` arrives *between* house 1's two visits, and house 3's photo `…bbb2` was created *before* house 1's.
 * Grouped by house — the rule ADR-20 and NFR-023 pin — both of house 3's rows still come last; sorted globally by
 * `arrivedAt` / `createdAt` they would not. The first version of this fixture had every visit and the only photo
 * on house 1, so it agreed with both rules by accident and pinned neither. Do not simplify it back.
 *
 * The same fixture is used by the Android team's golden files (S4-00 "shared fixture files"), so the two apps can
 * be compared row by row.
 */

export const FIXTURE_EXPORTED_AT = '2026-09-22T10:15:30.000Z';

const H1 = '11111111-1111-4111-8111-111111111111';
const H2 = '22222222-2222-4222-8222-222222222222';
const H3 = '33333333-3333-4333-8333-333333333333';

export const FIXTURE_HOUSES: HouseRecord[] = [
  {
    id: H1,
    label: 'Green View 2BHK',
    address: '12, MG Road',
    street: 'MG Road',
    locality: 'Adyar',
    lat: 13.006,
    lon: 80.2574,
    status: 'SHORTLISTED',
    price: 32000,
    priceType: 'RENT',
    bedrooms: 2,
    rating: 4,
    contactName: 'Ravi Kumar',
    contactPhone: '+91 98400 11111',
    listingUrl: 'https://example.com/listing/1',
    notes: 'Owner said "no pets" & <no smoking>.\nAsk about water in summer.',
    checklist: { water: 5, power: 3, parking: 4, newItemFromNewerApp: 2 },
    createdAt: '2026-09-01T06:00:00.000Z',
    updatedAt: '2026-09-10T08:30:00.000Z',
    deleted: false,
    syncVersion: 7,
    dirty: false,
  },
  {
    id: H2,
    // Starts with "=": a spreadsheet would treat it as a formula unless the exporter guards it.
    label: '=SUM(A1:A9) சென்னை flat',
    address: null,
    street: null,
    locality: null,
    lat: 0,
    lon: 0,
    status: 'NEW',
    price: null,
    priceType: null,
    bedrooms: null,
    rating: null,
    contactName: null,
    contactPhone: null,
    listingUrl: null,
    notes: null,
    checklist: {},
    createdAt: '2026-09-02T06:00:00.000Z',
    updatedAt: '2026-09-02T06:00:00.000Z',
    deleted: false,
    syncVersion: 8,
    dirty: true,
  },
  {
    id: H3,
    label: '',
    address: null,
    street: 'Beach Road',
    locality: null,
    lat: 13.05,
    lon: 80.28,
    status: 'REJECTED',
    price: 1250000,
    priceType: 'SALE',
    bedrooms: 3,
    rating: 1,
    contactName: null,
    contactPhone: null,
    listingUrl: null,
    notes: 'Too noisy | too dark',
    checklist: { noise: 0 },
    createdAt: '2026-09-03T06:00:00.000Z',
    updatedAt: '2026-09-03T06:00:00.000Z',
    deleted: false,
    syncVersion: 9,
    dirty: false,
  },
  {
    // A tombstone: never exported.
    id: '44444444-4444-4444-8444-444444444444',
    label: 'Deleted house',
    address: null,
    street: null,
    locality: null,
    lat: 1,
    lon: 1,
    status: 'NEW',
    price: null,
    priceType: null,
    bedrooms: null,
    rating: null,
    contactName: null,
    contactPhone: null,
    listingUrl: null,
    notes: null,
    checklist: {},
    createdAt: '2026-09-04T06:00:00.000Z',
    updatedAt: '2026-09-05T06:00:00.000Z',
    deleted: true,
    syncVersion: 10,
    dirty: false,
  },
];

export const FIXTURE_VISITS: VisitRecord[] = [
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    houseId: H1,
    lat: 13.006,
    lon: 80.2574,
    street: 'MG Road',
    arrivedAt: '2026-09-05T11:00:00.000Z',
    leftAt: '2026-09-05T11:25:00.000Z',
    source: 'MANUAL',
    updatedAt: '2026-09-05T11:25:00.000Z',
    deleted: false,
    syncVersion: 3,
    dirty: false,
  },
  {
    // Still open (no leftAt) and recorded automatically.
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2',
    houseId: H1,
    lat: 13.0061,
    lon: 80.2575,
    street: 'MG Road',
    arrivedAt: '2026-09-09T04:00:00.000Z',
    leftAt: null,
    source: 'AUTO',
    updatedAt: '2026-09-09T04:00:00.000Z',
    deleted: false,
    syncVersion: 4,
    dirty: false,
  },
  {
    // House 3's visit, arriving *between* house 1's two (2026-09-05 and 2026-09-09): grouped by house it comes
    // last anyway, sorted globally it would come second. That difference is what pins the ordering rule.
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3',
    houseId: H3,
    lat: 13.05,
    lon: 80.28,
    street: 'Beach Road',
    arrivedAt: '2026-09-06T13:06:40.000Z',
    leftAt: '2026-09-06T13:36:40.000Z',
    source: 'MANUAL',
    updatedAt: '2026-09-06T13:36:40.000Z',
    deleted: false,
    syncVersion: 5,
    dirty: false,
  },
  {
    // Deleted: never exported.
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4',
    houseId: H3,
    lat: 13.05,
    lon: 80.28,
    street: 'Beach Road',
    arrivedAt: '2026-09-07T04:00:00.000Z',
    leftAt: null,
    source: 'MANUAL',
    updatedAt: '2026-09-07T04:30:00.000Z',
    deleted: true,
    syncVersion: 6,
    dirty: false,
  },
];

/** Three bytes standing in for JPEG data; the exporters never look inside. */
export const FIXTURE_PHOTO_BYTES = new Uint8Array([0xff, 0xd8, 0xff]);

export const FIXTURE_PHOTOS: PhotoRecord[] = [
  {
    id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1',
    houseId: H1,
    blob: null,
    contentType: 'image/jpeg',
    sizeBytes: FIXTURE_PHOTO_BYTES.length,
    createdAt: '2026-09-05T11:05:00.000Z',
    updatedAt: '2026-09-05T11:05:00.000Z',
    deleted: false,
    syncVersion: 2,
    uploaded: true,
  },
  {
    // House 3's photo, created two days *before* house 1's: the second half of the interleaving pair.
    id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    houseId: H3,
    blob: null,
    contentType: 'image/jpeg',
    sizeBytes: FIXTURE_PHOTO_BYTES.length,
    createdAt: '2026-09-03T06:10:00.000Z',
    updatedAt: '2026-09-03T06:10:00.000Z',
    deleted: false,
    syncVersion: 6,
    uploaded: true,
  },
];

/** Photo bytes keyed by id, as the backup builder expects them. */
export const FIXTURE_PHOTO_MAP: ReadonlyMap<string, Uint8Array> = new Map(
  FIXTURE_PHOTOS.map((photo): [string, Uint8Array] => [photo.id, FIXTURE_PHOTO_BYTES]),
);

/** Fixed `data:` URIs, so the HTML golden does not depend on a canvas being available. */
export const FIXTURE_PHOTO_DATA_URIS: ReadonlyMap<string, string> = new Map(
  FIXTURE_PHOTOS.map((photo): [string, string] => [photo.id, 'data:image/jpeg;base64,/9j/']),
);

export const FIXTURE_OPTIONS: ExportOptions = { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' };

export function fixtureBundle(options: Partial<ExportOptions> = {}): ExportBundle {
  return collect({
    houses: FIXTURE_HOUSES,
    visits: FIXTURE_VISITS,
    photos: FIXTURE_PHOTOS,
    exportedAt: FIXTURE_EXPORTED_AT,
    options: { ...FIXTURE_OPTIONS, ...options },
  });
}
