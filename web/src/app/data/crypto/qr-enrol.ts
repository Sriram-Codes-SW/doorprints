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

import { b64, unb64, utf8 } from './bytes';

/**
 * The enrolment QR (docs/15 §9.5 i, S4b-BL-134). The text is `dp1.` plus base64url of
 * `pk_new (65) ‖ s (32)` and, since S4b-BL-144, one platform byte. `s` is 32 bytes: RFC 9180's minimum PSK for HKDF-SHA-256. The design's 128 bits are
 * shorter than that, so the website uses 32. `psk_id` is this constant.
 */
export const QR_PREFIX = 'dp1.';
export const QR_PSK_LEN = 32;
export const QR_PUBLIC_LEN = 65;
/** The platform byte after `s`: 0 unknown, 1 android, 2 ios, 3 web; any other value reads as unknown. */
export const QR_PLATFORM_LEN = 1;

export type QrPlatform = 'android' | 'ios' | 'web';
const PLATFORM_BYTES: readonly QrPlatform[] = ['android', 'ios', 'web'];
export const QR_PSK_ID = utf8('doorprints/dpx1/qr-psk');

/**
 * What a new browser offers an enrolled device: its public key and the secret PSK from the QR that authenticates the pairing.
 */
export interface QrOffer {
  publicKey: Uint8Array;
  psk: Uint8Array;
  /** The new device's platform; null for an offer made before S4b-BL-144 or with an unassigned byte. */
  platform: QrPlatform | null;
}

function b64url(bytes: Uint8Array): string {
  return b64(bytes).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function b64urlDecode(text: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]+$/.test(text)) return null;
  const pad = text.length % 4 === 0 ? '' : '='.repeat(4 - (text.length % 4));
  return unb64(text.replaceAll('-', '+').replaceAll('_', '/') + pad);
}

/**
 * The text a new browser shows as a QR code and as a code to copy. With `platform` it ends in the platform byte;
 * without, it is the earlier 97-byte form, which every reader still accepts.
 */
export function qrOfferText(publicKey: Uint8Array, psk: Uint8Array, platform?: QrPlatform): string {
  if (publicKey.length !== QR_PUBLIC_LEN || publicKey[0] !== 4) throw new RangeError('public key');
  if (psk.length !== QR_PSK_LEN) throw new RangeError('psk');
  const raw = new Uint8Array(QR_PUBLIC_LEN + QR_PSK_LEN + (platform ? QR_PLATFORM_LEN : 0));
  raw.set(publicKey);
  raw.set(psk, QR_PUBLIC_LEN);
  if (platform) raw[QR_PUBLIC_LEN + QR_PSK_LEN] = PLATFORM_BYTES.indexOf(platform) + 1;
  return QR_PREFIX + b64url(raw);
}

/** The offer inside pasted text or a scanned URL, or null when it is not one. */
export function parseQrOffer(text: string): QrOffer | null {
  const trimmed = text.trim();
  const at = trimmed.lastIndexOf(QR_PREFIX);
  if (at < 0) return null;
  const body = trimmed.slice(at + QR_PREFIX.length).split(/[\s#?&]/, 1)[0] ?? '';
  const raw = b64urlDecode(body);
  const base = QR_PUBLIC_LEN + QR_PSK_LEN;
  if (!raw || (raw.length !== base && raw.length !== base + QR_PLATFORM_LEN) || raw[0] !== 4) return null;
  const platform = raw.length > base ? (PLATFORM_BYTES[raw[base] - 1] ?? null) : null;
  return { publicKey: raw.subarray(0, QR_PUBLIC_LEN), psk: raw.subarray(QR_PUBLIC_LEN, base), platform };
}
