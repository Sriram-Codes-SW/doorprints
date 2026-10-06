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
 * Which houses *Which house was this walk to?* offers (docs/11 5.27.6): the house nearest to where the walk stopped (within
 * 30 m, never one whose spot is only an area) and the houses within 150 m of any point of the walk, nearest first. Pure.
 */

import type { HouseDto } from '../../core/models';
import { haversineM, isValidLatLon } from '../../shared/trace-geo';

/** The website has no Hunt radius setting: the nearest house counts within 30 m of the stop (docs/11 5.27.6). */
export const NEAREST_HOUSE_M = 30;
/** Houses within this distance of any point of the walk are listed (HuntEngine.NEAREST_SHOWN_M). */
export const NEAR_HOUSES_M = 150;

export interface HouseChoice {
  readonly house: HouseDto;
  readonly distanceM: number;
}

export interface HouseChoices {
  /** The preselected house, or null. */
  readonly nearest: HouseChoice | null;
  /** The other houses within {@link NEAR_HOUSES_M} of the walk, by their smallest distance. */
  readonly near: HouseChoice[];
}

export function houseChoices(points: readonly { lat: number; lon: number }[], houses: readonly HouseDto[]): HouseChoices {
  if (points.length === 0) return { nearest: null, near: [] };
  const last = points[points.length - 1];
  let nearest: HouseChoice | null = null;
  const near: HouseChoice[] = [];
  const located = houses.filter((h) => isValidLatLon(h.lat, h.lon));
  for (const house of located) {
    if (house.locationSource === 'APPROX') continue;
    const d = haversineM(last.lat, last.lon, house.lat, house.lon);
    if (d <= NEAREST_HOUSE_M && (nearest === null || d < nearest.distanceM)) nearest = { house, distanceM: d };
  }
  for (const house of located) {
    if (nearest !== null && house.id === nearest.house.id) continue;
    let smallest = Infinity;
    for (const p of points) smallest = Math.min(smallest, haversineM(p.lat, p.lon, house.lat, house.lon));
    if (smallest <= NEAR_HOUSES_M) near.push({ house, distanceM: smallest });
  }
  near.sort((a, b) => a.distanceM - b.distanceM);
  return { nearest, near };
}
