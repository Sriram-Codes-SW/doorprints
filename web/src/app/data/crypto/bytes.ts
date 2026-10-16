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

/** Small, explicit byte helpers for the crypto code (big-endian throughout); the twin of Kotlin's `Bytes.kt`. */

/** A copy backed by a plain ArrayBuffer, the form WebCrypto's BufferSource takes. */
export function ab(bytes: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(bytes.length);
  out.set(bytes);
  return out;
}

/** The parts joined into one new array, the building block of every length-prefixed encoding in the crypto formats. */
export function concat(...parts: Uint8Array[]): Uint8Array {
  let size = 0;
  for (const p of parts) size += p.length;
  const out = new Uint8Array(size);
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

/** I2OSP(value, length) for values up to 2^53 - 1. */
export function i2osp(value: number, length: number): Uint8Array {
  if (!Number.isSafeInteger(value) || value < 0 || length < 1 || length > 8) throw new RangeError('i2osp');
  if (length < 7 && value >= 2 ** (8 * length)) throw new RangeError('value does not fit');
  const out = new Uint8Array(length);
  let v = value;
  for (let i = length - 1; i >= 0; i--) {
    out[i] = v % 256;
    v = Math.floor(v / 256);
  }
  return out;
}

/** The 4-byte big-endian form of [value] (a chunk index in a `dpx/1` nonce and AAD); throws if it does not fit. */
export function u32(value: number): Uint8Array {
  return i2osp(value, 4);
}

const encoder = new TextEncoder();
/** The UTF-8 bytes of [text], so every format hashes and signs the same bytes on every device. */
export function utf8(text: string): Uint8Array {
  return encoder.encode(text);
}

/** `u8(length) ‖ utf8(text)`. */
export function label(text: string): Uint8Array {
  const b = utf8(text);
  if (b.length > 255) throw new RangeError('label too long');
  return concat(new Uint8Array([b.length]), b);
}

/** Byte-wise XOR of two equal-length arrays; throws `RangeError` when the lengths differ. */
export function xor(a: Uint8Array, b: Uint8Array): Uint8Array {
  if (a.length !== b.length) throw new RangeError('xor');
  return a.map((x, i) => x ^ b[i]);
}

/** Whether the arrays hold the same bytes. Not constant-time: use `constantTimeEquals` for MACs and other secrets. */
export function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** Compares in time that depends only on the lengths (for MACs and other secrets). */
export function constantTimeEquals(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** Standard base64 with padding (RFC 4648 §4). */
export function b64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Decodes [text] only if it is the canonical base64 of exactly [size] bytes (when size >= 0). */
export function unb64(text: string, size = -1): Uint8Array | null {
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(text) || text.length % 4 !== 0) return null;
  let bin: string;
  try {
    bin = atob(text);
  } catch {
    return null;
  }
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  if (size >= 0 && out.length !== size) return null;
  if (b64(out) !== text) return null;
  return out;
}

/** Lowercase hex of [bytes] (ids and test vectors, never secrets in logs). */
export function hex(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += b.toString(16).padStart(2, '0');
  return s;
}

/** Bytes of an even-length hex string (either case); throws `RangeError` on anything else. */
export function unhex(text: string): Uint8Array {
  if (text.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(text)) throw new RangeError('hex');
  const out = new Uint8Array(text.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(text.substr(2 * i, 2), 16);
  return out;
}
