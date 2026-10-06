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

package app.doorprints.drive.auth.browser

import app.doorprints.drive.device.BlobStore
import app.doorprints.drive.device.SecretWrapper

/**
 * Where the refresh token lives (docs/15 §5.5, Android row): **sealed**, never plain. The token is the only long-lived
 * secret of this path. Reads and writes may throw `DeviceKeyException` (`LOST`: the Keystore key is gone, the token is
 * unreadable for good; `NEEDS_UNLOCK`: wait).
 */
interface RefreshTokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

/**
 * The refresh token wrapped by a Keystore AES-GCM key ([SecretWrapper], the A2 seam; a key of its own, with the same
 * screen-lock binding) in one file in the app's no-backup directory ([BlobStore]). The AAD names the purpose and
 * version, so the device key's blob cannot be swapped in. Android backup never copies it (the file is in
 * `noBackupFilesDir`; `allowBackup` is off), and a restored phone has no Keystore key to open it.
 */
class SealedRefreshTokenStore(private val wrapper: SecretWrapper, private val blob: BlobStore) : RefreshTokenStore {
    override fun read(): String? {
        val sealed = blob.read() ?: return null
        return wrapper.unwrap(sealed, AAD).toString(Charsets.UTF_8)
    }

    override fun write(token: String) {
        wrapper.ensureKey()
        blob.write(wrapper.wrap(token.toByteArray(Charsets.UTF_8), AAD))
    }

    override fun clear() {
        blob.delete()
    }

    private companion object {
        val AAD = "doorprints-drive-refresh-token/1".toByteArray(Charsets.US_ASCII)
    }
}

/** Memory only: the answer when no safe place exists (and the default of tests). Lost with the process. */
class MemoryRefreshTokenStore : RefreshTokenStore {
    @Volatile
    private var token: String? = null
    override fun read() = token
    override fun write(token: String) {
        this.token = token
    }

    override fun clear() {
        token = null
    }
}
