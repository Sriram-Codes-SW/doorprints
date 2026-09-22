import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { LngLatBounds, Map as MlMap, NavigationControl, GeolocateControl, Popup } from 'maplibre-gl';
import { HouseApiService } from '../../core/house-api.service';
import {
  HouseDto,
  HouseStatus,
  STATUSES,
  STATUS_COLOR,
  STATUS_ICON,
  STATUS_KEY,
  StatsDto,
  houseScore,
} from '../../core/models';
import { errorMsg } from '../../core/format';
import { MAP_STYLE_URL, mapLocale } from '../../shared/map-style';
import { round6 } from '../../shared/location-map';
import { TKey } from '../../i18n/en';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';

type SortKey = 'recent' | 'score' | 'price';
type StatusFilter = HouseStatus | 'ALL';

interface ListItem {
  house: HouseDto;
  score: number | null;
}

const SOURCE_ID = 'houses';
const LAYER_ID = 'houses-circles';

@Component({
  selector: 'app-map-page',
  imports: [RouterLink, TPipe],
  templateUrl: './map-page.html',
  styleUrl: './map-page.css',
  host: { '(document:keydown.escape)': 'dismissPopup()' },
})
export class MapPage implements OnInit, AfterViewInit, OnDestroy {
  private readonly api = inject(HouseApiService);
  private readonly router = inject(Router);
  protected readonly i18n = inject(TranslationService);

  protected readonly houses = signal<HouseDto[]>([]);
  protected readonly stats = signal<StatsDto | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<Msg | null>(null);

  protected readonly search = signal('');
  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly sort = signal<SortKey>('recent');
  protected readonly addMode = signal(false);

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
        return list.sort((a, b) => (a.house.price ?? Number.MAX_VALUE) - (b.house.price ?? Number.MAX_VALUE));
      default:
        return list.sort((a, b) => timeOf(b.house) - timeOf(a.house));
    }
  });

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('mapEl');
  private map: MlMap | null = null;
  private popup: Popup | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private readonly mapReady = signal(false);
  private fitted = false;

  constructor() {
    // Push the filtered houses to the map whenever they (or the map) change.
    effect(() => {
      const items = this.items();
      if (!this.mapReady()) return;
      this.setMapData(items.map((i) => i.house));
    });
    // Fit to all houses once, after both the map and the data are ready.
    effect(() => {
      const houses = this.houses();
      const ready = this.mapReady();
      const loading = this.loading();
      if (!ready || loading || this.fitted) return;
      this.fitted = true;
      this.fitToHouses(houses);
    });
  }

  ngOnInit(): void {
    this.reload();
  }

  ngAfterViewInit(): void {
    const container = this.mapEl().nativeElement;
    const map = new MlMap({
      container,
      style: MAP_STYLE_URL,
      center: [0, 20],
      zoom: 1.5,
      attributionControl: { compact: true },
      locale: mapLocale(this.i18n),
    });
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    map.addControl(new GeolocateControl({ positionOptions: { enableHighAccuracy: true } }), 'top-right');

    map.on('load', () => {
      map.addSource(SOURCE_ID, {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      } as unknown as Parameters<MlMap['addSource']>[1]);
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
    this.resizeObserver?.disconnect();
    this.popup?.remove();
    this.map?.remove();
    this.map = null;
  }

  protected reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.houses().subscribe({
      next: (list) => {
        this.houses.set(list.filter((h) => !h.deleted));
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(errorMsg(err));
        this.loading.set(false);
      },
    });
    this.api.stats().subscribe({
      next: (s) => this.stats.set(s),
      error: () => this.stats.set(null),
    });
  }

  protected toggleAddMode(): void {
    const on = !this.addMode();
    this.addMode.set(on);
    if (this.map) this.map.getCanvas().style.cursor = on ? 'crosshair' : '';
  }

  protected onSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  protected onSort(event: Event): void {
    this.sort.set((event.target as HTMLSelectElement).value as SortKey);
  }

  /** Keyboard alternative to clicking the map: places the new house under the centre cross. */
  protected placeAtCenter(): void {
    const c = this.map?.getCenter();
    if (c) this.createAt(c.lat, c.lng);
  }

  /** From the empty state: turn on add mode and bring the map into view (it is above the list on phones). */
  protected startAdd(): void {
    if (!this.addMode()) this.toggleAddMode();
    this.mapEl().nativeElement.scrollIntoView({ block: 'nearest' });
  }

  protected clearFilters(): void {
    this.search.set('');
    this.statusFilter.set('ALL');
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

  /** Esc anywhere on the page dismisses the popup without moving focus or the pointer (WCAG 1.4.13). */
  protected dismissPopup(): void {
    this.popup?.remove();
    this.popup = null;
  }

  protected fitAll(): void {
    this.fitToHouses(this.items().map((i) => i.house));
  }

  private createAt(lat: number, lon: number): void {
    this.addMode.set(false);
    if (this.map) this.map.getCanvas().style.cursor = '';
    void this.router.navigate(['/houses/new'], {
      queryParams: { lat: round6(lat).toFixed(6), lon: round6(lon).toFixed(6) },
    });
  }

  private setMapData(houses: HouseDto[]): void {
    const source = this.map?.getSource(SOURCE_ID) as unknown as { setData(data: unknown): void } | undefined;
    if (!source) return;
    source.setData({
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
    map.fitBounds(bounds, { padding: 60, maxZoom: 16, duration: 0 });
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
    this.popup = new Popup({ closeButton: true, closeOnClick: true, closeOnMove: false, offset: 12 })
      .setLngLat([h.lon, h.lat])
      .setDOMContent(el)
      .addTo(map);
  }
}

function searchText(h: HouseDto): string {
  return [h.label, h.address, h.street, h.locality, h.notes, h.contactName]
    .filter((x) => !!x)
    .join(' ')
    .toLowerCase();
}

function timeOf(h: HouseDto): number {
  const t = Date.parse(h.updatedAt ?? h.createdAt ?? '');
  return Number.isNaN(t) ? 0 : t;
}
