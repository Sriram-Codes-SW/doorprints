import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocalStore } from '../data/local-store.service';
import { LocalDataError } from '../core/local-error';
import { DEFAULT_EXPORT_OPTIONS } from './export-model';
import { ExportService, MAX_EXPORT_PHOTO_BYTES, approximateSize, checkPhotoBudget, isAbortError } from './export.service';
import type { ExportResult } from './export.service';
import { FIXTURE_HOUSES, FIXTURE_PHOTOS, FIXTURE_VISITS, fixtureBundle } from './golden/fixture';
import type { PhotoRecord } from '../data/records';

/**
 * The two failure paths S4-03 has that CI cannot see by building a file: a print view the browser refuses to
 * open, and an export too big to assemble in a browser at all. Both used to end in silence — the first as a
 * promise that never settled (the export button disabled for ever), the second as an out-of-memory tab kill.
 */

const jpeg = (bytes: number) => new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' });

/** A store holding the golden fixture, plus whatever photos a test wants. */
function fakeStore(photos: PhotoRecord[]): Partial<LocalStore> {
  return {
    allHouses: () => Promise.resolve(FIXTURE_HOUSES.map((h) => ({ ...h }))),
    allVisits: () => Promise.resolve(FIXTURE_VISITS.map((v) => ({ ...v }))),
    allPhotos: () => Promise.resolve(photos),
  };
}

function withPhotos(photos: PhotoRecord[]): ExportService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: LocalStore, useValue: fakeStore(photos) }] });
  return TestBed.inject(ExportService);
}

