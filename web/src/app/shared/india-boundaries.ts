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

import type {
  ExpressionSpecification,
  FilterSpecification,
  GeoJSONSourceSpecification,
  LayerSpecification,
  LineLayerSpecification,
  SourceSpecification,
  StyleSpecification,
} from 'maplibre-gl';
// The held areas' polygon (S4b-BL-12), bundled into the app as text: the rule needs it when the style loads, before
// any fetch could answer. Byte-identical to Android's asset (IndiaBoundaryDataTest; the spec pins the same sha256).
import heldAreasText from '../../../public/geo/in-held-areas.geojson' with { loader: 'text' };
// The corridor around the Survey of India's lines (S4b-BL-99), bundled the same way and for the same reason: rule 2
// filters boundary_2 with it as the style loads. Byte-identical to Android's asset (the spec and IndiaBoundaryDataTest).
import soiCorridorText from '../../../public/geo/in-soi-corridor.geojson' with { loader: 'text' };

/**
 * India's external boundary as the Government of India shows it, on top of the OpenFreeMap "liberty" style.
 *
 * Every user of this app is in India, so this is the only view (owner, P0 of 2026-09-24). All of Jammu and Kashmir
 * and Ladakh (Pakistan-occupied Kashmir, Gilgit-Baltistan, Shaksgam and Aksai Chin included) and Arunachal Pradesh
 * are drawn inside India, with one solid outline and no Line of Control, Line of Actual Control or other de facto or
 * claim line. Liberty, as served, draws the ISO view. The Android app applies the same five rules to the same style
 * and the same data file, so both apps draw the same map:
 *
 *  1. layer `boundary_disputed` (every disputed line: the LoC, the LAC, claim lines) is hidden;
 *  2. layer `boundary_2` (country lines) starts at zoom 5, where the tiles carry `adm0_l`/`adm0_r`, keeps only the
 *     lines with at least one of the two, and leaves out the Pakistan-China line (both sides in PAK/CHN), India's line
 *     with China (China on one side, India or nothing on the other) and every one of India's lines (India or nothing
 *     on a side, or the Pakistan-Afghanistan line of the Wakhan) that lies wholly inside the corridor around the
 *     Survey of India's lines ({@link soiCorridorRule}, `geo/in-soi-corridor.geojson`, S4b-BL-99): the Survey of India
 *     line draws all of them instead. Below zoom
 *     5 the tiles' lines come from Natural Earth's ISO view with no country codes, so no filter can take the Pakistan
 *     line through Kashmir out of them. MapLibre draws a zoom 0-4 tile, overzoomed, in place of a zoom 5+ tile that is
 *     still loading or missing offline, so `boundary_2`, `boundary_3` and every other `boundary` line layer that
 *     starts at zoom 5 also take only the features of a zoom 5+ tile ({@link TILE_ZOOM_GUARD}): no zoom 0-4 line of
 *     any admin level is drawn through Jammu and Kashmir, Ladakh, Aksai Chin or Arunachal Pradesh at zoom 5 and above.
 *     Both renderers already leave a layer out of a tile below floor(minzoom), maplibre-gl on the web and
 *     maplibre-native on Android, so the guard is defence in depth on both apps ({@link takesTileZoomGuard}).
 *     And `boundary_3` leaves out every tile feature that lies wholly inside the polygon around the parts of India
 *     that Pakistan and China hold ({@link heldAreasRule}, `geo/in-held-areas.geojson`): from tile zoom 9 the tiles
 *     carry Pakistan's district and tehsil lines across Gilgit-Baltistan and PoK and China's county lines across
 *     Aksai Chin as undisputed admin level 5-6 lines with no country code, so only where they lie tells them apart;
 *  3. two GeoJSON sources with three line layers. `in-boundaries-soi` is the Survey of India's data
 *     (`geo/in-boundaries-soi.json`, OVSF/1M/7, vertices unaltered, decoded here from its polyline7 encoding,
 *     {@link soiBoundaryGeoJson}; credited "Boundary: Survey of India" in the map's attribution): `in-boundary-claim`
 *     (kind `claim`, India's international land boundary along Jammu and Kashmir, Ladakh, Himachal Pradesh,
 *     Uttarakhand, Sikkim and Arunachal Pradesh, at every zoom) and `in-boundary-state` (kind `state`, the
 *     Assam-Arunachal Pradesh state line, which the tiles mark disputed and claimed by China, so no layer draws it;
 *     from zoom 5, directly above `boundary_3`, with its dashed paint, {@link STATE_FALLBACK_LINE_PAINT} where it has
 *     none). `in-boundaries` is Natural Earth (`geo/in-boundaries.geojson`, public domain): `in-boundary-world` (kind
 *     `world`, below zoom 5 only, in place of the tiles' lines: the world's land boundaries with India's
 *     classification, without the stretches the Survey of India lines draw). `in-boundary-world` and
 *     `in-boundary-claim` go directly above `boundary_2` and copy its colour, width and opacity so they look like the
 *     base map's own lines ({@link FALLBACK_LINE_PAINT} where it has none). MapLibre generalises a GeoJSON line for
 *     the screen as it cuts it into tiles (geojson-vt keeps a subset of the vertices, whole, per zoom); the data it
 *     is given holds every Survey of India vertex as published;
 *  4. the state labels "Azad Kashmir" and "Gilgit-Baltistan" (English or Urdu name), which sit inside India's
 *     territory, are filtered out of every `place` label layer that can show a state;
 *  5. a layer that is missing is skipped with a warning, never an error, and the overlay is still added. A layer whose
 *     own filter is in the deprecated syntax gets rule 2 in that syntax, except the tile-zoom guard, which that syntax
 *     cannot express: that layer is left as it is, with a warning.
 *
 * {@link indiaBoundaryStyle} is the pure rule set (a style in, a new style out) and {@link applyIndiaBoundaries}
 * applies it to a live map on each `style.load` (see `createMlMap` in `map-style.ts`). The data file and its build
 * script (`web/scripts/geo/`) are the lead's; this file only decides how the map uses them.
 */

