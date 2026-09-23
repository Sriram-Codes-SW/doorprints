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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  type ControlPosition,
  GeolocateControl,
  type IControl,
  LngLatBounds,
  Map as MlMap,
  NavigationControl,
  Popup,
} from 'maplibre-gl';
import { LocalDataService } from '../../core/local-data.service';
import {
  HouseDto,
  STATUSES,
  STATUS_COLOR,
  STATUS_ICON,
  STATUS_KEY,
  StatsDto,
  houseScore,
} from '../../core/models';
import { errorMsg } from '../../core/format';
import { Announcer } from '../../core/announcer.service';
import { SyncService } from '../../data/sync.service';
import { createMlMap, localizeMap, watchMapStyle } from '../../shared/map-style';
import type { MapStyleWatch } from '../../shared/map-style';
import { round6 } from '../../shared/location-map';
import { COUNTRY_VIEW, loadMapView, locationErrorKey, saveMapView } from '../../shared/map-center';
import { locateOnce } from '../../shared/locate-once';
import { GLYPHS } from '../../shared/glyphs';
import { TKey } from '../../i18n/en';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';
import {
  SortKey,
  StatusFilter,
  comparePrice,
  listQueryParams,
  parseListQuery,
  searchText,
  timeOf,
} from './map-list';
import { ListReturn } from './list-return';
import { fitPadding } from './fit-padding';
import { RunResult, nextRunResult, runResult } from '../../shared/run-result';

interface ListItem {
  house: HouseDto;
  score: number | null;
}

const SOURCE_ID = 'houses';
const LAYER_ID = 'houses-circles';
/** Typing pause before the search goes into the URL and the new count is announced. */
const SEARCH_SETTLE_MS = 500;
/**
 * Where MapLibre's own controls go. The phone layout (map above the list, up to 760px) puts them bottom-right, in a
 * column above the page's actions, like Android's MapScreen; a short viewport (a phone in landscape) keeps them
 * top-right, where a map a few hundred pixels tall still has room for them.
 */
const BOTTOM_CONTROLS_QUERY = '(max-width: 760px) and (min-height: 501px)';

/**
 * Whether this page load has already framed all houses once. Returning from a house rebuilds the page; after the
 * first visit it keeps the view the user left (zoom and pan, saved by `moveend`) instead of fitting again.
 */
let fittedThisSession = false;

