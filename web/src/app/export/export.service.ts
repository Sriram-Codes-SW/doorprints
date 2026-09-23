import { Injectable, inject } from '@angular/core';
import { LocalStore } from '../data/local-store.service';
import { LocalDataError } from '../core/local-error';
import { DICTIONARIES } from '../i18n/languages';
import { resizeImage } from '../core/image-resize';
import { buildBackupZip } from './backup-export';
import { buildCsvTables } from './csv-export';
import { buildHtml } from './html-export';
import { buildMarkdown } from './markdown-export';
import { buildWorkbook } from './xlsx-sheets';
import { buildXlsx } from './xlsx-export';
import { collect } from './export-model';
import type { ExportBundle, ExportFormat, ExportOptions } from './export-model';
import { fileStamp, htmlCopyName } from './deterministic';
import { utf8, zip } from './zip';

/** What an export produced: a file to save or share, or — for PDF — a print view that was opened. */
export interface ExportResult {
  format: ExportFormat;
  fileName: string;
  blob: Blob | null;
  counts: { houses: number; visits: number; photos: number };
}

/**
 * How the export page follows and stops a long export. `onProgress` is called after each photo with how many of
 * the photos that carry bytes are done; `signal` aborts between photos and before the file is assembled, with an
 * `AbortError` DOMException, so a cancelled export produces nothing.
 */
export interface ExportControl {
  onProgress?: (done: number, total: number) => void;
  signal?: AbortSignal;
}

/** What `share()` achieved: only a real failure is worth telling the user about. */
export type ShareOutcome = 'shared' | 'cancelled' | 'unavailable' | 'failed';

/** Longest side of a photo embedded in the HTML copy, and its JPEG quality (docs/11 §5.2). */
const HTML_PHOTO_MAX = 1024;
const HTML_PHOTO_QUALITY = 0.7;

/**
 * How many bytes of photos an export may carry before this refuses to try.
 *
 * docs/11 §5.2 specifies `fflate` streaming in a Web Worker; Sprint 4a has neither (no new npm dependency, see
 * export/zip.ts), so a copy that embeds photos is assembled in memory on the main thread: the raw bytes, the
 * re-encoded base64 (~1.33x) and the finished ZIP are all resident at once, roughly 2.5-3x the photo corpus.
 * Past a point that is an out-of-memory tab kill — a silent crash — so refuse first, with a message that names
 * the two options that make the copy smaller. There is no per-account photo cap (only 20 per house), so this is
 * the only thing standing between a heavy user and a dead tab.
 */
export const MAX_EXPORT_PHOTO_BYTES = 250 * 1024 * 1024;

/** The formats that carry photo bytes; the rest only print file names and are never large. */
const PHOTO_BEARING: ReadonlySet<ExportFormat> = new Set<ExportFormat>(['html', 'pdf', 'backup']);

/** How long to wait for the print frame to load before giving up on the PDF export. */
export const PRINT_TIMEOUT_MS = 15_000;

/**
 * Builds the six deterministic formats from IndexedDB and hands them to the browser (S4-03).
 *
 * Everything runs offline: no server is contacted, and the same data with the same options always produces the
 * same bytes (apart from the export timestamp on the cover and in the manifest).
 */
@Injectable({ providedIn: 'root' })
export class ExportService {
  private readonly store = inject(LocalStore);

  /** Collects the data once so the export page can show counts before the user picks a format. */
  async bundle(options: ExportOptions, exportedAt: string = new Date().toISOString()): Promise<ExportBundle> {
    return collect({
      houses: await this.store.allHouses(),
      visits: await this.store.allVisits(),
      photos: await this.store.allPhotos(),
      exportedAt,
      options,
    });
  }

