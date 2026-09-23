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
