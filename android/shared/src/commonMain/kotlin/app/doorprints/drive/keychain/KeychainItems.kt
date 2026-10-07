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

import app.doorprints.drive.device.DeviceKeyException

/** What a Keychain read found: the Security framework's [status] and, on [KeychainStatus.SUCCESS], the item's [data]. */
class KeychainResult(val status: Int, val data: ByteArray?) {
    override fun toString() = "KeychainResult(status=$status)"
}

/** How an item is protected (docs/15 §5.5, §10.3). Both kinds are this device only, never in iCloud Keychain or a backup. */
enum class ItemProtection {
    /**
     * `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`: readable while the phone is unlocked; iOS **deletes** the item when
     * the passcode is removed (the refresh token's place, so a removed lock means connecting again).
     */
    WHEN_UNLOCKED_WITH_PASSCODE,

    /**
     * The same class plus `kSecAccessControlUserPresence`: every read asks the person (Face ID, Touch ID or the passcode)
     * through the system's own prompt, so the deletion proof's key cannot be used without them (docs/15 §10.2).
     */
    USER_PRESENCE_EACH_READ,
}

/** The few Keychain calls the Drive wiring makes, on generic-password items of one service; fakes stand in for tests. */
interface KeychainItems {
    /** The item's data. [prompt] (the reason the system shows) only matters for a [ItemProtection.USER_PRESENCE_EACH_READ] item. */
    fun read(account: String, prompt: String? = null): KeychainResult

    /** Updates the item or adds it (never delete-then-add, which could leave nothing); [KeychainStatus.SUCCESS] when it is there. */
    fun write(account: String, data: ByteArray, protection: ItemProtection): Int

    /** Adds the item only when there is none: [KeychainStatus.SUCCESS] also when it was already there (its data is not touched). */
    fun addIfAbsent(account: String, data: ByteArray, protection: ItemProtection): Int

    /** Removes the item; [KeychainStatus.SUCCESS] also when there was none. */
    fun delete(account: String): Int
}

/**
 * The Security framework's `OSStatus` values the Drive wiring tells apart, as plain numbers (they are part of Apple's stable
 * ABI), so the rules over them are common code and run on the host; `KeychainStatusTest` on the simulator checks each one
 * against the platform's own constant.
 */
object KeychainStatus {
    const val SUCCESS = 0
    const val USER_CANCELED = -128
    const val PARAM = -50
    const val MISSING_ENTITLEMENT = -34018
    const val NOT_AVAILABLE = -25291
    const val DUPLICATE_ITEM = -25299
    const val ITEM_NOT_FOUND = -25300
    const val AUTH_FAILED = -25293
    const val INTERACTION_NOT_ALLOWED = -25308

    /**
     * What a Security status means for the device key. Only the key store's own word loses a key (the item is gone, so iOS
     * deleted it, with the passcode); a locked phone, a refusal or any other failure waits (`NEEDS_UNLOCK`) and never
     * recreates, as Android's classification (a wrong guess would make a new key behind the person's back).
     */
    fun deviceKeyKind(status: Int): DeviceKeyException.Kind =
        if (status == ITEM_NOT_FOUND) DeviceKeyException.Kind.LOST else DeviceKeyException.Kind.NEEDS_UNLOCK
}
