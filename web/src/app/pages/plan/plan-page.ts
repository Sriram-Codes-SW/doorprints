import {
  AfterViewInit,
  Component,
  ElementRef,
  Injector,
  OnDestroy,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription, firstValueFrom } from 'rxjs';
import {
  GeoJSONSource,
  type IControl,
  LngLatBounds,
  Map as MlMap,
  Marker,
  NavigationControl,
} from 'maplibre-gl';
import { AI_MAX_QUESTION_CHARS, AiService, PlanResponse, aiErrorMsg } from '../../core/ai.service';
import { AiSessionState } from '../../core/ai-session.state';
import { Announcer } from '../../core/announcer.service';
import { ConfigService } from '../../core/config.service';
import { LocalDataService } from '../../core/local-data.service';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';
import { createMlMap, localizeMap, relabelUnavailable } from '../../shared/map-style';
import { RunResult, runResult } from '../../shared/run-result';
import { focusIfLost } from '../../shared/focus';
import { round6 } from '../../shared/location-map';
import { COUNTRY_VIEW, loadStartPoint, locationErrorKey, parseCoordinate } from '../../shared/map-center';
import { locateOnce } from '../../shared/locate-once';
import { NO_TYPED_START, type StartFieldId, type TypedStart, nextTypedStart, startFieldToFix } from './start-field';

const ROUTE_SOURCE = 'plan-route';

/**
 * "Plan my visits" (docs/ai 5.3): the agent picks houses matching the request and orders them into a walking route
 * from the start point. The ordered list is the accessible equivalent of the map; the start point can be set from
 * the device location, by dragging the start marker or by typing coordinates.
 *
 * Nothing happens on arrival: the location is only asked for when the user presses "Use my location" (no browser
 * permission prompt out of nowhere), and the start begins where the user last looked at the map, or at their newest
 * house. The route and the request survive going to a stop and coming Back (AiSessionState).
 *
 * Planning again after an error keeps the error card in place, drawn as being updated with "Planning…" as its state,
 * until the new run ends (Android 1.33's rule, `RefreshableResultCard`), so the route below does not jump. The card is
 * keyed on its run ({@link RunResult}), so a new failure is read even when its words are the same.
 */
@Component({
  selector: 'app-plan-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './plan-page.html',
  styleUrl: './plan-page.css',
})
export class PlanPage implements AfterViewInit, OnDestroy {
  protected readonly ai = inject(AiService);
  protected readonly config = inject(ConfigService);
  protected readonly i18n = inject(TranslationService);
  private readonly announcer = inject(Announcer);
  private readonly session = inject(AiSessionState);
  private readonly api = inject(LocalDataService);
  private readonly injector = inject(Injector);
  private readonly mapEl = viewChild<ElementRef<HTMLDivElement>>('mapEl');

  protected readonly maxChars = AI_MAX_QUESTION_CHARS;
  protected question = '';
  protected maxStops = 4;
  /** Start of the walk: the last map view, else the newest house; the centre of India only as a placeholder. */
  protected readonly start = signal<{ lat: number; lon: number }>({ lat: COUNTRY_VIEW.lat, lon: COUNTRY_VIEW.lon });
  /**
   * False while the start is still that placeholder: planning from the middle of India would be meaningless. The
   * start fields are then empty (the example is only their placeholder) and there is no start marker on the map, so
   * nothing looks like a chosen start before the user has chosen one.
   */
  protected readonly startSet = signal(false);
  /** The example shown in the empty start fields. */
  protected readonly startExample = { lat: String(COUNTRY_VIEW.lat), lon: String(COUNTRY_VIEW.lon) };
  /**
   * A coordinate typed while no start is set yet, kept until the other one is typed too. A field that turns invalid or
   * empty drops its kept value ({@link nextTypedStart}), so the start is never set from a value no longer in the field.
   */
  private typedStart: TypedStart = NO_TYPED_START;
  /** The user has typed in a start field: the first start read from the store must then not overwrite the fields. */
  private startTyped = false;
  /** "Plan route" was pressed with no request: said under the request box, which gets the focus. */
  protected readonly questionMissing = signal(false);
  protected readonly locating = signal(false);
  /**
   * Why the start point is not usable (no start yet, location blocked or unavailable), under the start fields (id
   * `start-msg`, also in the fields' aria-describedby while shown). Keyed on its run, so the same message twice in a
   * row is read again. Every way of setting a start withdraws it ({@link setStart}), not only the path that raised it.
   */
  protected readonly startMsg = signal<RunResult<Msg> | null>(null);
  /** The field "Plan route" focused for "Choose a start point first": marked aria-invalid while that message shows. */
  private readonly missingField = signal<StartFieldId | null>(null);
  protected readonly coordsInvalid = signal<{ lat: boolean; lon: boolean }>({ lat: false, lon: false });
  protected readonly busy = signal(false);
  /** The last run's failure; it stays on screen while the next run is busy, and that run's end replaces it. */
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly plan = signal<PlanResponse | null>(null);
  protected readonly stops = computed(() => [...(this.plan()?.stops ?? [])].sort((a, b) => a.order - b.order));
  protected readonly totalKm = computed(() => this.i18n.number((this.plan()?.totalMeters ?? 0) / 1000, 1));

