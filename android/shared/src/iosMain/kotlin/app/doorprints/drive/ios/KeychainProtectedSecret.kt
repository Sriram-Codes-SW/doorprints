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

import app.doorprints.crypto.CryptoProvider
import app.doorprints.drive.device.ProtectedSecret
import app.doorprints.drive.device.SecretOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Security.errSecAuthFailed
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecNotAvailable
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled

/**
 * The deletion proof's secret on the iPhone (docs/15 §10.2, S4b-BL-135): 32 random bytes in a Keychain item whose access
 * control is `kSecAccessControlUserPresence` on `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`. **Every read makes the
 * system ask the person** (Face ID, Touch ID or the passcode, with [open]'s reason as the prompt text), so the bytes are
 * released only to a person who just authenticated, by the platform and not by a check in the app; the item is this
 * device's only, is not in iCloud or a backup, and **iOS deletes it when the passcode is removed**: a removed lock means
 * a new key, which signs only new operations (it holds no state), as Android's invalidated key is replaced.
 *
 * The read blocks its thread while the prompt is up (the Security framework's way), so it runs on the I/O dispatcher, never
 * on the main thread. A caller that is cancelled meanwhile gets its cancellation when the person answers; the answer is
 * dropped. The first use makes the item with no prompt.
 *
 * Compiled here, not run: it needs a signed app on an iPhone with a passcode (docs/ops/manual-test-checklist.md).
 */
internal class KeychainProtectedSecret(
    private val crypto: CryptoProvider,
    private val lockPresent: () -> Boolean,
    private val keychain: DriveKeychainItems = SecurityDriveKeychain,
    private val account: String = ACCOUNT,
) : ProtectedSecret {

    override suspend fun open(reason: String): SecretOpen = withContext(Dispatchers.IO) { openNow(reason) }

    /** [open] on the calling thread (the tests' entry; the real one blocks while the system prompt is up). */
    internal fun openNow(reason: String): SecretOpen {
        if (!lockPresent()) return SecretOpen.NoLock
        val made = keychain.addIfAbsent(account, crypto.randomBytes(KEY_BYTES), ItemProtection.USER_PRESENCE_EACH_READ)
        if (made != errSecSuccess) return outcomeOf(made)
        var read = keychain.read(account, reason)
        if (read.status == errSecItemNotFound) {
            // The item went with the passcode between the add and the read, or an older one was removed: make a new one once.
            keychain.delete(account)
            val again = keychain.addIfAbsent(account, crypto.randomBytes(KEY_BYTES), ItemProtection.USER_PRESENCE_EACH_READ)
            if (again != errSecSuccess) return outcomeOf(again)
            read = keychain.read(account, reason)
        }
        if (read.status != errSecSuccess) return outcomeOf(read.status)
        val data = read.data
        if (data == null || data.size != KEY_BYTES) {
            data?.fill(0)
            return SecretOpen.Failed
        }
        return SecretOpen.Opened(data)
    }

    companion object {
        const val ACCOUNT = "delete-proof-key"
        private const val KEY_BYTES = 32

        /** `errSecParam`, which the keychain wrapper gives when the access control could not be made (no passcode). */
        private const val ERR_PARAM = -50

        /** `errSecMissingEntitlement`: a build with no Keychain access (an unsigned test host). */
        private const val ERR_MISSING_ENTITLEMENT = -34018

        /** What a Security status means for the person at the prompt. Anything not named is [SecretOpen.Failed]. */
        fun outcomeOf(status: Int): SecretOpen = when (status) {
            errSecUserCanceled -> SecretOpen.Cancelled
            errSecAuthFailed -> SecretOpen.Denied
            errSecNotAvailable, ERR_PARAM -> SecretOpen.NoLock
            errSecInteractionNotAllowed, ERR_MISSING_ENTITLEMENT -> SecretOpen.Unavailable
            else -> SecretOpen.Failed
        }
    }
}
