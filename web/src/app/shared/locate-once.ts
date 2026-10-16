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

/**
 * The options every "Use my location" in the app asks with: a precise fix, at most 15 s of waiting, and a fix up to a
 * minute old is good enough (the user is standing at the house).
 */
export const LOCATE_OPTIONS: PositionOptions = { enableHighAccuracy: true, timeout: 15_000, maximumAge: 60_000 };

/** What a page does with one location request, and how it tells that it has gone away meanwhile. */
export interface LocateHandlers {
  /**
   * True once the page that asked has been destroyed. Pages set their `destroyed` flag first thing in `ngOnDestroy`
   * and pass `() => this.destroyed`. It is read when the answer arrives, not when the question is asked.
   */
  readonly gone: () => boolean;
  /** The position was found and the page is still there. */
  readonly found: (position: GeolocationPosition) => void;
  /** The request failed (blocked, timed out, unavailable) and the page is still there. */
  readonly failed: (error: GeolocationPositionError) => void;
}

/**
 * One `getCurrentPosition` whose answer is dropped when the page that asked is gone. The answer can take a
 * while: the browser's permission prompt waits for the user, and a fix can take up to {@link LOCATE_OPTIONS}' 15 s.
 * A user who leaves the page in that time must not be sent to a new-house form they did not ask for (Map's "Add at my
 * location"), and a map that has been removed must not be moved (Plan's start, the house form's pin). The same
 * `if (gone) return` runs first in both callbacks, success and failure alike (coordinator final review, 2026-09-23).
 *
 * `geolocation` is a parameter for the unit test; pages use the browser's.
 */
export function locateOnce(handlers: LocateHandlers, geolocation: Geolocation = navigator.geolocation): void {
  const { gone, found, failed } = handlers;
  geolocation.getCurrentPosition(
    (position) => {
      if (gone()) return;
      found(position);
    },
    (error) => {
      if (gone()) return;
      failed(error);
    },
    LOCATE_OPTIONS,
  );
}

/** *Have I been here?* waits at most this long for a fix of at most this accuracy (docs/11 5.27.13: 15 s, the 50 m Hunt gate). */
export const LOCATE_BEST = { maxWaitMs: 15_000, maxAccuracyM: 50 } as const;

/** What the place check does with its one fresh fix. */
export interface LocateBestHandlers {
  /** True once the page that asked has been destroyed; read when an answer arrives (as {@link LocateHandlers.gone}). */
  readonly gone: () => boolean;
  /**
   * The first fix of {@link LOCATE_BEST}`.maxAccuracyM` or better, or at `maxWaitMs` the best fix so far (which may be
   * worse: the check then answers *not precise enough*). Called once.
   */
  readonly found: (position: GeolocationPosition) => void;
  /** No fix at all by `maxWaitMs`. Called once. */
  readonly timedOut: () => void;
  /** The permission was refused (or the browser cannot locate at all). Called once, at once. */
  readonly failed: (error: GeolocationPositionError) => void;
}

/**
 * One fresh fix for the place check (docs/11 5.27.13, docs/03 section 6.2b rows 14 and 16): `watchPosition` with
 * `{ enableHighAccuracy: true, maximumAge: 0 }`, started inside the button's click (a user gesture), stopped
 * (`clearWatch`) at the first fix of 50 m or better or after 15 s, when the best fix so far decides. A fix is used for
 * that one answer and never stored. Returns a function that cancels the wait (the *Cancel* button): after it nothing is
 * reported. `geolocation` is a parameter for the unit test; pages use the browser's.
 */
export function locateBest(handlers: LocateBestHandlers, geolocation: Geolocation = navigator.geolocation): () => void {
  let done = false;
  let best: GeolocationPosition | null = null;
  let watchId: number | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const stop = () => {
    done = true;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    if (watchId !== null) geolocation.clearWatch(watchId);
    watchId = null;
  };
  watchId = geolocation.watchPosition(
    (position) => {
      if (done) return;
      if (handlers.gone()) {
        stop();
        return;
      }
      if (position.coords.accuracy <= LOCATE_BEST.maxAccuracyM) {
        stop();
        handlers.found(position);
      } else if (best === null || position.coords.accuracy < best.coords.accuracy) {
        best = position;
      }
    },
    (error) => {
      if (done) return;
      if (handlers.gone()) {
        stop();
        return;
      }
      // Only a refused permission ends the wait early; an unavailable position or a timeout may still be followed by a fix.
      if (error.code === 1) {
        stop();
        handlers.failed(error);
      }
    },
    { enableHighAccuracy: true, maximumAge: 0 },
  );
  timer = setTimeout(() => {
    timer = null;
    if (done) return;
    const gone = handlers.gone();
    stop();
    if (gone) return;
    if (best !== null) handlers.found(best);
    else handlers.timedOut();
  }, LOCATE_BEST.maxWaitMs);
  return stop;
}
