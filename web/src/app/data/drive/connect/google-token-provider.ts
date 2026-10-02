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

import type { TokenProvider } from '../drive-client';

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

/** Default implementation: loads script tag into the document. */
export class DefaultScriptLoader implements ScriptLoader {
  async load(src: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.async = true;
      script.defer = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error(`Failed to load script: ${src}`));
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

  constructor(
    private readonly scriptLoader: ScriptLoader,
    private readonly config: GoogleConfig,
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

  private async ensureScriptLoaded(): Promise<void> {
    if (this.scriptLoaded) return;
    if (this.loadingScript) {
      await this.loadingScript;
      return;
    }

    this.loadingScript = this.scriptLoader
      .load('https://accounts.google.com/gsi/client')
      .then(() => {
        this.scriptLoaded = true;
      });

    try {
      await this.loadingScript;
    } finally {
      this.loadingScript = null;
    }
  }

  private async requestToken(clientId: string): Promise<string> {
    const gis = (window as unknown as { google?: unknown })?.google;
    if (!gis || typeof gis !== 'object') {
      throw new Error('Google Identity Services not loaded');
    }

    const accounts = (gis as Record<string, unknown>).accounts;
    if (!accounts || typeof accounts !== 'object') {
      throw new Error('Google Identity Services not available');
    }

    const oauth2 = (accounts as Record<string, unknown>).oauth2;
    if (!oauth2 || typeof oauth2 !== 'object') {
      throw new Error('Google OAuth2 not available');
    }

    const initTokenClient = (oauth2 as Record<string, unknown>).initTokenClient;
    if (typeof initTokenClient !== 'function') {
      throw new Error('initTokenClient not available');
    }

    // Initialize token client if not done yet
    if (!this.tokenClient) {
      this.tokenClient = (initTokenClient as Function)({
        client_id: clientId,
        scope: 'https://www.googleapis.com/auth/drive.file',
        callback: () => {}, // We'll use requestAccessToken instead
      });
    }

    // Request a token
    return new Promise((resolve, reject) => {
      const client = this.tokenClient as Record<string, unknown>;
      if (typeof client.requestAccessToken !== 'function') {
        reject(new Error('requestAccessToken not available'));
        return;
      }

      try {
        (client.requestAccessToken as Function)({
          prompt: this.currentToken ? '' : 'consent',
        });

        // Hook into the callback
        const originalCallback = client.callback;
        client.callback = (response: unknown) => {
          const resp = response as {
            access_token?: string;
            error?: string;
          } | null;

          if (!resp) {
            reject(new Error('No response from Google'));
            return;
          }

          if (resp.error) {
            const error = resp.error.toLowerCase();
            if (error.includes('popup_blocked')) {
              reject(new Error('popup_blocked'));
            } else if (error.includes('popup_closed')) {
              reject(new Error('popup_closed'));
            } else if (error.includes('access_denied') || error.includes('denied')) {
              reject(new Error('denied'));
            } else {
              reject(new Error(`Google error: ${resp.error}`));
            }
            return;
          }

          if (!resp.access_token) {
            reject(new Error('No access token in response'));
            return;
          }

          // Token is valid for 1 hour (3600 seconds)
          this.currentToken = resp.access_token;
          this.tokenExpiresAtMs = Date.now() + 3600 * 1000;
          resolve(resp.access_token);

          // Restore original callback
          if (typeof originalCallback === 'function') {
            client.callback = originalCallback;
          }
        };
      } catch (e) {
        if ((e as Error).message.includes('offline')) {
          reject(new Error('offline'));
        } else {
          reject(e);
        }
      }
    });
  }
}
