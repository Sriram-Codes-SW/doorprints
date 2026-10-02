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

import { Injectable } from '@angular/core';
import type { TokenProvider } from '../drive-client';
import { GoogleConfig, GoogleTokenProvider } from './google-token-provider';

/**
 * Seam for Google Drive OAuth sign-in (tests inject a fake; production uses GoogleTokenProvider).
 * S4b-BL-117, S4b-BL-73.
 */
@Injectable()
export class DriveSignIn {
  private tokenProvider: TokenProvider | null = null;
  private connected = false;

  constructor(private readonly config: GoogleConfig) {}

  available(): boolean {
    return this.config.clientId?.length > 0;
  }

  async connect(): Promise<TokenProvider> {
    if (!this.available()) {
      throw new Error('Google OAuth not configured');
    }
    this.tokenProvider = new GoogleTokenProvider(new DefaultScriptLoader(), this.config);
    // Test the token by requesting one
    await this.tokenProvider.accessToken();
    this.connected = true;
    return this.tokenProvider;
  }

  disconnect(): void {
    this.tokenProvider = null;
    this.connected = false;
  }

  isConnected(): boolean {
    return this.connected;
  }
}

/** Minimal script loader for production. */
class DefaultScriptLoader {
  async load(src: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error(`Failed to load ${src}`));
      document.head.appendChild(script);
    });
  }
}