/** Id of the GeoJSON source of the Natural Earth lines (kind `world`). */
export const IN_BOUNDARIES_SOURCE = 'in-boundaries';
/** Id of the GeoJSON source of the Survey of India's lines (kinds `claim` and `state`). */
export const IN_BOUNDARIES_SOI_SOURCE = 'in-boundaries-soi';
/** The world's land boundaries with India's classification, used below zoom 5 (kind `world`, Natural Earth). */
export const IN_BOUNDARY_WORLD_LAYER = 'in-boundary-world';
/** India's international land boundary by the Survey of India, used at every zoom (kind `claim`). */
export const IN_BOUNDARY_CLAIM_LAYER = 'in-boundary-claim';
/** India's state lines that the tiles leave undrawn (kind `state`: Assam-Arunachal Pradesh, Survey of India), from zoom 5. */
export const IN_BOUNDARY_STATE_LAYER = 'in-boundary-state';
/** The bundled data, relative to the app's base href (web/public/geo/, precached by sw.js with the rest of the build). */
export const IN_BOUNDARIES_PATH = 'geo/in-boundaries.geojson';
/** The Survey of India's lines, likewise (fetched and decoded on the first style load, {@link loadSoiBoundary}). */
export const IN_BOUNDARIES_SOI_PATH = 'geo/in-boundaries-soi.json';
/** Credit shown in the map's attribution while the overlay is drawn. Natural Earth is public domain; credit is courtesy. */
export const IN_BOUNDARIES_ATTRIBUTION = 'Natural Earth';
/**
 * The Survey of India's credit on the map (its condition of use, docs/ops/soi-review-pack.md section 7), in English;
 * the app passes the translated `map.boundaryCredit` ({@link applyIndiaBoundaries}).
 */
export const SOI_ATTRIBUTION = 'Boundary: Survey of India';

/** Liberty's country lines. */
export const COUNTRY_LINES_LAYER = 'boundary_2';
/** Liberty's state lines (admin levels 3 to 6, dashed, minzoom 5). */
export const STATE_LINES_LAYER = 'boundary_3';
/** Liberty's disputed lines (LoC, LAC, claim lines). */
export const DISPUTED_LINES_LAYER = 'boundary_disputed';
/** From this zoom the tiles' country lines carry `adm0_l`/`adm0_r` and are used; below it, the bundled `world` lines. */
export const TILE_BOUNDARY_MIN_ZOOM = 5;

/**
 * Paint for the overlay when `boundary_2` is missing or does not set a property: Liberty's own `boundary_2` colour, its
 * width at zoom 5 and full opacity. The same values as Android's `IndiaViewRules.FALLBACK_LINE_*`.
 */
export const FALLBACK_LINE_PAINT: Readonly<Record<string, unknown>> = Object.freeze({
  'line-color': 'hsl(248,1%,41%)',
  'line-width': 1.2,
  'line-opacity': 1,
});

/**
 * Paint for the state-line overlay when `boundary_3` is missing or does not set a property: Liberty's own `boundary_3`
 * colour and dashes, and its width at zoom 7. The same values as Android's `IndiaViewRules.STATE_FALLBACK_LINE_*`.
 */
export const STATE_FALLBACK_LINE_PAINT: Readonly<Record<string, unknown>> = Object.freeze({
  'line-color': 'hsl(0,0%,70%)',
  'line-width': 1,
  'line-dasharray': [1, 1],
});
/** Paint properties the state-line overlay copies from `boundary_3` when it sets them. */
const STATE_LINE_PAINT_KEYS = ['line-color', 'line-width', 'line-dasharray', 'line-opacity'];

const BOUNDARY_SOURCE_LAYER = 'boundary';
const PLACE_SOURCE_LAYER = 'place';
/** MapLibre's highest zoom: the "no maxzoom" value when a zoom range has to be set as a pair. */
const MAX_ZOOM = 24;

/** ISO3 codes of the two sides of the only admin-2 line to drop from the tiles (Khunjerab, through Gilgit-Baltistan). */
const PAKISTAN_CHINA = ['PAK', 'CHN'];
/** English names of the state labels that must not appear inside India. */
export const HIDDEN_STATE_NAMES = ['Azad Kashmir', 'Azad Jammu and Kashmir', 'Gilgit-Baltistan'];
/** The same labels by their local (Urdu) name, which the tiles carry as `name`. */
export const HIDDEN_STATE_LOCAL_NAMES = ['آزاد کشمیر', 'گلگت بلتستان'];

/**
 * Rule 2: the line carries `adm0_l` or `adm0_r`. The Natural Earth lines of the zoom 0-4 tiles never do, so a zoom 4
 * tile shown in place of a loading zoom 5+ one draws no country line, even in the deprecated syntax, which has no
 * zoom. `["has", key]` means "the feature has this property" in both syntaxes (style spec 26.4.4
 * `src/feature_filter/index.ts`, `classifyFilter`: the two-element `has` is "neutral"), so the same clause serves both.
 */
const ADM0_PRESENT: ExpressionSpecification = ['any', ['has', 'adm0_l'], ['has', 'adm0_r']];

/**
 * Rule 2: India's line with China, as the tiles carry it: China on one side and India, or no country (the tiles often
 * leave India's side empty), on the other. The tiles cut that line into short undisputed pieces (drawn) and disputed
 * ones (hidden), so it would show as stray pieces beside India's outline; the outline draws all of it instead.
 */
const INDIA_CHINA_LINE: ExpressionSpecification = [
  'any',
  ['all', ['==', ['get', 'adm0_l'], 'CHN'], ['==', ['coalesce', ['get', 'adm0_r'], 'IND'], 'IND']],
  ['all', ['==', ['get', 'adm0_r'], 'CHN'], ['==', ['coalesce', ['get', 'adm0_l'], 'IND'], 'IND']],
];

/**
 * Rule 2, `boundary_2`: at least one adm0 side ({@link ADM0_PRESENT}), not a line whose both sides are Pakistan or
 * China, and not India's line with China ({@link INDIA_CHINA_LINE}). Android's
 * `IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER`, with `in` for `match` (in maplibre-gl a null side is simply not found in
 * the list, the same result as `match`'s `false` branch).
 */
const COUNTRY_LINE_RULE: ExpressionSpecification = [
  'all',
  ADM0_PRESENT,
  [
    '!',
    [
      'all',
      ['in', ['get', 'adm0_l'], ['literal', PAKISTAN_CHINA]],
      ['in', ['get', 'adm0_r'], ['literal', PAKISTAN_CHINA]],
    ],
  ],
  ['!', INDIA_CHINA_LINE],
];

