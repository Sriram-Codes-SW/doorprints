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

import { haversineMeters, km1, walkMinutes } from '../core/ai/ai-core';
import { RECORD_ID_PATTERN } from '../data/records';

/**
 * Hunting areas, my places and area notes (docs/11 "Design of slice 4a", 5.17, 5.22, 5.23). Three record types, `area`,
 * `place` and `areanote`, each keyed by its own id (`a_`, `p_`, `n_` and 8 lowercase hex characters). The pure part,
 * the twin of Kotlin `Areas`/`Places`/`AreaNotes`/`Distances` in android/shared: reading a payload, the payload keys
 * in the contract's order, the notes that reach a house and the distances from a house to my places.
 */

export const AREA_TYPE = 'area';
export const PLACE_TYPE = 'place';
export const AREA_NOTE_TYPE = 'areanote';

export const MAX_AREAS = 20;
export const MAX_PLACES = 10;
export const MAX_AREA_NOTES = 200;
export const MAX_AREA_NAME = 100;
export const MAX_PLACE_NAME = 60;
export const MAX_STREET = 100;
export const MAX_NOTE_TEXT = 1_000;
/** An area's id in a note is at most this long (it may name an area that is gone). */
export const MAX_AREA_ID = 64;
export const MIN_RADIUS_M = 200;
export const MAX_RADIUS_M = 2_000;
export const DEFAULT_RADIUS_M = 500;
export const RADIUS_STEP_M = 100;

const AREA_ID = /^a_[0-9a-f]{8}$/;
const PLACE_ID = /^p_[0-9a-f]{8}$/;
const NOTE_ID = /^n_[0-9a-f]{8}$/;

export interface Area {
  id: string;
  /** 1..100. */
  name: string;
  lat: number;
  lon: number;
  /** 200..2000. */
  radiusM: number;
  /** Written only when false; the wake-up of slice 4b reads it. */
  enabled: boolean;
}

export interface Place {
  id: string;
  /** 1..60. */
  name: string;
  lat: number;
  lon: number;
}

/** Exactly one of `areaId` and `street` is set. */
export interface AreaNote {
  id: string;
  areaId?: string;
  street?: string;
  /** 1..1000. */
  text: string;
}

export interface AreaRow {
  id: string;
  updatedAt: string | null;
  area: Area;
}

export interface PlaceRow {
  id: string;
  updatedAt: string | null;
  place: Place;
}

export interface AreaNoteRow {
  id: string;
  updatedAt: string | null;
  note: AreaNote;
}

/** True for an id the app makes for an area (`a_` and 8 hex). */
export const isAppAreaId = (id: string): boolean => AREA_ID.test(id);
export const isAppPlaceId = (id: string): boolean => PLACE_ID.test(id);
export const isAppNoteId = (id: string): boolean => NOTE_ID.test(id);

/** True when `id` can be a record id at all (`.` and `..` are path segments, so never). */
export function isRecordKey(id: string): boolean {
  return RECORD_ID_PATTERN.test(id) && id !== '.' && id !== '..';
}

export const validLat = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && v >= -90 && v <= 90;
export const validLon = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && v >= -180 && v <= 180;
export const validRadius = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= MIN_RADIUS_M && v <= MAX_RADIUS_M;

function text(value: unknown, max: number): string | undefined {
  return typeof value === 'string' && value.trim() !== '' && value.length <= max ? value : undefined;
}

/**
 * Reads a record payload as an area. A bad id, a blank or over-long name or a coordinate outside its range is
 * untrusted, so the row is `null` and the caller skips it; a radius that is not a whole number of metres in 200..2000
 * reads as 500; only `enabled: false` turns it off. Kotlin: `Areas.fromPayload`.
 */
export function areaFromPayload(id: string, payload: Record<string, unknown> | null | undefined): Area | null {
  if (!isRecordKey(id) || !payload || typeof payload !== 'object') return null;
  const name = text(payload['name'], MAX_AREA_NAME);
  const lat = payload['lat'];
  const lon = payload['lon'];
  if (name === undefined || !validLat(lat) || !validLon(lon)) return null;
  const radius = payload['radiusM'];
  return { id, name, lat, lon, radiusM: validRadius(radius) ? radius : DEFAULT_RADIUS_M, enabled: payload['enabled'] !== false };
}

/** The payload keys in the contract's order: name, lat, lon, radiusM, then `enabled` only when false. */
export function areaToPayload(a: Area): Record<string, unknown> {
  const out: Record<string, unknown> = { name: a.name, lat: a.lat, lon: a.lon, radiusM: a.radiusM };
  if (!a.enabled) out['enabled'] = false;
  return out;
}

/** Kotlin: `Places.fromPayload`. */
export function placeFromPayload(id: string, payload: Record<string, unknown> | null | undefined): Place | null {
  if (!isRecordKey(id) || !payload || typeof payload !== 'object') return null;
  const name = text(payload['name'], MAX_PLACE_NAME);
  const lat = payload['lat'];
  const lon = payload['lon'];
  if (name === undefined || !validLat(lat) || !validLon(lon)) return null;
  return { id, name, lat, lon };
}

export function placeToPayload(p: Place): Record<string, unknown> {
  return { name: p.name, lat: p.lat, lon: p.lon };
}

