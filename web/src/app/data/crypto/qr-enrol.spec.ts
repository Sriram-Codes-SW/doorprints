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

import { describe, expect, it } from 'vitest';
import { hex, unhex } from './bytes';
import { WebCryptoProvider } from './crypto-provider';
import { HPKE_INFO, WrapAad, kidOf } from './folder-key';
import { Hpke } from './hpke';
import vectorsJson from '../../../../../docs/schemas/qr-enrol-vectors.json';
import { parseQrOffer, QR_PSK_ID, QR_PSK_LEN, qrOfferText, type QrPlatform } from './qr-enrol';

const p = new WebCryptoProvider();

describe('enrolment QR payload and HPKE PSK', () => {
  it('round-trips the public key and the PSK, including a pasted URL', async () => {
    const key = await p.p256Generate();
    const psk = p.randomBytes(QR_PSK_LEN);
    const text = qrOfferText(key.publicKey, psk);
    expect(text.startsWith('dp1.')).toBe(true);
    const parsed = parseQrOffer(`https://doorprints.web.app/enrol#${text}`);
    expect(parsed).not.toBeNull();
    expect(hex(parsed!.publicKey)).toBe(hex(key.publicKey));
    expect(hex(parsed!.psk)).toBe(hex(psk));
    expect(parseQrOffer('not-a-code')).toBeNull();
  });

  it('opens a PSK wrap only with the same PSK and the same public key', async () => {
    const recipient = await p.p256Generate();
    const psk = p.randomBytes(32);
    const folder = p.randomBytes(32);
    const kid = kidOf(p, recipient.publicKey);
    const hpke = new Hpke(p);
    const sealed = await hpke.sealPsk(recipient.publicKey, HPKE_INFO, WrapAad.folderKey(1, kid), folder, psk, QR_PSK_ID);
    const opened = await hpke.openPsk(sealed.enc, recipient, HPKE_INFO, WrapAad.folderKey(1, kid), sealed.ciphertext, psk, QR_PSK_ID);
    expect(hex(opened)).toBe(hex(folder));
    const wrong = psk.slice();
    wrong[0] ^= 1;
    await expect(hpke.openPsk(sealed.enc, recipient, HPKE_INFO, WrapAad.folderKey(1, kid), sealed.ciphertext, wrong, QR_PSK_ID)).rejects.toBeTruthy();
    const other = await p.p256Generate();
    await expect(hpke.openPsk(sealed.enc, other, HPKE_INFO, WrapAad.folderKey(1, kidOf(p, other.publicKey)), sealed.ciphertext, psk, QR_PSK_ID)).rejects.toBeTruthy();
  });
});

/** The `dp1.` text against `docs/schemas/qr-enrol-vectors.json`, the same cases as Kotlin's `QrEnrolTest`. */
describe('enrolment offer text against the shared vectors', () => {
  const vectors = vectorsJson as unknown as {
    valid: { name: string; publicKey: string; psk: string; platform: QrPlatform | 'unknown'; text: string }[];
    unassignedPlatform: { name: string; publicKey: string; psk: string; text: string }[];
    forms: { name: string; input: string; offer: string }[];
    invalid: { name: string; input: string }[];
  };

  const platformOf = (name: QrPlatform | 'unknown') => (name === 'unknown' ? undefined : name);

  it('writes each valid offer to the exact text', () => {
    for (const v of vectors.valid) expect(qrOfferText(unhex(v.publicKey), unhex(v.psk), platformOf(v.platform)), v.name).toBe(v.text);
  });

  it('reads each valid offer back to the exact bytes', () => {
    for (const v of vectors.valid) {
      const parsed = parseQrOffer(v.text);
      expect(parsed, v.name).not.toBeNull();
      expect(hex(parsed!.publicKey), v.name).toBe(v.publicKey);
      expect(hex(parsed!.psk), v.name).toBe(v.psk);
      expect(parsed!.platform, v.name).toBe(platformOf(v.platform) ?? null);
    }
  });

  it('reads an unassigned platform byte as unknown and keeps the offer', () => {
    expect(vectors.unassignedPlatform.length).toBeGreaterThan(0);
    for (const v of vectors.unassignedPlatform) {
      const parsed = parseQrOffer(v.text);
      expect(parsed, v.name).not.toBeNull();
      expect(hex(parsed!.publicKey), v.name).toBe(v.publicKey);
      expect(hex(parsed!.psk), v.name).toBe(v.psk);
      expect(parsed!.platform, v.name).toBeNull();
    }
  });

  it('finds the offer in every pasted form', () => {
    for (const f of vectors.forms) {
      const want = vectors.valid.find((v) => v.name === f.offer)!;
      const parsed = parseQrOffer(f.input);
      expect(parsed, f.name).not.toBeNull();
      expect(hex(parsed!.publicKey), f.name).toBe(want.publicKey);
      expect(hex(parsed!.psk), f.name).toBe(want.psk);
      expect(parsed!.platform, f.name).toBe(platformOf(want.platform) ?? null);
    }
  });

  it('refuses every invalid input', () => {
    for (const v of vectors.invalid) expect(parseQrOffer(v.input), v.name).toBeNull();
  });
});
