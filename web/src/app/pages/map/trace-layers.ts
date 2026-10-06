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
 * Puts the walks on a MapLibre map (docs/03 section 6.2b row 7, S4b-FR-15): the `track` and `track-check` sources and four
 * line layers, every one added under the house layer (`beforeId`), so above India's boundary layers (which are never
 * touched, ADR-22) and under the markers. A class, not a component: the Map page and the house page's own map both use it.
 *
 * `attach()` is called on EVERY `style.load` (a style replaced after an offline start comes without our layers). It is
 * idempotent, and it gives the new style the walks, the look and the check the person last had.
 */

import type { GeoJSONSource, Map as MlMap, PaddingOptions } from 'maplibre-gl';
import type { RepeatLook } from '../../data/trace-store';
import {
  TRACK_CHECK_SOURCE,
  TRACK_REPEAT_LAYER,
  TRACK_SOURCE,
  checkLayersJson,
  repeatVisibility,
  repeatWidthExpression,
  trackLayerJson,
  trackRepeatLayerJson,
} from '../../shared/trace-style';
import type { LineCollection } from '../../shared/trace-style';

const EMPTY: LineCollection = { type: 'FeatureCollection', features: [] };

/** True when the person asked the system for less motion: the map then jumps instead of flying. */
export function reducedMotion(): boolean {
  return typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export class TraceLayers {
  private walks: LineCollection = EMPTY;
  private look: RepeatLook = 'CLEAR';
  private check: LineCollection = EMPTY;

  /** `beforeId` is the layer the walks go under (the houses); a map without it (the house page) gets them on top. */
  constructor(
    private readonly map: MlMap,
    private readonly beforeId = 'houses-circles',
  ) {}

  /** Adds the sources (made with the walks and the check the person last had) and the layers the style does not have yet. Call it on every `style.load`. */
  attach(): void {
    const map = this.map;
    const before = map.getLayer(this.beforeId) ? this.beforeId : undefined;
    if (!map.getSource(TRACK_SOURCE)) map.addSource(TRACK_SOURCE, { type: 'geojson', data: this.walks as never });
    if (!map.getSource(TRACK_CHECK_SOURCE)) map.addSource(TRACK_CHECK_SOURCE, { type: 'geojson', data: this.check as never });
    const [halo, stretch] = checkLayersJson();
    // Bottom to top: the base line, the repeats over it, the check's halo and its stretch.
    for (const spec of [trackLayerJson(), trackRepeatLayerJson(this.look), halo, stretch]) {
      if (!map.getLayer(spec.id)) map.addLayer(spec as never, before);
    }
  }

  setWalks(walks: LineCollection): void {
    this.walks = walks;
    this.push(TRACK_SOURCE, walks);
  }

  /** Applies at once (a paint and a layout property), with no rebuild of the GeoJSON. Off hides the overlay only. */
  setLook(look: RepeatLook): void {
    this.look = look;
    if (!this.map.getLayer(TRACK_REPEAT_LAYER)) return;
    this.map.setPaintProperty(TRACK_REPEAT_LAYER, 'line-width', repeatWidthExpression(look) as never);
    this.map.setLayoutProperty(TRACK_REPEAT_LAYER, 'visibility', repeatVisibility(look));
  }

  /** The place check's halo; null clears it. */
  setCheck(check: LineCollection | null): void {
    this.check = check ?? EMPTY;
    this.push(TRACK_CHECK_SOURCE, this.check);
  }

  /** Frames `[[west, south], [east, north]]` with `padding` (a number or the page's {@link PaddingOptions}); a jump with reduced motion. */
  fitTo(bounds: [[number, number], [number, number]], padding: number | PaddingOptions): void {
    this.map.fitBounds(bounds, { padding, maxZoom: 18, duration: reducedMotion() ? 0 : 400 });
  }

  private push(sourceId: string, data: LineCollection): void {
    const source = this.map.getSource(sourceId) as GeoJSONSource | undefined;
    if (source) void source.setData(data as never);
  }
}
