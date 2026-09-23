import { describe, expect, it } from 'vitest';
import { DRAFT_PREFIX } from '../house-detail/draft-store';
import { SHARE_TEXT_KEY } from '../share/share-text';
import { clearLocalLeftovers, clearSessionLeftovers, LOCAL_KEY_PREFIXES } from './session-leftovers';

/** A Map-backed stand-in for sessionStorage. */
function fakeStorage(entries: Record<string, string>) {
  const data = new Map(Object.entries(entries));
  return {
    data,
    get length() {
      return data.size;
    },
    key: (i: number) => [...data.keys()][i] ?? null,
    removeItem: (key: string) => void data.delete(key),
  };
}

describe('"Remove all data" and this tab\'s sessionStorage', () => {
  it('removes every hh.* and doorprints.* key (drafts, the shared listing text, any other), and nothing else', () => {
    const storage = fakeStorage({
      'hh.houseDraft:abc': '{"draft":{}}',
      'hh.houseDraft:new:12.970000,77.590000': '{}',
      'hh.houseDraft:new:': '{}',
      'hh.shareText': 'Owner: 98450 12345',
      'hh.somethingElse': 'a future session key',
      'doorprints.x': 'a future session key',
      'house-hunt.api-config': 'kept: forgotten by ConfigService.clear()',
      'hhx.other-app': 'kept: not our prefix',
      other: 'x',
    });
    expect(clearSessionLeftovers(storage)).toBe(6);
    expect([...storage.data.keys()]).toEqual(['house-hunt.api-config', 'hhx.other-app', 'other']);
  });

  it('covers the draft and shared-text keys by prefix, so a rename cannot leave them behind unnoticed', () => {
    for (const key of [DRAFT_PREFIX, SHARE_TEXT_KEY]) {
      expect(LOCAL_KEY_PREFIXES.some((prefix) => key.startsWith(prefix))).toBe(true);
    }
  });

  it('does nothing without storage (blocked, or a private window)', () => {
    expect(clearSessionLeftovers(null)).toBe(0);
  });
});

describe('"Remove all data" and this origin\'s localStorage (docs/07 Appendix A.1, S10)', () => {
  it('removes every hh.* and doorprints.* key, and keeps the language and other sites\' keys', () => {
    const storage = fakeStorage({
      'hh.mapView': '{"lat":12.97,"lon":77.59,"zoom":14}',
      'hh.installDismissedAt': '2026-09-20T10:00:00.000Z',
      'hh.storageRiskDismissedAt': '2026-09-21T10:00:00.000Z',
      'doorprints.future': 'x',
      'house-hunt.lang': 'ta',
      'hhx.other-app': 'kept: not our prefix',
      other: 'x',
    });
    expect(clearLocalLeftovers(storage)).toBe(4);
    expect([...storage.data.keys()]).toEqual(['house-hunt.lang', 'hhx.other-app', 'other']);
  });

  it('does nothing without storage (blocked, or a private window)', () => {
    expect(clearLocalLeftovers(null)).toBe(0);
  });
});
