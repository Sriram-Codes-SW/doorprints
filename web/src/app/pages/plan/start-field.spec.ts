import { describe, expect, it } from 'vitest';
import { NO_TYPED_START, nextTypedStart, startFieldToFix } from './start-field';

const OK = { lat: false, lon: false };
const NONE = { lat: null, lon: null };

/** W2 (docs/10 §11.7): "Plan route" with an unusable start focuses the first start field the user still has to fix. */
describe('startFieldToFix', () => {
  it('focuses the latitude when both fields are empty', () => {
    expect(startFieldToFix(OK, NONE, false)).toBe('start-lat');
  });

  it('focuses the longitude when the latitude is typed and the longitude is empty', () => {
    expect(startFieldToFix(OK, { lat: 12.97, lon: null }, false)).toBe('start-lon');
  });

  it('focuses the latitude when the longitude is typed and the latitude is empty', () => {
    expect(startFieldToFix(OK, { lat: null, lon: 77.59 }, false)).toBe('start-lat');
  });

  it('focuses the invalid field first, in page order', () => {
    expect(startFieldToFix({ lat: true, lon: false }, { lat: null, lon: 77.59 }, false)).toBe('start-lat');
    expect(startFieldToFix({ lat: false, lon: true }, { lat: 12.97, lon: null }, false)).toBe('start-lon');
    expect(startFieldToFix({ lat: true, lon: true }, NONE, false)).toBe('start-lat');
  });

  it('focuses an empty latitude before an invalid longitude', () => {
    expect(startFieldToFix({ lat: false, lon: true }, NONE, false)).toBe('start-lat');
  });

  it('ignores what was typed once a start is set: only an invalid field needs fixing', () => {
    expect(startFieldToFix({ lat: false, lon: true }, NONE, true)).toBe('start-lon');
    expect(startFieldToFix({ lat: true, lon: false }, NONE, true)).toBe('start-lat');
  });
});

/** The typed-start buffer: a field that turns invalid or empty drops its kept value (round 1 review, major). */
describe('nextTypedStart', () => {
  it('keeps the first coordinate until the other one is typed, then commits both and empties the buffer', () => {
    const first = nextTypedStart(NO_TYPED_START, 'lat', 12.97);
    expect(first).toEqual({ typed: { lat: 12.97, lon: null }, commit: null });
    const second = nextTypedStart(first.typed, 'lon', 77.59);
    expect(second).toEqual({ typed: NO_TYPED_START, commit: { lat: 12.97, lon: 77.59 } });
  });

  it('drops a kept coordinate when its field turns invalid or is cleared', () => {
    const typed = nextTypedStart(NO_TYPED_START, 'lat', 12.97).typed;
    expect(nextTypedStart(typed, 'lat', null)).toEqual({ typed: NO_TYPED_START, commit: null });
  });

  it('sets no start from a latitude that was typed, then made invalid, when the longitude is typed', () => {
    // Type 12.97, change it to "12.9x" (or clear it), then type the longitude.
    let typed = nextTypedStart(NO_TYPED_START, 'lat', 12.97).typed;
    typed = nextTypedStart(typed, 'lat', null).typed;
    const afterLon = nextTypedStart(typed, 'lon', 77.59);
    expect(afterLon.commit).toBeNull();
    expect(afterLon.typed).toEqual({ lat: null, lon: 77.59 });
    // "Plan route" then focuses the latitude, which is the field that shows the problem.
    expect(startFieldToFix({ lat: true, lon: false }, afterLon.typed, false)).toBe('start-lat');
    expect(startFieldToFix({ lat: false, lon: false }, afterLon.typed, false)).toBe('start-lat');
  });

  it('commits once the removed coordinate is typed again', () => {
    let typed = nextTypedStart(NO_TYPED_START, 'lat', 12.97).typed;
    typed = nextTypedStart(typed, 'lat', null).typed;
    typed = nextTypedStart(typed, 'lon', 77.59).typed;
    expect(nextTypedStart(typed, 'lat', 13.01).commit).toEqual({ lat: 13.01, lon: 77.59 });
  });

  it('replaces a kept coordinate with a new valid one in the same field', () => {
    const typed = nextTypedStart(NO_TYPED_START, 'lon', 77.59).typed;
    expect(nextTypedStart(typed, 'lon', 80.27)).toEqual({ typed: { lat: null, lon: 80.27 }, commit: null });
  });
});
