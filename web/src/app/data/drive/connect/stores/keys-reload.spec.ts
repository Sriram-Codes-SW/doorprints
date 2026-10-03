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
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { KeysError, KeysFile, KeysGuard, sameWatermark } from '../../../crypto/keys-file';
import type { KeysWatermark, KeysWatermarkStore } from '../../../crypto/keys-file';
import { RecoveryKey } from '../../../crypto/recovery-key';

/**
 * jsdom has no IndexedDB, so a new LocalStore is a fresh MemoryDb and houses do not survive here
 * (local-store.spec documents the same). The pin that refuses a forged keys.json is KeysGuard against
 * a store that persists across two guard instances — the same object a reload would reopen from
 * IndexedDB. Chromium IndexedDB reload is tools/live-ui/drive-connect-built.js.
 */
class MemoryStore implements KeysWatermarkStore {
  value: KeysWatermark | null = null;
  async load() {
    return this.value;
  }
  async compareAndSet(expected: KeysWatermark | null, next: KeysWatermark) {
    if (!sameWatermark(this.value, expected)) return false;
    this.value = next;
    return true;
  }
}

describe('Drive stores survive a reload', () => {
  const crypto = new WebCryptoProvider();

  it('a new KeysGuard on the saved pin still refuses a forged keys.json', async () => {
    const store = new MemoryStore();
    const files = new KeysFile(crypto);
    const phone = await crypto.p256Generate();
    const written = await files.createFirstDevice(
      { publicKey: phone.publicKey, name: 'Browser', platform: 'web' },
      RecoveryKey.generate(crypto),
      1_790_000_000_000,
    );
    await new KeysGuard(crypto, store).pinCreated(written);

    const afterReload = new KeysGuard(crypto, store);
    const pin = await afterReload.watermark();
    expect(pin?.epoch).toBe(written.opened.epoch);

    const forged = written.bytes.slice();
    forged[forged.length - 1] ^= 0xff;
    await expect(files.open(forged, phone, afterReload)).rejects.toBeInstanceOf(KeysError);
    await files.open(written.bytes, phone, afterReload);
  });
});
