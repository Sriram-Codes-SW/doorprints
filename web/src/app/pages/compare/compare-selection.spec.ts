import { describe, expect, it } from 'vitest';
import { idsFromQuery } from './compare-selection';

describe('the compared houses in the URL (?ids=)', () => {
  const allowed = new Set(['a', 'b', 'c', 'd', 'e']);

  it('keeps the order, drops duplicates and houses that cannot be compared, and stops at four', () => {
    expect(idsFromQuery('c,a,c,x,b', allowed)).toEqual(['c', 'a', 'b']);
    expect(idsFromQuery('a,b,c,d,e', allowed)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('is null without the parameter (the page then picks a default), and empty for an empty one', () => {
    expect(idsFromQuery(null, allowed)).toBeNull();
    expect(idsFromQuery('', allowed)).toEqual([]);
  });
});
