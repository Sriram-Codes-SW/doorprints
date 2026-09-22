import { GPUInitializationError, Map as MlMap, type MapOptions, setWorkerUrl } from 'maplibre-gl';
import { TranslationService } from '../i18n/translation.service';

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
  };
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
 * Creates a map with the app's style and translated control labels. MapLibre 6 requires WebGL 2 and
 * throws `GPUInitializationError` without it; then a translated, announced message replaces the map
 * and `null` is returned, so the rest of the page (house list, forms) keeps working.
 */
export function createMlMap(
  i18n: TranslationService,
  options: Omit<MapOptions, 'style' | 'locale' | 'attributionControl'>,
): MlMap | null {
  configureWorker();
  try {
    return new MlMap({
      style: MAP_STYLE_URL,
      attributionControl: { compact: true },
      locale: mapLocale(i18n),
      ...options,
    });
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
