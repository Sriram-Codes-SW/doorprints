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
import { prfInput, prfOutputSeals } from './prf-seal';

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
  if (first instanceof ArrayBuffer) {
    return first.byteLength === 32 ? new Uint8Array(first.slice(0)) : null;
  }
  if (!ArrayBuffer.isView(first) || first.byteLength !== 32) return null;
  return new Uint8Array(first.buffer.slice(first.byteOffset, first.byteOffset + first.byteLength));
}

function rawIdBytes(credential: Credential): Uint8Array | null {
  if (!('rawId' in credential) || credential.rawId == null) return null;
  const raw = credential.rawId as BufferSource;
  if (raw instanceof ArrayBuffer) return raw.byteLength > 0 ? new Uint8Array(raw.slice(0)) : null;
  if (ArrayBuffer.isView(raw) && raw.byteLength > 0) {
    return new Uint8Array(raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength));
  }
  return null;
}

function credentialIdBytes(credential: Credential): Uint8Array {
  const fromRaw = rawIdBytes(credential);
  const fromId =
    'id' in credential && typeof credential.id === 'string' && credential.id.length > 0
      ? base64urlToBytes(credential.id)
      : null;
  // rawId is authoritative. A string id that disagrees with it is not this credential.
  if (fromRaw && fromId && !sameBytes(fromRaw, fromId)) {
    throw new Error('Passkey credential id did not match rawId.');
  }
  if (fromRaw) return fromRaw;
  if (fromId) return fromId;
  throw new Error('Passkey registration returned no credential id.');
}