/**
 * Rule 2, tile-zoom guard: only the features of a tile of zoom 5 or more. A filter's `zoom` is the zoom of the TILE
 * the feature comes from, not the map's: maplibre-gl 6.11.2 builds a line bucket with
 * `new EvaluationParameters(this.zoom)` and runs the layer filter with it (`src/data/bucket/line_bucket.ts:155-160`),
 * where `this.zoom` is the worker tile's (`src/source/worker_tile.ts:51,116`) and that is `tile.tileID.overscaledZ`
 * (`src/source/vector_tile_source.ts:211`). `zoom` is allowed anywhere in a filter: the filter is compiled with
 * `createExpression` and a spec whose parameters are `['zoom', 'feature']` (style spec 26.4.4
 * `src/feature_filter/index.ts:222-265`), not with `createPropertyExpression`, whose "zoom only as the input of a
 * top-level step or interpolate" rule (`src/expression/index.ts:517-527`) is for paint and layout properties, and
 * `validateFilter` forbids only `feature-state` in a filter (`src/validate/validate_expression.ts:56-63`). So a zoom 0-4
 * tile draws nothing through a guarded layer at any map zoom. Expression syntax only (Android's
 * `IndiaViewRules.TILE_ZOOM_GUARD`). Defence in depth on both renderers: maplibre-gl and maplibre-native already skip a
 * minzoom 5 layer in a zoom 0-4 tile (sources in {@link takesTileZoomGuard}); the guard keeps that true whatever a
 * renderer does with minzoom.
 */
export const TILE_ZOOM_GUARD: ExpressionSpecification = ['>=', ['zoom'], TILE_BOUNDARY_MIN_ZOOM];

/**
 * The polygon for the `within` expression, as a bare GeoJSON geometry. A Polygon only, on both apps: maplibre-native
 * reads only the first polygon feature of a collection (within.cpp `Within::parse`), and MapLibre Android's
 * `Expression.raw` turns `within`'s argument into a Polygon (`Expression.Converter.convert`, `Polygon.fromJson`).
 */
export interface HeldAreasGeometry {
  type: 'Polygon';
  coordinates: number[][][];
}

/**
 * The polygon of `geo/in-held-areas.geojson` (web/scripts/geo/build_in_held_areas.py): a FeatureCollection with one
 * feature whose geometry is a Polygon of closed [longitude, latitude] rings. Null (and the rule is skipped with a
 * warning) when the text is anything else, so a broken file can never break the style. Android's
 * `IndiaViewRules.heldAreasGeometry`.
 */
export function heldAreasGeometry(text: string): HeldAreasGeometry | null {
  const features = featuresOf(text);
  if (!features || features.length !== 1) return null;
  return polygonOf(features[0]);
}

/** The features of a GeoJSON FeatureCollection's text, or null when it is not one. */
function featuresOf(text: string): unknown[] | null {
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    return null;
  }
  const features = (root as { type?: unknown; features?: unknown } | null)?.features;
  if ((root as { type?: unknown } | null)?.type !== 'FeatureCollection' || !Array.isArray(features)) return null;
  return features;
}

/** A feature's geometry when it is a Polygon of closed rings of finite [longitude, latitude] pairs, else null. */
function polygonOf(feature: unknown): HeldAreasGeometry | null {
  const geometry = (feature as { geometry?: { type?: unknown; coordinates?: unknown } } | null)?.geometry;
  const isPosition = (p: unknown) => Array.isArray(p) && p.length === 2 && p.every((v) => typeof v === 'number' && Number.isFinite(v));
  const isRing = (ring: unknown) =>
    Array.isArray(ring) &&
    ring.length >= 4 &&
    ring.every(isPosition) &&
    ring[0][0] === ring[ring.length - 1][0] &&
    ring[0][1] === ring[ring.length - 1][1];
  const rings = geometry?.coordinates;
  if (geometry?.type !== 'Polygon' || !Array.isArray(rings) || rings.length === 0 || !rings.every(isRing)) return null;
  return { type: 'Polygon', coordinates: rings as number[][][] };
}

/** The bundled polygon, read once; null when the bundled file is not what {@link heldAreasGeometry} expects. */
export const HELD_AREAS: HeldAreasGeometry | null = heldAreasGeometry(heldAreasText);

/**
 * Rule 2, `boundary_3` (S4b-BL-12): not a tile feature that lies wholly inside the held areas' polygon. `within` on a
 * line feature is true only when every part of it is inside, so a feature that crosses the polygon's edge is drawn
 * whole: the polygon reaches about 20 km past the held areas (further where the zoom 9-11 tiles' pieces run on) and
 * about 700 m across the tiles' LoC/LAC, so a Pakistani or Chinese line ending on the LoC/LAC is inside it and an
 * Indian line is not. Same algorithm on both renderers: style spec 26.4.4 `src/expression/definitions/within.ts`
 * (`linesWithinPolygons`: the feature's bbox strictly inside the polygon's, then `lineStringWithinPolygon` in
 * `src/util/geometry_util.ts`: every vertex strictly inside, no segment crossing an edge) and maplibre-native
 * android-v13.6.1 `src/mln/style/expression/within.cpp` (`featureWithinPolygons`, lines 147-177) with
 * `src/mln/util/geometry_util.cpp` (`lineStringWithinPolygon`, lines 116-133); both run on the tile's own geometry
 * (buffer included) in that tile's coordinates. A feature with no geometry is not within (both), so it is drawn.
 * Expression syntax only (Android's `IndiaViewRules.heldAreasFilter`).
 */
export function heldAreasRule(geometry: HeldAreasGeometry): ExpressionSpecification {
  return ['!', ['within', geometry]];
}

/**
 * The polygons of `geo/in-soi-corridor.geojson` (web/scripts/geo/build_in_soi_corridor.py, S4b-BL-99): a
 * FeatureCollection of one or more features, each a Polygon of closed [longitude, latitude] rings, one per chain of the
 * Survey of India's land-boundary lines (Jammu and Kashmir to Uttarakhand, Sikkim, Arunachal Pradesh), 5.5 km wide on
 * each side. Null (and the rule is skipped with a warning) when the text is anything else. Polygons, not one
 * MultiPolygon, for Android (see {@link HeldAreasGeometry}). Android's `IndiaViewRules.soiCorridorGeometries`.
 */
export function soiCorridorGeometries(text: string): HeldAreasGeometry[] | null {
  const features = featuresOf(text);
  if (!features || features.length === 0) return null;
  const polygons = features.map(polygonOf);
  return polygons.every((p) => p !== null) ? (polygons as HeldAreasGeometry[]) : null;
}

/** The bundled corridor, read once; null when the bundled file is not what {@link soiCorridorGeometries} expects. */
export const SOI_CORRIDOR: HeldAreasGeometry[] | null = soiCorridorGeometries(soiCorridorText);

/**
 * Rule 2, `boundary_2` (S4b-BL-99): India's lines as the tiles carry them, in India's view: India or no country on a
 * side (the tiles often leave India's side empty), or Pakistan and Afghanistan, whose Wakhan line is Gilgit-Baltistan's
 * border. Android's `IndiaViewRules.INDIA_LINE`, with `in` for `match`.
 */