/**
 * Reads a record payload as an area note: exactly one of `areaId` (<= 64 characters; it may name an area that is gone)
 * and `street` (1..100), then `text` (1..1000). A row with neither or both targets or a blank text is `null`.
 * Kotlin: `AreaNotes.fromPayload`.
 */
export function areaNoteFromPayload(id: string, payload: Record<string, unknown> | null | undefined): AreaNote | null {
  if (!isRecordKey(id) || !payload || typeof payload !== 'object') return null;
  const areaId = text(payload['areaId'], MAX_AREA_ID);
  const street = text(payload['street'], MAX_STREET);
  const body = text(payload['text'], MAX_NOTE_TEXT);
  if (body === undefined || (areaId === undefined) === (street === undefined)) return null;
  return areaId !== undefined ? { id, areaId, text: body } : { id, street: street as string, text: body };
}

/** The payload keys in the contract's order: `areaId` or `street`, then `text`. */
export function areaNoteToPayload(n: AreaNote): Record<string, unknown> {
  return n.areaId !== undefined ? { areaId: n.areaId, text: n.text } : { street: n.street, text: n.text };
}

const compareIds = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
const stamp = (updatedAt: string | null | undefined): number => Date.parse(updatedAt ?? '') || 0;

/** Areas, places by name (code-unit order) then id; the order of the lists on the screens. */
export function sortByName<T extends { id: string }>(list: readonly T[], name: (t: T) => string): T[] {
  return [...list].sort((a, b) => compareIds(name(a).toLowerCase(), name(b).toLowerCase()) || compareIds(a.id, b.id));
}

/** Newest `updatedAt` first, ties by id (code-unit order). */
export function newestFirst<T extends { id: string; updatedAt: string | null }>(list: readonly T[]): T[] {
  return [...list].sort((a, b) => stamp(b.updatedAt) - stamp(a.updatedAt) || compareIds(a.id, b.id));
}

function newId(prefix: string): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return prefix + Array.from(bytes, (x) => x.toString(16).padStart(2, '0')).join('');
}

export const newAreaIdRandom = (): string => newId('a_');
export const newPlaceIdRandom = (): string => newId('p_');
export const newAreaNoteIdRandom = (): string => newId('n_');

/** The house values the derived rules read. */
export interface HousePoint {
  lat: number;
  lon: number;
  street?: string | null;
  locationSource?: string | null;
}

/** A house has real coordinates when it is not APPROX and not at (0, 0), where the apps keep a house without a point. */
export function hasRealPoint(house: HousePoint): boolean {
  return house.locationSource !== 'APPROX' && !(house.lat === 0 && house.lon === 0);
}

/** Street names match after trimming, ignoring case. */
export const sameStreet = (a: string | null | undefined, b: string | null | undefined): boolean => {
  const x = (a ?? '').trim().toLowerCase();
  return x !== '' && x === (b ?? '').trim().toLowerCase();
};

/**
 * The notes that reach a house (docs/11 "Design of slice 4a"): an area note when its area exists (the list holds live
 * areas only) and the house has a real point within the area's radius; a street note when the house's street, trimmed,
 * equals the note's street ignoring case. Newest `updatedAt` first, ties by id. A note whose area is gone reaches
 * nothing. Kotlin: `AreaNotes.reaching`.
 */
export function notesReaching(house: HousePoint, areas: readonly Area[], notes: readonly AreaNoteRow[]): AreaNoteRow[] {
  const byId = new Map(areas.map((a) => [a.id, a]));
  const real = hasRealPoint(house);
  return newestFirst(
    notes.filter((row) => {
      const n = row.note;
      if (n.areaId !== undefined) {
        const area = byId.get(n.areaId);
        return !!area && real && haversineMeters(house.lat, house.lon, area.lat, area.lon) <= area.radiusM;
      }
      return sameStreet(house.street, n.street);
    }),
  );
}

/** The areas whose circle holds the house's point (the house page offers these first for *Add a note for an area*). */
export function areasReaching(house: HousePoint, areas: readonly Area[]): Area[] {
  if (!hasRealPoint(house)) return [];
  return areas.filter((a) => haversineMeters(house.lat, house.lon, a.lat, a.lon) <= a.radiusM);
}

export interface PlaceDistance {
  place: Place;
  /** Straight-line metres. */
  meters: number;
  /** `meters` / 1000 with one decimal, half up ("8.6"). */
  km: string;
  /** The Plan estimate: the walking minutes of Plan (`walkMinutes`: road distance = metres x 1.3). */
  minutes: number;
}

/**
 * Per place, in the order given: straight-line metres, kilometres with one decimal (half up) and the Plan estimate.
 * A house without real coordinates gets none. Kotlin: `Distances.toPlaces`.
 */
export function distancesToPlaces(house: HousePoint, places: readonly Place[]): PlaceDistance[] {
  if (house.lat === 0 && house.lon === 0) return [];
  return places.map((place) => {
    const meters = haversineMeters(house.lat, house.lon, place.lat, place.lon);
    return { place, meters, km: km1(meters), minutes: walkMinutes(meters) };
  });
}

/** Nearest first (ties by name, then id): the order of the AI lines and the copies. */
export function nearestFirst(list: readonly PlaceDistance[]): PlaceDistance[] {
  return [...list].sort((a, b) => a.meters - b.meters || compareIds(a.place.name, b.place.name) || compareIds(a.place.id, b.place.id));
}
