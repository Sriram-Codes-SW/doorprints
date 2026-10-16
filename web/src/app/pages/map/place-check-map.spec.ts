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

import { describe, expect, it, vi } from 'vitest';
import type { Map as MlMap, PaddingOptions } from 'maplibre-gl';
import { PlaceCheckMapView } from './place-check-map';
import type { CheckAnswer } from './place-check-state';
import type { TraceLayers } from './trace-layers';

const answer = (over: Partial<CheckAnswer> = {}): CheckAnswer => ({
  kind: 'house',
  place: { lat: 13, lon: 80 },
  summary: null,
  stretches: [],
  bounds: [[79.99, 12.99], [80.01, 13.01]],
  ...over,
});

const PADDING: PaddingOptions = { top: 1, bottom: 2, left: 3, right: 4 };

function rig(visible: (lngLat: [number, number]) => boolean = () => false) {
  const markers: { element: HTMLElement; at: [number, number] | null; added: boolean; removed: boolean }[] = [];
  const fit = vi.fn();
  const map = { getBounds: () => ({ contains: (p: [number, number]) => visible(p) }) } as unknown as MlMap;
  const layers = { fitTo: fit } as unknown as TraceLayers;
  const view = new PlaceCheckMapView(map, layers, () => PADDING, (element) => {
    const m = { element, at: null as [number, number] | null, added: false, removed: false };
    markers.push(m);
    return {
      setLngLat(p: [number, number]) {
        m.at = p;
        return this;
      },
      addTo() {
        m.added = true;
        return this;
      },
      remove() {
        m.removed = true;
        return this;
      },
    };
  });
  return { view, markers, fit };
}

describe('PlaceCheckMapView', () => {
  it('puts a ring with the label at the place of an answer', () => {
    const { view, markers } = rig();
    view.sync(answer(), 'This house');
    expect(markers).toHaveLength(1);
    expect(markers[0].at).toEqual([80, 13]);
    expect(markers[0].added).toBe(true);
    expect(markers[0].element.getAttribute('aria-label')).toBe('This house');
  });

  it('frames the place and the stretches when they are not on screen, with the page\'s padding', () => {
    const { view, fit } = rig(() => false);
    view.sync(answer(), 'x');
    expect(fit).toHaveBeenCalledWith([[79.99, 12.99], [80.01, 13.01]], PADDING);
  });

  it('does not move the map when the place and the box corners are all on screen', () => {
    const { view, fit } = rig(() => true);
    view.sync(answer(), 'x');
    expect(fit).not.toHaveBeenCalled();
  });

  it('frames when only one corner of the box is off screen', () => {
    const { view, fit } = rig(([lon, lat]) => !(lon === 80.01 && lat === 13.01));
    view.sync(answer(), 'x');
    expect(fit).toHaveBeenCalledTimes(1);
  });

  it('removes the ring when the answer goes, and does not frame', () => {
    const { view, markers, fit } = rig();
    view.sync(answer(), 'x');
    fit.mockClear();
    view.sync(null, '');
    expect(markers[0].removed).toBe(true);
    expect(fit).not.toHaveBeenCalled();
  });

  it('replaces the ring when the language changes the label, without framing the same answer again', () => {
    const { view, markers, fit } = rig();
    const a = answer();
    view.sync(a, 'This house');
    fit.mockClear();
    view.sync(a, 'यह मकान');
    expect(markers).toHaveLength(2);
    expect(markers[0].removed).toBe(true);
    expect(markers[1].element.getAttribute('aria-label')).toBe('यह मकान');
    expect(fit).not.toHaveBeenCalled();
  });

  it('a new answer gets a new ring and is framed if off screen', () => {
    const { view, markers, fit } = rig();
    view.sync(answer(), 'x');
    view.sync(answer({ place: { lat: 14, lon: 81 } }), 'x');
    expect(markers).toHaveLength(2);
    expect(markers[0].removed).toBe(true);
    expect(fit).toHaveBeenCalledTimes(2);
  });

  it('Show on map frames the box whatever is on screen', () => {
    const { view, fit } = rig(() => true);
    view.fit([[1, 2], [3, 4]]);
    expect(fit).toHaveBeenCalledWith([[1, 2], [3, 4]], PADDING);
  });

  it('dispose removes the ring', () => {
    const { view, markers } = rig();
    view.sync(answer(), 'x');
    view.dispose();
    expect(markers[0].removed).toBe(true);
  });
});
