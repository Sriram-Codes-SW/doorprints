import { describe, expect, it } from 'vitest';
import { DRAFT_PREFIX } from '../house-detail/draft-store';
import { SHARE_TEXT_KEY } from '../share/share-text';
import { API_CONFIG_KEY, LANG_KEY } from '../../core/storage-keys';
import { clearLocalLeftovers, clearSessionLeftovers, KEPT_KEYS, LOCAL_KEY_PREFIXES } from './session-leftovers';

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
  it('removes every doorprints.* key (drafts, the shared listing text, any other) but the server settings', () => {
    const storage = fakeStorage({
      'doorprints.houseDraft:abc': '{"draft":{}}',
      'doorprints.houseDraft:new:12.970000,77.590000': '{}',
      'doorprints.houseDraft:new:': '{}',
      'doorprints.shareText': 'Owner: 98450 12345',
      'doorprints.x': 'a future session key',
      'hh.houseDraft:old': 'a pre-rename key that could not be moved',
      'doorprints.api-config': 'kept: forgotten by ConfigService.clear()',
      'hhx.other-app': 'kept: not our prefix',
      'doorprintsx.other-app': 'kept: not our prefix',
      other: 'x',
    });
    expect(clearSessionLeftovers(storage)).toBe(6);
    expect([...storage.data.keys()]).toEqual([
      'doorprints.api-config',
      'hhx.other-app',
      'doorprintsx.other-app',
      'other',
    ]);
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
  it('removes every doorprints.* key, and keeps the language, the server settings and other sites\' keys', () => {
    const storage = fakeStorage({
      'doorprints.mapView': '{"lat":12.97,"lon":77.59,"zoom":14}',
      'doorprints.installDismissedAt': '2026-09-20T10:00:00.000Z',
      'doorprints.storageRiskDismissedAt': '2026-09-21T10:00:00.000Z',
      'doorprints.future': 'x',
      'hh.mapView': 'a pre-rename key that could not be moved',
      'doorprints.lang': 'ta',
      'doorprints.api-config': 'kept: forgotten by ConfigService.clear()',
      'hhx.other-app': 'kept: not our prefix',
      other: 'x',
    });
    expect(clearLocalLeftovers(storage)).toBe(5);
    expect([...storage.data.keys()]).toEqual(['doorprints.lang', 'doorprints.api-config', 'hhx.other-app', 'other']);
  });

  it('keeps exactly the language and the server settings, by name', () => {
    expect(KEPT_KEYS).toEqual([LANG_KEY, API_CONFIG_KEY]);
    expect(KEPT_KEYS).toEqual(['doorprints.lang', 'doorprints.api-config']);
  });

  it('does nothing without storage (blocked, or a private window)', () => {
    expect(clearLocalLeftovers(null)).toBe(0);
  });
});
