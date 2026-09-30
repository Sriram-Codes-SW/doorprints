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

import type { TKey } from '../i18n/en';
import { evaluateScore } from '../shared/scoring';
import type { Scoring } from '../shared/scoring';

/**
 * `TAKEN` (the house the person chose; at most one, `HouseStatusRules`) and `NOT_CHOSEN` (the others once one is
 * taken) came with slice 5 (docs/11 5.24); `REJECTED` keeps its meaning, rejected after looking.
 */
export type HouseStatus = 'NEW' | 'SHORTLISTED' | 'REJECTED' | 'TAKEN' | 'NOT_CHOSEN';
export type PriceType = 'RENT' | 'SALE';
export type VisitSource = 'AUTO' | 'MANUAL';
/**
 * How the house got its position (docs/03 ADR-28, FR-068): `GPS` = *Use my location*, `MAP` = a tap, a drag or the
 * crosshair on the map, `APPROX` = the person says the spot is approximate (a listing whose locality is known but
 * not the building). Absent on a house saved before this: unknown, shown as before. An `APPROX` house is a hollow
 * marker on the map and never a Hunt-mode alert.
 */
export type LocationSource = 'GPS' | 'MAP' | 'APPROX';

/**
 * The real cost of a house (docs/11 5.21, 5.30 item 1). Whole rupees and whole months; a field that is not known
 * is absent (never `null` in a file). The deposit and the brokerage may be given in rupees or in months of rent;
 * when both are set the rupees win in the arithmetic (`costSummary`). Same eleven fields, same names, as Kotlin
 * `HouseCost` in android/shared and the server's `HouseDto.cost`.
 */
export interface HouseCost {
  deposit?: number | null;
  depositMonths?: number | null;
  maintenance?: number | null;
  maintenanceIncluded?: boolean | null;
  brokerage?: number | null;
  brokerageMonths?: number | null;
  lockInMonths?: number | null;
  noticeMonths?: number | null;
  /** A calendar date, `YYYY-MM-DD`, no time zone. */
  availableFrom?: string | null;
  myOffer?: number | null;
  agreedPrice?: number | null;
}

/** The eleven cost fields in the order every writer (backup, wire, readable copies) keeps them. */
export const COST_FIELDS = [
  'deposit',
  'depositMonths',
  'maintenance',
  'maintenanceIncluded',
  'brokerage',
  'brokerageMonths',
  'lockInMonths',
  'noticeMonths',
  'availableFrom',
  'myOffer',
  'agreedPrice',
] as const satisfies readonly (keyof HouseCost)[];

export type RoomType = 'BEDROOM' | 'HALL' | 'KITCHEN' | 'BATHROOM' | 'BALCONY' | 'POOJA' | 'STUDY' | 'UTILITY' | 'STORE' | 'OTHER';

/**
 * A room in a house (docs/11 5.6, slice 1c). Keys in order: id, type, name, lengthCm, widthCm, condition, notes, sort.
 * Unknown enum values on read coerce to OTHER; out-of-range dimensions/condition are unknown (absent).
 */
export interface HouseRoom {
  /** 1..64 characters, matching [A-Za-z0-9._-]{1,64} but not `.`/`..`, unique within the house. */
  id: string;
  /** One of the room types; unknown on read → OTHER. */
  type: RoomType;
  /** ≤60 characters, blank allowed; when blank the UI shows the type's translated name. */
  name?: string | null;
  /** Integer 0..5000 cm, absent when unknown. */
  lengthCm?: number | null;
  /** Integer 0..5000 cm, absent when unknown. */
  widthCm?: number | null;
  /** 1..5, absent when not checked. */
  condition?: number | null;
  /** ≤2000 characters, free text. */
  notes?: string | null;
  /** Integer ≥0, the sort order; readers re-sort by sort then id. */
  sort?: number | null;
}

/** The ten room types, in the order the spec names them. */
export const ROOM_TYPES: readonly RoomType[] = [
  'BEDROOM',
  'HALL',
  'KITCHEN',
  'BATHROOM',
  'BALCONY',
  'POOJA',
  'STUDY',
  'UTILITY',
  'STORE',
  'OTHER',
];

/** Translation key for each room type label. */
export const ROOM_TYPE_KEY: Readonly<Record<RoomType, TKey>> = {
  BEDROOM: 'roomType.BEDROOM',
  HALL: 'roomType.HALL',
  KITCHEN: 'roomType.KITCHEN',
  BATHROOM: 'roomType.BATHROOM',
  BALCONY: 'roomType.BALCONY',
  POOJA: 'roomType.POOJA',
  STUDY: 'roomType.STUDY',
  UTILITY: 'roomType.UTILITY',
  STORE: 'roomType.STORE',
  OTHER: 'roomType.OTHER',
};

export type AnswerStatus = 'OPEN' | 'ANSWERED' | 'SKIPPED';

/**
 * One item of a house's move-in checklist (docs/11 5.24, slice 5). Keys in order: id, text, done, sort. `done` is
 * written only when true.
 */
export interface MoveInItem {
  /** 1..64 characters, matching [A-Za-z0-9._-]{1,64}, unique within the house. */
  id: string;
  /** 1..200 characters. */
  text: string;
  /** Written only when true. */
  done?: boolean;
  /** Integer >= 0, the order on the card. */
  sort: number;
}

