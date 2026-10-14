/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import { DOCUMENT } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChunkNotice } from '../shared/chunk-notice';
import { CHUNK_RETRY_MS, ChunkRecovery, RELOAD_PAGE, isChunkLoadError } from './chunk-recovery';
import { CHUNK_RELOAD_KEY } from './storage-keys';

const CHUNK = new TypeError('Failed to fetch dynamically imported module: https://doorprints.web.app/chunk-ABC.js');

describe('isChunkLoadError', () => {
  it('knows the failed dynamic import of each browser, and the older webpack names', () => {
    expect(isChunkLoadError(CHUNK)).toBe(true);
    expect(isChunkLoadError(new TypeError('error loading dynamically imported module: https://x/chunk-1.js'))).toBe(true);
    expect(isChunkLoadError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isChunkLoadError(Object.assign(new Error('Loading chunk 7 failed.'), { name: 'ChunkLoadError' }))).toBe(true);
  });

  it('is false for every other error', () => {
    expect(isChunkLoadError(new Error('Cannot match any routes. URL Segment: x'))).toBe(false);
    expect(isChunkLoadError(new TypeError('Failed to fetch'))).toBe(false);
    expect(isChunkLoadError(undefined)).toBe(false);
  });
});

describe('ChunkRecovery', () => {
  let events: Subject<unknown>;
  let router: { events: Subject<unknown>; url: string; currentNavigation: () => unknown; navigateByUrl: ReturnType<typeof vi.fn> };
  let reload: ReturnType<typeof vi.fn>;
  let store: Map<string, string>;
  let recovery: ChunkRecovery;

  const fail = (url = '/', error: unknown = CHUNK) => recovery.handle(new NavigationError(1, url, error));

  beforeEach(() => {
    vi.useFakeTimers();
    events = new Subject();
    router = { events, url: '/', currentNavigation: () => null, navigateByUrl: vi.fn(() => Promise.resolve(true)) };
    reload = vi.fn();
    store = new Map();
    const sessionStorage = {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: router },
        { provide: DOCUMENT, useValue: { defaultView: { sessionStorage } } },
        { provide: RELOAD_PAGE, useValue: reload },
      ],
    });
    recovery = TestBed.inject(ChunkRecovery);
  });

  afterEach(() => vi.useRealTimers());

  it('retries the same address once, after about a second', () => {
    fail('/houses/new');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    vi.advanceTimersByTime(CHUNK_RETRY_MS - 1);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(router.navigateByUrl).toHaveBeenCalledExactlyOnceWith('/houses/new');
    expect(recovery.failedUrl()).toBeNull();
    expect(reload).not.toHaveBeenCalled();
  });

  it('shows nothing more when the retry loads the page', () => {
    fail();
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    events.next(new NavigationEnd(2, '/', '/'));
    expect(recovery.failedUrl()).toBeNull();
    expect(reload).not.toHaveBeenCalled();
    expect(store.size).toBe(0);
    // A later failure starts again with a fresh retry.
    fail();
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    expect(router.navigateByUrl).toHaveBeenCalledTimes(2);
  });

  it('reloads the tab once when the retry fails too, and marks the session', () => {
    fail();
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    fail();
    expect(reload).toHaveBeenCalledTimes(1);
    expect(store.get(CHUNK_RELOAD_KEY)).toBe('1');
    expect(recovery.failedUrl()).toBeNull();
  });

  it('shows the message instead of a second reload in the same session', () => {
    store.set(CHUNK_RELOAD_KEY, '1');
    fail('/data');
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    fail('/data');
    expect(reload).not.toHaveBeenCalled();
    expect(recovery.failedUrl()).toBe('/data');
  });

  it('shows the message when session storage cannot be used (a loop could not be ruled out)', () => {
    TestBed.inject(DOCUMENT).defaultView!.sessionStorage.getItem = () => {
      throw new Error('blocked');
    };
    fail();
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    fail();
    expect(reload).not.toHaveBeenCalled();
    expect(recovery.failedUrl()).toBe('/');
  });

  it('Try again repeats the navigation now, hides the message, and brings it back if that fails too', () => {
    store.set(CHUNK_RELOAD_KEY, '1');
    fail('/data');
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    fail('/data');
    router.navigateByUrl.mockClear();
    recovery.retry();
    expect(router.navigateByUrl).toHaveBeenCalledExactlyOnceWith('/data');
    expect(recovery.failedUrl()).toBeNull();
    fail('/data');
    expect(recovery.failedUrl()).toBe('/data');
    expect(reload).not.toHaveBeenCalled();
  });

  it('a page that loads hides the message', () => {
    store.set(CHUNK_RELOAD_KEY, '1');
    fail();
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    fail();
    events.next(new NavigationEnd(3, '/connect', '/connect'));
    expect(recovery.failedUrl()).toBeNull();
  });

  it('does not pull the person back when they went elsewhere while it waited', () => {
    fail('/data');
    router.url = '/connect';
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('does not start a retry over a navigation that is already running', () => {
    fail('/data');
    router.currentNavigation = () => ({});
    vi.advanceTimersByTime(CHUNK_RETRY_MS);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('leaves every other navigation error alone', () => {
    fail('/houses/x', new Error('Cannot match any routes'));
    fail('/houses/x', new TypeError('Failed to fetch'));
    vi.advanceTimersByTime(CHUNK_RETRY_MS * 3);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
    expect(recovery.failedUrl()).toBeNull();
  });
});

describe('ChunkNotice', () => {
  it('is empty until a page failed twice, then an alert with Try again and Reload', async () => {
    const failedUrl = signal<string | null>(null);
    const recovery = { failedUrl, retry: vi.fn(), reload: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: ChunkRecovery, useValue: recovery }] });
    const fixture = TestBed.createComponent(ChunkNotice);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')).toBeNull();

    failedUrl.set('/');
    await fixture.whenStable();
    const alert = el.querySelector('[role="alert"]')!;
    expect(alert.textContent).toContain('This page could not be loaded. Check your connection.');
    const [again, reload] = Array.from(el.querySelectorAll('button'));
    expect([again.textContent!.trim(), reload.textContent!.trim()]).toEqual(['Try again', 'Reload']);
    again.click();
    reload.click();
    expect(recovery.retry).toHaveBeenCalledTimes(1);
    expect(recovery.reload).toHaveBeenCalledTimes(1);
  });
});
