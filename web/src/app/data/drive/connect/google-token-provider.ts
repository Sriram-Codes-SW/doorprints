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

import { SignInError } from '../drive-client';
import type { TokenProvider } from '../drive-client';

const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file';

/** The possible outcomes of a token request. */
export type GoogleTokenResult =
  | { readonly kind: 'token'; readonly value: string }
  | { readonly kind: 'popup_blocked' }
  | { readonly kind: 'popup_closed' }
  | { readonly kind: 'denied' }
  | { readonly kind: 'offline' };

/** Injectable seam for loading the GIS script. */
export interface ScriptLoader {
  load(src: string): Promise<void>;
}

/** The injectable config source for OAuth client ID. */
export interface GoogleConfig {
  readonly clientId: string;
}

/** Whether GIS granted the Drive scope for this token response. Tests inject a stub; production fails closed. */
export type DriveScopeChecker = (response: unknown, scope: string) => boolean;

/** Production checker: if GIS cannot say the Drive scope was granted, refuse the token. */
export function productionDriveScopeChecker(response: unknown, scope: string): boolean {
  const oauth2 = (window as unknown as { google?: { accounts?: { oauth2?: { hasGrantedAllScopes?: unknown } } } }).google?.accounts?.oauth2;
  if (typeof oauth2?.hasGrantedAllScopes !== 'function') return false;
  return Boolean((oauth2.hasGrantedAllScopes as (r: unknown, s: string) => boolean)(response, scope));
}

/** Default implementation: loads script tag into the document. */
export class DefaultScriptLoader implements ScriptLoader {
  async load(src: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.async = true;
      script.defer = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new SignInError('unavailable'));
      document.head.appendChild(script);
    });
  }
}

/** Default implementation: reads from window.__DOORPRINTS__.googleClientId. */
export class WindowGoogleConfig implements GoogleConfig {
  get clientId(): string {
    const config = (window as unknown as { __DOORPRINTS__?: { googleClientId?: string } })?.__DOORPRINTS__;
    return config?.googleClientId ?? '';
  }
}

/**
 * Google Identity Services TokenProvider (S4b-BL-117).
 * Uses GIS TOKEN model (google.accounts.oauth2.initTokenClient).
 * - Scope: https://www.googleapis.com/auth/drive.file
 * - Token lifetime: 1 hour
 * - No refresh token, no secret
 * - Token held in memory only (never localStorage/IndexedDB)
 * - GIS script loaded LAZILY only when user presses Connect, never at app start
 */
export class GoogleTokenProvider implements TokenProvider {
  private tokenClient: unknown | null = null;
  private currentToken: string | null = null;
  private tokenExpiresAtMs: number | null = null;
  private scriptLoaded = false;
  private loadingScript: Promise<void> | null = null;
  private onResponse: ((response: unknown) => void) | null = null;
  private onClientError: ((error: unknown) => void) | null = null;

  constructor(
    private readonly scriptLoader: ScriptLoader,
    private readonly config: GoogleConfig,
    private readonly scopeGranted: DriveScopeChecker = productionDriveScopeChecker,
  ) {}

  /**
   * Returns a valid access token.
   * - On first call, loads the GIS script and initializes the token client.
   * - On expiry (1 hour), requests a fresh token silently.
   * - Returns UNAUTHORIZED if token expired and refresh failed.
   * - Throws for offline, popup blocked/closed/denied.
   */
  async accessToken(): Promise<string> {
    const clientId = this.config.clientId;
    if (!clientId) {
      throw new Error('Google Client ID not configured');
    }

    // Check if we have a valid token
    if (this.currentToken && this.tokenExpiresAtMs && Date.now() < this.tokenExpiresAtMs) {
      return this.currentToken;
    }

    // Load GIS script if needed
    if (!this.scriptLoaded) {
      await this.ensureScriptLoaded();
    }

    // Request a fresh token
    return this.requestToken(clientId);
  }

  /**
   * Called when Drive rejects a token (401).
   * Clears the cached token so the next accessToken() is fresh.
   */
  async onRejected(token: string): Promise<void> {
    if (this.currentToken === token) {
      this.currentToken = null;
      this.tokenExpiresAtMs = null;
    }
  }

