import { Injectable, computed, signal } from '@angular/core';

export interface ApiConfig {
  baseUrl: string;
  apiKey: string;
}

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
 * Content-Security-Policy (public/_headers) and the app never renders user data as HTML.
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

export function normalizeBaseUrl(url: string): string {
  return url.trim().replace(/\/+$/, '');
}
