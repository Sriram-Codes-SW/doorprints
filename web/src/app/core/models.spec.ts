import { describe, expect, it } from 'vitest';
import { houseScore } from './models';

describe('houseScore', () => {
  it('is null when neither checklist nor rating is set', () => {
    expect(houseScore({ checklist: {}, rating: null })).toBeNull();
    expect(houseScore({ checklist: {} })).toBeNull();
  });

  it('averages the checklist when there is no rating', () => {
    expect(houseScore({ checklist: { water: 5, power: 3 }, rating: null })).toBe(4);
    expect(houseScore({ checklist: { water: 1, power: 2, parking: 4 } })).toBeCloseTo(7 / 3, 10);
  });

  it('uses the star rating alone when the checklist is empty', () => {
    expect(houseScore({ checklist: {}, rating: 3 })).toBe(3);
  });

  it('blends checklist average and rating 50/50 when both exist', () => {
    // checklist average 4, rating 2 -> 3
    expect(houseScore({ checklist: { water: 5, power: 3 }, rating: 2 })).toBe(3);
    expect(houseScore({ checklist: { water: 5, noise: 4, security: 3 }, rating: 5 })).toBe(4.5);
  });

  it('treats a rating of 0 as a real value, not as missing', () => {
    expect(houseScore({ checklist: { water: 4 }, rating: 0 })).toBe(2);
    expect(houseScore({ checklist: {}, rating: 0 })).toBe(0);
  });

  it('ignores NaN checklist values', () => {
    expect(houseScore({ checklist: { water: Number.NaN, power: 4 }, rating: null })).toBe(4);
    expect(houseScore({ checklist: { water: Number.NaN }, rating: null })).toBeNull();
  });

  it('copes with a missing checklist object from older API responses', () => {
    const legacy = { checklist: null as unknown as Record<string, number>, rating: 4 };
    expect(houseScore(legacy)).toBe(4);
  });

  it('stays within 0..5 for in-range inputs', () => {
    expect(houseScore({ checklist: { a: 0, b: 0 }, rating: 0 })).toBe(0);
    expect(houseScore({ checklist: { a: 5, b: 5 }, rating: 5 })).toBe(5);
  });
});