  protected async ensureScriptLoaded(): Promise<void> {
    if (this.scriptLoaded) return;
    if (this.loadingScript) {
      await this.loadingScript;
      return;
    }

    this.loadingScript = this.scriptLoader
      .load('https://accounts.google.com/gsi/client')
      .then(() => {
        this.scriptLoaded = true;
      })
      .catch(() => {
        throw new SignInError(typeof navigator !== 'undefined' && navigator.onLine === false ? 'offline' : 'unavailable');
      });

    try {
      await this.loadingScript;
    } finally {
      this.loadingScript = null;
    }
  }

  protected async requestToken(clientId: string): Promise<string> {
    const gis = (window as unknown as { google?: unknown })?.google;
    if (!gis || typeof gis !== 'object') {
      throw new SignInError('unavailable');
    }

    const accounts = (gis as Record<string, unknown>).accounts;
    if (!accounts || typeof accounts !== 'object') {
      throw new SignInError('unavailable');
    }

    const oauth2 = (accounts as Record<string, unknown>).oauth2;
    if (!oauth2 || typeof oauth2 !== 'object') {
      throw new SignInError('unavailable');
    }

    const initTokenClient = (oauth2 as Record<string, unknown>).initTokenClient;
    if (typeof initTokenClient !== 'function') {
      throw new SignInError('unavailable');
    }

    // Initialize token client if not done yet
    if (!this.tokenClient) {
      this.tokenClient = this.createTokenClient(initTokenClient, clientId);
    }

    // Request a token
    return this.doRequestToken();
  }

  protected createTokenClient(
    initTokenClient: Function,
    clientId: string,
  ): unknown {
    return (initTokenClient as Function)({
      client_id: clientId,
      scope: DRIVE_SCOPE,
      callback: (response: unknown) => this.onResponse?.(response),
      // The popup failing to open or being closed is reported here, not through `callback`: without it a closed
      // popup would leave the sign-in waiting forever.
      error_callback: (error: unknown) => this.onClientError?.(error),
    });
  }

  protected async doRequestToken(): Promise<string> {
    return new Promise((resolve, reject) => {
      const client = this.tokenClient as Record<string, unknown>;
      if (typeof client.requestAccessToken !== 'function') {
        reject(new SignInError('unavailable'));
        return;
      }

      const settle = () => {
        this.onResponse = null;
        this.onClientError = null;
      };

      const handler = (response: unknown) => {
        settle();
        const resp = response as { access_token?: string; error?: string } | null;

        if (!resp) {
          reject(new SignInError('unavailable'));
          return;
        }

        if (resp.error) {
          const error = resp.error.toLowerCase();
          if (error.includes('popup_blocked') || error.includes('popup_failed_to_open')) {
            reject(new SignInError('popup_blocked'));
          } else if (error.includes('popup_closed')) {
            reject(new SignInError('popup_closed'));
          } else {
            // access_denied, and any other refusal: nothing was granted.
            reject(new SignInError('denied'));
          }
          return;
        }

        if (!resp.access_token || !this.grantsDrive(resp)) {
          // No token, or the person ticked off the Drive permission on Google's page (granular consent).
          reject(new SignInError('denied'));
          return;
        }

        // Token is valid for 1 hour (3600 seconds)
        this.currentToken = resp.access_token;
        this.tokenExpiresAtMs = Date.now() + 3600 * 1000;
        resolve(resp.access_token);
      };

      this.onResponse = handler;
      this.onClientError = (error: unknown) => {
        const type = String((error as { type?: unknown } | null)?.type ?? '');
        handler({ error: type || 'access_denied' });
      };
      client.callback = handler;

      try {
        (client.requestAccessToken as Function)({ prompt: '' });
      } catch (e) {
        settle();
        reject(e instanceof Error && e.message.includes('offline') ? new SignInError('offline') : new SignInError('unavailable'));
      }
    });
  }

  /** Whether Google says the Drive permission was granted. Fails closed when GIS cannot tell. */
  private grantsDrive(response: unknown): boolean {
    return this.scopeGranted(response, DRIVE_SCOPE);
  }
}