  private map: MlMap | null = null;
  private controls: IControl[] = [];
  private startMarker: Marker | null = null;
  private stopMarkers: Marker[] = [];
  private resizeObserver: ResizeObserver | null = null;
  private request: Subscription | null = null;
  private readonly viewReady = signal(false);
  /**
   * Set first thing in ngOnDestroy. "Use my location" and the first start (read from the store) can answer after the
   * page is gone: they then leave the start, the kept session and the removed map alone.
   */
  private destroyed = false;

  constructor() {
    const saved = this.session.plan;
    if (saved) {
      this.question = saved.question;
      this.maxStops = saved.maxStops;
      this.start.set(saved.start);
      this.startSet.set(saved.startSet);
      this.plan.set(saved.plan);
    } else {
      void this.initialStart();
    }
    // The map is only made while AI is on (the form is hidden otherwise), and once the view exists.
    effect(() => {
      if (!this.viewReady() || !this.ai.enabled() || this.map) return;
      const el = this.mapEl()?.nativeElement;
      if (el) untracked(() => this.createMap(el));
    });
    let lastLang = this.i18n.lang();
    effect(() => {
      const lang = this.i18n.lang();
      if (lang === lastLang) return;
      lastLang = lang;
      untracked(() => this.relocalize());
    });
  }

  ngAfterViewInit(): void {
    this.viewReady.set(true);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.request?.unsubscribe();
    // Kept for Back from a stop: the request, the start and the route (memory only).
    this.session.plan = {
      question: this.question,
      maxStops: this.maxStops,
      start: this.start(),
      startSet: this.startSet(),
      plan: this.plan(),
    };
    this.resizeObserver?.disconnect();
    this.stopMarkers.forEach((m) => m.remove());
    this.startMarker?.remove();
    this.map?.remove();
    this.map = null;
  }

  /**
   * The last map view, else the newest house; without either the start stays unset until the user sets it. A stored
   * view still centred on the country placeholder is not a start the user chose (the map page saves it on its first
   * layout), so loadStartPoint skips it and the newest house is used instead.
   */
  private async initialStart(): Promise<void> {
    const view = loadStartPoint();
    if (view) {
      this.setStart(view.lat, view.lon, true);
      return;
    }
    try {
      const houses = await firstValueFrom(this.api.houses());
      if (this.destroyed) return;
      const newest = [...houses]
        .filter((h) => !h.deleted)
        .sort((a, b) => Date.parse(b.createdAt ?? '') - Date.parse(a.createdAt ?? ''))[0];
      // Not over what the user typed in the meantime (a value, a typo or a cleared field).
      if (newest && !this.startSet() && !this.startTyped) this.setStart(newest.lat, newest.lon, true);
    } catch {
      // No houses readable: the user sets the start.
    }
  }

  protected useMyLocation(): void {
    if (typeof navigator === 'undefined' || !navigator.geolocation || this.locating()) return;
    this.locating.set(true);
    this.startMsg.set(null);
    // locateOnce drops the answer when this page is gone by then (the destroyed guard, as on the house form).
    locateOnce({
      gone: () => this.destroyed,
      found: (pos) => {
        this.setStart(pos.coords.latitude, pos.coords.longitude, true);
        this.locating.set(false);
      },
      failed: (err) => {
        this.locating.set(false);
        this.startMsg.set(runResult({ key: locationErrorKey(err) }));
      },
    });
  }

