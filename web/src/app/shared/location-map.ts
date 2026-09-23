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
import type { MapStyleWatch } from './map-style';

export interface LatLon {
  lat: number;
  lon: number;
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
        <p>{{ 'house.mapOffline' | t }}</p>
        <button type="button" class="btn btn-sm" (click)="retry()">{{ 'common.retry' | t }}</button>
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
  readonly moved = output<LatLon>();

  private readonly i18n = inject(TranslationService);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('mapEl');
  private map: MlMap | null = null;
  private controls: IControl[] = [];
  private marker: Marker | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private styleWatch: MapStyleWatch | null = null;
  /** False while the map style cannot load (offline); the overlay then points to the fields and "Use my location". */
  protected readonly available = signal(true);

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
    this.styleWatch = watchMapStyle(map, (ok) => this.available.set(ok));
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

    this.resizeObserver = new ResizeObserver(() => map.resize());
    this.resizeObserver.observe(container);
  }

  protected retry(): void {
    this.styleWatch?.retry();
  }

  ngOnDestroy(): void {
    this.styleWatch?.dispose();
    this.resizeObserver?.disconnect();
    this.marker?.remove();
    this.map?.remove();
    this.map = null;
    this.marker = null;
  }
}

export function round6(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}
