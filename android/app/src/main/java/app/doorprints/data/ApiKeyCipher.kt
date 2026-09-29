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

package app.doorprints.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the API key at rest (threat model F-03, SEC-010) with an AES-256-GCM key that lives in the Android
 * Keystore and never leaves it (hardware-backed where the device supports it). This is the pattern the deprecated
 * androidx.security:security-crypto library wrapped, without the dependency.
 *
 * Stored format: "v1:" + Base64(12-byte IV || ciphertext+tag). The Keystore key is not included in backups or
 * device transfers, so a restored or copied DataStore cannot be decrypted: [decrypt] returns null and the user
 * enters the key again.
 */
object ApiKeyCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    // A stored identifier, kept from before the Doorprints rename on purpose (also through the package rename of
    // 2026-09-24): Keystore entries are per app, and renaming the alias would orphan the key that already-encrypted
    // settings need, so the saved API key could no longer be read. Do not change it.
    private const val ALIAS = "house_hunt_api_key_v1"
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore picks a fresh random IV
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return null
        return runCatching {
            val all = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            require(all.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }
}
