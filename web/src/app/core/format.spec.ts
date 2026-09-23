import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { errorMsg, isQuotaError, retryAfterSeconds } from './format';
import { LocalDataError } from './local-error';

/**
 * A full browser store must reach the user as a translated sentence with advice (docs/05: never raw English
 * technical text), not as the DOMException's own English message through `error.detail`.
 */
describe('errorMsg', () => {
  it('turns a QuotaExceededError from IndexedDB into the storage-full message', () => {
    const quota = new DOMException('The quota has been exceeded.', 'QuotaExceededError');
    expect(isQuotaError(quota)).toBe(true);
    expect(errorMsg(quota)).toEqual({ key: 'error.storageFull' });
  });

  it('also recognises the legacy code 22 and Firefox’s old name', () => {
    expect(errorMsg({ name: 'Error', code: 22, message: 'full' })).toEqual({ key: 'error.storageFull' });
    expect(errorMsg({ name: 'NS_ERROR_DOM_QUOTA_REACHED', message: 'full' })).toEqual({ key: 'error.storageFull' });
  });

  it('leaves other errors as they were', () => {
    expect(isQuotaError(new Error('boom'))).toBe(false);
    expect(errorMsg({ status: 0 })).toEqual({ key: 'error.network' });
    expect(errorMsg(new LocalDataError('error.photoLimit'))).toEqual({ key: 'error.photoLimit', params: undefined });
    expect(errorMsg(null)).toEqual({ key: 'error.unknown' });
  });
});

/**
 * HTTP refusals whose body cannot be read — above all a photo download (`responseType: 'blob'`), whose problem
 * body arrives as a Blob — must not fall through to Angular's English "Http failure response for ..." text.
 */
describe('errorMsg for HTTP refusals', () => {
  const blobBody = () => new Blob(['{"status":429}'], { type: 'application/json' });

  it('maps 429 to the translated rate-limit message with the server’s Retry-After', () => {
    const err = new HttpErrorResponse({
      status: 429,
      error: blobBody(),
      headers: new HttpHeaders({ 'Retry-After': '7' }),
      url: 'https://api.example.com/api/photos/x',
    });
    expect(errorMsg(err)).toEqual({ key: 'error.rateLimited', params: { s: 7 } });
  });

  it('prefers the rate-limit message over the server’s English detail', () => {
    const err = new HttpErrorResponse({
      status: 429,
      error: { status: 429, detail: 'Rate limit exceeded, retry in 2s' },
      headers: new HttpHeaders({ 'Retry-After': '2' }),
    });
    expect(errorMsg(err)).toEqual({ key: 'error.rateLimited', params: { s: 2 } });
  });

  it('says a minute when the 429 has no Retry-After', () => {
    expect(errorMsg(new HttpErrorResponse({ status: 429 }))).toEqual({ key: 'error.rateLimited', params: { s: 60 } });
  });

  it('gives another unreadable 4xx its status, in the app language', () => {
    const err = new HttpErrorResponse({ status: 413, error: blobBody(), url: 'https://api.example.com/api/photos/x' });
    expect(err.message).toContain('Http failure response');
    expect(errorMsg(err)).toEqual({ key: 'error.httpStatus', params: { status: '413' } });
  });

  it('still shows a readable problem detail, 404 and 5xx as before', () => {
    expect(errorMsg(new HttpErrorResponse({ status: 400, error: { detail: 'label is required' } }))).toEqual({
      key: 'error.detail',
      params: { detail: 'label is required' },
    });
    expect(errorMsg(new HttpErrorResponse({ status: 404, error: blobBody() }))).toEqual({ key: 'error.notFound' });
    expect(errorMsg(new HttpErrorResponse({ status: 502, error: blobBody() }))).toEqual({ key: 'error.server' });
  });
});

describe('retryAfterSeconds', () => {
  const with429 = (value?: string) =>
    new HttpErrorResponse({ status: 429, headers: value === undefined ? new HttpHeaders() : new HttpHeaders({ 'Retry-After': value }) });

  it('reads delay-seconds, which is what the backend sends', () => {
    expect(retryAfterSeconds(with429('2'))).toBe(2);
    expect(retryAfterSeconds(with429(' 300 '))).toBe(300);
  });

  it('accepts an HTTP-date and never goes negative', () => {
    const soon = new Date(Date.now() + 10_000).toUTCString();
    const s = retryAfterSeconds(with429(soon));
    expect(s).not.toBeNull();
    expect(s as number).toBeGreaterThanOrEqual(0);
    expect(s as number).toBeLessThanOrEqual(11);
    expect(retryAfterSeconds(with429('Wed, 21 Oct 2015 07:28:00 GMT'))).toBe(0);
  });

  it('is null without a usable header', () => {
    expect(retryAfterSeconds(with429())).toBeNull();
    expect(retryAfterSeconds(with429('soon'))).toBeNull();
    expect(retryAfterSeconds(with429('-5'))).toBeNull();
    expect(retryAfterSeconds({ status: 429 })).toBeNull();
    expect(retryAfterSeconds(null)).toBeNull();
  });
});

/** A phone on a bus is offline, not misconfigured: no CORS advice then (UX audit 2026-09-23). */
describe('errorMsg when the device is offline', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('says "you are offline" for a request that got no response while the browser is offline', () => {
    vi.stubGlobal('navigator', { onLine: false });
    expect(errorMsg({ status: 0 })).toEqual({ key: 'error.offline' });
  });

  it('keeps the connection/CORS message while the browser says it is online', () => {
    vi.stubGlobal('navigator', { onLine: true });
    expect(errorMsg({ status: 0 })).toEqual({ key: 'error.network' });
  });
});
