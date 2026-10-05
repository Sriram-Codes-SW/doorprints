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

import { concat, hex, unhex, utf8 } from "../crypto/bytes";
import { CryptoError } from "../crypto/crypto-provider";
import type { CryptoProvider } from "../crypto/crypto-provider";
import { Hkdf } from "../crypto/hpke";

/**
 * The website's passkey seam (docs/15 10.4, S4b-BL-127). The website's device key is sealed under a key derived from
 * the passkey's WebAuthn PRF output, so the key itself needs the person's user verification (once per page load):
 * no passkey, no key. This file holds the pure pieces; the browser part (`navigator.credentials` with the `prf`
 * extension, `userVerification: "required"`) implements [PrfAuthenticator] and is NOT wired here: it needs a real
 * browser and authenticator (TC-M-50).
 */
export type PrfResult =
  | { kind: "OK"; output: Uint8Array }
  | { kind: "CANCELLED" }
  | { kind: "NOT_SUPPORTED" }
  | { kind: "FAILED" };

/** Salt and PRF output from the registration ceremony, evaluated together. */
export interface RegistrationPrf {
  salt: Uint8Array;
  output: Uint8Array;
}

export interface PrfAuthenticator {
  /** A platform authenticator with the PRF extension is usable in this browser. */
  isSupported(): Promise<boolean>;
  /**
   * One user-verified assertion for [credentialId]. [salt] is the blob salt; the authenticator applies the label
   * before the PRF sees it. The PRF output is 32 bytes.
   */
  evaluate(credentialId: Uint8Array, salt: Uint8Array): Promise<PrfResult>;
  /** Where the last passkey setup stopped (step names and flags, never a value); null when there is nothing to say. */
  lastPrfDetails?(): string | null;
  /**
   * Makes a new passkey for this site. The credential id, or null when the person cancelled.
   * A credential that cannot produce a PRF output is refused (the promise rejects).
   * The id is not stored until {@link commitRegistration} — success is the sealed blob, not the ceremony.
   */
  registerPasskey(displayName: string): Promise<Uint8Array | null>;
  /**
   * PRF output already produced for [credentialId] during registration, and the salt it was evaluated on.
   * Null when this registration did not produce one. Consumed once.
   */
  consumeRegistrationPrf?(credentialId: Uint8Array): RegistrationPrf | null;
  /** Remember the credential id once the sealed blob that uses its PRF output is stored. */
  commitRegistration?(credentialId: Uint8Array): Promise<void>;
  /**
   * Whether this browser supports the WebAuthn PRF extension.
   * Returns true if supported, false if not supported, null if indeterminate (error or API unavailable).
   */
  prfCapability?(): Promise<boolean | null>;
}

export interface SealedBlob {
  v: 1;
  credentialId: Uint8Array;
  /** Per-blob random salt, given to the PRF as its input. */
  salt: Uint8Array;
  nonce: Uint8Array;
  ciphertext: Uint8Array;
}

export type SealOpen =
  | { ok: true; plaintext: Uint8Array }
  | {
      ok: false;
      reason: "CANCELLED" | "NOT_SUPPORTED" | "FAILED" | "WRONG_KEY";
    };

/** Where the sealed blob is kept (the Drive database's key-value store). */
export const SEALED_BLOB_KEY = "doorprints-deletion-sealed-blob";

/** The blob as text, for storage: the byte fields as hex (JSON.stringify would turn a Uint8Array into an object). */
export function sealedBlobToJson(b: SealedBlob): string {
  return JSON.stringify({ v: b.v, credentialId: hex(b.credentialId), salt: hex(b.salt), nonce: hex(b.nonce), ciphertext: hex(b.ciphertext) });
}

/** The blob from {@link sealedBlobToJson}'s text, or null when the text is not one (a damaged or foreign value). */
export function sealedBlobFromJson(text: string): SealedBlob | null {
  try {
    const o = JSON.parse(text) as Record<string, unknown>;
    if (o['v'] !== 1) return null;
    const field = (name: string): Uint8Array | null => {
      const v = o[name];
      return typeof v === 'string' && /^(?:[0-9a-f]{2})+$/.test(v) ? unhex(v) : null;
    };
    const credentialId = field('credentialId');
    const salt = field('salt');
    const nonce = field('nonce');
    const ciphertext = field('ciphertext');
    return credentialId && salt && nonce && ciphertext ? { v: 1, credentialId, salt, nonce, ciphertext } : null;
  } catch {
    return null;
  }
}

const INFO = utf8("doorprints/device-seal/1");
const SALT_PREFIX = utf8("doorprints/device-seal/1/prf-salt");

async function sealKey(
  p: CryptoProvider,
  prfOutput: Uint8Array,
  salt: Uint8Array,
) {
  if (prfOutput.length !== 32)
    throw new CryptoError("INVALID_KEY", "PRF output must be 32 bytes");
  const raw = await new Hkdf(p).derive(salt, prfOutput, INFO, 32);
  const key = await p.aesKey(raw);
  raw.fill(0);
  return key;
}

function aad(credentialId: Uint8Array): Uint8Array {
  return concat(INFO, credentialId);
}

