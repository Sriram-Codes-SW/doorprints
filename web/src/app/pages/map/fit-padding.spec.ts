import { describe, expect, it } from 'vitest';
import { CONTROL_COLUMN_W, FIT_PADDING, MIN_FIT_SPAN, fitPadding } from './fit-padding';

describe('fitPadding ("Show all")', () => {
  it('is even on wider screens, where the controls are top-right and the bottom row has no box', () => {
    expect(fitPadding(false, 0, 1200, 800)).toEqual({ top: 60, bottom: 60, left: 60, right: 60 });
    // Even if something measured a height there, it is not used.
    expect(fitPadding(false, 104, 1200, 800)).toEqual({ top: 60, bottom: 60, left: 60, right: 60 });
  });

  it('keeps the bottom row and the control column clear on phones (UX lead audit, round 3)', () => {
    // 360 x 800 phone: about 400px of map, a 104px bottom row (legend and a 96px action column).
    expect(fitPadding(true, 104, 360, 400)).toEqual({
      top: FIT_PADDING,
      bottom: FIT_PADDING + 104,
      left: FIT_PADDING,
      right: FIT_PADDING + CONTROL_COLUMN_W,
    });
  });

  it('rounds a fractional measured height up and ignores a negative or missing one', () => {
    expect(fitPadding(true, 103.2, 360, 400).bottom).toBe(FIT_PADDING + 104);
    expect(fitPadding(true, -5, 360, 400).bottom).toBe(FIT_PADDING);
    expect(fitPadding(true, Number.NaN, 360, 400).bottom).toBe(FIT_PADDING);
  });

  it('scales opposite paddings down on a map too small for them, so fitBounds still moves', () => {
    // 360 x 640 class phone at 200% text: a 180px bottom row on a 256px map.
    const p = fitPadding(true, 180, 360, 256);
    expect(p.top + p.bottom).toBeLessThanOrEqual(256 - MIN_FIT_SPAN);
    expect(p.bottom).toBeGreaterThan(p.top);
    // The horizontal paddings fit, so they are left alone.
    expect(p.left).toBe(FIT_PADDING);
    expect(p.right).toBe(FIT_PADDING + CONTROL_COLUMN_W);
  });

  it('leaves the paddings alone while the map has no size yet', () => {
    expect(fitPadding(true, 104, 0, 0)).toEqual({ top: 60, bottom: 164, left: 60, right: 114 });
  });
});
