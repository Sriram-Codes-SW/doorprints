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

import app.doorprints.crypto.CryptoException
import app.doorprints.crypto.IosCryptoProvider
import app.doorprints.drive.device.DeviceKeyBackend
import app.doorprints.drive.device.DeviceKeyException
import app.doorprints.drive.device.DeviceKeyStatus
import app.doorprints.drive.keychain.KeychainStatus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import cnames.structs.__SecKey
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks

/**
 * The iPhone's device key (docs/15 §9.2, §9.3, §10.3; S4b-BL-117/-131): a P-256 key made **in the Secure Enclave**
 * (`kSecAttrTokenIDSecureEnclave`), permanent, with `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` and the key-usage
 * access control: the private key never leaves the chip, ECDH (`SecKeyCopyKeyExchangeResult`, the raw x coordinate) runs
 * inside it, and iOS **deletes the key when the passcode is removed**, which the identity reads as "lost while a folder is
 * pinned: connect again and enrol again" (§10.3). The key is usable while the phone is unlocked (background sync runs only
 * then, §5.5) and asks the person for nothing, unlike the deletion proof's key (`KeychainProtectedSecret`).
 *
 * The iOS Simulator has no Secure Enclave: there ([secureEnclave] false, `SIMULATOR_DEVICE_NAME` is set) the same key is
 * a software Keychain key with the same tag and access class, so development runs; a phone never takes that branch (the
 * variable is the simulator's own and a device never sets it). A real device whose Secure Enclave refuses fails the creation:
 * there is no weaker key on a phone.
 *
 * Compiled here, not run: it needs a signed app on a simulator or an iPhone (docs/ops/manual-test-checklist.md).
 * Nothing is logged; an error carries the Security status, never key material.
 */
@OptIn(ExperimentalForeignApi::class)
class SecureEnclaveDeviceKey(
    private val tag: String = DEFAULT_TAG,
    private val secureEnclave: Boolean = !runsInSimulator(),
) : DeviceKeyBackend {

    override fun status(): DeviceKeyStatus {
        val lookup = lookup()
        return when {
            lookup.status == errSecItemNotFound -> DeviceKeyStatus.ABSENT
            lookup.status != errSecSuccess -> DeviceKeyStatus.NEEDS_UNLOCK
            else -> try {
                // A key that cannot do one agreement now (the phone is locked, the chip is busy) is not READY.
                lookup.ref?.let { agreeWith(it, publicOf(it)) }
                DeviceKeyStatus.READY
            } catch (e: DeviceKeyException) {
                if (e.kind == DeviceKeyException.Kind.LOST) DeviceKeyStatus.INVALIDATED else DeviceKeyStatus.NEEDS_UNLOCK
            } finally {
                lookup.release()
            }
        }
    }

    override fun create(): ByteArray {
        val created = createKey() ?: throw DeviceKeyException(DeviceKeyException.Kind.NEEDS_UNLOCK, "the Secure Enclave key could not be made")
        try {
            return publicOf(created)
        } finally {
            CFRelease(created)
        }
    }

    override fun publicKey(): ByteArray {
        val lookup = lookup()
        val ref = lookup.ref ?: throw exceptionFor(lookup.status, "no device key")
        try {
            return publicOf(ref)
        } finally {
            lookup.release()
        }
    }

    override fun agree(peerPublic: ByteArray): ByteArray {
        val lookup = lookup()
        val ref = lookup.ref ?: throw exceptionFor(lookup.status, "no device key")
        try {
            return agreeWith(ref, peerPublic)
        } finally {
            lookup.release()
        }
    }

    override fun discard() {
        withCfDictionary({ put(kSecClass, kSecClassKey); putBridged(kSecAttrApplicationTag, tagData()) }) { query -> SecItemDelete(query) }
    }

    // ---- the Security calls ----

    private class Lookup(val status: Int, val ref: SecKeyRef?) {
        fun release() {
            ref?.let { CFRelease(it) }
        }
    }

    private fun lookup(): Lookup = withCfDictionary({
        put(kSecClass, kSecClassKey)
        putBridged(kSecAttrApplicationTag, tagData())
        put(kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        put(kSecReturnRef, kCFBooleanTrue)
    }) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            Lookup(status, if (status == errSecSuccess) result.value?.reinterpret<__SecKey>() else null)
        }
    }

    private fun createKey(): SecKeyRef? {
        val control = SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, kSecAccessControlPrivateKeyUsage, null)
            ?: return null
        try {
            return withCfDictionary({
                put(kSecAttrIsPermanent, kCFBooleanTrue)
                putBridged(kSecAttrApplicationTag, tagData())
                put(kSecAttrAccessControl, control)
            }) { privateAttributes ->
                withCfDictionary({
                    put(kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
                    putOwned(kSecAttrKeySizeInBits, IosCryptoProvider.number(256))
                    if (secureEnclave) put(kSecAttrTokenID, kSecAttrTokenIDSecureEnclave)
                    put(kSecPrivateKeyAttrs, privateAttributes)
                }) { attributes -> SecKeyCreateRandomKey(attributes, null) }
            }
        } finally {
            CFRelease(control)
        }
    }

    private fun publicOf(key: SecKeyRef): ByteArray {
        val publicRef = SecKeyCopyPublicKey(key) ?: throw DeviceKeyException(DeviceKeyException.Kind.NEEDS_UNLOCK, "no public key")
        try {
            val encoded = IosCryptoProvider.externalRepresentation(publicRef)
            if (encoded.size != 65 || encoded[0] != 0x04.toByte()) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "not an uncompressed P-256 point")
            return encoded
        } finally {
            CFRelease(publicRef)
        }
    }

    private fun agreeWith(key: SecKeyRef, peerPublic: ByteArray): ByteArray {
        val peer = try {
            IosCryptoProvider.importKey(peerPublic, kSecAttrKeyClassPublic)
        } catch (e: CryptoException) {
            throw e
        } ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "public key refused")
        try {
            return memScoped {
                val parameters = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                val error = alloc<CFErrorRefVar>()
                val secret = try {
                    SecKeyCopyKeyExchangeResult(key, kSecKeyAlgorithmECDHKeyExchangeStandard, peer, parameters, error.ptr)
                } finally {
                    CFRelease(parameters)
                }
                if (secret == null) {
                    val code = error.value?.let { e -> CFErrorGetCode(e).also { CFRelease(e) } }?.toInt() ?: 0
                    throw exceptionFor(code, "ECDH in the Secure Enclave")
                }
                try {
                    IosCryptoProvider.bytesOf(secret)
                } finally {
                    CFRelease(secret)
                }
            }.also { if (it.size != 32) throw DeviceKeyException(DeviceKeyException.Kind.NEEDS_UNLOCK, "unexpected shared secret size") }
        } finally {
            CFRelease(peer)
        }
    }

    private fun tagData(): NSData = tag.encodeToByteArray().toNSData()

    companion object {
        const val DEFAULT_TAG = "app.doorprints.drive.device-key"

        /** The Simulator sets this variable for every app it runs; a device never does. */
        fun runsInSimulator(): Boolean = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

        fun kindOf(status: Int): DeviceKeyException.Kind = KeychainStatus.deviceKeyKind(status)

        fun exceptionFor(status: Int, what: String): DeviceKeyException =
            DeviceKeyException(kindOf(status), "$what (status $status)")
    }
}

/** Whether this process runs in the iOS Simulator. */
internal fun runsInSimulator(): Boolean = SecureEnclaveDeviceKey.runsInSimulator()
