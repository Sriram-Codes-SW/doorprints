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

package app.doorprints.drive.connect

/**
 * The refresh token in a *sealed* [KeyValueStore] (Android: Keystore-sealed preferences that die with the screen lock;
 * the iPhone's Keychain item when its store exists). Reads and writes that cannot happen say [TokenStoreException],
 * never return a wrong value.
 */
class KvRefreshTokenStore(private val sealed: KeyValueStore) : RefreshTokenStore {
    override fun load(): String? = sealed.get(KEY)
    override fun save(token: String) = sealed.put(KEY, token)
    override fun clear() = sealed.remove(KEY)

    private companion object {
        const val KEY = "drive.refresh-token"
    }
}
