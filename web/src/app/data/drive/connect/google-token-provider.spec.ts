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

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { GoogleTokenProvider } from './google-token-provider';
import type { ScriptLoader, GoogleConfig } from './google-token-provider';

class FakeScriptLoader implements ScriptLoader {
  loadCalls: string[] = [];
  failLoad = false;

  async load(src: string): Promise<void> {
    this.loadCalls.push(src);
    if (this.failLoad) {
      throw new Error('Script load failed');
    }
  }
}

class FakeGoogleConfig implements GoogleConfig {
  constructor(readonly clientId: string) {}
}

class EmptyGoogleConfig implements GoogleConfig {
  readonly clientId = '';
}

class TestableGoogleTokenProvider extends GoogleTokenProvider {
  requestAccessTokenCalls: Array<{ prompt?: string }> = [];
  responseCallback: ((response: unknown) => void) | null = null;

  protected override createTokenClient(
    initTokenClient: Function,
    clientId: string,
  ): unknown {
    const self = this;
    const mock = {
      requestAccessToken: (opts: { prompt?: string }) => {
        self.requestAccessTokenCalls.push(opts);
        self.responseCallback = (response: unknown) => {
          const cb = (mock as Record<string, unknown>).callback as ((r: unknown) => void) | null;
          if (cb) cb(response);
        };
      },
      callback: null as ((response: unknown) => void) | null,
    };
    return mock;
  }

  triggerCallback(response: unknown): void {
    if (this.responseCallback) {
      this.responseCallback(response);
    }
  }
}

describe('GoogleTokenProvider', () => {
  let scriptLoader: FakeScriptLoader;
  let config: FakeGoogleConfig;

  beforeEach(() => {
    scriptLoader = new FakeScriptLoader();
    config = new FakeGoogleConfig('test-client-id.apps.example');
    // Mock GIS in window
    (window as unknown as Record<string, unknown>).google = {
      accounts: {
        oauth2: {
          initTokenClient: () => ({}),
        },
      },
    };
  });

  afterEach(() => {
    delete (window as unknown as Record<string, unknown>).google;
  });

  it('should throw when no client ID is configured', async () => {
    const emptyConfig = new EmptyGoogleConfig();
    const provider = new TestableGoogleTokenProvider(scriptLoader, emptyConfig);

    await expect(provider.accessToken()).rejects.toThrow('Google Client ID not configured');
  });

  it('should throw when script load fails', async () => {
    scriptLoader.failLoad = true;
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    await expect(provider.accessToken()).rejects.toThrow('unavailable');
  });

  it('should successfully request a token', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ access_token: 'test-token-123' }), 0);
    const token = await tokenPromise;

    expect(token).toBe('test-token-123');
    expect(scriptLoader.loadCalls).toContain('https://accounts.google.com/gsi/client');
  });

  it('should cache token within 1 hour', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise1 = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ access_token: 'test-token-123' }), 0);
    const token1 = await tokenPromise1;

    const token2 = await provider.accessToken();

    expect(token1).toBe('test-token-123');
    expect(token2).toBe('test-token-123');
    expect(provider.requestAccessTokenCalls).toHaveLength(1);
  });

  it('should load GIS script only once', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise1 = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ access_token: 'test-token-123' }), 0);
    await tokenPromise1;

    const token2 = await provider.accessToken();
    expect(token2).toBe('test-token-123');

    expect(scriptLoader.loadCalls).toHaveLength(1);
  });

  it('should handle denied error', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ error: 'access_denied' }), 0);

    await expect(tokenPromise).rejects.toThrow('denied');
  });

  it('should handle popup closed error', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ error: 'popup_closed' }), 0);

    await expect(tokenPromise).rejects.toThrow('popup_closed');
  });

  it('should handle popup blocked error', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ error: 'popup_blocked' }), 0);

    await expect(tokenPromise).rejects.toThrow('popup_blocked');
  });

  it('should clear token on onRejected', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise1 = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ access_token: 'test-token-123' }), 0);
    const token = await tokenPromise1;

    await provider.onRejected(token);

    const tokenPromise2 = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ access_token: 'test-token-456' }), 0);
    const token2 = await tokenPromise2;

    expect(token2).toBe('test-token-456');
    expect(provider.requestAccessTokenCalls).toHaveLength(2);
  });

  it('should not clear token on onRejected with different token', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config, () => true);

    const tokenPromise1 = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ access_token: 'test-token-123' }), 0);
    const token = await tokenPromise1;

    await provider.onRejected('different-token');

    const token2 = await provider.accessToken();

    expect(token2).toBe('test-token-123');
    expect(provider.requestAccessTokenCalls).toHaveLength(1);
  });

  it('refuses a token when GIS cannot say the Drive scope was granted', async () => {
    const provider = new TestableGoogleTokenProvider(scriptLoader, config);
    const tokenPromise = provider.accessToken();
    setTimeout(() => provider.triggerCallback({ access_token: 'test-token-123' }), 0);
    await expect(tokenPromise).rejects.toMatchObject({ kind: 'denied' });
  });
});


