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
 * How a walk looks on the map (docs/11 5.27.4, docs/03 section 6.2b row 6, S4b-FR-15): the ids, the colours, the widths and
 * the GeoJSON, as plain data. No Angular, no MapLibre: `TraceLayers` (pages/map/trace-layers.ts) puts this on a map. The
 * boundary layers of India (ADR-22) are never named here.
 */

import type { RepeatLook } from '../data/trace-store';
import type { TracePoint, TraceWalk } from './trace-geo';
import { pieces } from './trace-repeats';
import type { WalkRepeats } from './trace-repeats';

export const TRACK_SOURCE = 'track';
export const TRACK_LAYER = 'track-line';
export const TRACK_REPEAT_LAYER = 'track-repeat-line';
export const TRACK_CHECK_SOURCE = 'track-check';
export const TRACK_CHECK_HALO_LAYER = 'track-check-halo';
export const TRACK_CHECK_LINE_LAYER = 'track-check-line';

/** The base line: purple, as on the phones. */
export const TRACK_COLOR = '#8E24AA';
/** The second colour: a deep orange, the same on the light map in both themes (5.27.4). */
export const TRACK_REPEAT_COLOR = '#E65100';
/** Dash of three widths, gap of two: the cue that does not depend on colour. */
export const TRACK_REPEAT_DASH: readonly number[] = [3, 2];
/** The base widths by zoom (px), linear between. */
export const TRACK_WIDTHS: readonly (readonly [number, number])[] = [
  [10, 1.5],
  [14, 3],
  [18, 5],
];
export const TRACK_REPEAT_FACTOR_CLEAR = 1.8;
export const TRACK_REPEAT_FACTOR_SUBTLE = 1;

export interface LineFeature {
  readonly type: 'Feature';
  readonly properties: Record<string, string>;
  readonly geometry: { readonly type: 'LineString'; readonly coordinates: [number, number][] };
}
export interface LineCollection {
  readonly type: 'FeatureCollection';
  readonly features: LineFeature[];
}

/** A line layer as MapLibre's `addLayer` takes it. */
export interface LineLayerJson {
  readonly id: string;
  readonly type: 'line';
  readonly source: string;
  readonly filter?: unknown[];
  readonly layout: Record<string, unknown>;
  readonly paint: Record<string, unknown>;
}

/** The overlay's factor over the base width: 1.8 (Clear), 1.0 (Subtle), none for Off (no overlay). */
export function repeatFactor(look: RepeatLook): number | null {
  return look === 'CLEAR' ? TRACK_REPEAT_FACTOR_CLEAR : look === 'SUBTLE' ? TRACK_REPEAT_FACTOR_SUBTLE : null;
}

const round = (n: number) => Math.round(n * 100) / 100;

function widthStops(factor: number): unknown[] {
  return ['interpolate', ['linear'], ['zoom'], ...TRACK_WIDTHS.flatMap(([zoom, width]) => [zoom, round(width * factor)])];
}

/** The overlay's `line-width` by zoom. Off keeps the Subtle widths (the layer is hidden, so they are never seen). */
export function repeatWidthExpression(look: RepeatLook): unknown[] {
  return widthStops(repeatFactor(look) ?? TRACK_REPEAT_FACTOR_SUBTLE);
}

export function repeatVisibility(look: RepeatLook): 'visible' | 'none' {
  return look === 'OFF' ? 'none' : 'visible';
}

export function trackLayerJson(): LineLayerJson {
  return {
    id: TRACK_LAYER,
    type: 'line',
    source: TRACK_SOURCE,
    filter: ['==', ['get', 'kind'], 'base'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': TRACK_COLOR, 'line-opacity': 0.85, 'line-width': widthStops(1) },
  };
}

export function trackRepeatLayerJson(look: RepeatLook): LineLayerJson {
  return {
    id: TRACK_REPEAT_LAYER,
    type: 'line',
    source: TRACK_SOURCE,
    filter: ['==', ['get', 'kind'], 'repeat'],
    layout: { 'line-cap': 'butt', 'line-join': 'round', visibility: repeatVisibility(look) },
    paint: {
      'line-color': TRACK_REPEAT_COLOR,
      // Fades in from zoom 10.5: at zoom 10 a dash of 1.5 px reads as dots.
      'line-opacity': ['interpolate', ['linear'], ['zoom'], 10.5, 0, 11, 0.95],
      'line-width': repeatWidthExpression(look),
      'line-dasharray': [...TRACK_REPEAT_DASH],
    },
  };
}

/** The halo of the place check (a wide white casing) and the stretch over it: a form, not a colour (docs/11 5.27.13). */
export function checkLayersJson(): [LineLayerJson, LineLayerJson] {
  const wide = ['interpolate', ['linear'], ['zoom'], 10, 7, 14, 12, 18, 18];
  const stretch = ['interpolate', ['linear'], ['zoom'], 10, 3, 14, 5, 18, 7];
  return [
    {
      id: TRACK_CHECK_HALO_LAYER,
      type: 'line',
      source: TRACK_CHECK_SOURCE,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#FFFFFF', 'line-opacity': 0.95, 'line-width': wide },
    },
    {
      id: TRACK_CHECK_LINE_LAYER,
      type: 'line',
      source: TRACK_CHECK_SOURCE,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#4A148C', 'line-width': stretch },
    },
  ];
}

/** A walk's points as `[lon, lat]` lines, cut at every resumed point (no line across a hidden pause); a piece of one point is dropped. */
export function baseLines(points: readonly TracePoint[]): [number, number][][] {
  const lines: [number, number][][] = [];
  let current: [number, number][] = [];
  for (const p of points) {
    if (p.resumed === true && current.length > 0) {
      lines.push(current);
      current = [];
    }
    current.push([p.lon, p.lat]);
  }
  lines.push(current);
  return lines.filter((l) => l.length >= 2);
}

const line = (coordinates: [number, number][], properties: Record<string, string>): LineFeature => ({
  type: 'Feature',
  properties,
  geometry: { type: 'LineString', coordinates },
});

/**
 * Every walk whole (`kind` base) and every shown stretch of it (`kind` repeat). `repeats[i]` belongs to `walks[i]`; a walk
 * without an entry (left out of the detection) is drawn whole with no overlay.
 */
export function trackGeoJson(walks: readonly TraceWalk[], repeats: readonly WalkRepeats[]): LineCollection {
  const features: LineFeature[] = [];
  walks.forEach((w, i) => {
    for (const coordinates of baseLines(w.points)) features.push(line(coordinates, { kind: 'base', key: w.key }));
    const shown = repeats[i]?.shown ?? [];
    if (shown.length > 0) for (const coordinates of pieces(w, shown)) features.push(line(coordinates, { kind: 'repeat', key: w.key }));
  });
  return { type: 'FeatureCollection', features };
}

/** The matched stretches of the place check as the `track-check` source's data. */
export function checkGeoJson(stretches: readonly (readonly [number, number][])[]): LineCollection {
  return {
    type: 'FeatureCollection',
    features: stretches.filter((s) => s.length >= 2).map((s) => line([...s], { kind: 'check' })),
  };
}