const INDIA_LINE: ExpressionSpecification = [
  'any',
  ['==', ['coalesce', ['get', 'adm0_l'], 'IND'], 'IND'],
  ['==', ['coalesce', ['get', 'adm0_r'], 'IND'], 'IND'],
  ['all', ['in', ['get', 'adm0_l'], ['literal', ['PAK', 'AFG']]], ['in', ['get', 'adm0_r'], ['literal', ['PAK', 'AFG']]]],
];

/**
 * Rule 2, `boundary_2` (S4b-BL-99): not one of India's lines ({@link INDIA_LINE}) that lies wholly inside the corridor
 * around the Survey of India's lines, which draw that boundary instead, so from zoom 5 it is one line, not the Survey
 * of India's and OpenStreetMap's side by side (they lie a median 20-30 m apart along Nepal and Bhutan, about 500 m in
 * the Wakhan). `within` is all or nothing per tile feature ({@link heldAreasRule}): a tile feature that runs on past
 * the end of a Survey of India line (the Nepal line along Uttar Pradesh, the Bhutan line along Assam) is drawn whole,
 * so in the tiles that hold such an end both lines show for up to a tile's width (docs/06 TC-M-25). A line of another
 * country that meets India's (Nepal-China, Bhutan-China) is never hidden. Expression syntax only (Android's
 * `IndiaViewRules.soiCorridorFilter`).
 */
export function soiCorridorRule(polygons: readonly HeldAreasGeometry[]): ExpressionSpecification {
  return ['!', ['all', INDIA_LINE, ['any', ...polygons.map((p): ExpressionSpecification => ['within', p])]]];
}

/** A GeoJSON FeatureCollection of the Survey of India's lines as the map draws them ({@link soiBoundaryGeoJson}). */
export interface SoiFeatureCollection {
  type: 'FeatureCollection';
  features: {
    type: 'Feature';
    properties: { kind: 'claim' | 'state'; state: string };
    geometry: { type: 'LineString'; coordinates: [number, number][] };
  }[];
}

/** No Survey of India line yet: the source's data until {@link loadSoiBoundary} has read the file. */
export const NO_SOI_LINES: SoiFeatureCollection = Object.freeze({ type: 'FeatureCollection', features: [] }) as SoiFeatureCollection;

/**
 * Google's encoded polyline algorithm, decoded at a precision of 1e-7 degree (`in-boundaries-soi.json`, written by
 * web/scripts/geo/build_in_boundaries_soi.py): latitude then longitude, each a zigzag-encoded delta in 5-bit chunks
 * offset by 63. Gives [longitude, latitude] pairs, each the 1e-7 integer divided by 1e7, which is the double nearest
 * to the 7-decimal value the build wrote, so every vertex is the Survey of India's as published. Arithmetic, not
 * 32-bit bitwise operators: a longitude delta can reach 3.6e9. Throws on a character outside the alphabet or a
 * truncated value. Android's `SoiPolyline.decode` (common Kotlin); the parity test pins both to the same counts and
 * checksums.
 */
export function decodePolyline7(encoded: string): [number, number][] {
  const out: [number, number][] = [];
  let i = 0;
  let lat = 0;
  let lon = 0;
  const next = (): number => {
    let result = 0;
    let factor = 1;
    for (;;) {
      if (i >= encoded.length) throw new Error('polyline7: truncated value');
      const b = encoded.charCodeAt(i++) - 63;
      if (b < 0 || b > 63) throw new Error(`polyline7: character ${JSON.stringify(encoded[i - 1])} out of range`);
      result += (b & 0x1f) * factor;
      factor *= 32;
      if (b < 0x20) break;
      if (factor > 2 ** 45) throw new Error('polyline7: value too long');
    }
    return result % 2 === 1 ? -(result + 1) / 2 : result / 2;
  };
  while (i < encoded.length) {
    lat += next();
    lon += next();
    out.push([lon / 1e7, lat / 1e7]);
  }
  return out;
}

/**
 * The Survey of India's file (`geo/in-boundaries-soi.json`) as the map's GeoJSON: one LineString per run, with its
 * kind (`claim` or `state`) and state, the vertices decoded ({@link decodePolyline7}) and none added, moved or
 * removed. Null when the text is not that file (not JSON, a run of another kind, a run whose decoded vertex count
 * differs from the `vertices` it states, or one of fewer than two), so a broken or partial file never reaches the
 * map. Android's `SoiPolyline.geoJson`.
 */
export function soiBoundaryGeoJson(text: string): SoiFeatureCollection | null {
  const features = featuresOf(text);
  if (!features || features.length === 0) return null;
  const out: SoiFeatureCollection['features'] = [];
  for (const f of features) {
    const p = (f as { properties?: Record<string, unknown> } | null)?.properties;
    const kind = p?.['kind'];
    const encoded = p?.['polyline7'];
    if ((kind !== 'claim' && kind !== 'state') || typeof encoded !== 'string') return null;
    let coordinates: [number, number][];
    try {
      coordinates = decodePolyline7(encoded);
    } catch {
      return null;
    }
    if (coordinates.length < 2 || coordinates.length !== p?.['vertices']) return null;
    out.push({
      type: 'Feature',
      properties: { kind, state: typeof p?.['state'] === 'string' ? p['state'] : '' },
      geometry: { type: 'LineString', coordinates },
    });
  }
  return { type: 'FeatureCollection', features: out };
}

/**
 * Adler-style sums (mod 2^31 - 1) over the vertices of `lines` as 1e-7 degree integers, longitude then latitude, in
 * order: [s1, s2]. The parity check of the decoders (web/scripts/geo/test_build_in_boundaries.py and Android's
 * `SoiPolyline.checksum` compute the same); both stay below 2^32, so plain numbers are exact.
 */
export function soiChecksum(lines: readonly (readonly (readonly number[])[])[]): [number, number] {
  const m = 2147483647;
  let s1 = 0;
  let s2 = 0;
  for (const line of lines) {
    for (const [lon, lat] of line) {
      for (const v of [Math.round(lon * 1e7), Math.round(lat * 1e7)]) {
        s1 = (s1 + (((v % m) + m) % m)) % m;
        s2 = (s2 + s1) % m;
      }
    }
  }
  return [s1, s2];
}

const soiPromises = new Map<string, Promise<SoiFeatureCollection | null>>();
const soiLoaded = new Map<string, SoiFeatureCollection>();