  /** Same rules as the house page: an invalid value stays in the field, marked, and the start does not move. */
  protected onCoord(axis: 'lat' | 'lon', event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = parseCoordinate(input.value, axis === 'lat' ? 90 : 180);
    this.startTyped = true;
    this.coordsInvalid.update((c) => ({ ...c, [axis]: value === null }));
    if (value !== null && this.missingField() === `start-${axis}`) this.missingField.set(null);
    if (!this.startSet()) {
      // Both fields are needed (the placeholder is not a coordinate). An invalid or cleared field drops its kept value.
      const { typed, commit } = nextTypedStart(this.typedStart, axis, value);
      this.typedStart = typed;
      if (commit) this.setStart(commit.lat, commit.lon, true);
      return;
    }
    if (value === null) return;
    // Only this field is filled from the new start: the other keeps its text, and its invalid flag if it has one.
    const cur = this.start();
    if (axis === 'lat') this.setStart(value, cur.lon, true, { lat: true, lon: false });
    else this.setStart(cur.lat, value, true, { lat: false, lon: true });
  }

  /** The field is marked invalid: its text is not a coordinate, or "Plan route" found it still empty with no start. */
  protected fieldInvalid(axis: 'lat' | 'lon'): boolean {
    if (this.coordsInvalid()[axis]) return true;
    return this.startMsg()?.value.key === 'plan.startRequired' && this.missingField() === `start-${axis}`;
  }

  protected coordsDescribedBy(axis: 'lat' | 'lon'): string {
    const ids = ['start-hint'];
    if (this.coordsInvalid()[axis]) ids.push('start-coords-error');
    // "Choose a start point first" (or why the location failed) is read with the field that gets focus, not only as
    // an alert that the focus announcement can cut off (NVDA, TalkBack).
    if (this.startMsg()) ids.push('start-msg');
    return ids.join(' ');
  }

  /** Typing a request clears "Type what you want to see first". */
  protected onQuestion(value: string): void {
    if (this.questionMissing() && value.trim()) this.questionMissing.set(false);
  }

  protected submit(): void {
    if (this.busy()) return;
    const q = this.question.trim();
    if (!q) {
      // The button is aria-disabled, not disabled, so it can be pressed: say why nothing happened, where it is fixed.
      this.questionMissing.set(true);
      afterNextRender(() => document.getElementById('plan-question')?.focus(), { injector: this.injector });
      return;
    }
    this.questionMissing.set(false);
    const invalid = this.coordsInvalid();
    if (!this.startSet() || invalid.lat || invalid.lon) {
      // Say what is missing where it is fixed, instead of planning a walk from somewhere the user never chose.
      // The first field still to fix: with the latitude typed and the longitude empty, the longitude (W2).
      const field = startFieldToFix(invalid, this.typedStart, this.startSet());
      if (!this.startSet()) {
        this.missingField.set(field);
        this.startMsg.set(runResult({ key: 'plan.startRequired' }));
      }
      afterNextRender(() => document.getElementById(field)?.focus(), { injector: this.injector });
      return;
    }
    const s = this.start();
    // An earlier error stays in place while this run is busy (drawn as being updated); the run's end replaces it.
    this.busy.set(true);
    this.request = this.ai
      .planVisits({ question: q, startLat: s.lat, startLon: s.lon, maxStops: clamp(Math.round(this.maxStops), 1, 8) })
      .subscribe({
        next: (p) => {
          this.request = null;
          this.error.set(null);
          this.plan.set(p);
          this.busy.set(false);
          this.drawPlan(p);
          this.announcer.announce({ key: 'plan.ready', params: { n: p.stops.length } });
          // The route itself is not a live region (it would be read all at once): focus goes to its heading.
          afterNextRender(() => document.getElementById('route-heading')?.focus(), { injector: this.injector });
        },
        error: (err: unknown) => {
          this.request = null;
          this.error.set(runResult(aiErrorMsg(err)));
          this.busy.set(false);
          // Cancel went away with the request: if it had focus, focus goes back to the button that sends it again.
          afterNextRender(() => focusIfLost('plan-submit'), { injector: this.injector });
        },
      });
  }

