import { GPUInitializationError, Map as MlMap, type IControl, type MapOptions, setWorkerUrl } from 'maplibre-gl';
import { TranslationService } from '../i18n/translation.service';
import { applyIndiaBoundaries, type BoundaryStyleTarget, inBoundariesUrl } from './india-boundaries';

/** Free OpenFreeMap vector style — no API key needed. */
export const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty';

/**
 * Translated labels for MapLibre's own controls (zoom buttons, geolocate, attribution, marker).
 * Passed as the Map `locale` option; MapLibre reads it when the map is created.
 */
export function mapLocale(i18n: TranslationService): Record<string, string> {
  return {
    'Map.Title': i18n.t('map.regionLabel'),
    'Marker.Title': i18n.t('house.location'),
    'NavigationControl.ZoomIn': i18n.t('map.zoomIn'),
    'NavigationControl.ZoomOut': i18n.t('map.zoomOut'),
    'GeolocateControl.FindMyLocation': i18n.t('map.myLocation'),
    'GeolocateControl.LocationNotAvailable': i18n.t('map.locationUnavailable'),
    'AttributionControl.ToggleAttribution': i18n.t('map.attribution'),
    'Popup.Close': i18n.t('common.close'),
    // Shown over a map created with `cooperativeGestures` (every map on phones) when one finger tries to pan it.
    'CooperativeGesturesHandler.MobileHelpText': i18n.t('map.coopMobile'),
    'CooperativeGesturesHandler.WindowsHelpText': i18n.t('map.coopWindows'),
    'CooperativeGesturesHandler.MacHelpText': i18n.t('map.coopMac'),
  };
}

/**
 * Puts a live map's own labels into the current language after a language switch (A11Y-B03).
 *
 * MapLibre reads its `locale` table when the map is created and each control copies its labels when it is added,
 * so a switch used to leave the zoom buttons, the canvas label and the gesture hint in the old language until the
 * page was opened again. This patches the table (`_locale` is public on `Map`, if underscored; read through a cast
 * so a typing change cannot break the build), relabels what is already drawn, and hands back fresh controls built by
 * `makeControls` in place of `old` — a control's labels can only be set by adding it again.
 */
export function localizeMap(
  map: MlMap,
  i18n: TranslationService,
  old: readonly IControl[],
  makeControls: () => IControl[],
  position: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left' = 'top-right',
): IControl[] {
  const locale = mapLocale(i18n);
  const internal = map as unknown as { _locale?: Record<string, string> };
  internal._locale = { ...(internal._locale ?? {}), ...locale };
  map.getCanvas().setAttribute('aria-label', locale['Map.Title']);
  const container = map.getContainer();
  const mac = typeof navigator !== 'undefined' && (navigator.userAgent || '').includes('Mac');
  setText(container, '.maplibregl-desktop-message', locale[mac ? 'CooperativeGesturesHandler.MacHelpText' : 'CooperativeGesturesHandler.WindowsHelpText']);
  setText(container, '.maplibregl-mobile-message', locale['CooperativeGesturesHandler.MobileHelpText']);
  const attribution = container.querySelector<HTMLElement>('.maplibregl-ctrl-attrib-button');
  if (attribution) {
    attribution.title = locale['AttributionControl.ToggleAttribution'];
    attribution.setAttribute('aria-label', locale['AttributionControl.ToggleAttribution']);
  }
  for (const control of old) {
    if (map.hasControl(control)) map.removeControl(control);
  }
  const fresh = makeControls();
  for (const control of fresh) map.addControl(control, position);
  return fresh;
}

/** The "map unavailable" sentence {@link createMlMap} left in a container, rewritten in the current language. */
export function relabelUnavailable(container: HTMLElement | null | undefined, i18n: TranslationService): void {
  const p = container?.querySelector<HTMLElement>('.map-unavailable');
  if (p) p.textContent = i18n.t('map.unavailable');
}

function setText(root: HTMLElement, selector: string, text: string): void {
  const el = root.querySelector<HTMLElement>(selector);
  if (el) el.textContent = text;
}

/**
 * MapLibre GL 6 is ESM-only and no longer inlines its worker as a blob: URL. angular.json copies
 * `maplibre-gl-worker.mjs` and its `maplibre-gl-shared.mjs` chunk to `/maplibre/`, so the worker is
 * same-origin (CSP `worker-src 'self'`, no `blob:` needed).
 */
let workerConfigured = false;
function configureWorker(): void {
  if (workerConfigured) return;
  setWorkerUrl(new URL('maplibre/maplibre-gl-worker.mjs', document.baseURI).href);
  workerConfigured = true;
}

