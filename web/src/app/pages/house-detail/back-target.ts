/**
 * Where the house page's "Back" goes, decided once when the page is created (unit tested without the router).
 *
 * "Back" returns to the page the house was opened from — the filtered list, Compare, an Ask citation, a Plan stop —
 * with `Location.back()`, which restores that page's URL (the list's `?q=&status=&sort=`) and lets the app shell put
 * its scroll position back. That is only safe when the previous history entry is known to be that page:
 *
 *  * the house was reached by an in-app navigation (`imperative`), so the router's previous successful navigation is
 *    the entry right behind this one;
 *  * not by Back or Forward (`popstate`): the router's "previous navigation" is then the page just left, which may be
 *    **ahead** in history, and `Location.back()` would go one entry too far, or out of the app when the house was the
 *    tab's first entry;
 *  * a new house saved for the first time replaces `/houses/new` with `/houses/<id>` (`replaceUrl`), so the entry
 *    behind it is whatever was behind the form: the form hands its own decision on in the navigation state
 *    ({@link HOUSE_BACK_STATE}), and without one (a bookmarked form) the page falls back to the map link.
 *
 * Everything else — a bookmark, a shared link, a reload — has no in-app page behind it: "Back to map" is then a link
 * to the list, carrying the list's last search and filter (ListReturn).
 */
export type BackKey = 'house.back' | 'house.backGeneric';

/** Navigation-state key under which the new-house form hands its Back decision to the saved house's page. */
export const HOUSE_BACK_STATE = 'houseBack';

export interface BackArrival {
  /** How the navigation to this house started: `imperative` (a link, code), `popstate` (Back, Forward), … */
  trigger: string;
  /** Path (no query or fragment) of the router's previous successful navigation; null when there is none. */
  previousPath: string | null;
  /** The {@link HOUSE_BACK_STATE} value in this navigation's state, if any. */
  handedBackKey: unknown;
}

export interface BackTarget {
  /** True: "Back" is `Location.back()`. False: it is a link to the list. */
  toPrevious: boolean;
  /** "Back to map" when the page behind is the list, "Back" for anything else. */
  key: BackKey;
}

const TO_MAP: BackTarget = { toPrevious: false, key: 'house.back' };

export function backTarget(arrival: BackArrival): BackTarget {
  const previous = arrival.previousPath;
  if (arrival.trigger === 'popstate' || previous === null) return TO_MAP;
  if (previous === '/houses/new') {
    // Just saved for the first time: the form's own decision, since its entry was replaced.
    return isBackKey(arrival.handedBackKey) ? { toPrevious: true, key: arrival.handedBackKey } : TO_MAP;
  }
  return { toPrevious: true, key: previous === '/' ? 'house.back' : 'house.backGeneric' };
}

function isBackKey(value: unknown): value is BackKey {
  return value === 'house.back' || value === 'house.backGeneric';
}

/**
 * Where the page goes after Delete, or Discard on a new house: somewhere the removed page cannot be reached again
 * with Back.
 *
 *  * `back`: the page behind this one is the list, so the house's history entry is left behind with
 *    `Location.back()`, which also puts the list's scroll position back (a popstate). Pressing Forward is the only
 *    way to it, as in any browser after going Back.
 *  * `replace`: anything else (Compare, an answer, a route, no in-app page at all). The list replaces this entry
 *    (`replaceUrl`), so system Back from the list does not land on "House not found" or on a fresh empty form.
 *    Android pops the back stack in both cases.
 */
export type RemovalExit = 'back' | 'replace';

export function exitAfterRemoval(target: BackTarget): RemovalExit {
  return target.toPrevious && target.key === 'house.back' ? 'back' : 'replace';
}