  /**
   * Builds one file. Photo-bearing formats (HTML, PDF, backup) can carry up to {@link MAX_EXPORT_PHOTO_BYTES} of
   * photos, re-encoded or copied one at a time on the main thread: `control` reports progress after each photo and
   * can abort, and the loop yields to the event loop between photos so the page stays responsive meanwhile.
   */
  async build(
    format: ExportFormat,
    options: ExportOptions,
    now: Date = new Date(),
    control: ExportControl = {},
  ): Promise<ExportResult> {
    const bundle = await this.bundle(options, now.toISOString());
    control.signal?.throwIfAborted();
    if (PHOTO_BEARING.has(format)) checkPhotoBudget(bundle);
    const stamp = fileStamp(bundle.exportedAt);
    const dict = DICTIONARIES[options.lang];
    const counts = bundle.counts;

    switch (format) {
      case 'html':
      case 'pdf': {
        const photos = await this.photoDataUris(bundle, control);
        control.signal?.throwIfAborted();
        const html = buildHtml(bundle, dict, photos);
        return {
          format,
          fileName: htmlCopyName(bundle.exportedAt),
          blob: new Blob([html], { type: 'text/html;charset=utf-8' }),
          counts,
        };
      }
      case 'markdown': {
        const md = buildMarkdown(bundle, dict);
        return {
          format,
          fileName: `Doorprints-${stamp}.md`,
          blob: new Blob([md], { type: 'text/markdown;charset=utf-8' }),
          counts,
        };
      }
      case 'csv': {
        const tables = buildCsvTables(bundle);
        const entries = Object.keys(tables)
          .sort()
          .map((path) => ({ path, data: utf8(tables[path as keyof typeof tables]) }));
        return {
          format,
          fileName: `Doorprints-${stamp}-csv.zip`,
          blob: new Blob([toArrayBuffer(zip(entries, now))], { type: 'application/zip' }),
          counts,
        };
      }
      case 'xlsx': {
        const bytes = buildXlsx(buildWorkbook(bundle), now);
        return {
          format,
          fileName: `Doorprints-${stamp}.xlsx`,
          blob: new Blob([toArrayBuffer(bytes)], {
            type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          }),
          counts,
        };
      }
      case 'backup': {
        const photoBytes = await this.photoBytes(bundle, control);
        control.signal?.throwIfAborted();
        // The readable copy inside a backup names its photos instead of carrying them a second time as base64:
        // the bytes are already in this ZIP's photos/ folder (see html-export.ts, HtmlOptions).
        const html = buildHtml(bundle, dict, new Map(), { photosAsFileNames: true });
        const bytes = buildBackupZip(bundle, photoBytes, html, now);
        return {
          format,
          fileName: `Doorprints-backup-${stamp}.zip`,
          blob: new Blob([toArrayBuffer(bytes)], { type: 'application/zip' }),
          counts,
        };
      }
      default:
        // Unreachable while ExportFormat has exactly the six formats above; a new one must be handled here.
        throw new Error(`Unknown export format: ${String(format)}`);
    }
  }

  /** Saves the file with a normal download; falls back to opening it when downloads are blocked. */
  download(result: ExportResult): void {
    if (!result.blob || typeof document === 'undefined') return;
    const url = URL.createObjectURL(result.blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = result.fileName;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
    // Revoke a little later: Safari needs the URL to stay alive while the download starts.
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  /**
   * True when this browser can share files at all, checked **before** anything is built, so the export page can
   * offer "Share" next to "Download" instead of only after a file has been saved (phones, where sending the copy
   * to WhatsApp or Drive is the point). A tiny probe file stands in for the real one.
   */
  canShareFiles(): boolean {
    if (typeof navigator === 'undefined' || typeof navigator.canShare !== 'function') return false;
    try {
      return navigator.canShare({ files: [new File([''], 'x.txt', { type: 'text/plain' })] });
    } catch {
      return false;
    }
  }

  /** True when this browser can share files (Android Chrome, iOS Safari); false on most desktops. */
  canShare(result: ExportResult): boolean {
    if (!result.blob || typeof navigator === 'undefined' || typeof navigator.canShare !== 'function') return false;
    try {
      return navigator.canShare({ files: [new File([result.blob], result.fileName, { type: result.blob.type })] });
    } catch {
      return false;
    }
  }

  /**
   * Web Share with files (WhatsApp, Drive, Files…). Only the user closing the share sheet (`AbortError`) is a
   * silent outcome; anything else — `NotAllowedError` (no user gesture left after a long build), a file type or
   * size the platform refuses (`TypeError`, `DataError`) — is `failed`, so the page can say so instead of looking
   * as if nothing happened.
   */
  async share(result: ExportResult, title: string): Promise<ShareOutcome> {
    if (!result.blob || typeof navigator === 'undefined' || typeof navigator.share !== 'function') return 'unavailable';
    const file = new File([result.blob], result.fileName, { type: result.blob.type });
    try {
      if (typeof navigator.canShare === 'function' && !navigator.canShare({ files: [file] })) return 'failed';
    } catch {
      return 'failed';
    }
    try {
      await navigator.share({ files: [file], title });
      return 'shared';
    } catch (err: unknown) {
      return isAbortError(err) ? 'cancelled' : 'failed';
    }
  }

  /**
   * "PDF" is the HTML copy printed by the browser: the user picks "Save as PDF" (docs/11 §5.2). Browsers shape
   * Devanagari, Tamil and Telugu correctly; JavaScript PDF libraries do not, which is why there is no PDF writer.
   *
   * **This never hangs.** The frame is a `blob:` URL, and a Content-Security-Policy without `frame-src blob:`
   * blocks it without firing either `load` or `error` — the promise would then never settle, the caller's
   * `finally` would never run and the export button would stay disabled for the rest of the session with nothing
   * said. `web/firebase.json` does allow it, but an embedded browser, an extension or a host with its own policy
   * still can, so the wait is bounded and the failure is a translated {@link LocalDataError} the page can show.
   */
  printPdf(result: ExportResult, timeoutMs: number = PRINT_TIMEOUT_MS): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      if (!result.blob || typeof document === 'undefined') {
        resolve();
        return;
      }
      const frame = document.createElement('iframe');
      frame.setAttribute('aria-hidden', 'true');
      frame.title = result.fileName;
      frame.style.position = 'fixed';
      frame.style.right = '0';
      frame.style.bottom = '0';
      frame.style.width = '0';
      frame.style.height = '0';
      frame.style.border = '0';
      const url = URL.createObjectURL(result.blob);
      let settled = false;
      const giveUp = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        frame.remove();
        URL.revokeObjectURL(url);
        reject(new LocalDataError('error.printBlocked'));
      };
      const timer = setTimeout(giveUp, timeoutMs);
      frame.onerror = giveUp;
      frame.onload = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        try {
          frame.contentWindow?.focus();
          frame.contentWindow?.print();
        } catch {
          // Printing blocked (some embedded browsers): the user can still download the HTML and print it.
        }
        // The frame has to outlive the print dialog, which is modal and has no completion event.
        setTimeout(() => {
          frame.remove();
          URL.revokeObjectURL(url);
        }, 60_000);
        resolve();
      };
      frame.src = url;
      document.body.appendChild(frame);
    });
  }

  /** Photo bytes for the backup, keyed by photo id. Photos with no local bytes are simply left out. */
  private async photoBytes(bundle: ExportBundle, control: ExportControl): Promise<Map<string, Uint8Array>> {
    const out = new Map<string, Uint8Array>();
    const total = photosWithBytes(bundle);
    let done = 0;
    control.onProgress?.(0, total);
    for (const house of bundle.houses) {
      for (const photo of house.photos) {
        if (!photo.blob) continue;
        await nextTask();
        control.signal?.throwIfAborted();
        out.set(photo.id, new Uint8Array(await photo.blob.arrayBuffer()));
        control.onProgress?.(++done, total);
      }
    }
    return out;
  }

  /** Photos shrunk to 1024 px and re-encoded, then base64'd, for the self-contained HTML copy. */
  private async photoDataUris(bundle: ExportBundle, control: ExportControl): Promise<Map<string, string>> {
    const out = new Map<string, string>();
    const total = photosWithBytes(bundle);
    let done = 0;
    control.onProgress?.(0, total);
    for (const house of bundle.houses) {
      for (const photo of house.photos) {
        if (!photo.blob) continue;
        // Yield between photos: each one is a decode, a canvas re-encode and a base64 pass on the main thread,
        // and without a break the tab stops painting (and a phone may offer to kill it) for the whole export.
        await nextTask();
        control.signal?.throwIfAborted();
        let blob = photo.blob;
        try {
          blob = await resizeImage(photo.blob, HTML_PHOTO_MAX, HTML_PHOTO_QUALITY);
        } catch {
          // No canvas (or a photo this browser cannot decode): embed the original bytes instead of dropping it.
        }
        out.set(photo.id, `data:${blob.type || 'image/jpeg'};base64,${base64(new Uint8Array(await blob.arrayBuffer()))}`);
        control.onProgress?.(++done, total);
      }
    }
    return out;
  }
}

