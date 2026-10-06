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

import { ab, utf8 } from '../crypto/bytes';
import type { RecoveryKey } from '../crypto/recovery-key';

/**
 * The HMAC proof key derived from the recovery key (docs/15 §10.4a).
 * Non-extractable, sign-only, suitable for deletion proof operations.
 */
export async function recoveryProofKey(key: RecoveryKey): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', ab(key.bytes), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: ab(utf8('doorprints/deletion-proof/recovery')), info: ab(utf8('doorprints/deletion-proof/recovery/1')) },
    base,
    { name: 'HMAC', hash: 'SHA-256', length: 256 },
    false,
    ['sign'],
  );
}
