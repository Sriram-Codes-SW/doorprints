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

import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { type IControl, Map as MlMap, Marker, NavigationControl } from 'maplibre-gl';
import { TranslationService } from '../i18n/translation.service';
import { TPipe } from '../i18n/t.pipe';
import { createMlMap, localizeMap, relabelUnavailable, watchMapStyle } from './map-style';
import { TraceLayers } from '../pages/map/trace-layers';
import type { LineCollection } from './trace-style';
import { ringElement } from './trace-ring';
import type { MapStyleWatch } from './map-style';

export interface LatLon {
  lat: number;
  lon: number;
}

/**
 * Something drawn on the map besides the house's marker (docs/03 section 6.2b row 11): walks and the place check's halo, as
 * GeoJSON, and the box to frame (`[[west, south], [east, north]]`). Null draws nothing; the house form and the areas' point
 * picker never set it.
 */
export interface MapOverlay {
  readonly walks: LineCollection;
  readonly check: LineCollection | null;
  readonly fit: [[number, number], [number, number]] | null;
  /** The ring with a cross at the place of a check, with its label (*This house*): a form, not a colour. */
  readonly ring?: { readonly lat: number; readonly lon: number; readonly label: string } | null;
}

/**
 * Small map with a single (optionally draggable) marker for one house.
 * Dragging is never the only way to move it: the parent page offers latitude/longitude fields
 * (WCAG 2.5.7), referenced through `describedBy`.
 */
@Component({
  selector: 'app-location-map',
  imports: [TPipe],
  template: `
    <div #mapEl class="map"></div>
    @if (!available()) {
      <!-- Offline (the map style is never cached): point to what works without a map. -->
      <div class="offline" role="status">
        <p>{{ (workerFailed() ? 'map.workerFailed' : 'house.mapOffline') | t }}</p>
        @if (!workerFailed()) {
          <button type="button" class="btn btn-sm" (click)="retry()">{{ 'common.retry' | t }}</button>
        }
      </div>
    }
  `,
  host: {
    role: 'region',
    '[attr.aria-label]': 'label()',
    '[attr.aria-describedby]': 'describedBy()',
  },
  styles: `
    :host {
      display: block;
      position: relative;
      height: 240px;
      border-radius: var(--radius);
      overflow: hidden;
      border: 1px solid var(--border);
    }
    .map {
      position: absolute;
      inset: 0;
    }
    .offline {
      position: absolute;
      inset: 0;
      z-index: 3;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-2);
      padding: var(--space-3);
      background: var(--surface-2);
      color: var(--text);
      text-align: center;
      font-size: var(--text-sm);
    }
    .offline p {
      margin: 0;
    }
  `,
})
export class LocationMap implements AfterViewInit, OnDestroy {
  readonly lat = input.required<number>();
  readonly lon = input.required<number>();
  /** Zoom at creation only: street level for a known house, wider when the position is a starting guess. */
  readonly zoom = input(16);
  readonly color = input('#1F6F5C');
  readonly editable = input(true);
  readonly label = input<string | null>(null);
  readonly describedBy = input<string | null>(null);
  /** Walks and a halo drawn over the map (the house page's *Saved walks* and *Did I walk past this house?*); null draws nothing. */
  readonly overlay = input<MapOverlay | null>(null);
  readonly moved = output<LatLon>();

  private readonly i18n = inject(TranslationService);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('mapEl');
  private map: MlMap | null = null;
  private controls: IControl[] = [];
  private marker: Marker | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private styleWatch: MapStyleWatch | null = null;
  private layers: TraceLayers | null = null;
  private ring: { remove(): unknown } | null = null;
  /** False while the map style cannot load (offline); the overlay then points to the fields and "Use my location". */
  protected readonly available = signal(true);
  /** True when the map's worker failed to load while online: the message says to reload, not that we are offline. */
  protected readonly workerFailed = signal(false);

