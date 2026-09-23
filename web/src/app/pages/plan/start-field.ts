/** The ids of Plan's two start fields, in page order. */
export type StartFieldId = 'start-lat' | 'start-lon';

/** The coordinates typed while no start is set yet, kept until both are there. Null: nothing usable in that field. */
export interface TypedStart {
  readonly lat: number | null;
  readonly lon: number | null;
}

export const NO_TYPED_START: TypedStart = { lat: null, lon: null };

/**
 * One start field changed while no start is set yet. `value` is the field's new coordinate, or null when its text is
 * not one (a typo, or the field was cleared). An invalid or empty field drops its kept value, so a later commit
 * never applies, or puts back into the field, a coordinate the user has changed or removed. `commit` is the start to
 * set once both fields hold a coordinate (the kept pair is then emptied), else null.
 */
export function nextTypedStart(
  typed: TypedStart,
  axis: 'lat' | 'lon',
  value: number | null,
): { typed: TypedStart; commit: { lat: number; lon: number } | null } {
  const next: TypedStart = axis === 'lat' ? { lat: value, lon: typed.lon } : { lat: typed.lat, lon: value };
  if (next.lat === null || next.lon === null) return { typed: next, commit: null };
  return { typed: NO_TYPED_START, commit: { lat: next.lat, lon: next.lon } };
}

/**
 * Which start field "Plan route" focuses when the start cannot be used (docs/10 §11.7, W2): the first one, in page
 * order, that the user still has to fix. A field needs fixing when its text is not a coordinate (`invalid`, which
 * also covers a field typed in and then cleared), or, while no start is set yet, when nothing usable was typed in it
 * (`typed` is null). So with the latitude typed and the longitude empty, focus goes to the longitude. `typed` must
 * come from {@link nextTypedStart}, which drops a field's kept value when that field turns invalid or empty.
 *
 * `typed` only counts while `startSet` is false: once a start is set (a map click, "Use my location", the newest
 * house, or both fields typed), both fields show a coordinate and only `invalid` can make one need fixing.
 */
export function startFieldToFix(
  invalid: { readonly lat: boolean; readonly lon: boolean },
  typed: TypedStart,
  startSet: boolean,
): StartFieldId {
  if (invalid.lat || (!startSet && typed.lat === null)) return 'start-lat';
  if (invalid.lon || (!startSet && typed.lon === null)) return 'start-lon';
  // Not reached when called for a start that cannot be used; the first field is the safe answer.
  return 'start-lat';
}
