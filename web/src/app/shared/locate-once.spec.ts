import { describe, expect, it, vi } from 'vitest';
import { LOCATE_OPTIONS, locateOnce } from './locate-once';

/** A browser Geolocation that keeps the request open until the test answers it, like a pending permission prompt. */
class PendingGeolocation implements Geolocation {
  success: PositionCallback | null = null;
  error: PositionErrorCallback | null = null;
  options: PositionOptions | undefined;
  calls = 0;

  getCurrentPosition(success: PositionCallback, error?: PositionErrorCallback | null, options?: PositionOptions): void {
    this.calls += 1;
    this.success = success;
    this.error = error ?? null;
    this.options = options;
  }

  watchPosition(): number {
    throw new Error('not used');
  }

  clearWatch(): void {
    throw new Error('not used');
  }

  answer(lat: number, lon: number): void {
    this.success?.({ coords: { latitude: lat, longitude: lon, accuracy: 10 }, timestamp: 0 } as unknown as GeolocationPosition);
  }

  refuse(code: number): void {
    this.error?.({ code, message: '', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as unknown as GeolocationPositionError);
  }
}

/**
 * Map's "Add at my location", Plan's "Use my location" and the house form's "Use my location" (coordinator final
 * review, 2026-09-23): a page left while the permission prompt or the 15 s fix is pending never acts on the answer.
 * Before this, Map opened a new-house form the user had not asked for, on whatever page they had gone to.
 */
describe('locateOnce', () => {
  it('asks once, with the app-wide options', () => {
    const geo = new PendingGeolocation();
    locateOnce({ gone: () => false, found: vi.fn(), failed: vi.fn() }, geo);
    expect(geo.calls).toBe(1);
    expect(geo.options).toEqual(LOCATE_OPTIONS);
    expect(LOCATE_OPTIONS).toEqual({ enableHighAccuracy: true, timeout: 15_000, maximumAge: 60_000 });
  });

  it('hands the position to a page that is still there', () => {
    const geo = new PendingGeolocation();
    const found = vi.fn();
    const failed = vi.fn();
    locateOnce({ gone: () => false, found, failed }, geo);
    geo.answer(12.97, 77.59);
    expect(found).toHaveBeenCalledTimes(1);
    expect(found.mock.calls[0][0].coords.latitude).toBe(12.97);
    expect(failed).not.toHaveBeenCalled();
  });

  it('hands a failure to a page that is still there', () => {
    const geo = new PendingGeolocation();
    const found = vi.fn();
    const failed = vi.fn();
    locateOnce({ gone: () => false, found, failed }, geo);
    geo.refuse(1);
    expect(failed).toHaveBeenCalledTimes(1);
    expect(failed.mock.calls[0][0].code).toBe(1);
    expect(found).not.toHaveBeenCalled();
  });

  it('drops the position when the page was left while the prompt or the fix was pending', () => {
    const geo = new PendingGeolocation();
    // The page's own flag: false when it asks, set by ngOnDestroy before the answer arrives.
    let destroyed = false;
    const found = vi.fn();
    const failed = vi.fn();
    locateOnce({ gone: () => destroyed, found, failed }, geo);
    destroyed = true;
    geo.answer(12.97, 77.59);
    expect(found).not.toHaveBeenCalled();
    expect(failed).not.toHaveBeenCalled();
  });

  it('drops a failure too, so a left page shows or announces nothing', () => {
    const geo = new PendingGeolocation();
    let destroyed = false;
    const found = vi.fn();
    const failed = vi.fn();
    locateOnce({ gone: () => destroyed, found, failed }, geo);
    destroyed = true;
    geo.refuse(3);
    expect(failed).not.toHaveBeenCalled();
    expect(found).not.toHaveBeenCalled();
  });

  it('reads the flag when the answer arrives, not when the question is asked', () => {
    const geo = new PendingGeolocation();
    const gone = vi.fn(() => false);
    locateOnce({ gone, found: vi.fn(), failed: vi.fn() }, geo);
    expect(gone).not.toHaveBeenCalled();
    geo.answer(0.5, 0.5);
    expect(gone).toHaveBeenCalledTimes(1);
  });
});