@Component({
  selector: 'app-map-page',
  imports: [RouterLink, TPipe],
  templateUrl: './map-page.html',
  styleUrl: './map-page.css',
  host: { '(document:keydown.escape)': 'onEscape()' },
})
export class MapPage implements AfterViewInit, OnDestroy {
  private readonly api = inject(LocalDataService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly listReturn = inject(ListReturn);
  protected readonly sync = inject(SyncService);
  protected readonly i18n = inject(TranslationService);

  protected readonly houses = signal<HouseDto[]>([]);
  protected readonly stats = signal<StatsDto | null>(null);
  protected readonly loading = signal(true);
  /** Why the houses could not be read; keyed on its run so that Retry failing the same way is read again. */
  protected readonly error = signal<RunResult<Msg> | null>(null);

  protected readonly search = signal('');
  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly sort = signal<SortKey>('recent');
  protected readonly addMode = signal(false);
  /**
   * False while the map style could not be loaded (offline: the tiles are never cached). The page then says so over
   * the map and offers what works without one: the list, "Add at my location" and typed coordinates.
   */
  protected readonly mapAvailable = signal(true);
  /**
   * True when this browser cannot draw a map at all (MapLibre 6 needs WebGL 2; `createMlMap` returned null). Treated
   * like being offline, except that trying again cannot help: the same two ways to add a house are offered, with the
   * "map unavailable" sentence, and no Retry.
   */
  protected readonly mapUnsupported = signal(false);
  /** A map is on screen and can be clicked: the crosshair, "Place here", "Show all" and the legend need one. */
  protected readonly mapUsable = computed(() => this.mapAvailable() && !this.mapUnsupported());
  protected readonly locating = signal(false);
  /** Why "Add at my location" failed: the permission is blocked, or the position could not be found. */
  protected readonly locateError = signal<RunResult<TKey> | null>(null);
  protected readonly canLocate = typeof navigator !== 'undefined' && 'geolocation' in navigator;
  /**
   * The browser is still empty and the first-run download is offered or running: the houses exist, on the server.
   * The list then says that, instead of "No houses yet" under a banner that says the opposite.
   */
  protected readonly housesOnServer = computed(() => {
    const m = this.sync.migration();
    return m === 'offered' || m === 'running';
  });
  /**
   * Listing text handed over by the share target (/share). While it is set, add mode is on with a "choose where
   * this house is" hint, and the text goes on to the new-house form with the position the user picks.
   */
  protected readonly sharedText = signal<string | null>(null);

  protected readonly statuses = STATUSES;
  protected readonly statusKey = STATUS_KEY;
  protected readonly statusIcon = STATUS_ICON;
  protected readonly filterIcon: Readonly<Record<StatusFilter, string>> = { ALL: '', ...STATUS_ICON };
  protected readonly filters: readonly StatusFilter[] = ['ALL', 'NEW', 'SHORTLISTED', 'REJECTED'];
  protected readonly sorts: readonly { key: SortKey; labelKey: TKey }[] = [
    { key: 'recent', labelKey: 'map.sort.recent' },
    { key: 'score', labelKey: 'map.sort.score' },
    { key: 'price', labelKey: 'map.sort.price' },
  ];
  protected readonly skeletonRows: readonly number[] = [1, 2, 3, 4];
  /** Empty-state glyphs (Material, as in the navigation bar). */
  protected readonly glyphs = GLYPHS;

  protected readonly items = computed<ListItem[]>(() => {
    const q = this.search().trim().toLowerCase();
    const status = this.statusFilter();
    const list = this.houses()
      .filter((h) => status === 'ALL' || h.status === status)
      .filter((h) => !q || searchText(h).includes(q))
      .map((house) => ({ house, score: houseScore(house) }));
    switch (this.sort()) {
      case 'score':
        return list.sort((a, b) => (b.score ?? -1) - (a.score ?? -1));
      case 'price':
        return list.sort((a, b) => comparePrice(a.house, b.house));
      default:
        return list.sort((a, b) => timeOf(b.house) - timeOf(a.house));
    }
  });

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('mapEl');
  private readonly wrap = viewChild.required<ElementRef<HTMLElement>>('wrap');
  private readonly stack = viewChild.required<ElementRef<HTMLElement>>('stack');
  private map: MlMap | null = null;
  private controls: IControl[] = [];
  private controlPosition: ControlPosition = 'top-right';
  private controlsQuery: MediaQueryList | null = null;
  private onControlsQuery: (() => void) | null = null;
  private stackObserver: ResizeObserver | null = null;
  private popup: Popup | null = null;
  private popupHouse: HouseDto | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private styleWatch: MapStyleWatch | null = null;
  private readonly mapReady = signal(false);
  /** The map was made with cooperative gestures (a phone): add mode turns them off while it lasts. */
  private cooperative = false;
  private fitted = false;
  /** The map opened at a view the user left earlier (see {@link fittedThisSession}). */
  private hadSavedView = false;
  /** After the first successful read the list stays in the DOM on later reloads (no skeleton, focus is kept). */
  private loadedOnce = false;
  private searchTimer: ReturnType<typeof setTimeout> | undefined;
  /**
   * Set first thing in ngOnDestroy. "Add at my location" can answer long after it was pressed (the permission
   * prompt, a 15 s fix): a page that is gone by then must not open a new-house form the user did not ask for.
   * `downloadHere()` checks it too, after the download and after counting the houses.
   */
  private destroyed = false;

  constructor() {
    // The list as the user left it: search, filter and sort live in the URL (Back from a house, a bookmark).
    const params = this.route.snapshot.queryParamMap;
    const query = parseListQuery((name) => params.get(name));
    this.search.set(query.q);
    this.statusFilter.set(query.status);
    this.sort.set(query.sort);
    // Also for the ways back that are not the browser's Back (a house opened from a bookmark, Delete, Discard).
    this.listReturn.remember(query);
    const handover = sharedHandover(this.router);
    if (handover) {
      this.sharedText.set(handover);
      this.addMode.set(true);
    } else if (params.get('add') === '1') {
      // "Add a house on the map" from another page (Your data, Compare): open in add mode, once.
      this.addMode.set(true);
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { add: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    }
    // Load now, and again after writes to this browser's store (an edit here or in another tab, a sync pull, the
    // first-run "download my houses to this browser"). Without this the page reads IndexedDB once and a pull that
    // lands behind the banner leaves the map empty until the user reloads by hand. `settled`, not `revision`: a
    // pull writes one row at a time, and re-reading per row redrew the list per row.
    effect(() => {
      this.api.settled();
      this.reload();
    });
    // Push the filtered houses to the map whenever they (or the map) change.
    effect(() => {
      const items = this.items();
      if (!this.mapReady()) return;
      this.setMapData(items.map((i) => i.house));
    });
    // Frame all houses once, after both the map and the data are ready — the first time there **are** houses, and
    // only on the first visit of this page load or when there is no view to go back to. An empty first load (a
    // brand-new user, or a browser still waiting for the first-run download) does not use up the fit, so houses
    // that arrive later with the download are framed instead of left as dots on a far view. Coming back from a
    // house keeps the zoom and pan the user left.
    effect(() => {
      const houses = this.houses();
      const ready = this.mapReady();
      const loading = this.loading();
      if (!ready || loading || this.fitted || houses.length === 0) return;
      this.fitted = true;
      if (this.hadSavedView && fittedThisSession) return;
      fittedThisSession = true;
      this.fitToHouses(houses);
    });
    // A language switch relabels MapLibre's own controls and an open popup (A11Y-B03).
    let lastLang = this.i18n.lang();
    effect(() => {
      const lang = this.i18n.lang();
      if (lang === lastLang) return;
      lastLang = lang;
      untracked(() => this.relocalize());
    });
    // Add mode says "move the map so the cross is on the house": one finger must move the map then, not show "Use
    // two fingers" (see applyGestures).
    effect(() => {
      const adding = this.addMode();
      if (!this.mapReady()) return;
      untracked(() => this.applyGestures(adding));
    });
  }

  ngAfterViewInit(): void {
    const container = this.mapEl().nativeElement;
    // Start where the user last looked, else over India (not the whole globe, where the first house would be a
    // zoom of fifteen levels away); the fit still frames all houses on the first visit. A stored view that is still
    // the untouched country view (saved by this page's own first layout) reads as none, so it does not skip the fit.
    const saved = loadMapView();
    this.hadSavedView = saved !== null;
    const view = saved ?? COUNTRY_VIEW;
    this.watchStack();
    // On a phone the map is 55vh of a page that scrolls: createMlMap turns on cooperative gestures there (one finger
    // scrolls the page, two move the map; UX-007), as for every other map in the app.
    const map = createMlMap(this.i18n, {
      container,
      center: [view.lon, view.lat],
      zoom: view.zoom,
    });
    if (!map) {
      // No WebGL 2: the overlay says so and offers the two ways to add a house that need no map. createMlMap's
      // own sentence is taken out, so it is not read twice.
      container.replaceChildren();
      this.mapUnsupported.set(true);
      return;
    }
    this.styleWatch = watchMapStyle(map, (available) => this.mapAvailable.set(available));
    this.cooperative = map.cooperativeGestures.isEnabled();
    if (this.addMode()) map.getCanvas().style.cursor = 'crosshair';
    // Remembered so a new house opened without a position starts here, and so Back from a house returns to it.
    map.on('moveend', () => {
      const c = map.getCenter();
      saveMapView({ lat: round6(c.lat), lon: round6(c.lng), zoom: Math.round(map.getZoom() * 10) / 10 });
    });
    this.controlPosition = this.wantedControlPosition();
    this.controls = makeControls(this.controlPosition);
    for (const control of this.controls) map.addControl(control, this.controlPosition);
    this.watchControlPosition(map);

    // 'style.load', not the one-off 'load': after an offline start the style is requested again when the connection
    // returns (watchMapStyle), and a new style comes without our source and layer.
    map.on('style.load', () => {
      if (!map.getSource(SOURCE_ID)) {
        map.addSource(SOURCE_ID, {
          type: 'geojson',
          data: { type: 'FeatureCollection', features: [] },
        } as unknown as Parameters<MlMap['addSource']>[1]);
      }
      if (map.getLayer(LAYER_ID)) {
        this.setMapData(this.items().map((i) => i.house));
        this.mapReady.set(true);
        return;
      }
      map.addLayer({
        id: LAYER_ID,
        type: 'circle',
        source: SOURCE_ID,
        paint: {
          // Size also encodes status (shortlisted larger, rejected smaller), so colour is not the only cue.
          'circle-radius': [
            'interpolate',
            ['linear'],
            ['zoom'],
            8,
            ['match', ['get', 'status'], 'SHORTLISTED', 7, 'REJECTED', 4, 5],
            14,
            ['match', ['get', 'status'], 'SHORTLISTED', 11, 'REJECTED', 6, 8],
            18,
            ['match', ['get', 'status'], 'SHORTLISTED', 15, 'REJECTED', 9, 12],
          ],
          'circle-color': [
            'match',
            ['get', 'status'],
            'NEW',
            STATUS_COLOR.NEW,
            'SHORTLISTED',
            STATUS_COLOR.SHORTLISTED,
            'REJECTED',
            STATUS_COLOR.REJECTED,
            '#888888',
          ],
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': ['match', ['get', 'status'], 'SHORTLISTED', 3, 2],
          'circle-opacity': ['match', ['get', 'status'], 'REJECTED', 0.75, 1],
        },
      } as unknown as Parameters<MlMap['addLayer']>[0]);
      // mapReady may already be true (a style reloaded after going online): push the houses in either case.
      this.setMapData(this.items().map((i) => i.house));
      this.mapReady.set(true);
    });

    map.on('click', LAYER_ID, (e) => {
      if (this.addMode()) return;
      const id = e.features?.[0]?.properties?.['id'];
      if (typeof id === 'string') {
        void this.router.navigate(['/houses', id]);
      }
    });

    map.on('click', (e) => {
      if (!this.addMode()) return;
      this.createAt(e.lngLat.lat, e.lngLat.lng);
    });

    map.on('mouseenter', LAYER_ID, (e) => {
      map.getCanvas().style.cursor = 'pointer';
      const id = e.features?.[0]?.properties?.['id'];
      const house = this.houses().find((h) => h.id === id);
      if (house) this.showPopup(map, house);
    });

    // WCAG 1.4.13: the popup stays while the pointer moves onto it (hoverable) and until the user dismisses it
    // (Esc, its close button, a map click, or hovering another house), so it is not removed on mouseleave.
    map.on('mouseleave', LAYER_ID, () => {
      map.getCanvas().style.cursor = this.addMode() ? 'crosshair' : '';
    });

    this.map = map;
    this.resizeObserver = new ResizeObserver(() => map.resize());
    this.resizeObserver.observe(container);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    clearTimeout(this.searchTimer);
    this.styleWatch?.dispose();
    this.resizeObserver?.disconnect();
    this.stackObserver?.disconnect();
    if (this.controlsQuery && this.onControlsQuery) {
      this.controlsQuery.removeEventListener('change', this.onControlsQuery);
    }
    this.popup?.remove();
    this.map?.remove();
    this.map = null;
  }

  /**
   * Reads the houses and the numbers. Only the **first** read shows the skeleton: later ones (after a sync pull or
   * an edit) keep the list in the DOM and swap the data in, so `track item.house.id` keeps a focused row focused
   * and the live regions are not recreated and re-announced. An error stays on screen until a read succeeds.
   * `userAsked` is Retry: its failure is a new run and is read again even with the same words, while a background
   * re-read that fails the same way keeps the run on screen and is not read out on every sync pull.
   */
  protected reload(userAsked = false): void {
    if (!this.loadedOnce) this.loading.set(true);
    this.api.houses().subscribe({
      next: (list) => {
        this.houses.set(list.filter((h) => !h.deleted));
        this.error.set(null);
        this.loadedOnce = true;
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.update((previous) => nextRunResult(previous, errorMsg(err), userAsked));
        this.loading.set(false);
      },
    });
    this.api.stats().subscribe({
      next: (s) => this.stats.set(s),
      error: () => this.stats.set(null),
    });
  }

  /**
   * The Add house button. Its label says which state it is in ("Add house" / "Cancel adding"), so it is a plain
   * button, not a toggle with aria-pressed as well. Turning add mode on says what to do next, through the app's
   * live region: a hint inserted together with its text is often not read.
   */
  protected toggleAddMode(): void {
    const on = !this.addMode();
    this.addMode.set(on);
    this.locateError.set(null);
    if (!on && this.sharedText() !== null) {
      // Cancelled: the shared text is dropped (it is still in the app that shared it), and Back must not bring
      // the pick-a-place mode back.
      this.sharedText.set(null);
      forgetHandover();
    }
    if (on && this.mapUsable()) {
      this.announcer.announce({ key: this.sharedText() !== null ? 'map.pickShared' : 'map.addHint' });
    }
    if (this.map) this.map.getCanvas().style.cursor = on ? 'crosshair' : '';
  }

  /**
   * Esc: dismisses the popup (WCAG 1.4.13), and leaves add mode, like the Cancel adding button. "Place here" goes
   * away with add mode: if it had focus, focus moves to the Add house button instead of falling to <body>.
   */
  protected onEscape(): void {
    this.dismissPopup();
    if (!this.addMode()) return;
    const active = typeof document === 'undefined' ? null : document.activeElement;
    const fromPlaceHere = active !== null && active.id === 'place-here';
    this.toggleAddMode();
    afterNextRender(
      () => {
        const now = document.activeElement;
        if (fromPlaceHere || now === null || now === document.body) document.getElementById('add-toggle')?.focus();
      },
      { injector: this.injector },
    );
  }

  protected onSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
    // Once typing pauses: into the URL, and the new count said once, not on every keystroke.
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.writeQuery();
      this.announceCount();
    }, SEARCH_SETTLE_MS);
  }

  protected setStatusFilter(filter: StatusFilter): void {
    this.statusFilter.set(filter);
    this.writeQuery();
    this.announceCount();
  }

  protected onSort(event: Event): void {
    this.sort.set((event.target as HTMLSelectElement).value as SortKey);
    this.writeQuery();
  }

  /** Keyboard alternative to clicking the map: places the new house under the centre cross. */
  protected placeAtCenter(): void {
    const c = this.map?.getCenter();
    if (c) this.createAt(c.lat, c.lng);
  }

  /**
   * From the empty state: turn on add mode, bring the map into view (it is above the list on phones) and put focus
   * on what adds the house — "Place here", or without a usable map the first of "Add at my location" and "Type
   * latitude and longitude".
   */
  protected startAdd(): void {
    if (!this.addMode()) this.toggleAddMode();
    this.mapEl().nativeElement.scrollIntoView({ block: 'nearest' });
    afterNextRender(
      () => {
        const target =
          document.getElementById('place-here') ??
          document.getElementById('add-at-location') ??
          document.getElementById('type-coords');
        target?.focus();
      },
      { injector: this.injector },
    );
  }

  protected clearFilters(): void {
    clearTimeout(this.searchTimer);
    this.search.set('');
    this.statusFilter.set('ALL');
    this.writeQuery();
    this.announceCount();
  }

  protected filterKey(f: StatusFilter): TKey {
    return f === 'ALL' ? 'status.ALL' : STATUS_KEY[f];
  }

  protected countFor(f: StatusFilter): number {
    const all = this.houses();
    return f === 'ALL' ? all.length : all.filter((h) => h.status === f).length;
  }

  /** Hovering or focusing a list row highlights that house on the map with a popup. */
  protected hoverHouse(h: HouseDto): void {
    if (this.map && this.mapReady()) this.showPopup(this.map, h);
  }

  /** Dismisses the popup without moving focus or the pointer (WCAG 1.4.13). */
  protected dismissPopup(): void {
    this.popup?.remove();
    this.popup = null;
    this.popupHouse = null;
  }

  protected fitAll(): void {
    this.fitToHouses(this.items().map((i) => i.house));
  }

  /** "Try again" on the offline overlay: request the map style now rather than waiting for the `online` event. */
  protected retryMap(): void {
    this.styleWatch?.retry();
  }

  /**
   * Add mode without a map (offline, or no WebGL 2): the new house goes where the user is standing. Geolocation works
   * offline. The form then opens at that position, where the pin can still be moved or the coordinates typed.
   */
  protected addAtMyLocation(): void {
    if (!this.canLocate || this.locating()) return;
    this.locating.set(true);
    this.locateError.set(null);
    // locateOnce drops the answer when this page is gone by then (the destroyed guard, as on the house form).
    locateOnce({
      gone: () => this.destroyed,
      found: (pos) => {
        this.locating.set(false);
        this.createAt(pos.coords.latitude, pos.coords.longitude);
      },
      failed: (err) => {
        this.locating.set(false);
        this.locateError.set(runResult(locationErrorKey(err)));
      },
    });
  }

  /** Add mode without a map: open the form with no position, where latitude and longitude can be typed. */
  protected typeCoordinates(): void {
    this.addMode.set(false);
    const shared = this.sharedText();
    this.sharedText.set(null);
    if (shared !== null) forgetHandover();
    void this.router.navigate(['/houses/new'], { state: shared !== null ? { shared } : undefined });
  }

  /**
   * "Download now" from the list's own empty state (the same action as the banner's). The button is replaced by
   * the progress line at once, so focus moves to that line (it would otherwise fall to <body>), and to the list
   * heading when the download is over.
   */
  protected async downloadHere(): Promise<void> {
    const done = this.sync.downloadToThisBrowser();
    afterNextRender(() => document.getElementById('download-progress')?.focus(), { injector: this.injector });
    await done;
    // Rule (f): the page may have been left during the download. Then nothing is said on another page or focused.
    if (this.destroyed) return;
    if (this.sync.migration() === 'done') {
      let n: number | null = null;
      try {
        n = (await firstValueFrom(this.api.houses())).length;
      } catch {
        // The houses could not be read back (an IndexedDB error): the count is not said, but focus still moves on.
      }
      if (this.destroyed) return;
      if (n !== null) this.announcer.announce({ key: 'data.migrationDone', params: { n } });
    }
    const heading = document.getElementById('houses-heading');
    heading?.setAttribute('tabindex', '-1');
    heading?.focus({ preventScroll: true });
  }

  /** Search, filter and sort into the URL, replacing the entry: Back goes to the previous page, not the last filter. */
  private writeQuery(): void {
    const query = { q: this.search(), status: this.statusFilter(), sort: this.sort() };
    this.listReturn.remember(query);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: listQueryParams(query),
      queryParamsHandling: 'merge',
      replaceUrl: true,
      // Keep the share handover (listing text in history.state) while the user filters in pick mode.
      state: this.sharedText() !== null ? { shared: this.sharedText(), pickLocation: true } : undefined,
    });
  }

  /** "Houses shown: n of m", said once after a search pause or a filter change (the line itself is not live). */
  private announceCount(): void {
    this.announcer.announce({ key: 'map.shown', params: { shown: this.items().length, total: this.houses().length } });
  }

  private createAt(lat: number, lon: number): void {
    this.addMode.set(false);
    if (this.map) this.map.getCanvas().style.cursor = '';
    const shared = this.sharedText();
    this.sharedText.set(null);
    // This history entry is still the map's: strip the handover so Back from the form shows the plain map.
    if (shared !== null) forgetHandover();
    void this.router.navigate(['/houses/new'], {
      queryParams: { lat: round6(lat).toFixed(6), lon: round6(lon).toFixed(6) },
      // Same rule as the share page: the text (often with a phone number) travels in state, never in the URL.
      state: shared !== null ? { shared } : undefined,
    });
  }

  private setMapData(houses: HouseDto[]): void {
    // MapLibre 6: GeoJSONSource.setData returns a Promise<void> (no longer `this`).
    const source = this.map?.getSource(SOURCE_ID) as unknown as { setData(data: unknown): Promise<void> } | undefined;
    if (!source) return;
    void source.setData({
      type: 'FeatureCollection',
      features: houses.map((h) => ({
        type: 'Feature',
        id: h.id,
        geometry: { type: 'Point', coordinates: [h.lon, h.lat] },
        properties: { id: h.id, status: h.status, label: h.label },
      })),
    });
  }

  private fitToHouses(houses: HouseDto[]): void {
    const map = this.map;
    if (!map || houses.length === 0) return;
    const bounds = new LngLatBounds();
    for (const h of houses) bounds.extend([h.lon, h.lat]);
    // On a phone the bottom row (legend, actions) and MapLibre's control column are drawn over the map: keep the
    // houses out from under them. The row is measured the same way watchStack() measures it for --map-stack-h.
    const container = map.getContainer();
    const padding = fitPadding(
      this.wantedControlPosition() === 'bottom-right',
      this.stack().nativeElement.getBoundingClientRect().height,
      container.clientWidth,
      container.clientHeight,
    );
    map.fitBounds(bounds, { padding, maxZoom: 16, duration: 0 });
  }

  /**
   * On a phone the map is made with cooperative gestures, so one finger scrolls the page past it. In add mode the user
   * is deliberately working the map ("move the map so the cross is on the house"), as on Android's full-screen
   * MapScreen: one finger moves it then, and cooperative gestures come back when add mode ends. A map made without
   * them (desktop) is left alone.
   */
  private applyGestures(adding: boolean): void {
    const map = this.map;
    if (!map || !this.cooperative) return;
    const gestures = map.cooperativeGestures;
    if (adding && gestures.isEnabled()) gestures.disable();
    else if (!adding && !gestures.isEnabled()) gestures.enable();
  }

  private wantedControlPosition(): ControlPosition {
    return typeof matchMedia !== 'undefined' && matchMedia(BOTTOM_CONTROLS_QUERY).matches ? 'bottom-right' : 'top-right';
  }

  /** A rotation or a resize across {@link BOTTOM_CONTROLS_QUERY} moves MapLibre's controls to the other corner. */
  private watchControlPosition(map: MlMap): void {
    if (typeof matchMedia === 'undefined') return;
    const query = matchMedia(BOTTOM_CONTROLS_QUERY);
    const onChange = () => {
      const position = this.wantedControlPosition();
      if (position === this.controlPosition) return;
      this.controlPosition = position;
      this.controls = localizeMap(map, this.i18n, this.controls, () => makeControls(position), position);
    };
    query.addEventListener('change', onChange);
    this.controlsQuery = query;
    this.onControlsQuery = onChange;
  }

  /**
   * The phone layout's bottom row (legend and actions) is measured into `--map-stack-h` on the map region, so
   * MapLibre's bottom-right controls and the offline message sit above it whatever its height (a two-row legend in
   * Tamil, two stacked buttons, 200% text). On wider screens the row has no box and measures 0, which nothing uses.
   */
  private watchStack(): void {
    if (typeof ResizeObserver === 'undefined') return;
    const wrap = this.wrap().nativeElement;
    const stack = this.stack().nativeElement;
    const update = () => {
      wrap.style.setProperty('--map-stack-h', `${Math.ceil(stack.getBoundingClientRect().height)}px`);
    };
    this.stackObserver = new ResizeObserver(update);
    this.stackObserver.observe(stack);
    update();
  }

  /** After a language switch: MapLibre's controls and the open popup in the new language. */
  private relocalize(): void {
    const map = this.map;
    if (!map) return;
    const position = this.controlPosition;
    this.controls = localizeMap(map, this.i18n, this.controls, () => makeControls(position), position);
    const house = this.popupHouse;
    if (house && this.popup?.isOpen()) this.showPopup(map, house);
  }

  private showPopup(map: MlMap, h: HouseDto): void {
    this.popup?.remove();
    const el = document.createElement('div');
    const title = document.createElement('strong');
    title.textContent = h.label || this.i18n.t('common.untitled');
    el.appendChild(title);
    const details = [
      `${STATUS_ICON[h.status]} ${this.i18n.t(STATUS_KEY[h.status])}`,
      h.price != null ? this.i18n.price(h.price, h.priceType) : null,
      h.bedrooms != null ? this.i18n.t('common.bhk', { n: h.bedrooms }) : null,
      this.i18n.t('common.scoreValue', { score: this.i18n.score(houseScore(h)) }),
    ].filter((x): x is string => !!x);
    const sub = document.createElement('div');
    sub.textContent = details.join(' · ');
    el.appendChild(sub);
    this.popupHouse = h;
    this.popup = new Popup({ closeButton: true, closeOnClick: true, closeOnMove: false, offset: 12 })
      .setLngLat([h.lon, h.lat])
      .setDOMContent(el)
      .addTo(map);
  }
}

