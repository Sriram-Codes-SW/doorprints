/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LOCATE_BEST, LOCATE_OPTIONS, locateBest, locateOnce } from './locate-once';

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

/** A Geolocation whose `watchPosition` the test drives: fixes and errors on demand, and what was cleared. */
class WatchingGeolocation implements Geolocation {
  success: PositionCallback | null = null;
  error: PositionErrorCallback | null = null;
  options: PositionOptions | undefined;
  watches = 0;
  cleared: number[] = [];

  getCurrentPosition(): void {
    throw new Error('not used');
  }

  watchPosition(success: PositionCallback, error?: PositionErrorCallback | null, options?: PositionOptions): number {
    this.watches += 1;
    this.success = success;
    this.error = error ?? null;
    this.options = options;
    return 7;
  }

  clearWatch(id: number): void {
    this.cleared.push(id);
  }

  fix(accuracy: number, lat = 12.97): void {
    this.success?.({ coords: { latitude: lat, longitude: 77.59, accuracy }, timestamp: 0 } as unknown as GeolocationPosition);
  }

  fail(code: number): void {
    this.error?.({ code, message: '', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as unknown as GeolocationPositionError);
  }
}

/**
 * *Have I been here?* asks for one fresh fix (docs/11 5.27.13): a watch, stopped at the first fix of 50 m or better or
 * at 15 s, when the best fix so far decides. Never a stale fix, never kept.
 */
describe('locateBest', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const handlers = (gone = false) => ({ gone: () => gone, found: vi.fn(), timedOut: vi.fn(), failed: vi.fn() });

  it('watches with a fresh precise fix (no cached position) and the 15 s, 50 m limits', () => {
    const geo = new WatchingGeolocation();
    locateBest(handlers(), geo);
    expect(geo.watches).toBe(1);
    expect(geo.options).toEqual({ enableHighAccuracy: true, maximumAge: 0 });
    expect(LOCATE_BEST).toEqual({ maxWaitMs: 15_000, maxAccuracyM: 50 });
  });

  it('uses the first fix of 50 m or better, stops the watch and ignores later fixes', () => {
    const geo = new WatchingGeolocation();
    const h = handlers();
    locateBest(h, geo);
    geo.fix(50);
    expect(h.found).toHaveBeenCalledTimes(1);
    expect(h.found.mock.calls[0][0].coords.accuracy).toBe(50);
    expect(geo.cleared).toEqual([7]);
    geo.fix(5);
    vi.advanceTimersByTime(20_000);
    expect(h.found).toHaveBeenCalledTimes(1);
  });

  it('ignores a worse fix while a better one may come, and at 15 s the best so far decides', () => {
    const geo = new WatchingGeolocation();
    const h = handlers();
    locateBest(h, geo);
    geo.fix(90, 1);
    geo.fix(60, 2);
    geo.fix(75, 3);
    vi.advanceTimersByTime(14_999);
    expect(h.found).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(h.found).toHaveBeenCalledTimes(1);
    expect(h.found.mock.calls[0][0].coords.accuracy).toBe(60);
    expect(h.timedOut).not.toHaveBeenCalled();
    expect(geo.cleared).toEqual([7]);
  });

  it('says timed out when nothing came at all, and treats an unavailable position as waiting', () => {
    const geo = new WatchingGeolocation();
    const h = handlers();
    locateBest(h, geo);
    geo.fail(2);
    vi.advanceTimersByTime(15_000);
    expect(h.timedOut).toHaveBeenCalledTimes(1);
    expect(h.found).not.toHaveBeenCalled();
    expect(h.failed).not.toHaveBeenCalled();
  });

  it('reports a refused permission at once and stops', () => {
    const geo = new WatchingGeolocation();
    const h = handlers();
    locateBest(h, geo);
    geo.fail(1);
    expect(h.failed).toHaveBeenCalledTimes(1);
    expect(geo.cleared).toEqual([7]);
    vi.advanceTimersByTime(20_000);
    expect(h.timedOut).not.toHaveBeenCalled();
  });

  it('drops every answer once the page is gone, and still stops the watch', () => {
    const geo = new WatchingGeolocation();
    const h = handlers(true);
    locateBest(h, geo);
    geo.fix(10);
    expect(h.found).not.toHaveBeenCalled();
    expect(geo.cleared).toEqual([7]);
    const geo2 = new WatchingGeolocation();
    const h2 = handlers(true);
    locateBest(h2, geo2);
    geo2.fail(1);
    vi.advanceTimersByTime(20_000);
    expect(h2.failed).not.toHaveBeenCalled();
    expect(h2.timedOut).not.toHaveBeenCalled();
  });

  it('can be cancelled: the watch and the timer stop and nothing is reported', () => {
    const geo = new WatchingGeolocation();
    const h = handlers();
    const cancel = locateBest(h, geo);
    geo.fix(90);
    cancel();
    expect(geo.cleared).toEqual([7]);
    vi.advanceTimersByTime(20_000);
    expect(h.found).not.toHaveBeenCalled();
    expect(h.timedOut).not.toHaveBeenCalled();
    cancel();
    expect(geo.cleared).toEqual([7]);
  });
});
