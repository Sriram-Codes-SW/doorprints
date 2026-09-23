/**
 * The last place the user looked at on the map, remembered per browser (a view preference, not data).
 *
 * The map page stores it after every move; the new-house form starts there when it was opened without a position
 * (from the share target, or a bookmarked `/houses/new`), instead of at latitude 0, longitude 0 in the Gulf of
 * Guinea. localStorage, wrapped: it may be blocked (private mode), and then the caller falls back further.
 *
 * A stored view that still equals the {@link COUNTRY_VIEW} placeholder is never read as a user choice: the map page
 * saves it on its first layout. parseMapView refuses it outright, and a starting point (Plan's start, a new house's
 * pin) goes through {@link loadStartPoint}, which also refuses the placeholder centre at another zoom.
 */
export interface MapView {
  lat: number;
  lon: number;
  zoom: number;
}

export const MAP_VIEW_KEY = 'hh.mapView';

/**
 * Where a map starts when nothing says where to look: the whole of India at country zoom, the market this app is
 * built for (BHK, rupees, hi/ta/te). Used by the map page before any house is loaded, and by a new house with no
 * position (a starting view only: that house cannot be saved until the pin is put).
 */
export const COUNTRY_VIEW: Readonly<MapView> = { lat: 20.5937, lon: 78.9629, zoom: 4 };

/** Parses a stored view; null for anything that is not a usable position. */
export function parseMapView(raw: string | null): MapView | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== 'object') return null;
    const { lat, lon, zoom } = value as Partial<Record<keyof MapView, unknown>>;
    if (typeof lat !== 'number' || typeof lon !== 'number' || typeof zoom !== 'number') return null;
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || !Number.isFinite(zoom)) return null;
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || zoom < 0 || zoom > 24) return null;
    // The world view the map opens at before anything is loaded says nothing about where the user hunts.
    if (zoom < 3) return null;
    const view = { lat, lon, zoom };
    // Nor does the untouched country view: the map page saves it on its first layout (see isUntouchedCountryView).
    return isUntouchedCountryView(view) ? null : view;
  } catch {
    return null;
  }
}

/**
 * True when a centre is still the {@link COUNTRY_VIEW} placeholder's. MapLibre reports the map page's first fit to
 * its box (`Map.resize()` from the ResizeObserver) as a move, so `moveend` saves the placeholder before the user has
 * touched the map (web README, TC-S-19). A stored value there says "saved because the library fired an event on first
 * layout", not "saved because the user moved". The tolerance only absorbs float and 6-decimal rounding; any pan moves
 * the centre by far more.
 */
export function isCountryCenter(lat: number, lon: number): boolean {
  return Math.abs(lat - COUNTRY_VIEW.lat) < 1e-4 && Math.abs(lon - COUNTRY_VIEW.lon) < 1e-4;
}

/** The placeholder exactly as the map page first saves it: its centre at its zoom (saved to one decimal). */
export function isUntouchedCountryView(view: MapView): boolean {
  return isCountryCenter(view.lat, view.lon) && Math.abs(view.zoom - COUNTRY_VIEW.zoom) < 0.05;
}

/**
 * A stored view as a starting **point** (Plan's start, a new house's pin), or null. Stricter than
 * {@link parseMapView}: a view still centred on the placeholder is refused at any zoom, because the + and - buttons
 * zoom about the centre. A user who only zoomed in has still not chosen that spot in the middle of India; the view is
 * kept for the map page itself, but a start or a pin is never put there.
 */
export function startPointFromView(view: MapView | null): MapView | null {
  return view && !isCountryCenter(view.lat, view.lon) ? view : null;
}

/** The last map view as a starting point, see {@link startPointFromView}. */
export function loadStartPoint(): MapView | null {
  return startPointFromView(loadMapView());
}

export function loadMapView(): MapView | null {
  try {
    return typeof localStorage === 'undefined' ? null : parseMapView(localStorage.getItem(MAP_VIEW_KEY));
  } catch {
    return null;
  }
}

export function saveMapView(view: MapView): void {
  try {
    localStorage.setItem(MAP_VIEW_KEY, JSON.stringify(view));
  } catch {
    // Storage blocked: the form falls back to the newest house, then to the country view.
  }
}

/**
 * Reads a typed latitude or longitude, or null when it is not a number within ±`limit` (90 or 180).
 *
 * The house page and Plan both use `type="text" inputmode="decimal"` (iOS has no minus key on a decimal pad for
 * `type="number"`), so the text is parsed here, the same way on both screens: a typographic minus (−) or dash is
 * read as a minus, one decimal comma as a point, and anything with other characters ("12abc", "1,234.5") is
 * refused rather than silently cut short the way `parseFloat` would.
 */
export function parseCoordinate(raw: string, limit: number): number | null {
  const text = raw.trim().replace(/[−–]/g, '-').replace(',', '.');
  if (!/^[-+]?(\d+(\.\d*)?|\.\d+)$/.test(text)) return null;
  const value = Number(text);
  return Number.isFinite(value) && Math.abs(value) <= limit ? value : null;
}

/**
 * Which message a failed location request gets: "blocked for this site" when the user or the browser refused the
 * permission (`GeolocationPositionError.PERMISSION_DENIED`, code 1), which only the site settings can undo, and the
 * general "could not get your location" for a timeout or an unavailable position, where trying again may work.
 */
export function locationErrorKey(err: { code?: number } | null | undefined): 'house.locationDenied' | 'house.locationFailed' {
  return err?.code === 1 ? 'house.locationDenied' : 'house.locationFailed';
}

/**
 * Forgets the remembered view. Part of "Remove all data": the last place looked at is where the user is house
 * hunting, which is not something to leave behind on a shared computer.
 */
export function clearMapView(): void {
  try {
    localStorage.removeItem(MAP_VIEW_KEY);
  } catch {
    // Storage blocked: nothing was stored either.
  }
}