/**
 * The Survey of India's lines from `url`, fetched and decoded once per page ({@link soiBoundaryGeoJson}); null when
 * the file cannot be read or is not that file (then the next call tries again). Same-origin and precached by sw.js,
 * so it also loads offline once the app has been installed or opened.
 */
export function loadSoiBoundary(
  url: string,
  fetchFn: (url: string) => Promise<{ ok: boolean; text(): Promise<string> }> = (u) => fetch(u),
): Promise<SoiFeatureCollection | null> {
  let pending = soiPromises.get(url);
  if (!pending) {
    pending = fetchFn(url)
      .then((r) => (r.ok ? r.text() : null))
      .then((text) => (text === null ? null : soiBoundaryGeoJson(text)))
      .catch(() => null)
      .then((fc) => {
        if (fc) soiLoaded.set(url, fc);
        else soiPromises.delete(url);
        return fc;
      });
    soiPromises.set(url, pending);
  }
  return pending;
}

/** The lines {@link loadSoiBoundary} has already read from `url`, or undefined. */
export function loadedSoiBoundary(url: string): SoiFeatureCollection | undefined {
  return soiLoaded.get(url);
}

/** Forgets what {@link loadSoiBoundary} has read (for the specs). */
export function forgetSoiBoundary(): void {
  soiPromises.clear();
  soiLoaded.clear();
}

/** Rule 4: neither the English (or default) name nor the local name is one of the hidden state labels. */
const NOT_HIDDEN_STATE: ExpressionSpecification[] = [
  ['!', ['in', ['coalesce', ['get', 'name:en'], ['get', 'name']], ['literal', HIDDEN_STATE_NAMES]]],
  ['!', ['in', ['get', 'name'], ['literal', HIDDEN_STATE_LOCAL_NAMES]]],
];

/**
 * The same two rules in the deprecated filter syntax, for a layer whose own filter is written in it: MapLibre refuses
 * a filter that mixes the two syntaxes. Liberty uses expressions throughout, so this is only a safety net if the
 * style changes. (`!in` on the English and the local name hides a little more than the `coalesce` form: also a
 * feature whose English name is something else but whose local name is one of these. Both are the same labels.)
 */
const COUNTRY_LINE_RULE_LEGACY: unknown[] = [
  'all',
  ADM0_PRESENT,
  [
    'none',
    ['all', ['in', 'adm0_l', ...PAKISTAN_CHINA], ['in', 'adm0_r', ...PAKISTAN_CHINA]],
    ['all', ['==', 'adm0_l', 'CHN'], ['any', ['!has', 'adm0_r'], ['==', 'adm0_r', 'IND']]],
    ['all', ['==', 'adm0_r', 'CHN'], ['any', ['!has', 'adm0_l'], ['==', 'adm0_l', 'IND']]],
  ],
];
const NOT_HIDDEN_STATE_LEGACY: unknown[][] = [
  ['!in', 'name:en', ...HIDDEN_STATE_NAMES],
  ['!in', 'name', ...HIDDEN_STATE_NAMES, ...HIDDEN_STATE_LOCAL_NAMES],
];

/** What {@link indiaBoundaryStyle} returns: the new style, and one line per rule it had to skip. */
export interface IndiaBoundaryResult {
  style: StyleSpecification;
  warnings: string[];
}

/** The absolute URL of the bundled data for a document whose base URI is `baseUri` (works from any deep link). */
export function inBoundariesUrl(baseUri: string): string {
  return new URL(IN_BOUNDARIES_PATH, baseUri).href;
}

/** The absolute URL of the Survey of India's file, likewise. */
export function inBoundariesSoiUrl(baseUri: string): string {
  return new URL(IN_BOUNDARIES_SOI_PATH, baseUri).href;
}

/** What the Survey of India's source gets: its data (the decoded lines, or none yet) and its credit. */
export interface SoiOverlay {
  data: SoiFeatureCollection;
  attribution: string;
}

/**
 * `style` with the five rules above applied. Pure: `style` is not changed, and each changed layer is a new object.
 *
 * `dataUrl` is where MapLibre loads the Natural Earth GeoJSON from ({@link inBoundariesUrl} on the web); `heldAreas`
 * is the polygon for `boundary_3` ({@link HELD_AREAS} unless a test gives another); `soi` is the Survey of India's
 * source (no lines and the English credit unless the caller gives them, {@link applyIndiaBoundaries}); `corridor` the
 * polygons for `boundary_2` ({@link SOI_CORRIDOR}). A style that already has the `in-boundaries` source is returned
 * as it is, so applying the rules twice changes nothing.
 */
