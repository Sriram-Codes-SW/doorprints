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
