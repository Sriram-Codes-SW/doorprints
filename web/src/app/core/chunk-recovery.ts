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
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { CHUNK_RELOAD_KEY } from './storage-keys';

/** How long the first retry waits: a 502 from the host usually clears in a moment. */
export const CHUNK_RETRY_MS = 1000;

/** Reloads the page; a token so a spec can count the calls instead of leaving the test page. */
export const RELOAD_PAGE = new InjectionToken<() => void>('reload the page', {
  providedIn: 'root',
  factory: () => {
    const doc = inject(DOCUMENT);
    return () => doc.defaultView?.location.reload();
  },
});

/**
 * True for the error a browser throws when a lazy route's script cannot be fetched: Chromium "Failed to fetch
 * dynamically imported module: <url>", Firefox "error loading dynamically imported module", Safari "Importing a module
 * script failed", and the older webpack names. Anything else is not ours to recover.
 */
export function isChunkLoadError(error: unknown): boolean {
  const text = error instanceof Error ? `${error.name} ${error.message}` : String(error);
  return /dynamically imported module|Importing a module script failed|ChunkLoadError|Loading chunk \S+ failed/i.test(text);
}

/** Marks this tab as reloaded; false when it already was (or storage is unusable, so a loop could not be ruled out). */
function reloadAllowedOnce(doc: Document): boolean {
  try {
    const store = doc.defaultView?.sessionStorage;
    if (!store || store.getItem(CHUNK_RELOAD_KEY)) return false;
    store.setItem(CHUNK_RELOAD_KEY, '1');
    return true;
  } catch {
    return false;
  }
}

/**
 * Recovery from a lazy route whose script did not load (a flaky connection, a 502 from the host, or an old cached
 * shell asking for a chunk that a newer deploy renamed). Fed by the router's navigation error hook in app.config.ts.
 *
 * 1. The first failure of a navigation: the same address is tried once more after {@link CHUNK_RETRY_MS}, unless the
 *    person has gone elsewhere meanwhile.
 * 2. The retry fails too: the tab reloads itself once per session (a new shell has the new chunk names), guarded by
 *    sessionStorage so it cannot loop.
 * 3. A reload was already done (or storage is blocked): {@link failedUrl} is set, and `app-chunk-notice` shows a
 *    message with *Try again* ({@link retry}) and *Reload*.
 *
 * It only watches: the router still rejects the navigation as it always did, and any other navigation error is
 * ignored here, so it surfaces as before.
 */
@Injectable({ providedIn: 'root' })
export class ChunkRecovery {
  private readonly router = inject(Router);
  private readonly doc = inject(DOCUMENT);
  private readonly reloadPage = inject(RELOAD_PAGE);
  private readonly failed = signal<string | null>(null);
  /** The address whose retry is waiting or running, and the address the app was at when it failed. */
  private retrying: { url: string; from: string } | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;

  /** The address that could not be loaded, while the message is shown. */
  readonly failedUrl = this.failed.asReadonly();

  constructor() {
    // Any page that loads ends the episode (and hides the message).
    this.router.events.subscribe((e) => {
      if (e instanceof NavigationEnd) {
        this.retrying = null;
        this.failed.set(null);
      }
    });
  }

  /** The router's navigation error hook. */
  handle(error: NavigationError): void {
    if (!isChunkLoadError(error.error)) return;
    if (this.retrying?.url !== error.url) {
      // The first failure of this address.
      this.retrying = { url: error.url, from: this.router.url };
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.retryNow(), CHUNK_RETRY_MS);
      return;
    }
    // The retry failed too.
    this.retrying = null;
    if (reloadAllowedOnce(this.doc)) this.reloadPage();
    else this.failed.set(error.url);
  }

  /** *Try again*: the same navigation, now; if it fails again the message comes back. */
  retry(): void {
    const url = this.failed();
    if (url === null) return;
    this.failed.set(null);
    this.retrying = { url, from: this.router.url };
    this.go(url);
  }

  /** *Reload* of the message. */
  reload(): void {
    this.reloadPage();
  }

  private retryNow(): void {
    this.timer = undefined;
    const r = this.retrying;
    // The person went elsewhere (or another navigation started) while waiting: do not pull them back.
    if (!r || this.router.currentNavigation() || this.router.url !== r.from) {
      this.retrying = null;
      return;
    }
    this.go(r.url);
  }

  private go(url: string): void {
    // A chunk failure is handled again through the hook; any other error of this navigation is not ours to hide.
    this.router.navigateByUrl(url).catch((e: unknown) => {
      if (!isChunkLoadError(e)) throw e;
    });
  }
}
