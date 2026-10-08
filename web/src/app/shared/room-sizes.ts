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
 * Room size conversion and display helpers (docs/11 5.6, slice 1c). The TypeScript twin of Kotlin `RoomSizes` in
 * android/shared: the same rules, the same test vectors in `room-sizes.spec.ts`. Sizes are stored in centimetres
 * and displayed as feet+inches ("13 ft 0 in") or metres with one decimal ("3.96 m"); the form takes two numbers per
 * dimension in feet mode and one decimal number in metres mode.
 */

/** A length in whole feet and inches. */
export interface FeetInches {
  feet: number;
  inches: number;
}

/**
 * Convert centimetres to feet and inches.
 * 396 cm = 13 ft 0 in.
 */
export function cmToFeetInches(cm: number): FeetInches {
  const totalInches = Math.round(cm / 2.54);
  const feet = Math.floor(totalInches / 12);
  const inches = totalInches % 12;
  return { feet, inches };
}

/**
 * Convert feet and inches to centimetres.
 * 13 ft 0 in = 396 cm.
 */
export function feetInchesToCm(feet: number, inches: number): number {
  return Math.round((feet * 12 + inches) * 2.54);
}

/**
 * Room area in square feet, rounded to the nearest whole number.
 * 396 cm × 366 cm = 156 sq ft (155.99 → 156).
 */
export function areaSqFt(lengthCm: number, widthCm: number): number {
  return Math.round((lengthCm * widthCm) / 929.0304);
}

/**
 * Room area in square metres, rounded to one decimal place.
 * 3.96 m × 3.66 m = 14.5 m².
 */
export function areaSqM(lengthCm: number, widthCm: number): number {
  const sqM = (lengthCm * widthCm) / 10_000;
  return Math.round(sqM * 10) / 10;
}

/** The length preference (a local setting, never synced): feet and inches, or metres. */
export type LengthUnit = 'FT' | 'M';

/** Whole square feet of an area in square centimetres (a total). */
export function sqFt(sqCm: number): number {
  return Math.round(sqCm / 929.0304);
}

/** Square metres to one decimal of an area in square centimetres. */
export function sqM(sqCm: number): number {
  return Math.round(sqCm / 1000) / 10;
}

/** `13 ft 0 in`: the house form, Compare, the readable copies and AI (always this form). */
export function feetInchesText(cm: number): string {
  const { feet, inches } = cmToFeetInches(cm);
  return `${feet} ft ${inches} in`;
}

/** Metres with two decimals, the centimetre shown: `3.96` (for `3.96 m`). */
export function metresText(cm: number): string {
  return `${Math.floor(cm / 100)}.${String(cm % 100).padStart(2, '0')}`;
}

/** One size in the unit: `13 ft 0 in` or `3.96 m`. */
export function lengthText(cm: number, unit: LengthUnit): string {
  return unit === 'M' ? `${metresText(cm)} m` : feetInchesText(cm);
}

/** The area of a room in square centimetres, or null unless both sizes are known. */
export function areaSqCm(room: { lengthCm?: number | null; widthCm?: number | null }): number | null {
  return room.lengthCm == null || room.widthCm == null ? null : room.lengthCm * room.widthCm;
}

/** `13 ft 0 in × 12 ft 0 in`, or null unless both sizes are known. */
export function sizeText(room: { lengthCm?: number | null; widthCm?: number | null }, unit: LengthUnit): string | null {
  if (room.lengthCm == null || room.widthCm == null) return null;
  return `${lengthText(room.lengthCm, unit)} × ${lengthText(room.widthCm, unit)}`;
}

/** An area's number in the unit, without the unit: `156` (sq ft) or `14.5` (m²). */
export function areaNumber(sqCm: number, unit: LengthUnit): string {
  return unit === 'M' ? sqM(sqCm).toFixed(1) : String(sqFt(sqCm));
}

/** The total of the rooms' areas in square centimetres and how many rooms have both sizes. */
export function totalAreaSqCm(rooms: readonly { lengthCm?: number | null; widthCm?: number | null }[]): {
  total: number;
  sized: number;
} {
  let total = 0;
  let sized = 0;
  for (const room of rooms) {
    const area = areaSqCm(room);
    if (area !== null) {
      total += area;
      sized += 1;
    }
  }
  return { total, sized };
}

/** Metres as typed in the form (`3.96`, `3,96`, `4`), as whole centimetres within 0..5000; null otherwise (blank too). */
export function parseMetres(text: string): number | null {
  const t = text.trim().replace(',', '.');
  if (!/^\d{1,2}(\.\d{0,2})?$/.test(t)) return null;
  const cm = Math.round(Number(t) * 100);
  return cm <= 5000 ? cm : null;
}

/** Feet and inches as typed (blank is 0, inches 0..11), as whole centimetres within 0..5000; null otherwise. */
export function parseFeetInches(feet: string, inches: string): number | null {
  const f = feet.trim() === '' ? 0 : Number(feet.trim());
  const i = inches.trim() === '' ? 0 : Number(inches.trim());
  if (!Number.isInteger(f) || !Number.isInteger(i) || f < 0 || i < 0 || i > 11 || f > 200) return null;
  const cm = feetInchesToCm(f, i);
  return cm <= 5000 ? cm : null;
}

/**
 * The rooms after moving room `id` one place up (`by` -1) or down (+1) in the order shown (sort, then id; S4b-BL-87),
 * every `sort` renumbered 0..n-1 in that order; the list as it was (in the order shown) when `id` is not in it or is
 * already first or last. Kotlin: `HouseRooms.move`, with the same vectors in `room-sizes.spec.ts` ("moveRoom").
 */
export function moveRoom<T extends { id: string; sort?: number | null }>(rooms: readonly T[], id: string, by: number): T[] {
  const ordered = [...rooms].sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const from = ordered.findIndex((r) => r.id === id);
  const to = from + by;
  if (from >= 0 && to >= 0 && to < ordered.length) ordered.splice(to, 0, ...ordered.splice(from, 1));
  return ordered.map((r, i) => (r.sort === i ? r : { ...r, sort: i }));
}
