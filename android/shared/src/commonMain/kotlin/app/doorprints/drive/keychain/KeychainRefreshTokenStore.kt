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

package app.doorprints.drive.keychain

import app.doorprints.drive.auth.browser.RefreshTokenStore
import app.doorprints.drive.device.DeviceKeyException

/**
 * The iPhone's sealed refresh token (docs/15 §5.5): a Keychain item, `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`
 * (not in iCloud Keychain, not in a device backup, **deleted by iOS when the passcode is removed**, §10.3). It is readable
 * while the phone is unlocked, so a background run on a locked phone waits ([DeviceKeyException.Kind.NEEDS_UNLOCK]) instead
 * of looking for a token that is only out of reach. A read the Keychain refuses for any other reason is
 * [DeviceKeyException.Kind.LOST]: the sign-in then asks for consent again, which is all a lost token costs. The token is
 * never logged and never in an exception message.
 */
class KeychainRefreshTokenStore(
    private val keychain: KeychainItems,
    private val account: String = ACCOUNT,
) : RefreshTokenStore {

    override fun read(): String? {
        val result = keychain.read(account)
        return when (result.status) {
            KeychainStatus.SUCCESS -> result.data?.decodeToString()?.takeIf { it.isNotBlank() }
            KeychainStatus.ITEM_NOT_FOUND -> null
            KeychainStatus.INTERACTION_NOT_ALLOWED -> throw DeviceKeyException(DeviceKeyException.Kind.NEEDS_UNLOCK, "the Keychain is locked (status ${result.status})")
            else -> throw DeviceKeyException(DeviceKeyException.Kind.LOST, "the refresh token could not be read (status ${result.status})")
        }
    }

    override fun write(token: String) {
        val status = keychain.write(account, token.encodeToByteArray(), ItemProtection.WHEN_UNLOCKED_WITH_PASSCODE)
        if (status != KeychainStatus.SUCCESS) throw IllegalStateException("the refresh token was not kept (status $status)")
    }

    override fun clear() {
        keychain.delete(account)
    }

    companion object {
        const val ACCOUNT = "refresh-token"
    }
}
