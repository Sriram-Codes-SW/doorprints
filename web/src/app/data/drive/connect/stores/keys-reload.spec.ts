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

import { afterEach, describe, expect, it } from 'vitest';
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { KeysError, KeysFile, KeysGuard } from '../../../crypto/keys-file';
import { RecoveryKey } from '../../../crypto/recovery-key';
import { LocalStore } from '../../../local-store.service';
import { openDriveDb } from './drive-db';

/**
 * A reload of this browser keeps local houses and the Drive pin, and a forged keys.json is still refused
 * (the watermark lives in IndexedDB).
 */
describe('Drive stores survive a reload', () => {
  const crypto = new WebCryptoProvider();

  afterEach(async () => {
    indexedDB.deleteDatabase('doorprints-drive');
    const leftover = new LocalStore();
    await leftover.ready();
    await leftover.clearEverything();
  });

  it('keeps a house after a new LocalStore (reload) and refuses a forged keys.json against the saved pin', async () => {
    const first = new LocalStore();
    await first.ready();
    await first.putHouseFromServer({
      id: 'h-reload',
      label: 'Reload house',
      lat: 13,
      lon: 80,
      status: 'NEW',
      checklist: {},
      deleted: false,
      syncVersion: 1,
    });
    first.close?.();

    const reopened = new LocalStore();
    await reopened.ready();
    const houses = await reopened.allHouses();
    expect(houses.some((h) => h.id === 'h-reload' && h.label === 'Reload house')).toBe(true);
    await reopened.clearEverything();

    const a = await openDriveDb();
    expect(a.db.kind).toBe('indexeddb');
    const phone = await crypto.p256Generate();
    const files = new KeysFile(crypto);
    const written = await files.createFirstDevice(
      { publicKey: phone.publicKey, name: 'Browser', platform: 'web' },
      RecoveryKey.generate(crypto),
      1_790_000_000_000,
    );
    const guard = new KeysGuard(crypto, a.keysWatermarkStore);
    await guard.pinCreated(written);
    a.db.close();

    const b = await openDriveDb();
    expect(b.db.kind).toBe('indexeddb');
    const afterReload = new KeysGuard(crypto, b.keysWatermarkStore);
    const pin = await afterReload.watermark();
    expect(pin).toBeTruthy();
    expect(pin?.epoch).toBe(written.opened.epoch);

    const forged = written.bytes.slice();
    forged[forged.length - 1] ^= 0xff;
    try {
      await files.open(forged, phone, afterReload);
      throw new Error('forged file should not open');
    } catch (e) {
      expect(e).toBeInstanceOf(KeysError);
    }
    await files.open(written.bytes, phone, afterReload);
    b.db.close();
  });
});