/**
 * The map page's own controls, made afresh after a language switch (a control copies its labels when added) or a
 * move to the other corner. MapLibre stacks controls added to a bottom corner upwards (each goes above the ones
 * before), so there "my location" is added first and ends up below the zoom buttons, as on Android.
 */
function makeControls(position: ControlPosition): IControl[] {
  const zoom = new NavigationControl({ showCompass: false });
  const locate = new GeolocateControl({ positionOptions: { enableHighAccuracy: true } });
  return position.startsWith('bottom') ? [locate, zoom] : [zoom, locate];
}

/**
 * Listing text the share page sent here to be placed on the map (`{ shared, pickLocation: true }` in the
 * navigation state), or null. Read from the navigation in flight, or from `history.state` for a re-entered route.
 */
function sharedHandover(router: Router): string | null {
  const state: unknown = router.currentNavigation()?.extras.state ?? (typeof history === 'undefined' ? null : history.state);
  if (!state || typeof state !== 'object') return null;
  const { shared, pickLocation } = state as { shared?: unknown; pickLocation?: unknown };
  return pickLocation === true && typeof shared === 'string' && shared.trim() !== '' ? shared : null;
}

/** Removes the handover from the current history entry, keeping the router's own keys in it. */
function forgetHandover(): void {
  if (typeof history === 'undefined') return;
  try {
    const state: unknown = history.state;
    if (!state || typeof state !== 'object') return;
    const rest: Record<string, unknown> = { ...(state as Record<string, unknown>) };
    delete rest['shared'];
    delete rest['pickLocation'];
    history.replaceState(rest, '');
  } catch {
    // A browser that refuses replaceState: Back would show the pick mode again, which is harmless.
  }
}
