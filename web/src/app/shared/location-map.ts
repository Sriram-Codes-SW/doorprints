import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import { Map as MlMap, Marker, NavigationControl } from 'maplibre-gl';
import { TranslationService } from '../i18n/translation.service';
import { createMlMap } from './map-style';

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
  template: `<div #mapEl class="map"></div>`,
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
  `,
})
export class LocationMap implements AfterViewInit, OnDestroy {
  readonly lat = input.required<number>();
  readonly lon = input.required<number>();
  readonly color = input('#1F6F5C');
  readonly editable = input(true);
  readonly label = input<string | null>(null);
  readonly describedBy = input<string | null>(null);
  readonly moved = output<LatLon>();

  private readonly i18n = inject(TranslationService);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('mapEl');
  private map: MlMap | null = null;
  private marker: Marker | null = null;
  private resizeObserver: ResizeObserver | null = null;

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
        this.map.easeTo({ center: [lon, lat] });
      }
    });
    effect(() => {
      const draggable = this.editable();
      this.marker?.setDraggable(draggable);
    });
  }

  ngAfterViewInit(): void {
    const container = this.mapEl().nativeElement;
    const center: [number, number] = [this.lon(), this.lat()];
    const map = createMlMap(this.i18n, { container, center, zoom: 16 });
    if (!map) return;
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    const marker = new Marker({ color: this.color(), draggable: this.editable() }).setLngLat(center).addTo(map);
    marker.on('dragend', () => {
      const p = marker.getLngLat();
      this.moved.emit({ lat: round6(p.lat), lon: round6(p.lng) });
    });
    map.on('click', (e) => {
      if (!this.editable()) return;
      marker.setLngLat(e.lngLat);
      this.moved.emit({ lat: round6(e.lngLat.lat), lon: round6(e.lngLat.lng) });
    });
    this.map = map;
    this.marker = marker;

    this.resizeObserver = new ResizeObserver(() => map.resize());
    this.resizeObserver.observe(container);
  }

  ngOnDestroy(): void {
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