/** The PRF input is the salt prefixed with a label, so it cannot be mistaken for another use of the same passkey. */
export function prfInput(salt: Uint8Array): Uint8Array {
  return concat(SALT_PREFIX, salt);
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/**
 * True only for a 32-byte authenticator result. An empty buffer, all zeros, the salt itself, the label's first 32
 * bytes, or the client-side WebAuthn salt (SHA-256 of "WebAuthn PRF" ‖ 0x00 ‖ {@link prfInput}) are public: sealing
 * with one of them would store a key anyone who can read the blob can recompute.
 */
export async function prfOutputSeals(output: Uint8Array, salt: Uint8Array): Promise<boolean> {
  if (output.length !== 32) return false;
  let zeros = true;
  for (let i = 0; i < output.length; i++) if (output[i] !== 0) zeros = false;
  if (zeros || sameBytes(output, salt)) return false;
  const labeled = prfInput(salt);
  if (labeled.length >= 32 && sameBytes(output, labeled.subarray(0, 32))) return false;
  const prefix = utf8("WebAuthn PRF");
  const material = new Uint8Array(prefix.length + 1 + labeled.length);
  material.set(prefix, 0);
  material[prefix.length] = 0;
  material.set(labeled, prefix.length + 1);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", material));
  const forged = sameBytes(output, digest);
  digest.fill(0);
  material.fill(0);
  return !forged;
}

export async function sealWithPrf(
  p: CryptoProvider,
  prf: PrfAuthenticator,
  credentialId: Uint8Array,
  plaintext: Uint8Array,
): Promise<
  SealedBlob | { ok: false; reason: "CANCELLED" | "NOT_SUPPORTED" | "FAILED" }
> {
  // Registration may already have evaluated a salt (one Windows Hello PIN). Use that output.
  // Otherwise evaluate the raw blob salt; the authenticator applies the label once.
  const pending = prf.consumeRegistrationPrf?.(credentialId) ?? null;
  const salt = pending?.salt ?? p.randomBytes(32);
  const evaluated = pending ? null : await prf.evaluate(credentialId, salt);
  if (!pending && evaluated?.kind !== "OK") {
    return { ok: false, reason: evaluated?.kind ?? "FAILED" };
  }
  const output = pending ? pending.output : evaluated && evaluated.kind === "OK" ? evaluated.output : null;
  if (!output) return { ok: false, reason: "NOT_SUPPORTED" };
  // Length is not enough: zeros, the salt, and the public WebAuthn client salt must not seal.
  let seals = false;
  try {
    seals = await prfOutputSeals(output, salt);
  } catch {
    output.fill(0);
    return { ok: false, reason: "FAILED" };
  }
  if (!seals) {
    output.fill(0);
    return { ok: false, reason: "NOT_SUPPORTED" };
  }
  try {
    const key = await sealKey(p, output, salt);
    const nonce = p.randomBytes(12);
    const ciphertext = await p.aesGcmSeal(
      key,
      nonce,
      aad(credentialId),
      plaintext,
    );
    return {
      v: 1,
      credentialId: credentialId.slice(),
      salt: salt.slice(),
      nonce,
      ciphertext,
    };
  } catch {
    return { ok: false, reason: "FAILED" };
  } finally {
    output.fill(0);
  }
}

/** Opens only with the person's verification; any other outcome is a typed failure and no plaintext. */
export async function openWithPrf(
  p: CryptoProvider,
  prf: PrfAuthenticator,
  blob: SealedBlob,
  /** Sees the PRF output before it is overwritten. Used to derive the deletion-proof key (S4b-BL-135). */
  onPrf?: (output: Uint8Array) => Promise<void>,
): Promise<SealOpen> {
  if (blob.v !== 1) return { ok: false, reason: "FAILED" };
  const r = await prf.evaluate(blob.credentialId, blob.salt);
  if (r.kind !== "OK") return { ok: false, reason: r.kind };
  try {
    if (onPrf) await onPrf(r.output);
    const key = await sealKey(p, r.output, blob.salt);
    return {
      ok: true,
      plaintext: await p.aesGcmOpen(
        key,
        blob.nonce,
        aad(blob.credentialId),
        blob.ciphertext,
      ),
    };
  } catch (e) {
    return {
      ok: false,
      reason:
        e instanceof CryptoError && e.kind === "AUTH_FAILED"
          ? "WRONG_KEY"
          : "FAILED",
    };
  } finally {
    r.output.fill(0);
  }
}

/** A deterministic fake for tests: the output depends on the credential id and the input, or a scripted result. */
export class FakePrfAuthenticator implements PrfAuthenticator {
  supported = true;
  next: PrfResult["kind"] | null = null;
  asks = 0;
  /** Capability to report when prfCapability is called. */
  capability: boolean | null = null;
  constructor(
    private readonly p: CryptoProvider,
    private readonly secret: Uint8Array = utf8("fake-authenticator-secret"),
  ) {}
  async isSupported() {
    return this.supported;
  }
  /** Scripted like `next`: null while `registerNext` says the person cancels. */
  registerNext: "OK" | "CANCELLED" = "OK";
  async registerPasskey(): Promise<Uint8Array | null> {
    return this.registerNext === "OK" ? utf8("fake-credential-id") : null;
  }
  async evaluate(
    credentialId: Uint8Array,
    salt: Uint8Array,
  ): Promise<PrfResult> {
    this.asks++;
    if (!this.supported) return { kind: "NOT_SUPPORTED" };
    if (this.next && this.next !== "OK") return { kind: this.next };
    return {
      kind: "OK",
      output: await this.p.hmacSha256(this.secret, concat(credentialId, salt)),
    };
  }
  async prfCapability(): Promise<boolean | null> {
    return this.capability;
  }
}