export function indiaBoundaryStyle(
  style: StyleSpecification,
  dataUrl: string,
  heldAreas: HeldAreasGeometry | null = HELD_AREAS,
  soi: SoiOverlay = { data: NO_SOI_LINES, attribution: SOI_ATTRIBUTION },
  corridor: readonly HeldAreasGeometry[] | null = SOI_CORRIDOR,
): IndiaBoundaryResult {
  const warnings: string[] = [];
  if (style.sources && IN_BOUNDARIES_SOURCE in style.sources) return { style, warnings };

  const layers = [...(style.layers ?? [])];
  const indexOf = (id: string) => layers.findIndex((layer) => layer.id === id);

  // 1. No disputed lines at all.
  const disputed = indexOf(DISPUTED_LINES_LAYER);
  if (disputed < 0) {
    warnings.push(`layer "${DISPUTED_LINES_LAYER}" not found; nothing to hide`);
  } else {
    const layer = layers[disputed];
    const layout = (read(layer, 'layout') as Record<string, unknown> | undefined) ?? {};
    layers[disputed] = patch(layer, { layout: { ...layout, visibility: 'none' } });
  }

  // 2. The tiles' country lines from zoom 5, only those with an adm0 side, without the Pakistan-China line.
  const country = indexOf(COUNTRY_LINES_LAYER);
  let paint: Record<string, unknown> = { ...FALLBACK_LINE_PAINT };
  if (country < 0) {
    warnings.push(`layer "${COUNTRY_LINES_LAYER}" not found; the overlay is placed by the fallback rule`);
  } else {
    const layer = layers[country];
    const minzoom = Math.max(numberOr(read(layer, 'minzoom'), 0), TILE_BOUNDARY_MIN_ZOOM);
    const filter = andFilter(read(layer, 'filter') as FilterSpecification | undefined, [COUNTRY_LINE_RULE], [
      COUNTRY_LINE_RULE_LEGACY,
    ]);
    layers[country] = patch(layer, { minzoom, filter });
    paint = copyPaint(read(layer, 'paint'));
  }

  // 2b. The country and state lines, and any other boundary line layer from zoom 5, take only the features of a zoom
  //     5+ tile, so a zoom 0-4 tile drawn in place of a loading or missing one adds no line of any admin level.
  let guarded = 0;
  for (let i = 0; i < layers.length; i++) {
    if (!takesTileZoomGuard(layers[i])) continue;
    guarded++;
    const layer = layers[i];
    const existing = read(layer, 'filter') as FilterSpecification | undefined;
    if (existing !== undefined && existing !== true && isLegacyFilter(existing)) {
      warnings.push(
        `layer "${layer.id}" has a filter in the deprecated syntax, which has no zoom; not guarded against zoom 0-4 tiles`,
      );
      continue;
    }
    const filter = existing === undefined || existing === true ? TILE_ZOOM_GUARD : ['all', existing, TILE_ZOOM_GUARD];
    layers[i] = patch(layer, { filter });
  }
  if (guarded === 0) {
    warnings.push(`no line layer on source-layer "${BOUNDARY_SOURCE_LAYER}" starts at zoom 5; no tile-zoom guard added`);
  }

  // 2c. No Pakistani or Chinese admin line inside India's outline: boundary_3 leaves out every tile feature wholly
  //     inside the held areas' polygon. Nothing to do without boundary_3 (no admin line is drawn at all).
  const stateLinesAt = indexOf(STATE_LINES_LAYER);
  if (stateLinesAt >= 0) {
    const layer = layers[stateLinesAt];
    const existing = read(layer, 'filter') as FilterSpecification | undefined;
    if (!heldAreas) {
      warnings.push(`the held areas' polygon is missing or malformed; "${STATE_LINES_LAYER}" keeps the admin lines inside them`);
    } else if (existing !== undefined && existing !== true && isLegacyFilter(existing)) {
      warnings.push(
        `layer "${STATE_LINES_LAYER}" has a filter in the deprecated syntax, which has no "within"; its admin lines inside the held areas are kept`,
      );
    } else {
      const rule = heldAreasRule(heldAreas);
      layers[stateLinesAt] = patch(layer, {
        filter: existing === undefined || existing === true ? rule : ['all', existing, rule],
      });
    }
  }

  // 2d. One line along the Survey of India's (S4b-BL-99): boundary_2 leaves out India's tile lines wholly inside the
  //     corridor around it. After the guard, so the filter reads all(all(all(Liberty's, rule 2), guard), corridor),
  //     as on Android and iOS.
  if (country >= 0) {
    const layer = layers[country];
    const existing = read(layer, 'filter') as FilterSpecification | undefined;
    if (!corridor) {
      warnings.push(`the Survey of India corridor is missing or malformed; "${COUNTRY_LINES_LAYER}" keeps its lines beside them`);
    } else if (existing !== undefined && existing !== true && isLegacyFilter(existing)) {
      warnings.push(
        `layer "${COUNTRY_LINES_LAYER}" has a filter in the deprecated syntax, which has no "within"; its lines beside the Survey of India's are kept`,
      );
    } else {
      const rule = soiCorridorRule(corridor);
      layers[country] = patch(layer, { filter: existing === undefined || existing === true ? rule : ['all', existing, rule] });
    }
  }

  // 4. No "Azad Kashmir" or "Gilgit-Baltistan" state label. (Before 3, so the indexes of 3 are final.)
  let labelLayers = 0;
  for (let i = 0; i < layers.length; i++) {
    if (!canShowState(layers[i])) continue;
    const layer = layers[i];
    const filter = andFilter(read(layer, 'filter') as FilterSpecification | undefined, NOT_HIDDEN_STATE, NOT_HIDDEN_STATE_LEGACY);
    layers[i] = patch(layer, { filter });
    labelLayers++;
  }
  if (labelLayers === 0) warnings.push(`no symbol layer on source-layer "${PLACE_SOURCE_LAYER}" shows states; no label hidden`);

  // 3. India's lines, directly above the country lines; India's state line directly above the state lines.
  const at = overlayIndex(layers, country);
  const ids = new Set(layers.map((layer) => layer.id));
  const free = (layer: LineLayerSpecification) => {
    if (!ids.has(layer.id)) return true;
    warnings.push(`layer id "${layer.id}" is already taken; that overlay layer is not added`);
    return false;
  };
  const overlay = [
    overlayLayer(IN_BOUNDARY_WORLD_LAYER, IN_BOUNDARIES_SOURCE, 'world', paint, undefined, TILE_BOUNDARY_MIN_ZOOM),
    overlayLayer(IN_BOUNDARY_CLAIM_LAYER, IN_BOUNDARIES_SOI_SOURCE, 'claim', paint),
  ].filter(free);
  layers.splice(at, 0, ...overlay);
  const stateLines = indexOf(STATE_LINES_LAYER);
  const statePaint = copyPaint(stateLines < 0 ? undefined : read(layers[stateLines], 'paint'), STATE_FALLBACK_LINE_PAINT, STATE_LINE_PAINT_KEYS);
  const state = overlayLayer(IN_BOUNDARY_STATE_LAYER, IN_BOUNDARIES_SOI_SOURCE, 'state', statePaint, TILE_BOUNDARY_MIN_ZOOM);
  if (free(state)) {
    // Without boundary_3, directly below the other overlay layers (or where they would have gone).
    const worldAt = overlay.some((l) => l.id === IN_BOUNDARY_WORLD_LAYER) ? indexOf(IN_BOUNDARY_WORLD_LAYER) : -1;
    layers.splice(stateLines >= 0 ? stateLines + 1 : worldAt >= 0 ? worldAt : at, 0, state);
  }

  const source: GeoJSONSourceSpecification = { type: 'geojson', data: dataUrl, attribution: IN_BOUNDARIES_ATTRIBUTION };
  const soiSource: GeoJSONSourceSpecification = {
    type: 'geojson',
    data: soi.data as unknown as GeoJSONSourceSpecification['data'],
    attribution: soi.attribution,
  };
  return {
    style: {
      ...style,
      sources: { ...(style.sources ?? {}), [IN_BOUNDARIES_SOURCE]: source, [IN_BOUNDARIES_SOI_SOURCE]: soiSource },
      layers,
    },
    warnings,
  };
}

/**
 * The map operations {@link applyIndiaBoundaries} needs. `map-style.ts` implements it on a MapLibre `Map`; the specs
 * implement it on a plain object, so the rules are tested without WebGL.
 */
