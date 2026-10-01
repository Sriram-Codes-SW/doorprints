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

import { STATUS_COLOR } from '../../core/models';
import type { HouseDto } from '../../core/models';

/**
 * The houses layer of the map: what each house's marker says and how it is drawn. Kept apart from the page so the
 * rules can be tested without MapLibre. Android's `MapRules` draws the same: colour and size by status, and an
 * `APPROX` house (FR-068, slice 1a) as a hollow ring in its status colour, so a spot the person only knows roughly
 * is never read as the building.
 */

/** A MapLibre expression; the page casts the whole spec once, so these stay plain data here. */
type Expression = readonly unknown[];

const STATUS_COLOUR: Expression = [
  'match',
  ['get', 'status'],
  'NEW',
  STATUS_COLOR.NEW,
  'SHORTLISTED',
  STATUS_COLOR.SHORTLISTED,
  'REJECTED',
  STATUS_COLOR.REJECTED,
  'TAKEN',
  STATUS_COLOR.TAKEN,
  'NOT_CHOSEN',
  STATUS_COLOR.NOT_CHOSEN,
  '#888888',
];

const IS_APPROX: Expression = ['==', ['get', 'approx'], true];

export const HOUSE_PAINT = {
  // Size also encodes status (shortlisted and taken larger, rejected and not chosen smaller), so colour is not the only cue.
  'circle-radius': [
    'interpolate',
    ['linear'],
    ['zoom'],
    8,
    ['match', ['get', 'status'], 'SHORTLISTED', 7, 'TAKEN', 7, 'REJECTED', 4, 'NOT_CHOSEN', 5, 5],
    14,
    ['match', ['get', 'status'], 'SHORTLISTED', 11, 'TAKEN', 11, 'REJECTED', 6, 'NOT_CHOSEN', 7, 8],
    18,
    ['match', ['get', 'status'], 'SHORTLISTED', 15, 'TAKEN', 15, 'REJECTED', 9, 'NOT_CHOSEN', 10, 12],
  ],
  'circle-color': STATUS_COLOUR,
  // A hollow ring for an approximate spot: no fill, the stroke takes the status colour instead of white.
  'circle-stroke-color': ['case', IS_APPROX, STATUS_COLOUR, '#ffffff'],
  'circle-stroke-width': ['match', ['get', 'status'], 'SHORTLISTED', 3, 'TAKEN', 3, 2],
  'circle-opacity': ['case', IS_APPROX, 0, ['match', ['get', 'status'], 'REJECTED', 0.75, 'NOT_CHOSEN', 0.6, 1]],
} as const;

export interface HouseFeature {
  type: 'Feature';
  id: string;
  geometry: { type: 'Point'; coordinates: [number, number] };
  properties: { id: string; status: HouseDto['status']; label: string; approx: boolean };
}

/** One point feature per house; `approx` is what the paint above reads. */
export function houseFeatures(houses: readonly HouseDto[]): HouseFeature[] {
  return houses.map((h) => ({
    type: 'Feature',
    id: h.id,
    geometry: { type: 'Point', coordinates: [h.lon, h.lat] },
    properties: { id: h.id, status: h.status, label: h.label, approx: h.locationSource === 'APPROX' },
  }));
}