/**
 * A phone-sized touch screen: every map there sits in a page that scrolls (the Map page's 55vh map, the Plan map, the
 * 240px map in the middle of the house form), so one finger must scroll the page and two move the map (UX-007).
 */
export const PHONE_MAP_QUERY = '(pointer: coarse) and (max-width: 760px)';

/** Whether {@link PHONE_MAP_QUERY} matches now (false where `matchMedia` does not exist, as in unit tests). */
export function isPhoneMap(): boolean {
  return typeof matchMedia !== 'undefined' && matchMedia(PHONE_MAP_QUERY).matches;
}

/**
 * Creates a map with the app's style and translated control labels. MapLibre 6 requires WebGL 2 and
 * throws `GPUInitializationError` without it; then a translated, announced message replaces the map
 * and `null` is returned, so the rest of the page (house list, forms) keeps working.
 *
 * On a phone ({@link isPhoneMap}) every map gets `cooperativeGestures` unless the caller says otherwise, so no map
 * traps a one-finger scroll; MapLibre then shows the translated two-finger hint (`map.coopMobile`).
 *
 * Every map is north-up and flat (Android handover 33; Android 1.31 did the same, WCAG 2.5.1): no right-drag or
 * Ctrl-drag rotate or tilt, no two-finger twist or tilt, no Shift+arrow rotate or tilt. The zoom control is built
 * with `showCompass: false`, so a rotated map had no way back to north. Pan and zoom (mouse, touch, keyboard, +/-)
 * are unchanged. Option names and methods checked against maplibre-gl-js v6.10.0 (`src/ui/map.ts` MapOptions
 * `dragRotate`, `touchPitch`, `pitchWithRotate`; `TwoFingersTouchZoomRotateHandler.disableRotation`,
 * `KeyboardHandler.disableRotation`, which also stops Shift+Up/Down pitch).
 *
 * Every map shows India's boundaries as the Government of India does (`india-boundaries.ts`): on **every**
 * `style.load` (the first one, and each reload by {@link watchMapStyle}) the rules are applied to the freshly loaded
 * Liberty style before any tile is drawn. This listener is added here, before the page adds its own, so the page's
 * layers go on top of a style that already has them. Not `setStyle`'s `transformStyle`: in MapLibre 6.10
 * `Map._updateStyle` with a `transformStyle` first waits for the previous style's `style.load` if that style has not
 * loaded, and after an offline start it never does, so the retry when the connection returns would wait for ever.
 */
export function createMlMap(
  i18n: TranslationService,
  options: Omit<MapOptions, 'style' | 'locale' | 'attributionControl'>,
): MlMap | null {
  configureWorker();
  try {
    const map = new MlMap({
      style: MAP_STYLE_URL,
      attributionControl: { compact: true },
      locale: mapLocale(i18n),
      cooperativeGestures: isPhoneMap(),
      dragRotate: false,
      pitchWithRotate: false,
      touchPitch: false,
      ...options,
    });
    map.touchZoomRotate.disableRotation();
    map.keyboard.disableRotation();
    foldAttributionLater(map);
    map.on('style.load', () => applyIndiaBoundaries(boundaryTarget(map), inBoundariesUrl(document.baseURI)));
    return map;
  } catch (e) {
    if (!(e instanceof GPUInitializationError)) throw e;
    const container = options.container;
    if (container instanceof HTMLElement) {
      const msg = document.createElement('p');
      msg.className = 'map-unavailable';
      msg.setAttribute('role', 'status');
      msg.textContent = i18n.t('map.unavailable');
      container.replaceChildren(msg);
    }
    return null;
  }
}

/** How long a narrow map shows its credits in full, after its style first loads, before they fold into the (i) button. */
export const ATTRIBUTION_SHOW_MS = 5000;

/** MapLibre's own width for folding the credits (maplibre-gl-js v6.10.0 `AttributionControl._updateCompact`: 640). */
const COMPACT_MAX_WIDTH = 640;

/**
 * Folds a narrow map's credits into MapLibre's (i) button; true when it did. Nothing happens on a map wider than
 * 640px (desktop keeps MapLibre's behaviour) or when the credits are already folded.
 *
 * MapLibre opens compact credits when a map loads and folds them only on the first one-finger drag. On a phone every
 * map has cooperative gestures, so one finger scrolls the page and that drag never comes: the two-line credits
 * stayed open across the bottom of the Map page's map for good (owner report 2026-09-24, a 384px Android phone).
 * Folded, they are one tap away and read in full (the button toggles them; its name is `map.attribution`), which
 * OpenFreeMap and the OpenStreetMap attribution guidelines accept for small screens.
 */
