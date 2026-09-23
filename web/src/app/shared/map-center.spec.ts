import { describe, expect, it } from 'vitest';
import {
  COUNTRY_VIEW,
  isCountryCenter,
  locationErrorKey,
  parseCoordinate,
  parseMapView,
  startPointFromView,
} from './map-center';

describe('parseCoordinate', () => {
  it('reads plain, signed and comma-decimal numbers within the limit', () => {
    expect(parseCoordinate('12.971599', 90)).toBe(12.971599);
    expect(parseCoordinate(' -33.86 ', 90)).toBe(-33.86);
    expect(parseCoordinate('−33.86', 90)).toBe(-33.86);
    expect(parseCoordinate('77,5946', 180)).toBe(77.5946);
    expect(parseCoordinate('180', 180)).toBe(180);
  });

  it('refuses text, out-of-range values and numbers with junk after them', () => {
    expect(parseCoordinate('', 90)).toBeNull();
    expect(parseCoordinate('abc', 90)).toBeNull();
    expect(parseCoordinate('12abc', 90)).toBeNull();
    expect(parseCoordinate('91', 90)).toBeNull();
    expect(parseCoordinate('-180.5', 180)).toBeNull();
    expect(parseCoordinate('1,234.5', 180)).toBeNull();
  });
});

describe('locationErrorKey', () => {
  it('tells a blocked permission apart from a timeout or an unavailable position', () => {
    expect(locationErrorKey({ code: 1 })).toBe('house.locationDenied');
    expect(locationErrorKey({ code: 2 })).toBe('house.locationFailed');
    expect(locationErrorKey({ code: 3 })).toBe('house.locationFailed');
    expect(locationErrorKey(null)).toBe('house.locationFailed');
  });
});

/**
 * A stored view that still equals the placeholder is not a user choice: the map page saves COUNTRY_VIEW on its first
 * layout (MapLibre's resize fires moveend), before the user has moved anything.
 */
describe('stored map view', () => {
  const stored = (v: object) => JSON.stringify(v);

  it('reads a view the user moved to', () => {
    const moved = { lat: 12.9716, lon: 77.5946, zoom: 13 };
    expect(parseMapView(stored(moved))).toEqual(moved);
  });

  it('refuses the untouched country view, as the map page saves it on first layout', () => {
    expect(parseMapView(stored(COUNTRY_VIEW))).toBeNull();
    expect(parseMapView(stored({ lat: 20.5937, lon: 78.9629, zoom: 4 }))).toBeNull();
    // Float noise from the map's own centre, rounded to 6 decimals, is still the placeholder.
    expect(parseMapView(stored({ lat: 20.593701, lon: 78.962899, zoom: 4 }))).toBeNull();
  });

  it('keeps a view panned away from the country centre, even at country zoom', () => {
    const panned = { lat: 21.1458, lon: 79.0882, zoom: 4 };
    expect(parseMapView(stored(panned))).toEqual(panned);
  });

  it('refuses the world view, junk and out-of-range values', () => {
    expect(parseMapView(null)).toBeNull();
    expect(parseMapView('not json')).toBeNull();
    expect(parseMapView(stored({ lat: 10, lon: 10, zoom: 1 }))).toBeNull();
    expect(parseMapView(stored({ lat: 91, lon: 10, zoom: 10 }))).toBeNull();
  });

  it('never gives the placeholder centre as a starting point, at any zoom', () => {
    expect(isCountryCenter(COUNTRY_VIEW.lat, COUNTRY_VIEW.lon)).toBe(true);
    // Zoomed in with the + button: the map page keeps this view, but Plan and a new house do not start there.
    const zoomedOnly = parseMapView(stored({ lat: 20.5937, lon: 78.9629, zoom: 6 }));
    expect(zoomedOnly).toEqual({ lat: 20.5937, lon: 78.9629, zoom: 6 });
    expect(startPointFromView(zoomedOnly)).toBeNull();
    expect(startPointFromView(null)).toBeNull();
    const moved = { lat: 12.9716, lon: 77.5946, zoom: 13 };
    expect(startPointFromView(moved)).toBe(moved);
  });
});
