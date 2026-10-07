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

package app.doorprints.drive.ios

import app.doorprints.drive.device.DeviceKeyException
import app.doorprints.drive.keychain.ItemProtection
import app.doorprints.drive.keychain.KeychainStatus
import platform.Security.errSecAuthFailed
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.errSecParam
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plain numbers of [KeychainStatus] (common code, run on the host) are the platform's own constants. */
class KeychainStatusConstantsTest {
    @Test
    fun everyNumberIsTheSecurityFrameworksConstant() {
        assertEquals(errSecSuccess, KeychainStatus.SUCCESS)
        assertEquals(errSecUserCanceled, KeychainStatus.USER_CANCELED)
        assertEquals(errSecParam, KeychainStatus.PARAM)
        assertEquals(errSecMissingEntitlement, KeychainStatus.MISSING_ENTITLEMENT)
        assertEquals(errSecNotAvailable, KeychainStatus.NOT_AVAILABLE)
        assertEquals(errSecDuplicateItem, KeychainStatus.DUPLICATE_ITEM)
        assertEquals(errSecItemNotFound, KeychainStatus.ITEM_NOT_FOUND)
        assertEquals(errSecAuthFailed, KeychainStatus.AUTH_FAILED)
        assertEquals(errSecInteractionNotAllowed, KeychainStatus.INTERACTION_NOT_ALLOWED)
    }
}

class SecureEnclaveDeviceKeyStatusTest {
    @Test
    fun theDeviceKeysErrorsCarryTheStatusAndTheRightKind() {
        assertEquals(DeviceKeyException.Kind.LOST, SecureEnclaveDeviceKey.kindOf(errSecItemNotFound))
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, SecureEnclaveDeviceKey.kindOf(errSecInteractionNotAllowed))
        assertTrue("-25300" in SecureEnclaveDeviceKey.exceptionFor(errSecItemNotFound, "x").message.orEmpty())
    }

    @Test
    fun theTagIsTheOneThePrivacyAndDocsNameAndTheSimulatorIsRecognisedOnlyBySimulatorsVariable() {
        assertEquals("app.doorprints.drive.device-key", SecureEnclaveDeviceKey.DEFAULT_TAG)
    }
}

/**
 * One round trip on the real Keychain, **skipped** where the test host has no Keychain access (an unsigned simulator
 * test binary answers `errSecMissingEntitlement`): the device and signed-simulator check is MT-86 (docs/ops).
 */
class SecurityDriveKeychainTest {
    private val keychain = SecurityDriveKeychain
    private val account = "test-round-trip"

    @Test
    fun aRealItemIsAddedReadUpdatedAndDeletedOrTheHostHasNoKeychain() {
        val added = keychain.write(account, "one".encodeToByteArray(), ItemProtection.WHEN_UNLOCKED_WITH_PASSCODE)
        if (added == errSecMissingEntitlement || added == errSecNotAvailable || added == errSecInteractionNotAllowed) return
        try {
            assertEquals(errSecSuccess, added)
            assertEquals("one", keychain.read(account).data?.decodeToString())
            assertEquals(errSecSuccess, keychain.write(account, "two".encodeToByteArray(), ItemProtection.WHEN_UNLOCKED_WITH_PASSCODE))
            assertEquals("two", keychain.read(account).data?.decodeToString())
        } finally {
            assertEquals(errSecSuccess, keychain.delete(account))
        }
        assertEquals(errSecItemNotFound, keychain.read(account).status)
        assertEquals(errSecSuccess, keychain.delete(account))
    }
}
