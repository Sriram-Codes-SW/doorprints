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

import type { PrfAuthenticator, PrfResult } from './prf-seal';
import { prfInput } from './prf-seal';

/** Decode a base64url string to Uint8Array. */
function base64urlToBytes(base64url: string): Uint8Array {
  // Pad the string if needed
  let padded = base64url.replace(/-/g, '+').replace(/_/g, '/');
  padded = padded.padEnd(padded.length + ((4 - (padded.length % 4)) % 4), '=');
  const binaryString = atob(padded);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes;
}

function randomBytes(n: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(n));
}

/** WebAuthn PRF extension input shape. */
interface PrfExtensionInput {
  prf?: {
    eval?: {
      first: BufferSource;
    };
  };
}

/** WebAuthn PRF extension output shape from getClientExtensionResults(). */
interface PrfExtensionOutput {
  prf?: {
    enabled?: boolean;
    results?: {
      first?: ArrayBuffer;
    };
  };
}

/** PublicKeyCredentialCreationOptions with PRF extension. */
interface CreationOptionsWithPrf {
  publicKey: PublicKeyCredentialCreationOptions & {
    extensions?: {
      prf?: Record<string, never>;
    };
  };
}

/** PublicKeyCredentialRequestOptions with PRF extension. */
interface RequestOptionsWithPrf {
  publicKey: PublicKeyCredentialRequestOptions & PrfExtensionInput;
}

/** Type guard to check if extension output has PRF results. */
function isPrfExtensionOutput(ext: unknown): ext is PrfExtensionOutput {
  return ext !== null && typeof ext === 'object' && 'prf' in ext;
}

/** The 32-byte PRF output, or null when this ceremony did not return one. `enabled` is not that output. */
function prfOutputOf(ext: unknown): Uint8Array | null {
  if (!isPrfExtensionOutput(ext)) return null;
  const first = ext.prf?.results?.first as BufferSource | undefined;
  if (!first) return null;
  const bytes = first instanceof ArrayBuffer
    ? new Uint8Array(first.slice(0))
    : new Uint8Array(first.buffer.slice(first.byteOffset, first.byteOffset + first.byteLength));
  return bytes.length === 32 ? bytes : null;
}

