import type { Msg } from '../i18n/translation.service';
import { ImageResizeError } from './image-resize';

// Number, price, date and duration formatting lives in TranslationService so it follows the chosen
// language (en-IN / hi-IN / ta-IN / te-IN). This file only turns errors into translatable messages.

/**
 * Turns an HttpErrorResponse (RFC 7807 JSON body) or any other error into a translatable message.
 * Server-provided `detail`/`title` text is shown as is (the API speaks English).
 */
export function errorMsg(err: unknown): Msg {
  if (err instanceof ImageResizeError) {
    return { key: err.reason === 'canvas' ? 'error.imageCanvas' : err.reason === 'encode' ? 'error.imageEncode' : 'error.imageRead' };
  }
  if (err && typeof err === 'object') {
    const e = err as { status?: number; error?: unknown; message?: string };
    if (e.status === 0) return { key: 'error.network' };
    if (e.status === 401 || e.status === 403) return { key: 'error.auth' };
    const body = e.error;
    if (body && typeof body === 'object') {
      const b = body as { detail?: unknown; title?: unknown };
      if (typeof b.detail === 'string' && b.detail) return { key: 'error.detail', params: { detail: b.detail } };
      if (typeof b.title === 'string' && b.title) return { key: 'error.detail', params: { detail: b.title } };
    }
    if (e.status === 404) return { key: 'error.notFound' };
    if (typeof e.status === 'number' && e.status >= 500) return { key: 'error.server' };
    if (typeof e.message === 'string' && e.message) return { key: 'error.detail', params: { detail: e.message } };
  }
  return { key: 'error.unknown' };
}

export function telHref(phone: string | null | undefined): string {
  return 'tel:' + (phone ?? '').replace(/[^0-9+]/g, '');
}