  constructor() {
    // Keep the marker in sync when the position changes from outside (loading, saving, typed coordinates).
    effect(() => {
      const lat = this.lat();
      const lon = this.lon();
      const marker = this.marker;
      if (!marker || !this.map) return;
      const cur = marker.getLngLat();
      if (Math.abs(cur.lat - lat) > 1e-7 || Math.abs(cur.lng - lon) > 1e-7) {
        marker.setLngLat([lon, lat]);
        // Zoom in as well when the map was showing a wide starting view (a new house with no position yet).
        this.map.easeTo({ center: [lon, lat], zoom: Math.max(this.map.getZoom(), 15) });
      }
    });
    effect(() => {
      const draggable = this.editable();
      this.marker?.setDraggable(draggable);
    });
    // The overlay (walks, halo) follows its input; it is drawn once the map has a style.
    effect(() => {
      const overlay = this.overlay();
      untracked(() => this.applyOverlay(overlay));
    });
    // A language switch relabels the zoom buttons and the marker, or the "map unavailable" sentence (A11Y-B03).
    let lastLang = this.i18n.lang();
    effect(() => {
      const lang = this.i18n.lang();
      if (lang === lastLang) return;
      lastLang = lang;
      untracked(() => {
        const map = this.map;
        if (!map) {
          relabelUnavailable(this.mapEl().nativeElement, this.i18n);
          return;
        }
        this.controls = localizeMap(map, this.i18n, this.controls, () => [new NavigationControl({ showCompass: false })]);
        this.marker?.getElement().setAttribute('aria-label', this.i18n.t('house.location'));
      });
    });
  }

  ngAfterViewInit(): void {
    const container = this.mapEl().nativeElement;
    const center: [number, number] = [this.lon(), this.lat()];
    const map = createMlMap(this.i18n, { container, center, zoom: this.zoom() });
    if (!map) return;
    this.styleWatch = watchMapStyle(map, (ok, key) => {
      this.available.set(ok);
      this.workerFailed.set(!ok && key === 'map.workerFailed');
    });
    this.controls = [new NavigationControl({ showCompass: false })];
    for (const control of this.controls) map.addControl(control, 'top-right');
    const marker = new Marker({ color: this.color(), draggable: this.editable() }).setLngLat(center).addTo(map);
    // The marker is left at the rounded position the parent will store, so the effect above sees no change and
    // only recentres (and zooms in) for a move that came from outside: typed coordinates, "Use my location".
    marker.on('dragend', () => {
      const p = marker.getLngLat();
      const lat = round6(p.lat);
      const lon = round6(p.lng);
      marker.setLngLat([lon, lat]);
      this.moved.emit({ lat, lon });
    });
    map.on('click', (e) => {
      if (!this.editable()) return;
      const lat = round6(e.lngLat.lat);
      const lon = round6(e.lngLat.lng);
      marker.setLngLat([lon, lat]);
      this.moved.emit({ lat, lon });
    });
    this.map = map;
    this.marker = marker;
    // The walks' layers are added on EVERY style.load (a style replaced after an offline start comes without them).
    this.layers = new TraceLayers(map);
    map.on('style.load', () => {
      this.layers?.attach();
      this.applyOverlay(this.overlay());
    });

    // jsdom has no ResizeObserver. createMlMap usually returns null there (no WebGL 2), so this line is never
    // reached. The Plan page spec replaces maplibre-gl for the shared test chunk with a map that does construct,
    // and the unit-test runner does not isolate files, so a later spec that opens this map throws — which spec
    // depends on file order. A browser has ResizeObserver, and the map still follows its box there.
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(() => map.resize());
      this.resizeObserver.observe(container);
    }
  }

  /** Draws (or clears) the overlay and frames it. A map that is not made (jsdom, no WebGL) has no layers: nothing to do. */
  private applyOverlay(overlay: MapOverlay | null): void {
    const layers = this.layers;
    if (!layers) return;
    this.ring?.remove();
    this.ring = null;
    if (overlay?.ring && this.map) this.ring = this.addRing(this.map, overlay.ring);
    layers.setWalks(overlay?.walks ?? EMPTY_LINES);
    layers.setCheck(overlay?.check ?? null);
    if (overlay?.fit) layers.fitTo(overlay.fit, 40);
  }

  /** The ring of a check at its place, as a DOM marker that lets every pointer through. */
  protected addRing(map: MlMap, ring: { lat: number; lon: number; label: string }): { remove(): unknown } {
    return new Marker({ element: ringElement(ring.label), anchor: 'center' }).setLngLat([ring.lon, ring.lat]).addTo(map);
  }

  protected retry(): void {
    this.styleWatch?.retry();
  }

  ngOnDestroy(): void {
    this.styleWatch?.dispose();
    this.resizeObserver?.disconnect();
    this.ring?.remove();
    this.marker?.remove();
    this.map?.remove();
    this.map = null;
    this.marker = null;
    this.layers = null;
  }
}

const EMPTY_LINES: LineCollection = { type: 'FeatureCollection', features: [] };

export function round6(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}