/** How many photos of the bundle carry bytes, i.e. how many steps a photo-bearing export has. */
function photosWithBytes(bundle: ExportBundle): number {
  let n = 0;
  for (const house of bundle.houses) for (const photo of house.photos) if (photo.blob) n++;
  return n;
}

/** Resolves on the next macrotask, letting the browser paint and handle input between two photos. */
function nextTask(): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/** True for the error an aborted export or a dismissed share sheet rejects with. */
export function isAbortError(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'AbortError';
}

/**
 * Refuses an export whose photos would not fit in memory, before a single byte is read.
 *
 * `sizeBytes` is what the store recorded when the photo was saved, so this costs nothing: no blob is touched.
 * Nothing partial is produced — the caller gets the error instead of a half-written file.
 */
export function checkPhotoBudget(bundle: ExportBundle, limit: number = MAX_EXPORT_PHOTO_BYTES): void {
  let total = 0;
  for (const house of bundle.houses) {
    for (const photo of house.photos) {
      const size = photo.sizeBytes;
      if (typeof size === 'number' && Number.isFinite(size) && size > 0) total += size;
    }
  }
  if (total > limit) throw new LocalDataError('error.exportTooLarge', { size: approximateSize(total) });
}

/** `12 MB` / `1.2 GB`, for the "too large" message; no Intl, so it reads the same everywhere. */
export function approximateSize(bytes: number): string {
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return `${(Math.round((mb / 1024) * 10) / 10).toString()} GB`;
  return `${Math.round(mb).toString()} MB`;
}

/** Base64 without `btoa`'s string-length limits: 32 KB of bytes at a time. */
export function base64(bytes: Uint8Array): string {
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

/** `new Blob([view])` is fine in browsers, but a plain ArrayBuffer keeps the types simple and copies once. */
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(bytes.length);
  new Uint8Array(copy).set(bytes);
  return copy;
}
