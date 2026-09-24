/**
 * How much of the house list shows under the phone map at the top of the page, in px: from the top of the list's
 * heading to the foot of its counters, so "Your houses" and every counter, number and caption, are on screen above
 * the bottom bar before any scrolling (owner report 2026-09-24: on a 384x615 Android phone the counters' captions were
 * under the bottom bar). Without counters (still reading the houses) the heading alone.
 *
 * `head` is the heading block's box and `stats` the counters' (null when there are none), both from
 * getBoundingClientRect(); the result is rounded up so a fraction of a pixel never tucks a caption under the bar.
 */
export function listPeek(head: { top: number; bottom: number }, stats: { bottom: number } | null): number {
  const bottom = stats ? Math.max(stats.bottom, head.bottom) : head.bottom;
  return Math.max(0, Math.ceil(bottom - head.top));
}