export interface BoundaryStyleTarget {
  /** The style as MapLibre holds it now (`Map.getStyle()`), or undefined before one has loaded. */
  getStyle(): StyleSpecification | undefined;
  addSource(id: string, source: SourceSpecification): void;
  addLayer(layer: LayerSpecification, beforeId?: string): void;
  setVisibility(layerId: string, visibility: 'visible' | 'none'): void;
  setFilter(layerId: string, filter: FilterSpecification | null): void;
  setZoomRange(layerId: string, minzoom: number, maxzoom: number): void;
  /** Replaces a GeoJSON source's data (`GeoJSONSource.setData`), for the Survey of India's lines once they are read. */
  setSourceData(sourceId: string, data: SoiFeatureCollection): void;
}

/** Where {@link applyIndiaBoundaries} reads the Survey of India's lines from, and their translated credit. */
export interface SoiOptions {
  /** The file's absolute URL ({@link inBoundariesSoiUrl}). */
  url: string;
  /** `map.boundaryCredit` in the current language. */
  attribution: string;
  /** Reads the file ({@link loadSoiBoundary} unless a test gives another). */
  load?: (url: string) => Promise<SoiFeatureCollection | null>;
  /** The lines already read, if any ({@link loadedSoiBoundary} unless a test gives another). */
  loaded?: (url: string) => SoiFeatureCollection | undefined;
}

/**
 * Applies {@link indiaBoundaryStyle} to a map whose style has just loaded (`style.load`): works out what the rules
 * change and makes exactly those changes (visibility, filter, zoom range, the new sources and layers). Every rule it
 * had to skip, and every map call that failed, is passed to `warn`; nothing throws, so a changed base style can
 * never take the map (or the page) down with it.
 *
 * The Survey of India's source starts with the lines {@link loadSoiBoundary} has already read from `soi.url`; on the
 * first style load of a page they are usually still on their way, so the source starts empty and gets them
 * (`setSourceData`) as soon as they are read. A file that cannot be read is a warning: the map then has no northern
 * boundary line until the next style load tries again (the tiles' own lines there stay hidden, as ADR-22 requires).
 */
export function applyIndiaBoundaries(
  target: BoundaryStyleTarget,
  dataUrl: string,
  warn: (message: string) => void = (message) => console.warn(`India boundaries: ${message}`),
  soi?: SoiOptions,
): void {
  let before: StyleSpecification | undefined;
  try {
    before = target.getStyle();
  } catch (e) {
    warn(`could not read the style (${errorText(e)})`);
    return;
  }
  if (!before) {
    warn('no style loaded yet');
    return;
  }
  const ready = soi ? (soi.loaded ?? loadedSoiBoundary)(soi.url) : undefined;
  const overlay: SoiOverlay = { data: ready ?? NO_SOI_LINES, attribution: soi?.attribution ?? SOI_ATTRIBUTION };
  const { style: after, warnings } = indiaBoundaryStyle(before, dataUrl, HELD_AREAS, overlay);
  for (const message of warnings) warn(message);
  if (after === before) return;

  const attempt = (what: string, fn: () => void) => {
    try {
      fn();
    } catch (e) {
      warn(`${what} failed (${errorText(e)})`);
    }
  };

  for (const [id, source] of Object.entries(after.sources ?? {})) {
    if (!before.sources || !(id in before.sources)) attempt(`adding source "${id}"`, () => target.addSource(id, source));
  }

  const old = new Map((before.layers ?? []).map((layer) => [layer.id, layer] as const));
  const next = after.layers ?? [];
  next.forEach((layer, i) => {
    const was = old.get(layer.id);
    if (!was) {
      // Insert below the next layer that already exists, which keeps the order indiaBoundaryStyle chose.
      const beforeId = next.slice(i + 1).find((l) => old.has(l.id))?.id;
      attempt(`adding layer "${layer.id}"`, () => target.addLayer(layer, beforeId));
      return;
    }
    if (was === layer) return;
    const visibility = visibilityOf(layer);
    if (visibility !== visibilityOf(was)) {
      attempt(`hiding layer "${layer.id}"`, () => target.setVisibility(layer.id, visibility));
    }
    const filter = read(layer, 'filter') as FilterSpecification | undefined;
    if (JSON.stringify(filter) !== JSON.stringify(read(was, 'filter'))) {
      attempt(`filtering layer "${layer.id}"`, () => target.setFilter(layer.id, filter ?? null));
    }
    const minzoom = numberOr(read(layer, 'minzoom'), 0);
    const maxzoom = numberOr(read(layer, 'maxzoom'), MAX_ZOOM);
    if (minzoom !== numberOr(read(was, 'minzoom'), 0) || maxzoom !== numberOr(read(was, 'maxzoom'), MAX_ZOOM)) {
      attempt(`setting the zoom range of layer "${layer.id}"`, () => target.setZoomRange(layer.id, minzoom, maxzoom));
    }
  });

  if (soi && !ready) {
    void (soi.load ?? loadSoiBoundary)(soi.url).then((lines) => {
      if (!lines) {
        warn(`the Survey of India's lines (${soi.url}) could not be read; India's northern boundary is not drawn`);
        return;
      }
      attempt(`setting the data of source "${IN_BOUNDARIES_SOI_SOURCE}"`, () => target.setSourceData(IN_BOUNDARIES_SOI_SOURCE, lines));
    });
  }
}

/**
 * True for a symbol layer on source-layer `place` that can show a state label: no filter at all, or a filter that
 * names the class `state`. Liberty: `label_state`, and `label_other`, whose filter names `state` to leave it out
 * (the extra condition changes nothing there). City, town, village and country labels are untouched.
 */
function canShowState(layer: LayerSpecification): boolean {
  if (layer.type !== 'symbol' || read(layer, 'source-layer') !== PLACE_SOURCE_LAYER) return false;
  const filter = read(layer, 'filter');
  return filter === undefined || JSON.stringify(filter).includes('"state"');
}

/**
 * True for a line layer on source-layer `boundary` that gets {@link TILE_ZOOM_GUARD}: `boundary_2` and `boundary_3` by
 * name, and every other one whose minzoom is 5 or more. Never `boundary_disputed` (hidden), a symbol layer, or a line
 * layer meant for zoom 0-4, whose low-zoom lines the guard would remove. Android's `tileZoomGuardedLayers`.
 *
 * The guard is defence in depth on both renderers, and parity with Android. On the web, for a layer with minzoom 5,
 * maplibre-gl 6.11.2 already builds no bucket in a tile whose zoom is below floor(minzoom)
 * (`src/source/worker_tile.ts:110`, `layer.isHidden(this.zoom, true)`; `src/style/style_layer.ts:321-322`), so the
 * minzoom alone kept the zoom 0-4 tiles' lines off the map. The guard makes that hold whatever the renderer does with minzoom. On Android,
 * maplibre-native android-v13.6.1 (c7506d6): the worker's parse loop (`src/mln/tile/geometry_tile_worker.cpp`,
 * lines 446-502) has no zoom check of its own and runs the filter with `overscaledZ` (line 502), but
 * `GeometryTile::setLayers` leaves out a layer whose floor(minZoom) is above the tile's `overscaledZ` before the worker
 * gets the layers (`src/mln/tile/geometry_tile.cpp:317`, called for new and relaid-out tiles,
 * `src/mln/renderer/tile_pyramid.cpp:167,193`), so by the source maplibre-native skips such a layer too and Android's
 * guard is also defence in depth, not the fix (read from the source, not yet checked on a device: docs/06 TC-M-25
 * steps (7) to (9); S4b-BL-13).
 */