function credentialIdBytes(credential: Credential): Uint8Array {
  if ('rawId' in credential && credential.rawId instanceof ArrayBuffer) {
    return new Uint8Array(credential.rawId.slice(0));
  }
  if ('id' in credential && typeof credential.id === 'string') return base64urlToBytes(credential.id);
  throw new Error('Passkey registration returned no credential id.');
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** The credential exists, but it did not return the PRF output the deletion seal needs. */
export class PasskeyPrfMissingError extends Error {
  constructor() {
    super('This passkey did not return the PRF output needed to seal deletions.');
    this.name = 'PasskeyPrfMissingError';
  }
}

/** Hostname used as WebAuthn `rp.id`. IPs and an empty host map to `localhost`. */
export function relyingPartyIdOf(hostname: string): string {
  const host = hostname.trim().toLowerCase();
  if (!host || host === '127.0.0.1' || host === '[::1]' || host === '::1') return 'localhost';
  return host;
}

function defaultRelyingPartyId(): string {
  if (typeof location === 'undefined' || !location.hostname) return 'localhost';
  return relyingPartyIdOf(location.hostname);
}

/** Type guard to check if credential has getClientExtensionResults method. */
function hasGetClientExtensionResults(
  cred: unknown
): cred is { getClientExtensionResults: () => unknown } {
  return (
    cred !== null &&
    typeof cred === 'object' &&
    'getClientExtensionResults' in cred &&
    typeof (cred as Record<string, unknown>).getClientExtensionResults === 'function'
  );
}

/**
 * The real WebAuthn PRF authenticator for the browser (S4b-BL-127). Uses navigator.credentials
 * with user verification REQUIRED. The credential ID is stored in IndexedDB and reused for all
 * assertions. Feature-detects PRF support and gracefully falls back to NOT_SUPPORTED.
 *
 * S4b-BL-127, docs/15 §10.4.
 */
export class WebAuthnPrfAuthenticator implements PrfAuthenticator {
  private credentialId: Uint8Array | null = null;
  /** Output from the registration ceremony, not stored until the sealed blob is kept. */
  private pending: { credentialId: Uint8Array; salt: Uint8Array; output: Uint8Array } | null = null;

  constructor(
    private readonly storageKey: (key: string) => Promise<string | undefined>,
    private readonly storageSet: (key: string, value: string) => Promise<void>,
    private readonly relyingPartyId: () => string = defaultRelyingPartyId,
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
  async evaluate(
    credentialId: Uint8Array,
    salt: Uint8Array,
    evalOptions?: { persist?: boolean },
  ): Promise<PrfResult> {
    const persist = evalOptions?.persist !== false;
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
      const options: CredentialRequestOptions = {
        publicKey: {
          challenge: randomBytes(32), // nothing verifies a signature (no server): fresh anyway, so no assertion is replayable
          userVerification: 'required',
          allowCredentials: [
            {
              type: 'public-key',
              id: targetId,
            },
          ],
          extensions: {
            prf: {
              eval: {
                first: prfInput(salt),
              },
            },
          },
        } as PublicKeyCredentialRequestOptions & PrfExtensionInput,
      };

      const assertion = await navigator.credentials.get(options);

      if (!assertion) {
        return { kind: 'CANCELLED' };
      }

      // Extract PRF output from extension result
      if (!hasGetClientExtensionResults(assertion)) {
        return { kind: 'FAILED' };
      }

      const extensionResults = assertion.getClientExtensionResults();
      if (!extensionResults || !isPrfExtensionOutput(extensionResults)) {
        return { kind: 'NOT_SUPPORTED' };
      }

      const prfExt = extensionResults.prf;
      if (!prfExt || !prfExt.results || !prfExt.results.first) {
        return { kind: 'NOT_SUPPORTED' };
      }

      const prfOutput = prfOutputOf(extensionResults);
      if (!prfOutput) {
        return { kind: 'NOT_SUPPORTED' };
      }

      // A registration probe must not count as success: the sealed blob is what the deletion flow keeps.
      if (persist && (!this.credentialId || this.credentialId.length === 0)) {
        await this.commitRegistration(targetId);
      }

      return { kind: 'OK', output: prfOutput };
    } catch (e) {
      const error = e instanceof Error ? e : new Error(String(e));
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

  consumeRegistrationPrf(credentialId: Uint8Array): { salt: Uint8Array; output: Uint8Array } | null {
    const pending = this.pending;
    this.pending = null;
    if (!pending || !sameBytes(pending.credentialId, credentialId)) return null;
    return { salt: pending.salt, output: pending.output };
  }

  async commitRegistration(credentialId: Uint8Array): Promise<void> {
    const idStr = Array.from(credentialId).join(',');
    await this.storageSet('doorprints-webauthn-credential-id', idStr);
    this.credentialId = credentialId.slice();
  }

  /**
   * Registers a new discoverable platform passkey and asks for a PRF output in that same ceremony.
   * Returns the credential ID, or null when the person cancelled the prompt.
   * The id is not stored here. A credential that never returns a 32-byte PRF output throws
   * {@link PasskeyPrfMissingError} — that is not "no platform authenticator".
   */
  async registerPasskey(displayName: string): Promise<Uint8Array | null> {
    if (typeof navigator === 'undefined' || !navigator.credentials) {
      return null;
    }

    try {
      const salt = randomBytes(32);
      const options: CredentialCreationOptions = {
        publicKey: {
          challenge: randomBytes(32),
          rp: {
            name: 'Doorprints',
            id: relyingPartyIdOf(this.relyingPartyId()),
          },
          user: {
            // Random per passkey: the same id would make some authenticators replace the earlier passkey.
            id: randomBytes(16),
            name: 'doorprints-user',
            displayName: displayName || 'Doorprints User',
          },
          // ES256 first, then RS256. Windows Hello refuses a list that omits RS256 before it
          // shows a prompt (Chromium: content/browser/webauth/pub_key_cred_params.md).
          pubKeyCredParams: [
            { alg: -7, type: 'public-key' },
            { alg: -257, type: 'public-key' },
          ],
          authenticatorSelection: {
            authenticatorAttachment: 'platform',
            // A passkey is a discoverable credential. Without this, the default is "discouraged".
            residentKey: 'required',
            requireResidentKey: true,
            userVerification: 'required',
          },
          // Evaluate during create so one Windows Hello PIN returns the PRF output. An empty
          // `prf: {}` only asks whether the extension is enabled, and Windows Hello reports that
          // as false after the PIN even when an evaluation would succeed.
          extensions: {
            prf: {
              eval: {
                first: prfInput(salt),
              },
            },
          },
        } as PublicKeyCredentialCreationOptions & {
          authenticatorSelection?: {
            authenticatorAttachment?: string;
            residentKey?: string;
            requireResidentKey?: boolean;
            userVerification?: string;
          };
          extensions?: {
            prf?: { eval?: { first: BufferSource } };
          };
        },
      };

      const credential = await navigator.credentials.create(options);

      if (!credential) {
        return null;
      }

      const credIdBuffer = credentialIdBytes(credential);
      // `prf.enabled` is not the output the deletion flow opens. Windows Hello can leave it false
      // after a successful PIN and still return results.first from this eval, or from one assertion.
      let output = hasGetClientExtensionResults(credential)
        ? prfOutputOf(credential.getClientExtensionResults())
        : null;
      if (!output) {
        const evaluated = await this.evaluate(credIdBuffer, salt, { persist: false });
        if (evaluated.kind === 'CANCELLED') return null;
        if (evaluated.kind !== 'OK') throw new PasskeyPrfMissingError();
        output = evaluated.output;
      }
      this.pending = {
        credentialId: credIdBuffer.slice(),
        salt: salt.slice(),
        output: output.slice(),
      };
      return credIdBuffer;
    } catch (e) {
      // DOMException is not an Error in every runtime, but it still carries name.
      const name =
        e !== null && typeof e === 'object' && 'name' in e && typeof e.name === 'string' ? e.name : '';
      // NotAllowedError is cancel or a timeout, including the person dismissing Windows Hello.
      if (name === 'NotAllowedError') return null;
      if (e instanceof Error) throw e;
      throw new Error(name ? `${name}: ${String(e)}` : String(e));
    }
  }
}
