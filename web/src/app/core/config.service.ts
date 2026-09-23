import { Injectable, computed, signal } from '@angular/core';

export interface ApiConfig {
  baseUrl: string;
  apiKey: string;
}

// Keeps the pre-rename 'house-hunt.' prefix on purpose: changing it would lose settings already saved in
// users' browsers after the rename to Doorprints. Do not change it without a migration.
const STORAGE_KEY = 'house-hunt.api-config';
export const DEFAULT_BASE_URL = 'http://localhost:8080';

/**
 * Holds the API base URL and key (threat model F-04, SEC-010).
 *
 * - "Remember on this device" on: localStorage (survives closing the browser).
 * - Off (the default for new connections): sessionStorage, so the key is gone when the tab is closed. On a shared
 *   or borrowed computer this is the safer choice.
 *
 * Either way any script running on this origin could read the key, which is why the site ships a strict
 * Content-Security-Policy and the app never renders user data as HTML. The policy is in `web/firebase.json` (sent
 * as a header by Firebase Hosting, the live host) and is also written into index.html as a `<meta>` at build time
 * (`scripts/sw-precache.mjs`), as defence in depth for any host that ignores `firebase.json`.
 *
 * "This origin" must be the app's own: localStorage (the key, when remembered) and IndexedDB are per origin, not
 * per path. That is why the live site is on Firebase Hosting (`https://doorprints.web.app`, an origin of its
 * own) and not on GitHub Pages, where `https://<owner>.github.io` is shared by every project site of that owner
 * and a script in any of them could read them. web/README.md ("Deploy") says so.
 */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private readonly state = signal<ApiConfig | null>(ConfigService.load());
  private readonly rememberState = signal<boolean>(ConfigService.storedIn(localStorageOrNull()));

  readonly config = this.state.asReadonly();
  /** True when the key is kept in localStorage. */
  readonly remembered = this.rememberState.asReadonly();
  readonly configured = computed(() => {
    const c = this.state();
    return !!c && !!c.baseUrl && !!c.apiKey;
  });

  save(config: ApiConfig, remember: boolean): void {
    const clean: ApiConfig = { baseUrl: normalizeBaseUrl(config.baseUrl), apiKey: config.apiKey.trim() };
    this.state.set(clean);
    this.rememberState.set(remember);
    const keep = remember ? localStorageOrNull() : sessionStorageOrNull();
    const drop = remember ? sessionStorageOrNull() : localStorageOrNull();
    try {
      drop?.removeItem(STORAGE_KEY);
      keep?.setItem(STORAGE_KEY, JSON.stringify(clean));
    } catch {
      // Storage unavailable (private mode etc.): keep it in memory for this page only.
    }
  }

  clear(): void {
    this.state.set(null);
    for (const s of [localStorageOrNull(), sessionStorageOrNull()]) {
      try {
        s?.removeItem(STORAGE_KEY);
      } catch {
        // ignore
      }
    }
  }

  /** Session storage wins, so choosing "don't remember" on a device that remembered before takes effect. */
  private static load(): ApiConfig | null {
    return ConfigService.read(sessionStorageOrNull()) ?? ConfigService.read(localStorageOrNull());
  }

  private static storedIn(storage: Storage | null): boolean {
    return ConfigService.read(storage) !== null;
  }

  private static read(storage: Storage | null): ApiConfig | null {
    try {
      const raw = storage?.getItem(STORAGE_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as Partial<ApiConfig> | null;
      if (parsed && typeof parsed.baseUrl === 'string' && typeof parsed.apiKey === 'string') {
        return { baseUrl: parsed.baseUrl, apiKey: parsed.apiKey };
      }
    } catch {
      // ignore corrupt or unavailable storage
    }
    return null;
  }
}

function localStorageOrNull(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

function sessionStorageOrNull(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch {
    return null;
  }
}

/**
 * The address the Connect page starts with before anything is saved: the local development server on localhost,
 * and nothing on the live site. A prefilled `http://localhost:8080` there was an address nobody has, flagged as a
 * blocked http:// address before the user had typed anything; the example stays as the field's placeholder.
 */
export function initialBaseUrl(hostname: string): string {
  return hostname === 'localhost' || hostname === '127.0.0.1' ? DEFAULT_BASE_URL : '';
}

export function normalizeBaseUrl(url: string): string {
  return url.trim().replace(/\/+$/, '');
}