function takesTileZoomGuard(layer: LayerSpecification): boolean {
  if (layer.type !== 'line' || read(layer, 'source-layer') !== BOUNDARY_SOURCE_LAYER) return false;
  if (layer.id === DISPUTED_LINES_LAYER) return false;
  return (
    layer.id === COUNTRY_LINES_LAYER ||
    layer.id === STATE_LINES_LAYER ||
    numberOr(read(layer, 'minzoom'), 0) >= TILE_BOUNDARY_MIN_ZOOM
  );
}

/** Where the overlay goes: above `boundary_2`; else above the first `boundary` layer; else below the first symbol layer. */
function overlayIndex(layers: LayerSpecification[], country: number): number {
  if (country >= 0) return country + 1;
  const boundary = layers.findIndex((layer) => read(layer, 'source-layer') === BOUNDARY_SOURCE_LAYER);
  if (boundary >= 0) return boundary + 1;
  const symbol = layers.findIndex((layer) => layer.type === 'symbol');
  return symbol >= 0 ? symbol : layers.length;
}

function overlayLayer(
  id: string,
  source: string,
  kind: 'world' | 'claim' | 'state',
  paint: Record<string, unknown>,
  minzoom?: number,
  maxzoom?: number,
): LineLayerSpecification {
  const layer: LineLayerSpecification = {
    id,
    type: 'line',
    source,
    filter: ['==', ['get', 'kind'], kind],
    // A dashed line keeps butt caps, as boundary_3 does (round caps would fill its gaps).
    layout: kind === 'state' ? { 'line-join': 'round' } : { 'line-join': 'round', 'line-cap': 'round' },
    paint: paint as unknown as LineLayerSpecification['paint'],
  };
  if (minzoom !== undefined) layer.minzoom = minzoom;
  if (maxzoom !== undefined) layer.maxzoom = maxzoom;
  return layer;
}

/**
 * A base layer's paint for an overlay layer, so it looks like the base map's own lines: `boundary_2`'s colour, width
 * and opacity by default; a property the base layer does not set comes from `fallback` ({@link FALLBACK_LINE_PAINT}).
 */
function copyPaint(
  paint: unknown,
  fallback: Readonly<Record<string, unknown>> = FALLBACK_LINE_PAINT,
  keys: readonly string[] = Object.keys(FALLBACK_LINE_PAINT),
): Record<string, unknown> {
  const out: Record<string, unknown> = JSON.parse(JSON.stringify(fallback)) as Record<string, unknown>;
  if (!paint || typeof paint !== 'object') return out;
  for (const key of keys) {
    const value = (paint as Record<string, unknown>)[key];
    // A copy (style values are plain JSON), so the overlay never shares an array with boundary_2.
    if (value !== undefined) out[key] = JSON.parse(JSON.stringify(value)) as unknown;
  }
  return out;
}

/**
 * `existing` AND every clause. MapLibre does not accept a filter that mixes the expression and the deprecated
 * syntax, so a deprecated `existing` is combined with the deprecated form of the clauses.
 */
function andFilter(
  existing: FilterSpecification | undefined,
  clauses: ExpressionSpecification[],
  legacyClauses: unknown[][],
): FilterSpecification {
  if (existing === undefined || existing === true) {
    return (clauses.length === 1 ? clauses[0] : ['all', ...clauses]) as unknown as FilterSpecification;
  }
  const extra = isLegacyFilter(existing) ? legacyClauses : clauses;
  return ['all', existing, ...extra] as unknown as FilterSpecification;
}

/**
 * Whether MapLibre reads `filter` in the deprecated syntax: a port of `classifyFilter` in
 * `@maplibre/maplibre-gl-style-spec` 26.4.4 (`src/feature_filter/index.ts`), where `isExpressionFilter(f)` is
 * `classifyFilter(f) !== 'legacy'`.
 */
export function isLegacyFilter(filter: unknown): boolean {
  return classify(filter) === 'legacy';
}

function classify(filter: unknown): 'expression' | 'legacy' | 'neutral' {
  if (typeof filter === 'boolean') return 'neutral';
  if (!Array.isArray(filter) || filter.length === 0) return 'legacy';
  switch (filter[0]) {
    case 'has':
      if (filter.length < 2 || filter[1] === '$id' || filter[1] === '$type') return 'legacy';
      return filter.length === 2 ? 'neutral' : 'expression';
    case 'in':
      return filter.length >= 3 && (typeof filter[1] !== 'string' || Array.isArray(filter[2])) ? 'expression' : 'legacy';
    case '!in':
    case '!has':
    case 'none':
      return 'legacy';
    case '==':
    case '!=':
    case '>':
    case '>=':
    case '<':
    case '<=':
      return filter.length !== 3 || Array.isArray(filter[1]) || Array.isArray(filter[2]) ? 'expression' : 'legacy';
    case 'any':
    case 'all': {
      let sawLegacy = false;
      for (const child of filter.slice(1)) {
        const kind = classify(child);
        if (kind === 'expression') return 'expression';
        if (kind === 'legacy') sawLegacy = true;
      }
      return sawLegacy ? 'legacy' : 'neutral';
    }
    default:
      return 'expression';
  }
}

function visibilityOf(layer: LayerSpecification): 'visible' | 'none' {
  const layout = read(layer, 'layout') as Record<string, unknown> | undefined;
  return layout?.['visibility'] === 'none' ? 'none' : 'visible';
}

/** One property of a layer, read without narrowing the layer-type union first. */
function read(layer: LayerSpecification, key: string): unknown {
  return (layer as unknown as Record<string, unknown>)[key];
}

/** A copy of `layer` with `change` written over it. */
function patch(layer: LayerSpecification, change: Record<string, unknown>): LayerSpecification {
  return { ...(layer as unknown as Record<string, unknown>), ...change } as unknown as LayerSpecification;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
