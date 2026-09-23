import type { Msg } from '../i18n/translation.service';
import { ImageResizeError } from './image-resize';
import { LocalDataError } from './local-error';

// Number, price, date and duration formatting lives in TranslationService so it follows the chosen
// language (en-IN / hi-IN / ta-IN / te-IN). This file only turns errors into translatable messages.

/**
 * Turns an HttpErrorResponse (RFC 7807 JSON body) or any other error into a translatable message.
 * Server-provided `detail`/`title` text is shown as is (the API speaks English).
 */
export function errorMsg(err: unknown): Msg {
  // Local-store failures already carry a translation key (no server was involved).
  if (err instanceof LocalDataError) return { key: err.key, params: err.params };
  // The browser's storage is full (IndexedDB rejects with the raw DOMException): say so in the app language and
  // what to do about it, instead of the browser's English "QuotaExceededError" text.
  if (isQuotaError(err)) return { key: 'error.storageFull' };
  if (err instanceof ImageResizeError) {
    return { key: err.reason === 'canvas' ? 'error.imageCanvas' : err.reason === 'encode' ? 'error.imageEncode' : 'error.imageRead' };
  }
  if (err && typeof err === 'object') {
    const e = err as { status?: number; error?: unknown; message?: string };
    // Status 0 is "no response at all". When the device says it is offline, that is the whole story: say so plainly,
    // not the CORS advice meant for a server that is up but refusing this site (a phone on a bus is not a CORS case).
    if (e.status === 0) return { key: isOffline() ? 'error.offline' : 'error.network' };
    if (e.status === 401 || e.status === 403) return { key: 'error.auth' };
    // Rate limited (the backend's per-address limit, or the wrong-key lockout). Checked before the body: the
    // server's English "Rate limit exceeded, retry in 2s" is not shown, and a photo download's body is a Blob.
    if (e.status === 429) return { key: 'error.rateLimited', params: { s: retryAfterSeconds(err) ?? 60 } };
    const body = e.error;
    if (body && typeof body === 'object') {
      const b = body as { detail?: unknown; title?: unknown };
      if (typeof b.detail === 'string' && b.detail) return { key: 'error.detail', params: { detail: b.detail } };
      if (typeof b.title === 'string' && b.title) return { key: 'error.detail', params: { detail: b.title } };
    }
    if (e.status === 404) return { key: 'error.notFound' };
    if (typeof e.status === 'number' && e.status >= 500) return { key: 'error.server' };
    // Any other HTTP refusal without a readable problem body (a `responseType: 'blob'` request, a proxy's HTML
    // page): Angular's own `message` is English ("Http failure response for ..."), so say it in the app language.
    if (typeof e.status === 'number' && e.status >= 400) {
      return { key: 'error.httpStatus', params: { status: String(e.status) } };
    }
    if (typeof e.message === 'string' && e.message) return { key: 'error.detail', params: { detail: e.message } };
  }
  return { key: 'error.unknown' };
}

/**
 * The `Retry-After` of an HTTP error, in whole seconds, or `null` when there is none. The backend sends
 * delay-seconds (ApiRateLimitFilter, ApiKeyFilter, the AI handlers); an HTTP-date is accepted too, as RFC 9110
 * allows it. Duck-typed on `headers.get`, so it works on an `HttpErrorResponse` and on a test double.
 */
export function retryAfterSeconds(err: unknown): number | null {
  if (!err || typeof err !== 'object') return null;
  const headers = (err as { headers?: { get?: unknown } }).headers;
  if (!headers || typeof headers.get !== 'function') return null;
  const raw: unknown = (headers.get as (name: string) => unknown).call(headers, 'Retry-After');
  if (typeof raw !== 'string' || raw.trim() === '') return null;
  const value = raw.trim();
  if (/^\d+$/.test(value)) return Number.parseInt(value, 10);
  // Only an HTTP-date (always "... GMT"): `Date.parse` alone is lenient and reads "-5" or "5.5" as dates in 2001.
  if (!/ GMT$/.test(value)) return null;
  const at = Date.parse(value);
  if (Number.isNaN(at)) return null;
  return Math.max(0, Math.ceil((at - Date.now()) / 1000));
}

/**
 * True for "this browser has no space left": `QuotaExceededError` (every current engine; legacy code 22), and
 * Firefox's older `NS_ERROR_DOM_QUOTA_REACHED`. Duck-typed on `name`/`code`, not `instanceof DOMException`, so it
 * also matches an error re-thrown across a realm or wrapped by a test double.
 */
export function isQuotaError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false;
  const { name, code } = err as { name?: unknown; code?: unknown };
  return name === 'QuotaExceededError' || name === 'NS_ERROR_DOM_QUOTA_REACHED' || code === 22;
}

/** True when the browser reports no network connection (`navigator.onLine === false`; unknown counts as online). */
export function isOffline(): boolean {
  return typeof navigator !== 'undefined' && navigator.onLine === false;
}

export function telHref(phone: string | null | undefined): string {
  return 'tel:' + (phone ?? '').replace(/[^0-9+]/g, '');
}