const OPTIONS = { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' as const };

describe('photo budget', () => {
  it('lets a normal export through', () => {
    expect(() => checkPhotoBudget(fixtureBundle())).not.toThrow();
  });

  it('refuses before reading a single byte when the photos would not fit in memory', async () => {
    const huge: PhotoRecord[] = [{ ...FIXTURE_PHOTOS[0], sizeBytes: MAX_EXPORT_PHOTO_BYTES + 1, blob: null }];
    const service = withPhotos(huge);
    // The bytes are never touched: only the recorded sizeBytes is summed, so this costs one number per photo.
    await expect(service.build('backup', OPTIONS)).rejects.toBeInstanceOf(LocalDataError);
    await expect(service.build('backup', OPTIONS)).rejects.toMatchObject({ key: 'error.exportTooLarge' });
    await expect(service.build('html', OPTIONS)).rejects.toMatchObject({ key: 'error.exportTooLarge' });
    await expect(service.build('pdf', OPTIONS)).rejects.toMatchObject({ key: 'error.exportTooLarge' });
  });

  it('still allows the formats that only name the photos', async () => {
    const huge: PhotoRecord[] = [{ ...FIXTURE_PHOTOS[0], sizeBytes: MAX_EXPORT_PHOTO_BYTES + 1, blob: null }];
    const service = withPhotos(huge);
    // CSV, XLSX and Markdown list file names, so they are never large and are the way out of the message.
    await expect(service.build('csv', OPTIONS)).resolves.toMatchObject({ format: 'csv' });
    await expect(service.build('markdown', OPTIONS)).resolves.toMatchObject({ format: 'markdown' });
  });

  it('names a size the user can recognise', () => {
    expect(approximateSize(250 * 1024 * 1024)).toBe('250 MB');
    expect(approximateSize(3 * 1024 * 1024 * 1024)).toBe('3 GB');
  });

  it('produces no partial file when it refuses', async () => {
    const huge: PhotoRecord[] = [{ ...FIXTURE_PHOTOS[0], sizeBytes: MAX_EXPORT_PHOTO_BYTES + 1, blob: null }];
    const service = withPhotos(huge);
    const results: ExportResult[] = [];
    try {
      results.push(await service.build('backup', OPTIONS));
    } catch {
      // expected
    }
    expect(results).toHaveLength(0);
  });
});

describe('backup contents', () => {
  it('writes each photo once, as bytes, and never again as base64 in the bundled HTML', async () => {
    const photo: PhotoRecord = { ...FIXTURE_PHOTOS[0], blob: jpeg(3), sizeBytes: 3 };
    const service = withPhotos([photo]);
    const result = await service.build('backup', OPTIONS, new Date('2026-09-22T10:15:30.000Z'));
    const text = new TextDecoder('latin1').decode(new Uint8Array(await result.blob!.arrayBuffer()));
    expect(text).toContain(`photos/${photo.id}.jpg`);
    // The readable copy names its photos instead of carrying them a second time (~1.33x) as data: URIs.
    expect(text).not.toContain('data:image/jpeg;base64,');
    expect(text).toContain(`<code>${photo.id}.jpg</code>`);
  });
});

/**
 * HTML, PDF and backup copies can carry up to 250 MB of photos, built one at a time on the main thread. The page
 * shows "Preparing photos i of n" and offers Cancel, so the service reports after each photo and stops between
 * two of them when asked, producing nothing.
 */
describe('progress and cancel', () => {
  const photos = (): PhotoRecord[] => FIXTURE_PHOTOS.map((p) => ({ ...p, blob: jpeg(4), sizeBytes: 4 }));

  it('reports progress after each photo, from 0 to the total', async () => {
    const service = withPhotos(photos());
    const seen: string[] = [];
    await service.build('backup', OPTIONS, new Date('2026-09-22T10:15:30.000Z'), {
      onProgress: (done, total) => seen.push(`${done}/${total}`),
    });
    expect(seen).toEqual(['0/2', '1/2', '2/2']);
  });

  it('stops between photos when aborted, with an AbortError and no file', async () => {
    const service = withPhotos(photos());
    const abort = new AbortController();
    const build = service.build('backup', OPTIONS, new Date('2026-09-22T10:15:30.000Z'), {
      signal: abort.signal,
      onProgress: (done) => {
        if (done === 1) abort.abort();
      },
    });
    const err: unknown = await build.then(
      () => null,
      (e: unknown) => e,
    );
    expect(isAbortError(err)).toBe(true);
  });

  it('refuses at once when already aborted', async () => {
    const service = withPhotos(photos());
    const abort = new AbortController();
    abort.abort();
    const err: unknown = await service.build('csv', OPTIONS, new Date(), { signal: abort.signal }).then(
      () => null,
      (e: unknown) => e,
    );
    expect(isAbortError(err)).toBe(true);
  });
});

describe('share', () => {
  const result: ExportResult = {
    format: 'csv',
    fileName: 'Doorprints-2026-09-22-csv.zip',
    blob: new Blob(['x'], { type: 'application/zip' }),
    counts: { houses: 1, visits: 0, photos: 0 },
  };
  const original = { share: navigator.share, canShare: navigator.canShare };

  afterEach(() => {
    Object.defineProperty(navigator, 'share', { value: original.share, configurable: true, writable: true });
    Object.defineProperty(navigator, 'canShare', { value: original.canShare, configurable: true, writable: true });
  });

  function fakeShare(outcome: () => Promise<void>): void {
    Object.defineProperty(navigator, 'canShare', { value: () => true, configurable: true, writable: true });
    Object.defineProperty(navigator, 'share', { value: outcome, configurable: true, writable: true });
  }

  it('is silent only when the user closed the share sheet', async () => {
    const service = withPhotos([]);
    fakeShare(() => Promise.reject(new DOMException('closed', 'AbortError')));
    expect(await service.share(result, 'Doorprints')).toBe('cancelled');
  });

  it('reports every other refusal as a failure the page can show', async () => {
    const service = withPhotos([]);
    fakeShare(() => Promise.reject(new DOMException('no gesture', 'NotAllowedError')));
    expect(await service.share(result, 'Doorprints')).toBe('failed');
    fakeShare(() => Promise.resolve());
    expect(await service.share(result, 'Doorprints')).toBe('shared');
  });
});

describe('printPdf', () => {
  let frame: HTMLIFrameElement | null = null;

  /** The iframe `printPdf` tried to insert. A function, so TypeScript does not narrow it to `null`. */
  function appendedFrame(): HTMLIFrameElement {
    if (!frame) throw new Error('printPdf did not append a frame');
    return frame;
  }

  const result: ExportResult = {
    format: 'pdf',
    fileName: 'Doorprints-copy-2026-09-22.html',
    blob: new Blob(['<p>copy</p>'], { type: 'text/html' }),
    counts: { houses: 1, visits: 0, photos: 0 },
  };

  beforeEach(() => {
    vi.useFakeTimers();
    frame = null;
    // jsdom does not implement object URLs, and the frame is kept out of the document on purpose: a frame that
    // is never inserted never fires `load`, which is exactly what a CSP without `frame-src blob:` produces.
    Object.defineProperty(URL, 'createObjectURL', {
      value: () => 'blob:doorprints/test',
      configurable: true,
      writable: true,
    });
    Object.defineProperty(URL, 'revokeObjectURL', { value: () => undefined, configurable: true, writable: true });
    vi.spyOn(document.body, 'appendChild').mockImplementation(((node: Node) => {
      frame = node as HTMLIFrameElement;
      return node;
    }) as typeof document.body.appendChild);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('rejects with a translated reason instead of hanging when the frame never loads', async () => {
    const service = TestBed.inject(ExportService);
    const promise = service.printPdf(result, 1000);
    const settled = promise.then(
      () => 'resolved',
      (err: unknown) => err,
    );
    await vi.advanceTimersByTimeAsync(1000);
    const outcome = await settled;
    expect(outcome).toBeInstanceOf(LocalDataError);
    expect(outcome).toMatchObject({ key: 'error.printBlocked' });
    expect(appendedFrame().title).toBe(result.fileName);
  });

  it('resolves once the frame loads, and does not then fire the timeout', async () => {
    const service = TestBed.inject(ExportService);
    const promise = service.printPdf(result, 1000);
    const inserted = appendedFrame();
    inserted.onload?.call(inserted, new Event('load'));
    await expect(promise).resolves.toBeUndefined();
    // The frame is cleaned up later; advancing past the timeout must not turn a success into a rejection.
    await vi.advanceTimersByTimeAsync(120_000);
  });

  it('resolves quietly when there is nothing to print', async () => {
    const service = TestBed.inject(ExportService);
    await expect(service.printPdf({ ...result, blob: null }, 1000)).resolves.toBeUndefined();
  });
});