  /** "Cancel" while a route is being planned: drops the request; nothing is shown from it. */
  protected cancel(): void {
    if (!this.request) return;
    this.request.unsubscribe();
    this.request = null;
    this.busy.set(false);
    this.announcer.announce({ key: 'ai.cancelled' });
    afterNextRender(() => document.getElementById('plan-submit')?.focus(), { injector: this.injector });
  }

  /**
   * Sets the start. `filled` names the fields the new start is written into (both, except when one field was edited
   * with a start already set). Each filled field shows the new coordinate and loses its invalid flag in the same
   * step, so no field shows a value marked with an error it no longer has, or keeps an error for text that is gone.
   */
  private setStart(
    lat: number,
    lon: number,
    move: boolean,
    filled: { lat: boolean; lon: boolean } = { lat: true, lon: true },
  ): void {
    const next = { lat: round6(lat), lon: round6(lon) };
    const invalid = this.coordsInvalid();
    this.start.set(next);
    this.startSet.set(true);
    this.typedStart = NO_TYPED_START;
    this.missingField.set(null);
    // Every message under the start fields is about the start not being usable ("Choose a start point first", location
    // blocked or unavailable). A start set any way (map, drag, typing, location, newest house) withdraws it, so a stale
    // one is not read through the fields' aria-describedby on each focus of a field that is now valid.
    this.startMsg.set(null);
    for (const axis of ['lat', 'lon'] as const) {
      if (!filled[axis] || !invalid[axis]) continue;
      // The [value] binding writes only when the number changes: an invalid text over an unchanged start (a location
      // that equals the start) would stay. So the field is written here too.
      const input = document.getElementById(`start-${axis}`);
      if (input instanceof HTMLInputElement) input.value = String(next[axis]);
    }
    this.coordsInvalid.update((c) => ({ lat: c.lat && !filled.lat, lon: c.lon && !filled.lon }));
    if (this.startMarker) this.startMarker.setLngLat([lon, lat]);
    else if (this.map) this.startMarker = this.addStartMarker(this.map, lat, lon);
    if (move) this.map?.easeTo({ center: [lon, lat], zoom: 14 });
  }

  private createMap(container: HTMLDivElement): void {
    const s = this.start();
    const map = createMlMap(this.i18n, { container, center: [s.lon, s.lat], zoom: this.startSet() ? 13 : 4 });
    if (!map) return;
    this.controls = [new NavigationControl({ showCompass: false })];
    for (const control of this.controls) map.addControl(control, 'top-right');
    // No marker for the placeholder start: it appears where the user sets the start.
    if (this.startSet()) this.startMarker = this.addStartMarker(map, s.lat, s.lon);
    // Until a start is set, choosing a spot on the map (a click or a tap) sets it there (afterwards the marker is dragged).
    map.on('click', (e) => {
      if (this.startSet()) return;
      this.setStart(e.lngLat.lat, e.lngLat.lng, false);
    });
    map.on('load', () => {
      map.addSource(ROUTE_SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      map.addLayer({
        id: 'plan-route-line',
        type: 'line',
        source: ROUTE_SOURCE,
        paint: { 'line-color': '#1F6F5C', 'line-width': 4, 'line-dasharray': [2, 1] },
      });
      // A route kept from before (Back from a stop) is drawn again.
      const p = this.plan();
      if (p) this.drawPlan(p);
    });
    this.map = map;
    this.resizeObserver = new ResizeObserver(() => map.resize());
    this.resizeObserver.observe(container);
  }

  /** The draggable start marker; dragging it moves the start. */
  private addStartMarker(map: MlMap, lat: number, lon: number): Marker {
    const marker = new Marker({ color: '#1F6F5C', draggable: true }).setLngLat([lon, lat]).addTo(map);
    marker.getElement().setAttribute('aria-label', this.i18n.t('plan.start'));
    marker.on('dragend', () => {
      const p = marker.getLngLat();
      this.setStart(p.lat, p.lng, false);
    });
    return marker;
  }

  /** After a language switch: the zoom buttons, the start marker and the "map unavailable" sentence (A11Y-B03). */
  private relocalize(): void {
    const map = this.map;
    if (!map) {
      relabelUnavailable(this.mapEl()?.nativeElement, this.i18n);
      return;
    }
    this.controls = localizeMap(map, this.i18n, this.controls, () => [new NavigationControl({ showCompass: false })]);
    this.startMarker?.getElement().setAttribute('aria-label', this.i18n.t('plan.start'));
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
