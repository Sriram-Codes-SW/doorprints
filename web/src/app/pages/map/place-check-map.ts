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
 * What the Map shows of a place check besides the halo (docs/11 5.27.13, docs/03 section 6.2b rows 7 and 15): the ring with a
 * cross at the place, and the framing of the place and the matched stretches. The halo itself is the `track-check` source,
 * which `TraceLayers` draws from `TraceView.check`. Decoration: the text answer is the whole answer.
 */

import { Marker } from 'maplibre-gl';
import type { Map as MlMap, PaddingOptions } from 'maplibre-gl';
import { ringElement } from '../../shared/trace-ring';
import type { CheckAnswer } from './place-check-state';
import type { TraceLayers } from './trace-layers';

/** The part of a MapLibre marker this uses (a test supplies its own). */
export interface RingMarker {
  setLngLat(lngLat: [number, number]): RingMarker;
  addTo(map: MlMap): RingMarker;
  remove(): RingMarker;
}

const defaultMarker = (element: HTMLElement): RingMarker => new Marker({ element, anchor: 'center' }) as unknown as RingMarker;

export class PlaceCheckMapView {
  private marker: RingMarker | null = null;
  private shown: CheckAnswer | null = null;

  constructor(
    private readonly map: MlMap,
    private readonly layers: TraceLayers,
    /** The padding that keeps the framed box clear of the actions and controls drawn over the map. */
    private readonly padding: () => PaddingOptions,
    private readonly makeMarker: (element: HTMLElement) => RingMarker = defaultMarker,
  ) {}

  /** Shows the ring for `answer` (null removes it) and frames a new answer that is not on screen. `label` is in the app language. */
  sync(answer: CheckAnswer | null, label: string): void {
    this.marker?.remove();
    this.marker = null;
    const isNew = answer !== this.shown;
    this.shown = answer;
    if (answer === null) return;
    this.marker = this.makeMarker(ringElement(label)).setLngLat([answer.place.lon, answer.place.lat]).addTo(this.map);
    if (isNew && !this.onScreen(answer)) this.fit(answer.bounds);
  }

  /** *Show on map*: frames the box whatever is on screen. */
  fit(bounds: [[number, number], [number, number]]): void {
    this.layers.fitTo(bounds, this.padding());
  }

  dispose(): void {
    this.marker?.remove();
    this.marker = null;
    this.shown = null;
  }

  private onScreen(answer: CheckAnswer): boolean {
    const view = this.map.getBounds();
    const [[west, south], [east, north]] = answer.bounds;
    const corners: [number, number][] = [
      [answer.place.lon, answer.place.lat],
      [west, south],
      [west, north],
      [east, south],
      [east, north],
    ];
    return corners.every((c) => view.contains(c));
  }
}
