import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigService, DEFAULT_BASE_URL, initialBaseUrl, normalizeBaseUrl } from './config.service';

const STORAGE_KEY = 'doorprints.api-config';

function stored(storage: Storage): unknown {
  const raw = storage.getItem(STORAGE_KEY);
  return raw === null ? null : JSON.parse(raw);
}

describe('ConfigService', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('starts unconfigured with empty storage', () => {
    const service = new ConfigService();
    expect(service.config()).toBeNull();
    expect(service.configured()).toBe(false);
    expect(service.remembered()).toBe(false);
  });

  it('keeps the key only for this tab (sessionStorage) when not remembered', () => {
    const service = new ConfigService();
    service.save({ baseUrl: 'https://api.example.org', apiKey: 'k1' }, false);

    expect(stored(sessionStorage)).toEqual({ baseUrl: 'https://api.example.org', apiKey: 'k1' });
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(service.remembered()).toBe(false);
    expect(service.configured()).toBe(true);
  });

  it('keeps the key in localStorage when remembered, and removes the session copy', () => {
    const service = new ConfigService();
    service.save({ baseUrl: 'https://a.example', apiKey: 'session' }, false);
    service.save({ baseUrl: 'https://a.example', apiKey: 'local' }, true);

    expect(stored(localStorage)).toEqual({ baseUrl: 'https://a.example', apiKey: 'local' });
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(service.remembered()).toBe(true);
  });

  it('switching back to "do not remember" deletes the localStorage copy', () => {
    const service = new ConfigService();
    service.save({ baseUrl: 'https://a.example', apiKey: 'k' }, true);
    service.save({ baseUrl: 'https://a.example', apiKey: 'k' }, false);

    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(stored(sessionStorage)).toEqual({ baseUrl: 'https://a.example', apiKey: 'k' });
    expect(service.remembered()).toBe(false);
  });

  it('normalises the base URL and trims the key before saving', () => {
    const service = new ConfigService();
    service.save({ baseUrl: '  https://api.example.org///  ', apiKey: '  secret \n' }, false);
    expect(service.config()).toEqual({ baseUrl: 'https://api.example.org', apiKey: 'secret' });
    expect(stored(sessionStorage)).toEqual({ baseUrl: 'https://api.example.org', apiKey: 'secret' });
  });

  it('is not configured when the key or URL is blank', () => {
    const service = new ConfigService();
    service.save({ baseUrl: 'https://a.example', apiKey: '   ' }, false);
    expect(service.configured()).toBe(false);
    service.save({ baseUrl: ' / ', apiKey: 'k' }, false);
    expect(service.configured()).toBe(false);
  });

  it('clear() forgets the config everywhere', () => {
    const service = new ConfigService();
    service.save({ baseUrl: 'https://a.example', apiKey: 'k' }, true);
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ baseUrl: 'x', apiKey: 'y' }));

    service.clear();

    expect(service.config()).toBeNull();
    expect(service.configured()).toBe(false);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('loads a remembered config on start', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ baseUrl: 'https://l.example', apiKey: 'L' }));
    const service = new ConfigService();
    expect(service.config()).toEqual({ baseUrl: 'https://l.example', apiKey: 'L' });
    expect(service.remembered()).toBe(true);
    expect(service.configured()).toBe(true);
  });

  it('prefers the session copy over the remembered one on start', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ baseUrl: 'https://l.example', apiKey: 'L' }));
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ baseUrl: 'https://s.example', apiKey: 'S' }));
    expect(new ConfigService().config()).toEqual({ baseUrl: 'https://s.example', apiKey: 'S' });
  });

  it('ignores corrupt or incomplete stored values', () => {
    sessionStorage.setItem(STORAGE_KEY, '{not json');
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ baseUrl: 'https://l.example' }));
    const service = new ConfigService();
    expect(service.config()).toBeNull();
    expect(service.remembered()).toBe(false);
  });

  it('only keeps the known fields from storage', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ baseUrl: 'https://l.example', apiKey: 'L', extra: 'x' }));
    expect(new ConfigService().config()).toEqual({ baseUrl: 'https://l.example', apiKey: 'L' });
  });

  it('keeps the config in memory when storage throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    const service = new ConfigService();
    service.save({ baseUrl: 'https://a.example/', apiKey: 'k' }, true);

    expect(service.config()).toEqual({ baseUrl: 'https://a.example', apiKey: 'k' });
    expect(service.configured()).toBe(true);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});

describe('normalizeBaseUrl', () => {
  it.each([
    ['http://localhost:8080', 'http://localhost:8080'],
    ['http://localhost:8080/', 'http://localhost:8080'],
    [' https://api.example.org/v1// ', 'https://api.example.org/v1'],
    ['', ''],
  ])('%j -> %j', (input, expected) => {
    expect(normalizeBaseUrl(input)).toBe(expected);
  });
});

describe('the Connect page’s starting address', () => {
  it('is the local development server only on localhost', () => {
    expect(initialBaseUrl('localhost')).toBe(DEFAULT_BASE_URL);
    expect(initialBaseUrl('127.0.0.1')).toBe(DEFAULT_BASE_URL);
  });

  it('is empty on the live site, so nothing is flagged before the user types', () => {
    expect(initialBaseUrl('doorprints.web.app')).toBe('');
  });
});