export function foldAttribution(root: HTMLElement): boolean {
  if (root.offsetWidth > COMPACT_MAX_WIDTH) return false;
  const credits = root.querySelector<HTMLElement>('.maplibregl-ctrl-attrib.maplibregl-compact.maplibregl-compact-show');
  if (!credits) return false;
  credits.classList.remove('maplibregl-compact-show');
  return true;
}

/**
 * The credits are shown in full for {@link ATTRIBUTION_SHOW_MS} after the map's style first loads, then folded
 * ({@link foldAttribution}); sooner when the user zooms or moves the map (a pinch, the zoom buttons, the keyboard).
 */
function foldAttributionLater(map: MlMap): void {
  let folded = false;
  const fold = () => {
    if (folded) return;
    folded = true;
    foldAttribution(map.getContainer());
  };
  // From the first style.load, when the credits appear (`load` waits for every tile, which on a slow connection may
  // be a long time or never). `on` with a flag rather than `once`: only the first one counts.
  let loaded = false;
  map.on('style.load', () => {
    if (loaded) return;
    loaded = true;
    setTimeout(fold, ATTRIBUTION_SHOW_MS);
  });
  const onUserMove = (e: { originalEvent?: unknown }) => {
    if (e.originalEvent) fold();
  };
  map.on('zoomstart', onUserMove);
  map.on('movestart', onUserMove);
}

/**
 * The six map calls `applyIndiaBoundaries` makes, on a MapLibre map (`Map.getStyle`, `addSource`, `addLayer`,
 * `setLayoutProperty`, `setFilter`, `setLayerZoomRange`, as in maplibre-gl-js v6.10.0 `src/ui/map.ts`).
 */
function boundaryTarget(map: MlMap): BoundaryStyleTarget {
  return {
    getStyle: () => map.getStyle(),
    addSource: (id, source) => {
      map.addSource(id, source);
    },
    addLayer: (layer, beforeId) => {
      map.addLayer(layer, beforeId);
    },
    setVisibility: (layerId, visibility) => {
      map.setLayoutProperty(layerId, 'visibility', visibility);
    },
    setFilter: (layerId, filter) => {
      map.setFilter(layerId, filter);
    },
    setZoomRange: (layerId, minzoom, maxzoom) => {
      map.setLayerZoomRange(layerId, minzoom, maxzoom);
    },
  };
}

/** What {@link watchMapStyle} hands back: stop listening, and ask for the style again now. */
export interface MapStyleWatch {
  dispose(): void;
  retry(): void;
}

/**
 * Tells the page whether the map can actually be drawn, and brings it back when the connection returns.
 *
 * The style comes from tiles.openfreemap.org, which `sw.js` deliberately never caches (third-party tiles, their
 * terms, and the storage they would take). Offline the style request fails, MapLibre never loads a style, no
 * house layer can be added, and the map region stays blank grey with nothing saying why. So:
 *
 *  * `onChange(false)` when the style has not loaded and either the request failed (`error` before `style.load`)
 *    or the browser says it is offline; the page then shows an overlay that explains it and offers the ways that
 *    work without a map (the list, "use my location", typed coordinates);
 *  * `onChange(true)` on every `style.load`;
 *  * on the window `online` event, while no style has loaded, the style is requested again with `diff: false`
 *    (a fresh `Style`, not a diff against the one that never loaded). The page adds its sources and layers on
 *    `style.load`, not on the one-off `load`, so they come back with it, and so do India's boundaries
 *    ({@link createMlMap}).
 *
 * Tile errors after the style has loaded are left to MapLibre: the pins still draw on the background.
 */
export function watchMapStyle(map: MlMap, onChange: (available: boolean) => void): MapStyleWatch {
  let loaded = false;
  const reload = () => {
    if (loaded) return;
    map.setStyle(MAP_STYLE_URL, { diff: false });
  };
  map.on('style.load', () => {
    loaded = true;
    onChange(true);
  });
  map.on('error', () => {
    if (!loaded) onChange(false);
  });
  const onOffline = () => {
    if (!loaded) onChange(false);
  };
  if (typeof window !== 'undefined') {
    window.addEventListener('offline', onOffline);
    window.addEventListener('online', reload);
  }
  if (typeof navigator !== 'undefined' && navigator.onLine === false) onChange(false);
  return {
    dispose: () => {
      if (typeof window === 'undefined') return;
      window.removeEventListener('offline', onOffline);
      window.removeEventListener('online', reload);
    },
    retry: reload,
  };
}
