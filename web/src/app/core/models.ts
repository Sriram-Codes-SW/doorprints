import type { TKey } from '../i18n/en';

export type HouseStatus = 'NEW' | 'SHORTLISTED' | 'REJECTED';
export type PriceType = 'RENT' | 'SALE';
export type VisitSource = 'AUTO' | 'MANUAL';

export interface HouseDto {
  id: string;
  label: string;
  address?: string | null;
  street?: string | null;
  locality?: string | null;
  lat: number;
  lon: number;
  status: HouseStatus;
  price?: number | null;
  priceType?: PriceType | null;
  bedrooms?: number | null;
  rating?: number | null;
  contactName?: string | null;
  contactPhone?: string | null;
  listingUrl?: string | null;
  notes?: string | null;
  checklist: Record<string, number>;
  createdAt?: string | null;
  updatedAt?: string | null;
  deleted: boolean;
  syncVersion: number;
  distanceMeters?: number | null;
}

export interface VisitDto {
  id: string;
  houseId?: string | null;
  lat: number;
  lon: number;
  street?: string | null;
  arrivedAt: string;
  leftAt?: string | null;
  source: VisitSource;
  updatedAt?: string | null;
  deleted: boolean;
  syncVersion: number;
}

/**
 * One row of `GET /api/photos?since=`: a new photo or a delete tombstone (never the bytes).
 * Field names match `PhotoDto` on the server and `PhotoChangeDto` in android/shared.
 */
export interface PhotoChangeDto {
  id: string;
  houseId: string;
  contentType?: string | null;
  sizeBytes?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  deleted: boolean;
  syncVersion: number;
}

export interface StatsDto {
  houses: number;
  shortlisted: number;
  rejected: number;
  visits: number;
  streets: number;
  /**
   * The highest sync version the server has handed out (S4b-BL-20, added to the backend on 2026-09-24). Absent from
   * an older server and from this browser's own count (`LocalStore.stats`): unknown, so no reset is read from it.
   */
  maxSyncVersion?: number | null;
}

export interface ChecklistItem {
  /** Stored key, shared with the Android app and the API. */
  key: string;
  /** Translation key for the visible label. */
  labelKey: TKey;
}

/** Same keys as the Android app. Each item is scored 0 (bad) to 5 (great). */
export const CHECKLIST: readonly ChecklistItem[] = [
  { key: 'water', labelKey: 'check.water' },
  { key: 'power', labelKey: 'check.power' },
  { key: 'parking', labelKey: 'check.parking' },
  { key: 'sunlight', labelKey: 'check.sunlight' },
  { key: 'ventilation', labelKey: 'check.ventilation' },
  { key: 'noise', labelKey: 'check.noise' },
  { key: 'security', labelKey: 'check.security' },
  { key: 'maintenance', labelKey: 'check.maintenance' },
  { key: 'neighbourhood', labelKey: 'check.neighbourhood' },
  { key: 'commute', labelKey: 'check.commute' },
];

export const STATUSES: readonly HouseStatus[] = ['NEW', 'SHORTLISTED', 'REJECTED'];

/** Translation key for each status label. */
export const STATUS_KEY: Readonly<Record<HouseStatus, TKey>> = {
  NEW: 'status.NEW',
  SHORTLISTED: 'status.SHORTLISTED',
  REJECTED: 'status.REJECTED',
};

/** Icon shown next to the status text, so status is never conveyed by colour alone (WCAG 1.4.1). */
export const STATUS_ICON: Readonly<Record<HouseStatus, string>> = {
  NEW: '●',
  SHORTLISTED: '★',
  REJECTED: '✕',
};

/**
 * Marker colours on the (light) map tiles. All reach at least 5:1 against white; SHORTLISTED was
 * darkened from #1F8A4C (4.38:1) to #1A7A43 (5.37:1). Keep in sync with --status-* in styles.css.
 */
export const STATUS_COLOR: Readonly<Record<HouseStatus, string>> = {
  NEW: '#3C5A99',
  SHORTLISTED: '#1A7A43',
  REJECTED: '#B3261E',
};

/**
 * 0–5 overall score: average of the checklist, blended 50/50 with the star rating when both exist.
 * Null if nothing has been scored yet.
 */
export function houseScore(h: Pick<HouseDto, 'checklist' | 'rating'>): number | null {
  const values = Object.values(h.checklist ?? {}).filter((v) => typeof v === 'number' && !Number.isNaN(v));
  const check = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
  const rating = h.rating ?? null;
  if (check !== null && rating !== null) return (check + rating) / 2;
  return check ?? rating;
}

export function newHouse(lat: number, lon: number): HouseDto {
  return {
    id: uuid(),
    label: '',
    address: null,
    street: null,
    locality: null,
    lat,
    lon,
    status: 'NEW',
    price: null,
    priceType: 'RENT',
    bedrooms: null,
    rating: null,
    contactName: null,
    contactPhone: null,
    listingUrl: null,
    notes: null,
    checklist: {},
    deleted: false,
    syncVersion: 0,
  };
}

/** RFC 4122 v4 UUID. crypto.randomUUID only exists in secure contexts (HTTPS/localhost), so fall back. */
export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
