import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DRAFT_PREFIX } from '../pages/house-detail/draft-store';
import { SHARE_TEXT_KEY } from '../pages/share/share-text';
import { MAP_VIEW_KEY } from '../shared/map-center';
import { RISK_DISMISSED_KEY } from '../data/storage.service';
import { ConfigService } from './config.service';
import { INSTALL_DISMISSED_KEY } from './pwa.service';
import {
  API_CONFIG_KEY,
  currentKeyFor,
  LANG_KEY,
  migrateLegacyKeys,
  migrateLegacyStorage,
  STORAGE_PREFIX,
} from './storage-keys';
import { initialLang } from '../i18n/translation.service';

/** A Map-backed stand-in for Storage; `failWrites` makes setItem throw like a full or blocked storage. */
function fakeStorage(entries: Record<string, string>, failWrites = false) {
  const data = new Map(Object.entries(entries));
  return {
    data,
    get length() {
      return data.size;
    },
    key: (i: number) => [...data.keys()][i] ?? null,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      if (failWrites) throw new DOMException('full', 'QuotaExceededError');
      data.set(key, value);
    },
    removeItem: (key: string) => void data.delete(key),
  };
}

describe('storage keys from before the rename to Doorprints', () => {
  it('gives every key this app writes the doorprints. prefix', () => {
    const keys = [LANG_KEY, API_CONFIG_KEY, DRAFT_PREFIX, SHARE_TEXT_KEY, MAP_VIEW_KEY];
    for (const key of [...keys, INSTALL_DISMISSED_KEY, RISK_DISMISSED_KEY]) {
      expect(key.startsWith(STORAGE_PREFIX)).toBe(true);
    }
  });

  it('maps each old name to its new one', () => {
    expect(currentKeyFor('house-hunt.lang')).toBe('doorprints.lang');
    expect(currentKeyFor('house-hunt.api-config')).toBe('doorprints.api-config');
    expect(currentKeyFor('hh.mapView')).toBe(MAP_VIEW_KEY);
    expect(currentKeyFor('hh.installDismissedAt')).toBe(INSTALL_DISMISSED_KEY);
    expect(currentKeyFor('hh.storageRiskDismissedAt')).toBe(RISK_DISMISSED_KEY);
    expect(currentKeyFor('hh.shareText')).toBe(SHARE_TEXT_KEY);
    expect(currentKeyFor('hh.houseDraft:new:12.970000,77.590000')).toBe(`${DRAFT_PREFIX}new:12.970000,77.590000`);
    expect(currentKeyFor('house-hunt.other')).toBeNull();
    expect(currentKeyFor('hhx.other-app')).toBeNull();
    expect(currentKeyFor('doorprints.lang')).toBeNull();
  });

  it('moves every old key to its new name and removes the old one', () => {
    const storage = fakeStorage({
      'house-hunt.lang': 'ta',
      'house-hunt.api-config': '{"baseUrl":"http://localhost:8080","apiKey":"k"}',
      'hh.mapView': '{"lat":12.97,"lon":77.59,"zoom":14}',
      'hh.installDismissedAt': '2026-09-20T10:00:00.000Z',
      'hh.houseDraft:abc': '{"draft":{}}',
      'hhx.other-app': 'not ours',
      other: 'x',
    });
    expect(migrateLegacyKeys(storage)).toBe(5);
    expect(Object.fromEntries(storage.data)).toEqual({
      'doorprints.lang': 'ta',
      'doorprints.api-config': '{"baseUrl":"http://localhost:8080","apiKey":"k"}',
      'doorprints.mapView': '{"lat":12.97,"lon":77.59,"zoom":14}',
      'doorprints.installDismissedAt': '2026-09-20T10:00:00.000Z',
      'doorprints.houseDraft:abc': '{"draft":{}}',
      'hhx.other-app': 'not ours',
      other: 'x',
    });
  });

  it('keeps a value already saved under the new name, and drops the old one', () => {
    const storage = fakeStorage({ 'house-hunt.lang': 'ta', 'doorprints.lang': 'hi' });
    expect(migrateLegacyKeys(storage)).toBe(1);
    expect(Object.fromEntries(storage.data)).toEqual({ 'doorprints.lang': 'hi' });
  });

  it('keeps the old key when the new one cannot be written, so nothing is lost', () => {
    const storage = fakeStorage({ 'house-hunt.lang': 'ta' }, true);
    expect(migrateLegacyKeys(storage)).toBe(0);
    expect(Object.fromEntries(storage.data)).toEqual({ 'house-hunt.lang': 'ta' });
  });

  it('does nothing a second time, or without storage', () => {
    const storage = fakeStorage({ 'hh.shareText': 'Owner: 98450 12345' });
    expect(migrateLegacyKeys(storage)).toBe(1);
    expect(migrateLegacyKeys(storage)).toBe(0);
    expect(migrateLegacyKeys(null)).toBe(0);
  });
});

describe('migrateLegacyStorage at start', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('moves the keys in localStorage and sessionStorage, so the saved language and server are read again', () => {
    localStorage.setItem('house-hunt.lang', 'te');
    localStorage.setItem('hh.mapView', '{"lat":12.97,"lon":77.59,"zoom":14}');
    const server = { baseUrl: 'http://localhost:8080', apiKey: 'k'.repeat(32) };
    sessionStorage.setItem('house-hunt.api-config', JSON.stringify(server));
    sessionStorage.setItem('hh.houseDraft:abc', '{"draft":{}}');

    migrateLegacyStorage();

    expect(localStorage.getItem('house-hunt.lang')).toBeNull();
    expect(localStorage.getItem(LANG_KEY)).toBe('te');
    expect(localStorage.getItem(MAP_VIEW_KEY)).not.toBeNull();
    expect(sessionStorage.getItem('house-hunt.api-config')).toBeNull();
    expect(sessionStorage.getItem(`${DRAFT_PREFIX}abc`)).toBe('{"draft":{}}');
    expect(initialLang()).toBe('te');
    expect(new ConfigService().config()?.baseUrl).toBe('http://localhost:8080');
  });

  it('never throws when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'key').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    localStorage.setItem('house-hunt.lang', 'te');
    expect(() => migrateLegacyStorage()).not.toThrow();
  });
});