describe('GoogleTokenProvider with the real token client wiring', () => {
  interface Captured {
    config: { callback: (r: unknown) => void; error_callback: (e: unknown) => void; scope: string };
    requests: Array<{ prompt?: string }>;
    revoked: string[];
  }

  function install(granted: boolean): Captured {
    const captured: Captured = { config: null as never, requests: [], revoked: [] };
    (window as unknown as Record<string, unknown>).google = {
      accounts: {
        oauth2: {
          initTokenClient: (config: Captured['config']) => {
            captured.config = config;
            return { requestAccessToken: (o: { prompt?: string }) => captured.requests.push(o) };
          },
          hasGrantedAllScopes: () => granted,
          revoke: (token: string, done: () => void) => {
            captured.revoked.push(token);
            done();
          },
        },
      },
    };
    return captured;
  }

  afterEach(() => {
    delete (window as unknown as Record<string, unknown>).google;
  });

  const loader: ScriptLoader = { load: async () => undefined };
  const cfg: GoogleConfig = { clientId: 'client.apps.example' };
  const tick = () => new Promise((r) => setTimeout(r, 0));

  it('asks only for the drive.file scope', async () => {
    const g = install(true);
    const p = new GoogleTokenProvider(loader, cfg).accessToken();
    await tick();
    expect(g.config.scope).toBe('https://www.googleapis.com/auth/drive.file');
    g.config.callback({ access_token: 't' });
    await expect(p).resolves.toBe('t');
  });

  it('a popup the person closes ends the sign-in instead of waiting forever', async () => {
    const g = install(true);
    const p = new GoogleTokenProvider(loader, cfg).accessToken();
    await tick();
    g.config.error_callback({ type: 'popup_closed' });
    await expect(p).rejects.toMatchObject({ kind: 'popup_closed' });
  });

  it('a popup that could not open is a blocked popup', async () => {
    const g = install(true);
    const p = new GoogleTokenProvider(loader, cfg).accessToken();
    await tick();
    g.config.error_callback({ type: 'popup_failed_to_open' });
    await expect(p).rejects.toMatchObject({ kind: 'popup_blocked' });
  });

  it('a token without the Drive permission (ticked off on the consent page) is refused', async () => {
    const g = install(false);
    const p = new GoogleTokenProvider(loader, cfg).accessToken();
    await tick();
    g.config.callback({ access_token: 'no-drive' });
    await expect(p).rejects.toMatchObject({ kind: 'denied' });
  });

  it('a refused token is not kept: the next call asks again', async () => {
    const g = install(false);
    const provider = new GoogleTokenProvider(loader, cfg);
    const first = provider.accessToken();
    await tick();
    g.config.callback({ access_token: 'no-drive' });
    await expect(first).rejects.toBeTruthy();
    const second = provider.accessToken();
    await tick();
    expect(g.requests).toHaveLength(2);
    g.config.error_callback({ type: 'popup_closed' });
    await expect(second).rejects.toBeTruthy();
  });

  it('revokeAccess asks GIS to drop the in-memory token and then asks again', async () => {
    const g = install(true);
    const provider = new GoogleTokenProvider(loader, cfg);
    const first = provider.accessToken();
    await tick();
    g.config.callback({ access_token: 'memory-token' });
    await expect(first).resolves.toBe('memory-token');
    await provider.revokeAccess();
    expect(g.revoked).toEqual(['memory-token']);
    const again = provider.accessToken();
    await tick();
    expect(g.requests).toHaveLength(2);
    g.config.error_callback({ type: 'popup_closed' });
    await expect(again).rejects.toBeTruthy();
  });
});
