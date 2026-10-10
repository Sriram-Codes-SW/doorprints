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

// The walking-route maths and the visit-plan fallback's candidates (split from ai-core.ts to keep it under its size budget).

import { inTheRunning } from '../../shared/house-status';

export interface RoutePoint { id: string; lat: number; lon: number }
export interface Leg { to: RoutePoint; meters: number; walkMinutes: number }

const EARTH_RADIUS_M = 6_371_008.8;
const rad = (d: number) => (d * Math.PI) / 180;

/** The great-circle distance between two points in metres. */
export function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = rad(lat2 - lat1);
  const dLon = rad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Whole minutes to walk `meters`: a 1.3 detour factor over the straight line at 80 m a minute, rounded up. */
export function walkMinutes(meters: number): number {
  return meters <= 0 ? 0 : Math.ceil((meters * 1.3) / 80);
}

/** Java's Math.round (half up); JavaScript's Math.round is half up for positives too, so this is it. */
export const roundHalfUp = (v: number) => Math.floor(v + 0.5);

/**
 * Orders `stops` by going to the nearest unvisited one each time, starting at the given point. Good enough for a walk
 * between a few houses and the same rule as the server's RouteOptimizer.
 */
export function nearestNeighbour(lat: number, lon: number, stops: RoutePoint[]): Leg[] {
  const remaining = [...stops];
  const legs: Leg[] = [];
  while (remaining.length) {
    let bestIdx = 0;
    let best = Number.MAX_VALUE;
    remaining.forEach((p, i) => {
      const d = haversineMeters(lat, lon, p.lat, p.lon);
      if (d < best) { best = d; bestIdx = i; }
    });
    const next = remaining.splice(bestIdx, 1)[0];
    legs.push({ to: next, meters: best, walkMinutes: walkMinutes(best) });
    lat = next.lat;
    lon = next.lon;
  }
  return legs;
}

/** The legs from the start point through `stops` in the order given. */
export function legsInOrder(lat: number, lon: number, stops: RoutePoint[]): Leg[] {
  return stops.map((p) => {
    const d = haversineMeters(lat, lon, p.lat, p.lon);
    lat = p.lat;
    lon = p.lon;
    return { to: p, meters: d, walkMinutes: walkMinutes(d) };
  });
}

/**
 * A house offered to the model for a visit plan: the facts it needs and its distance from the start point. Never the
 * contact.
 */
export interface PlanCandidate {
  id: string; label: string; locality: string | null; street: string | null; status: string | null;
  price: number | null; priceType: string | null; bedrooms: number | null; rating: number | null;
  lat: number; lon: number; distanceMeters: number;
}

export interface AgentPlan { summary?: string | null; stops?: { houseId?: string | null; reason?: string | null }[] | null }

export const FALLBACK_REASON = 'Found by the search; ordered by walking distance';
/** How far from the start point a house may be for the fallback route to offer it, as the server's `FALLBACK_MAX_METERS`: a house hunt is one city. */
export const FALLBACK_MAX_METERS = 50_000;
/** The summary of a fallback when no candidate is within {@link FALLBACK_MAX_METERS} of the start. */
export const FALLBACK_NONE_IN_REACH = 'No saved houses within reach of your start point were found.';
export const FALLBACK_SUMMARY = 'The assistant could not finish a plan, so these are the houses it found, ordered by nearest neighbour from your start point.';

/**
 * The fallback's candidates, as the server's `fallbackPoints`: the houses in the running with usable coordinates within
 * {@link FALLBACK_MAX_METERS} of the start, nearest to the start first (equal distances by house id), at most `maxStops`.
 */
export function fallbackPoints(seen: Map<string, PlanCandidate>, lat: number, lon: number, maxStops: number): RoutePoint[] {
  return [...seen.values()]
    .filter((h) => inTheRunning(h.status))
    .map((h) => ({ h, meters: haversineMeters(lat, lon, h.lat, h.lon) }))
    // A NaN distance (an unusable coordinate) is not within reach.
    .filter((n) => n.meters <= FALLBACK_MAX_METERS)
    .sort((x, y) => x.meters - y.meters || (x.h.id.toLowerCase() < y.h.id.toLowerCase() ? -1 : x.h.id.toLowerCase() > y.h.id.toLowerCase() ? 1 : 0))
    .slice(0, maxStops)
    .map((n) => ({ id: n.h.id, lat: n.h.lat, lon: n.h.lon }));
}