/**
 * Moving in (docs/11 5.24, slice 5): stored nested in the house after `answers`. Keys in order: date, notes, items.
 * Absent (null) when it has no date, no notes and no items, never an empty object in a file.
 */
export interface MoveIn {
  /** Epoch milliseconds (> 0) of the move-in date; absent when not set. */
  date?: number;
  /** At most 2000 characters; absent when empty. */
  notes?: string;
  /** At most 30 items. */
  items?: MoveInItem[];
}

/**
 * A question asked about one house (docs/11 5.5, slice 3a). Keys in order: id, questionId, text, answer, status, sort.
 * `text` is the question as asked (a snapshot), so the copy reads even when the bank question is edited or deleted.
 * A non-blank `answer` with status OPEN reads as ANSWERED, and ANSWERED with no answer reads as OPEN.
 */
export interface HouseAnswer {
  /** 1..64 characters, matching [A-Za-z0-9._-]{1,64} but not `.`/`..`, unique within the house. */
  id: string;
  /** The bank question it came from; may name a question that no longer exists. */
  questionId?: string | null;
  /** 1..300 characters. */
  text: string;
  /** 1..2000 characters, absent when empty. */
  answer?: string | null;
  status: AnswerStatus;
  /** Integer >= 0, the order on the house; readers use `HouseAnswers.ordered`. */
  sort: number;
}

export const ANSWER_STATUSES: readonly AnswerStatus[] = ['OPEN', 'ANSWERED', 'SKIPPED'];

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
  /** Carpet area in square feet as the person writes it, 1..100000; absent when unknown. */
  areaSqft?: number | null;
  locationSource?: LocationSource | null;
  /** Absent (or null) when no cost field is known; never an empty object in a file. */
  cost?: HouseCost | null;
  /** At most 30 rooms; absent when empty, never an empty array in a file. */
  rooms?: HouseRoom[] | null;
  /** At most 60 questions asked about this house (slice 3a); absent when empty, never an empty array in a file. */
  answers?: HouseAnswer[] | null;
  /** Moving in (slice 5), right after `answers`; absent when it has no date, no notes and no items. */
  moveIn?: MoveIn | null;
  /**
   * The record id of the broker this house is linked to (slice 1b); no foreign key, so an id that names no broker
   * reads as "no broker". The house keeps copies of the broker's name and phone in `contactName`/`contactPhone`.
   */
  brokerId?: string | null;
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
  /** Photo meta (slice 5, docs/11 5.7): the room, tags and caption, with `metaUpdatedAt` (epoch ms, 0 = never edited). */
  roomId?: string | null;
  tags?: string[] | null;
  caption?: string | null;
  metaUpdatedAt?: number | null;
}

/**
 * The envelope every Sprint 4b entity other than houses, visits and photos travels in (docs/11 5.30, ADR-28):
 * `GET /api/records?since=` and `PUT /api/records/{type}/{id}`. The server stores `payload` opaquely and never
 * reads it; each `type` is a small typed class on the apps (`RecordType<T>` in android/shared, its TypeScript twin).
 */
export interface RecordDto {
  type: string;
  id: string;
  payload: Record<string, unknown>;
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

export const STATUSES: readonly HouseStatus[] = ['NEW', 'SHORTLISTED', 'REJECTED', 'TAKEN', 'NOT_CHOSEN'];
export const LOCATION_SOURCES: readonly LocationSource[] = ['GPS', 'MAP', 'APPROX'];

/** Translation key for each status label. */
export const STATUS_KEY: Readonly<Record<HouseStatus, TKey>> = {
  NEW: 'status.NEW',
  SHORTLISTED: 'status.SHORTLISTED',
  REJECTED: 'status.REJECTED',
  TAKEN: 'status.TAKEN',
  NOT_CHOSEN: 'status.NOT_CHOSEN',
};

/** Icon shown next to the status text, so status is never conveyed by colour alone (WCAG 1.4.1). */
export const STATUS_ICON: Readonly<Record<HouseStatus, string>> = {
  NEW: '●',
  SHORTLISTED: '★',
  REJECTED: '✕',
  TAKEN: '⌂',
  NOT_CHOSEN: '–',
};

/**
 * Marker colours on the (light) map tiles. All reach at least 5:1 against white; SHORTLISTED was
 * darkened from #1F8A4C (4.38:1) to #1A7A43 (5.37:1). Keep in sync with --status-* in styles.css.
 */
export const STATUS_COLOR: Readonly<Record<HouseStatus, string>> = {
  NEW: '#3C5A99',
  SHORTLISTED: '#1A7A43',
  REJECTED: '#B3261E',
  TAKEN: '#6A1B9A',
  NOT_CHOSEN: '#5F6B66',
};

/**
 * 0–5 overall score of a house under the effective scoring (docs/11 5.4, `evaluateScore`): the weighted checklist
 * blended with the star rating by the rating share. Null if nothing has been scored yet. With `DEFAULT_SCORING` this
 * is the old rule (average of the checklist, 50/50 with the rating). Every caller passes the scoring it loaded.
 */
export function houseScore(h: Pick<HouseDto, 'checklist' | 'rating'>, scoring: Scoring): number | null {
  return evaluateScore(h.checklist, h.rating, scoring).overall;
}

export function newHouse(lat: number, lon: number, locationSource: LocationSource | null = null): HouseDto {
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
    areaSqft: null,
    locationSource,
    cost: null,
    rooms: null,
    answers: null,
    moveIn: null,
    brokerId: null,
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
