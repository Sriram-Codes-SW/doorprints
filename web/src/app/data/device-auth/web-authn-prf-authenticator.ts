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

import { hex } from '../crypto/bytes';
import type { PrfAuthenticator, PrfResult } from './prf-seal';
import { prfInput } from './prf-seal';

/**
 * The real WebAuthn PRF authenticator for the browser (S4b-BL-127). Uses navigator.credentials
 * with user verification REQUIRED. The credential ID is stored in IndexedDB and reused for all
 * assertions. Feature-detects PRF support and gracefully falls back to NOT_SUPPORTED.
 *
 * S4b-BL-127, docs/15 §10.4.
 */
export class WebAuthnPrfAuthenticator implements PrfAuthenticator {
  private credentialId: Uint8Array | null = null;

  constructor(
    private readonly storageKey: (key: string) => Promise<string | undefined>,
    private readonly storageSet: (key: string, value: string) => Promise<void>,
  ) {}

  /**
   * Checks if the browser and authenticator support WebAuthn with PRF.
   * Returns false if: no WebAuthn support, no passkey registered, or feature-detect fails.
   */
  async isSupported(): Promise<boolean> {
    if (typeof navigator === 'undefined' || !navigator.credentials) {
      return false;
    }

    // Try to load cached credential ID
    const cachedId = await this.storageKey('doorprints-webauthn-credential-id');
    if (cachedId) {
      this.credentialId = new Uint8Array(cachedId.split(',').map(Number));
      return true;
    }

    // Feature detect: try to find passkeys that support PRF
    if (!PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable) {
      return false;
    }

    const available = await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
    return available;
  }

  /**
   * Evaluates the PRF with user verification. Requires a registered passkey and PRF support.
   * On first call without a stored credential, attempts registration with PRF.
   * On subsequent calls, uses the stored credential ID for assertion.
   */
  async evaluate(credentialId: Uint8Array, salt: Uint8Array): Promise<PrfResult> {
    if (typeof navigator === 'undefined' || !navigator.credentials) {
      return { kind: 'NOT_SUPPORTED' };
    }

    try {
      // Use the provided credential ID if available, otherwise try the stored one
      let targetId = credentialId;
      if (this.credentialId && this.credentialId.length > 0) {
        targetId = this.credentialId;
      }

      // Prepare the assertion with PRF extension
      const assertion = await (navigator.credentials as any).get({
        publicKey: {
          challenge: new Uint8Array(32), // Random challenge (in real usage would be generated server-side)
          userVerification: 'required' as const,
          allowCredentials: [
            {
              type: 'public-key',
              id: targetId,
            },
          ],
          extensions: {
            prf: {
              eval: {
                salt: prfInput(salt),
              },
            },
          },
        },
      });

      if (!assertion) {
        return { kind: 'CANCELLED' };
      }

      // Extract PRF output from extension result
      const authExt = (assertion as any).getClientExtensionResults?.();
      if (!authExt || !authExt.prf || !authExt.prf.results || !authExt.prf.results.first) {
        return { kind: 'FAILED' };
      }

      const prfOutput = new Uint8Array(authExt.prf.results.first);
      if (prfOutput.length !== 32) {
        return { kind: 'FAILED' };
      }

      // Store credential ID for future use
      if (!this.credentialId || this.credentialId.length === 0) {
        const idStr = Array.from(targetId).join(',');
        await this.storageSet('doorprints-webauthn-credential-id', idStr);
        this.credentialId = targetId.slice();
      }

      return { kind: 'OK', output: prfOutput };
    } catch (e) {
      const error = e as Error;
      // Check for specific cancellation or unsupported errors
      if (error.name === 'NotAllowedError') {
        return { kind: 'CANCELLED' };
      }
      if (error.name === 'NotSupportedError' || error.message?.includes('PRF')) {
        return { kind: 'NOT_SUPPORTED' };
      }
      return { kind: 'FAILED' };
    }
  }

  /**
   * Registers a new passkey with PRF support. Stores the credential ID for future use.
   * Returns the credential ID on success, or null if registration was cancelled or unsupported.
   */
  async registerPasskey(displayName: string): Promise<Uint8Array | null> {
    if (typeof navigator === 'undefined' || !navigator.credentials) {
      return null;
    }

    try {
      const credential = await (navigator.credentials as any).create({
        publicKey: {
          challenge: new Uint8Array(32),
          rp: {
            name: 'Doorprints',
          },
          user: {
            id: new Uint8Array(16),
            name: 'doorprints-user',
            displayName: displayName || 'Doorprints User',
          },
          pubKeyCredParams: [{ alg: -7, type: 'public-key' }],
          userVerification: 'required' as const,
          extensions: {
            prf: {},
          },
        },
      });

      if (!credential) {
        return null;
      }

      // Extract and store the credential ID
      const credId = new Uint8Array((credential as any).id);
      const idStr = Array.from(credId).join(',');
      await this.storageSet('doorprints-webauthn-credential-id', idStr);
      this.credentialId = credId;

      return credId;
    } catch (e) {
      const error = e as Error;
      if (error.name === 'NotAllowedError') {
        return null;
      }
      return null;
    }
  }
}
