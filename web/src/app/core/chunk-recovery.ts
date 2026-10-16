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

import { DOCUMENT, Location } from '@angular/common';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { CHUNK_RELOAD_KEY } from './storage-keys';

/** How long the fresh load waits: a 502 from the host usually clears in a moment. */
export const CHUNK_RETRY_MS = 1000;

/** Loads a page in a new document; a token so a spec can count the calls instead of leaving the test page. */
export interface PageLoader {
  /** The app address [url] (a router URL such as `/houses/new`), loaded afresh with the base href applied. */
  to(url: string): void;
  /** The current address, loaded afresh. */
  reload(): void;
}

export const PAGE_LOADER = new InjectionToken<PageLoader>('page loader', {
  providedIn: 'root',
  factory: () => {
    const doc = inject(DOCUMENT);
    const location = inject(Location);
    return {
      to: (url) => doc.defaultView?.location.assign(location.prepareExternalUrl(url)),
      reload: () => doc.defaultView?.location.reload(),
    };
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

/** Marks this tab as having done its one fresh load; false when it already did (or storage is unusable, so a loop could not be ruled out). */
function freshLoadAllowedOnce(doc: Document): boolean {
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
 * Why a fresh document, not a second `import()`: a browser keeps the failed fetch of a module in the document's module
 * map, so importing the same chunk again fails at once without asking the network (seen in Chromium: the second
 * attempt never reached the server). Only a new document tries the chunk again, and a new document also gets the
 * newest shell and chunk names after a deploy.
 *
 * 1. The first failure in this tab's session: about {@link CHUNK_RETRY_MS} later the address that failed is loaded
 *    again as a new document, once (a flag in sessionStorage, so it cannot loop), unless the person has gone
 *    elsewhere meanwhile.
 * 2. A failure after that (or storage blocked): {@link failedUrl} is set, and `app-chunk-notice` shows a message with
 *    *Try again* ({@link retry}: a fresh load of that address, started by a click, so it needs no flag) and *Reload*.
 *
 * It only watches: the router still rejects the navigation as it always did, and any other navigation error is
 * ignored here, so it surfaces as before.
 */
@Injectable({ providedIn: 'root' })
export class ChunkRecovery {
  private readonly router = inject(Router);
  private readonly doc = inject(DOCUMENT);
  private readonly page = inject(PAGE_LOADER);
  private readonly failed = signal<string | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  /** The address that could not be loaded, while the message is shown. */
  readonly failedUrl = this.failed.asReadonly();

  constructor() {
    // Any page that loads ends the episode and hides the message.
    this.router.events.subscribe((e) => {
      if (e instanceof NavigationEnd) this.failed.set(null);
    });
  }

  /** The router's navigation error hook. */
  handle(error: NavigationError): void {
    if (!isChunkLoadError(error.error) || this.timer !== undefined) return;
    if (!freshLoadAllowedOnce(this.doc)) {
      this.failed.set(error.url);
      return;
    }
    const from = this.router.url;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      // The person went elsewhere (or another navigation started) while waiting: do not pull them back.
      if (this.router.currentNavigation() || this.router.url !== from) return;
      this.page.to(error.url);
    }, CHUNK_RETRY_MS);
  }

  /** *Try again*: the address that failed, loaded afresh now. */
  retry(): void {
    const url = this.failed();
    if (url !== null) this.page.to(url);
  }

  /** *Reload* of the message. */
  reload(): void {
    this.page.reload();
  }
}
