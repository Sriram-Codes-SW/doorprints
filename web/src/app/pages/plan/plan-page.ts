import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { GeoJSONSource, LngLatBounds, Map as MlMap, Marker, NavigationControl } from 'maplibre-gl';
import { AI_MAX_QUESTION_CHARS, AiService, PlanResponse, aiErrorMsg } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';
import { createMlMap } from '../../shared/map-style';
import { round6 } from '../../shared/location-map';

const ROUTE_SOURCE = 'plan-route';

/**
 * "Plan my visits" (docs/ai 5.3): the agent picks houses matching the request and orders them into a walking route
 * from the start point. The ordered list is the accessible equivalent of the map; the start point can be set from
 * the device location, by dragging the start marker or by typing coordinates.
 */
@Component({
  selector: 'app-plan-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './plan-page.html',
  styleUrl: './plan-page.css',
})
export class PlanPage implements AfterViewInit, OnDestroy {
  protected readonly ai = inject(AiService);
  protected readonly i18n = inject(TranslationService);
  private readonly announcer = inject(Announcer);
  private readonly mapEl = viewChild<ElementRef<HTMLDivElement>>('mapEl');

  protected readonly maxChars = AI_MAX_QUESTION_CHARS;
  protected question = '';
  protected maxStops = 4;
  /** Start of the walk; defaults to the centre of India until the device location or the user sets it. */
  protected readonly start = signal<{ lat: number; lon: number }>({ lat: 20.59, lon: 78.96 });
  protected readonly locating = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<Msg | null>(null);
  protected readonly plan = signal<PlanResponse | null>(null);
  protected readonly stops = computed(() => [...(this.plan()?.stops ?? [])].sort((a, b) => a.order - b.order));
  protected readonly totalKm = computed(() => this.i18n.number((this.plan()?.totalMeters ?? 0) / 1000, 1));

  private map: MlMap | null = null;
  private startMarker: Marker | null = null;
  private stopMarkers: Marker[] = [];
  private resizeObserver: ResizeObserver | null = null;

  ngAfterViewInit(): void {
    const el = this.mapEl()?.nativeElement;
    if (el) this.createMap(el);
    this.useMyLocation();
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.stopMarkers.forEach((m) => m.remove());
    this.startMarker?.remove();
    this.map?.remove();
    this.map = null;
  }

  protected useMyLocation(): void {
    if (typeof navigator === 'undefined' || !navigator.geolocation) return;
    this.locating.set(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        this.setStart(pos.coords.latitude, pos.coords.longitude, true);
        this.locating.set(false);
      },
      () => {
        this.locating.set(false);
        this.error.set({ key: 'plan.noLocation' });
      },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 },
    );
  }

  protected onCoord(axis: 'lat' | 'lon', event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = Number.parseFloat(input.value);
    const limit = axis === 'lat' ? 90 : 180;
    const cur = this.start();
    if (!Number.isFinite(value) || Math.abs(value) > limit) {
      input.value = String(cur[axis]);
      this.error.set({ key: 'house.coordsInvalid' });
      return;
    }
    this.error.set(null);
    if (axis === 'lat') this.setStart(value, cur.lon, true);
    else this.setStart(cur.lat, value, true);
  }

  protected submit(): void {
    const q = this.question.trim();
    if (!q || this.busy()) return;
    const s = this.start();
    this.busy.set(true);
    this.error.set(null);
    this.ai
      .planVisits({ question: q, startLat: s.lat, startLon: s.lon, maxStops: clamp(Math.round(this.maxStops), 1, 8) })
      .subscribe({
        next: (p) => {
          this.plan.set(p);
          this.busy.set(false);
          this.drawPlan(p);
          this.announcer.announce({ key: 'plan.ready', params: { n: p.stops.length } });
        },
        error: (err: unknown) => {
          this.error.set(aiErrorMsg(err));
          this.busy.set(false);
        },
      });
  }

  private setStart(lat: number, lon: number, move: boolean): void {
    this.start.set({ lat: round6(lat), lon: round6(lon) });
    this.startMarker?.setLngLat([lon, lat]);
    if (move) this.map?.easeTo({ center: [lon, lat], zoom: 14 });
  }

  private createMap(container: HTMLDivElement): void {
    const s = this.start();
    const map = createMlMap(this.i18n, { container, center: [s.lon, s.lat], zoom: 4 });
    if (!map) return;
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    const marker = new Marker({ color: '#1F6F5C', draggable: true }).setLngLat([s.lon, s.lat]).addTo(map);
    marker.on('dragend', () => {
      const p = marker.getLngLat();
      this.setStart(p.lat, p.lng, false);
    });
    map.on('load', () => {
      map.addSource(ROUTE_SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      map.addLayer({
        id: 'plan-route-line',
        type: 'line',
        source: ROUTE_SOURCE,
        paint: { 'line-color': '#1F6F5C', 'line-width': 4, 'line-dasharray': [2, 1] },
      });
      const p = this.plan();
      if (p) this.drawPlan(p);
    });
    this.map = map;
    this.startMarker = marker;
    this.resizeObserver = new ResizeObserver(() => map.resize());
    this.resizeObserver.observe(container);
  }

  /** Numbered markers (text only, no HTML from the server) and a straight-line route in visiting order. */
  private drawPlan(p: PlanResponse): void {
    const map = this.map;
    if (!map) return;
    this.stopMarkers.forEach((m) => m.remove());
    const stops = [...p.stops].sort((a, b) => a.order - b.order);
    this.stopMarkers = stops.map((stop) => {
      const el = document.createElement('div');
      el.className = 'plan-stop-marker';
      el.textContent = String(stop.order);
      el.setAttribute('aria-hidden', 'true');
      return new Marker({ element: el }).setLngLat([stop.lon, stop.lat]).addTo(map);
    });
    const s = this.start();
    const coords: [number, number][] = [[s.lon, s.lat], ...stops.map((x): [number, number] => [x.lon, x.lat])];
    const source = map.getSource(ROUTE_SOURCE) as GeoJSONSource | undefined;
    // MapLibre 6: setData returns a Promise<void>; the route is fire-and-forget.
    void source?.setData({
      type: 'FeatureCollection',
      features: [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } }],
    });
    if (coords.length > 1) {
      const bounds = new LngLatBounds();
      coords.forEach((c) => bounds.extend(c));
      map.fitBounds(bounds, { padding: 60, maxZoom: 16, duration: 0 });
    }
  }
}

function clamp(n: number, min: number, max: number): number {
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : min;
}
