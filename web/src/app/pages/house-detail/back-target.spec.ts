import { describe, expect, it } from 'vitest';
import { backTarget, exitAfterRemoval } from './back-target';

describe('the house page\'s "Back"', () => {
  it('goes back in history to the filtered list it was opened from, named "Back to map"', () => {
    expect(backTarget({ trigger: 'imperative', previousPath: '/', handedBackKey: undefined })).toEqual({
      toPrevious: true,
      key: 'house.back',
    });
  });

  it('goes back to Compare, an Ask answer or a Plan stop as a plain "Back"', () => {
    for (const previousPath of ['/compare', '/ask', '/plan']) {
      expect(backTarget({ trigger: 'imperative', previousPath, handedBackKey: undefined })).toEqual({
        toPrevious: true,
        key: 'house.backGeneric',
      });
    }
  });

  it('is a link to the map for a bookmark, a shared link or a reload (no page behind it in the app)', () => {
    expect(backTarget({ trigger: 'imperative', previousPath: null, handedBackKey: undefined })).toEqual({
      toPrevious: false,
      key: 'house.back',
    });
  });

  it('never goes back in history after arriving with Back or Forward, where the previous page may be ahead', () => {
    expect(backTarget({ trigger: 'popstate', previousPath: '/compare', handedBackKey: undefined })).toEqual({
      toPrevious: false,
      key: 'house.back',
    });
  });

  it('after the first save of a new house, keeps what the form had decided, since its entry was replaced', () => {
    expect(backTarget({ trigger: 'imperative', previousPath: '/houses/new', handedBackKey: 'house.backGeneric' })).toEqual({
      toPrevious: true,
      key: 'house.backGeneric',
    });
    expect(backTarget({ trigger: 'imperative', previousPath: '/houses/new', handedBackKey: undefined })).toEqual({
      toPrevious: false,
      key: 'house.back',
    });
    expect(backTarget({ trigger: 'imperative', previousPath: '/houses/new', handedBackKey: 'nav.map' })).toEqual({
      toPrevious: false,
      key: 'house.back',
    });
  });
});

describe('leaving the house page after Delete, or Discard on a new house', () => {
  it('goes back in history when the page behind is the list, so the removed page is not behind the list', () => {
    const fromList = backTarget({ trigger: 'imperative', previousPath: '/', handedBackKey: undefined });
    expect(exitAfterRemoval(fromList)).toBe('back');
  });

  it('replaces this entry with the list after Compare, an answer or a route', () => {
    for (const previousPath of ['/compare', '/ask', '/plan']) {
      expect(exitAfterRemoval(backTarget({ trigger: 'imperative', previousPath, handedBackKey: undefined }))).toBe('replace');
    }
  });

  it('replaces this entry with the list when nothing in the app is behind it (a bookmark, a reload, Back or Forward)', () => {
    expect(exitAfterRemoval(backTarget({ trigger: 'imperative', previousPath: null, handedBackKey: undefined }))).toBe('replace');
    expect(exitAfterRemoval(backTarget({ trigger: 'popstate', previousPath: '/', handedBackKey: undefined }))).toBe('replace');
  });

  it('goes back to the list after a new house saved from it, whose form handed on "Back to map"', () => {
    const saved = backTarget({ trigger: 'imperative', previousPath: '/houses/new', handedBackKey: 'house.back' });
    expect(exitAfterRemoval(saved)).toBe('back');
  });
});
