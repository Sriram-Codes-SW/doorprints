/**
 * The padding for "Show all" (and the first framing of all houses), so no house ends up under something drawn over
 * the map. Pure, so it is unit tested without MapLibre.
 */

/** Around the houses on every side: room for a marker and a popup. */
export const FIT_PADDING = 60;

/**
 * MapLibre's control column on phones (zoom, my location, attribution in the bottom-right corner): a 44px control
 * plus its 10px margin from the map's edge.
 */
export const CONTROL_COLUMN_W = 54;

/** At least this much of the map is left between opposite paddings, so fitBounds always has room to fit into. */
export const MIN_FIT_SPAN = 48;

/** The same shape as MapLibre's `PaddingOptions` (all four sides set). */
export interface FitPadding {
  top: number;
  bottom: number;
  left: number;
  right: number;
}

/**
 * On a phone (`bottomControls`: MapLibre's controls in the bottom-right corner, over the map's bottom row) the bottom
 * row — the legend and the action column, measured as `stackHeight` (the same height MapPage writes to
 * `--map-stack-h`) — and the control column on the right are kept clear as well, so the southernmost house is not left
 * under the legend or the "Show all" button itself (UX lead audit, round 3). Elsewhere the padding is even.
 *
 * MapLibre does not move at all when the paddings leave no room ("Map cannot fit within canvas"), so on a very small
 * map (a phone in landscape at 400% zoom) opposite paddings are scaled down together until {@link MIN_FIT_SPAN} is
 * left. A map that is not laid out yet (0 × 0) gets the paddings unchanged.
 */
export function fitPadding(bottomControls: boolean, stackHeight: number, mapWidth: number, mapHeight: number): FitPadding {
  const stack = Number.isFinite(stackHeight) ? Math.max(0, Math.ceil(stackHeight)) : 0;
  const top = FIT_PADDING;
  const left = FIT_PADDING;
  const right = bottomControls ? FIT_PADDING + CONTROL_COLUMN_W : FIT_PADDING;
  const bottom = bottomControls ? FIT_PADDING + stack : FIT_PADDING;
  const [t, b] = shrink(top, bottom, mapHeight);
  const [l, r] = shrink(left, right, mapWidth);
  return { top: t, bottom: b, left: l, right: r };
}

/** Scales two opposite paddings down in proportion when they would leave less than {@link MIN_FIT_SPAN} of `size`. */
function shrink(a: number, b: number, size: number): [number, number] {
  if (!(size > 0)) return [a, b];
  const room = Math.max(0, size - MIN_FIT_SPAN);
  const sum = a + b;
  if (sum <= room) return [a, b];
  const k = room / sum;
  return [Math.floor(a * k), Math.floor(b * k)];
}
