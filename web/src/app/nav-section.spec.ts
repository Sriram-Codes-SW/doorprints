import { describe, expect, it } from 'vitest';
import { hidesBottomBar, inDataSection, inMapSection, sectionCurrent } from './nav-section';

describe('the navigation item for the page on screen', () => {
  it('marks Map on the map, a house and the new-house form, and nowhere else', () => {
    for (const path of ['/', '/houses/new', '/houses/0b7c2f0e-1d7a-4d0e-9d0c-9f1f6a2b3c4d']) {
      expect(inMapSection(path)).toBe(true);
    }
    for (const path of ['/compare', '/ask', '/plan', '/data', '/connect', '/share', '/housesx']) {
      expect(inMapSection(path)).toBe(false);
    }
  });

  it('says "page" on the map itself and "true" on a house under it (UX lead review 2026-09-22)', () => {
    expect(sectionCurrent(true, inMapSection('/'))).toBe('page');
    expect(sectionCurrent(false, inMapSection('/houses/new'))).toBe('true');
    expect(sectionCurrent(false, inMapSection('/compare'))).toBeNull();
  });

  it('hides the phone bottom bar on a house and the new-house form only, as Android does (UX lead audit, round 3)', () => {
    for (const path of ['/houses/new', '/houses/0b7c2f0e-1d7a-4d0e-9d0c-9f1f6a2b3c4d']) {
      expect(hidesBottomBar(path)).toBe(true);
    }
    for (const path of ['/', '/compare', '/ask', '/plan', '/data', '/connect', '/share', '/housesx']) {
      expect(hidesBottomBar(path)).toBe(false);
    }
  });

  it('marks Your data on Connect only on phones, where Connect has no item of its own', () => {
    expect(inDataSection('/data', false)).toBe(true);
    expect(inDataSection('/connect', true)).toBe(true);
    expect(inDataSection('/connect', false)).toBe(false);
  });
});