/** UV flag in authenticatorData. Creation asks for it; an assertion must show it. There is no server to check. */
function assertionUserVerified(credential: Credential): boolean {
  if (!('response' in credential) || credential.response == null || typeof credential.response !== 'object') {
    return false;
  }
  const data = (credential.response as { authenticatorData?: BufferSource }).authenticatorData;
  if (!data) return false;
  const flags = data instanceof ArrayBuffer
    ? new Uint8Array(data)
    : ArrayBuffer.isView(data)
      ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
      : null;
  return !!flags && flags.length >= 33 && (flags[32] & 0x04) !== 0;
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** The credential exists, but it did not return the PRF output the deletion seal needs. */
export class PasskeyPrfMissingError extends Error {
  constructor(
    /** Which step returned no output (booleans and step names only, never a secret); for the card's copyable details. */
    readonly details: string | null = null,
  ) {
    super('This passkey did not return the PRF output needed to seal deletions.');
    this.name = 'PasskeyPrfMissingError';
  }
}

/** What a create ceremony's extension results hold about the PRF: step names and flags only, never a value. */
function describeCreateResult(ext: unknown): string {
  if (ext === null || typeof ext !== 'object') return 'no-extension-results';
  if (!('prf' in ext)) return 'prf-absent';
  const prf = (ext as PrfExtensionOutput).prf;
  const enabled = prf?.enabled === undefined ? 'unset' : String(prf.enabled);
  return `prf-present enabled=${enabled} first=${prf?.results?.first ? 'yes' : 'no'}`;
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
  /** The step the last assertion ended at (a name, never a value): 'ok', 'no-prf-results enabled=false', ... */
  private assertionStage = 'not-tried';
  private lastDetails: string | null = null;
  /** Output from the registration ceremony, not stored until the sealed blob is kept. */
  private pending: { credentialId: Uint8Array; salt: Uint8Array; output: Uint8Array } | null = null;

  constructor(
    private readonly storageKey: (key: string) => Promise<string | undefined>,
    private readonly storageSet: (key: string, value: string) => Promise<void>,
    private readonly relyingPartyId: () => string = defaultRelyingPartyId,
    private readonly getPublicKeyCredential: () => typeof PublicKeyCredential | undefined = () => typeof PublicKeyCredential !== 'undefined' ? PublicKeyCredential : undefined,
  ) {}

  /**
   * Checks if the browser supports WebAuthn with PRF.
   * Returns true when navigator.credentials and PublicKeyCredential both exist (also when a cached credential id exists).
   * Returns false if: no WebAuthn support or the browser lacks required APIs.
   * Does NOT call isUserVerifyingPlatformAuthenticatorAvailable anymore.
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

    // Check if PublicKeyCredential is available
    const PubKeyCredential = this.getPublicKeyCredential();
    return PubKeyCredential !== undefined;
  }

  /**
   * Evaluates the PRF with user verification. Requires a registered passkey and PRF support.
   * On first call without a stored credential, attempts registration with PRF.
   * On subsequent calls, uses the stored credential ID for assertion.
   */
  async evaluate(
    credentialId: Uint8Array,
    salt: Uint8Array,
    evalOptions?: { persist?: boolean; useStoredCredential?: boolean },
  ): Promise<PrfResult> {
    const persist = evalOptions?.persist !== false;
    // Opens keep using the stored passkey. A registration probe must pass false: otherwise an
    // already stored id is asserted, and its PRF output would be sealed under the new credential.
    const useStored = evalOptions?.useStoredCredential !== false;
    if (typeof navigator === 'undefined' || !navigator.credentials) {
      return { kind: 'NOT_SUPPORTED' };
    }

    this.assertionStage = 'started';
    try {
      let targetId = credentialId;
      if (useStored && this.credentialId && this.credentialId.length > 0) {
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
        this.assertionStage = 'no-assertion';
        return { kind: 'CANCELLED' };
      }

      // userVerification: "required" is also checked on the response. A clear UV flag is not a PRF result.
      if (!assertionUserVerified(assertion)) {
        this.assertionStage = 'not-user-verified';
        return { kind: 'FAILED' };
      }

      let assertedId: Uint8Array;
      try {
        assertedId = credentialIdBytes(assertion);
      } catch {
        this.assertionStage = 'bad-credential-id';
        return { kind: 'FAILED' };
      }
      if (!sameBytes(assertedId, targetId)) {
        this.assertionStage = 'credential-id-mismatch';
        return { kind: 'FAILED' };
      }

      // Extract PRF output from extension result
      if (!hasGetClientExtensionResults(assertion)) {
        this.assertionStage = 'no-extension-api';
        return { kind: 'FAILED' };
      }

      const extensionResults = assertion.getClientExtensionResults();
      if (!extensionResults || !isPrfExtensionOutput(extensionResults)) {
        this.assertionStage = 'prf-absent';
        return { kind: 'NOT_SUPPORTED' };
      }

      const prfExt = extensionResults.prf;
      if (!prfExt || !prfExt.results || !prfExt.results.first) {
        this.assertionStage = `no-prf-results enabled=${prfExt?.enabled === undefined ? 'unset' : String(prfExt.enabled)}`;
        return { kind: 'NOT_SUPPORTED' };
      }

      const prfOutput = prfOutputOf(extensionResults);
      if (!prfOutput || !(await prfOutputSeals(prfOutput, salt))) {
        this.assertionStage = prfOutput ? 'output-does-not-seal' : 'output-not-32-bytes';
        prfOutput?.fill(0);
        return { kind: 'NOT_SUPPORTED' };
      }

      // A registration probe must not count as success: the sealed blob is what the deletion flow keeps.
      if (persist && (!this.credentialId || this.credentialId.length === 0)) {
        await this.commitRegistration(targetId);
      }

      this.assertionStage = 'ok';
      return { kind: 'OK', output: prfOutput };
    } catch (e) {
      const error = e instanceof Error ? e : new Error(String(e));
      // Extract error name from DOMException or Error object
      const errorName = (
        e !== null && typeof e === 'object' && 'name' in e && typeof e.name === 'string'
          ? e.name
          : error.name || 'unknown'
      );
      this.assertionStage = `error:${errorName}`;
      // Check for specific cancellation or unsupported errors
      if (errorName === 'NotAllowedError') {
        return { kind: 'CANCELLED' };
      }
      if (errorName === 'NotSupportedError' || error.message?.includes('PRF')) {
        return { kind: 'NOT_SUPPORTED' };
      }
      return { kind: 'FAILED' };
    }
  }

  /** Where the last passkey setup stopped, as step names and flags (never a value): for the card's copyable details. */
  lastPrfDetails(): string | null {
    return this.lastDetails;
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
   * Checks if this browser supports the WebAuthn PRF extension.
   * Returns true if the browser reports PRF is supported, false if not supported, null if indeterminate.
   */
  async prfCapability(): Promise<boolean | null> {
    const PubKeyCredential = this.getPublicKeyCredential();
    if (!PubKeyCredential || typeof PubKeyCredential.getClientCapabilities !== 'function') {
      return null;
    }
    try {
      const caps = await PubKeyCredential.getClientCapabilities();
      if (caps === null || typeof caps !== 'object') {
        return null;
      }
      const prfExt = (caps as Record<string, unknown>)['extension:prf'];
      if (prfExt === true) return true;
      if (prfExt === false) return false;
      return null;
    } catch {
      return null;
    }
  }

  /**
   * Checks if a built-in platform authenticator is available.
   * Returns true if available, false if not available, null if the check is unavailable or throws.
   */
  async builtInAuthenticatorAvailable(): Promise<boolean | null> {
    const PubKeyCredential = this.getPublicKeyCredential();
    if (!PubKeyCredential || typeof PubKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable !== 'function') {
      return null;
    }
    try {
      return await PubKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
    } catch {
      return null;
    }
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
      const createResults = hasGetClientExtensionResults(credential) ? credential.getClientExtensionResults() : null;
      const createNote = describeCreateResult(createResults);
      this.lastDetails = `create: ${createNote}; assertion: not-tried`;
      let output = createResults ? prfOutputOf(createResults) : null;
      if (output && !(await prfOutputSeals(output, salt))) {
        output.fill(0);
        output = null;
      }
      if (!output) {
        // The assertion has to be for this new credential, not one already stored in the browser.
        const evaluated = await this.evaluate(credIdBuffer, salt, {
          persist: false,
          useStoredCredential: false,
        });
        if (evaluated.kind === 'CANCELLED') return null;
        this.lastDetails = `create: ${createNote}; assertion: ${this.assertionStage}`;
        if (evaluated.kind === 'FAILED') {
          throw new Error('Passkey assertion was not user-verified for this credential.');
        }
        if (evaluated.kind !== 'OK') throw new PasskeyPrfMissingError(this.lastDetails);
        output = evaluated.output;
      }
      const kept = output.slice();
      output.fill(0);
      this.pending = {
        credentialId: credIdBuffer.slice(),
        salt: salt.slice(),
        output: kept,
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
