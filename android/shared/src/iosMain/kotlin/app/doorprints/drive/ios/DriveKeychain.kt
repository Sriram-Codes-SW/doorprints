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

import app.doorprints.drive.keychain.ItemProtection
import app.doorprints.drive.keychain.KeychainItems
import app.doorprints.drive.keychain.KeychainResult

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.LocalAuthentication.LAContext
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlUserPresence
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData

/**
 * [KeychainItems] on the Security framework: generic passwords of service `app.doorprints.drive`. Compiled for
 * the simulator and the device; the calls need a signed app with Keychain access, which a unit-test host does not have
 * (`errSecMissingEntitlement`), so a real round trip is a device check (docs/ops/manual-test-checklist.md). No call logs
 * anything: statuses are returned, data never leaves the result.
 */
@OptIn(ExperimentalForeignApi::class)
object SecurityDriveKeychain : KeychainItems {
    private const val SERVICE = "app.doorprints.drive"

    override fun read(account: String, prompt: String?): KeychainResult {
        val context = prompt?.let { reason -> LAContext().also { it.localizedReason = reason } }
        val contextRef = context?.let { CFBridgingRetain(it) }
        try {
            val extra = buildList {
                add(kSecReturnData to kCFBooleanTrue)
                add(kSecMatchLimit to kSecMatchLimitOne)
                if (contextRef != null) add(kSecUseAuthenticationContext to contextRef)
            }
            return withQuery(account, extra) { query ->
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    val status = SecItemCopyMatching(query, result.ptr)
                    if (status != errSecSuccess) return@memScoped KeychainResult(status, null)
                    val data = CFBridgingRelease(result.value) as? NSData
                    val bytes = data?.bytes
                    KeychainResult(status, if (data == null || bytes == null) ByteArray(0) else bytes.reinterpret<ByteVar>().readBytes(data.length.toInt()))
                }
            }
        } finally {
            contextRef?.let { CFRelease(it) }
        }
    }

    override fun write(account: String, data: ByteArray, protection: ItemProtection): Int {
        val updated = withQuery(account, emptyList()) { query ->
            withDictionary(listOf(kSecValueData to CFBridgingRetain(data.toNSData())), release = 1) { changes -> SecItemUpdate(query, changes) }
        }
        return if (updated == errSecItemNotFound) add(account, data, protection) else updated
    }

    override fun addIfAbsent(account: String, data: ByteArray, protection: ItemProtection): Int {
        val status = add(account, data, protection)
        return if (status == errSecDuplicateItem) errSecSuccess else status
    }

    override fun delete(account: String): Int {
        val status = withQuery(account, emptyList()) { query -> SecItemDelete(query) }
        return if (status == errSecItemNotFound) errSecSuccess else status
    }

    /**
     * Adds the item with the protection asked for, always this-device-only and available only when a passcode is
     * set; the user-presence one needs the person on every read.
     */
    private fun add(account: String, data: ByteArray, protection: ItemProtection): Int {
        val control = if (protection == ItemProtection.USER_PRESENCE_EACH_READ) {
            SecAccessControlCreateWithFlags(
                null, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, kSecAccessControlUserPresence, null,
            ) ?: return ACCESS_CONTROL_REFUSED
        } else {
            null
        }
        try {
            val protectionEntry: Pair<CFTypeRef?, CFTypeRef?> =
                if (control != null) kSecAttrAccessControl to control else kSecAttrAccessible to kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
            return withQuery(account, listOf(kSecValueData to CFBridgingRetain(data.toNSData()), protectionEntry), release = 1) { query ->
                SecItemAdd(query, null)
            }
        } finally {
            control?.let { CFRelease(it) }
        }
    }

    /** Runs [block] with a query for the item plus [extra]; the first [release] values of [extra] were retained by the caller and are released after. */
    private fun <T> withQuery(
        account: String,
        extra: List<Pair<CFTypeRef?, CFTypeRef?>>,
        release: Int = 0,
        block: (CFMutableDictionaryRef?) -> T,
    ): T {
        val serviceRef = CFBridgingRetain(SERVICE)
        val accountRef = CFBridgingRetain(account)
        try {
            return withDictionary(
                listOf(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef, kSecAttrAccount to accountRef) + extra,
                release = 0,
                block = block,
            ).also { extra.take(release).forEach { CFRelease(it.second) } }
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
        }
    }

    /** Runs [block] with a dictionary of [entries]; the first [release] values were retained by the caller and are released after. */
    private fun <T> withDictionary(entries: List<Pair<CFTypeRef?, CFTypeRef?>>, release: Int, block: (CFMutableDictionaryRef?) -> T): T {
        val dictionary = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
            entries.take(release).forEach { CFRelease(it.second) }
        }
    }

    /** `errSecParam`: the access control could not be made (no passcode set on the phone). */
    private const val ACCESS_CONTROL_REFUSED: Int = -50
}
