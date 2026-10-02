package app.doorprints.drive

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import app.doorprints.drive.connect.KeyValueStore
import app.doorprints.drive.connect.TokenStoreException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (the Google refresh token, the device key's scalar) under an AES-256-GCM key that never leaves the
 * Android Keystore (S4b-BL-117, docs/15 §5.5, §10.3). From Android 9 the key is `setUnlockedDeviceRequired`: it works
 * only while the phone is unlocked and **Android destroys it for good when the secure screen lock is removed**, so the
 * sealed data cannot be opened any more (docs/15 §10.3: "dies with the lock"). Android 8 has no such flag: there
 * `LockLostDetector` (the keyguard) is what drops the data. A restored or copied preferences file cannot be opened
 * (the key is not in backups).
 *
 * [open] tells apart a key that is gone for good ([Opened.Gone]: the entry is removed) from a phone that is merely locked
 * right now ([Opened.Unavailable]: keep everything, try later).
 */
class KeystoreSealer(private val alias: String) {
    sealed interface Opened {
        class Bytes(val value: ByteArray) : Opened
        data object Gone : Opened
        data object Unavailable : Opened
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setUnlockedDeviceRequired(true)
        generator.init(spec.build())
        return generator.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = cipher.doFinal(plain)
        return PREFIX + Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    fun open(stored: String): Opened {
        if (!stored.startsWith(PREFIX)) return Opened.Gone
        val all = try {
            Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return Opened.Gone
        }
        if (all.size <= IV_BYTES) return Opened.Gone
        return try {
            val key = key(create = false) ?: return Opened.Gone
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            Opened.Bytes(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES))
        } catch (_: KeyPermanentlyInvalidatedException) {
            Opened.Gone
        } catch (_: UnrecoverableKeyException) {
            Opened.Gone
        } catch (_: AEADBadTagException) {
            Opened.Gone
        } catch (_: Exception) {
            // UserNotAuthenticatedException (locked right now), a Keystore daemon hiccup: not proof that the key is gone.
            Opened.Unavailable
        }
    }

    /** False only when the key existed and Android has invalidated it (the lock was removed); a locked phone is true. */
    fun keyStillUsable(): Boolean {
        val key = try {
            key(create = false)
        } catch (_: Exception) {
            return true
        } ?: return true
        return try {
            Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            false
        } catch (_: Exception) {
            true
        }
    }

    fun deleteKey() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** A [KeyValueStore] over private preferences; with a [sealer] every value is sealed (the secrets), else plain (the pins). */
class PrefsKeyValueStore(private val prefs: SharedPreferences, private val sealer: KeystoreSealer? = null) : KeyValueStore {
    override fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        if (sealer == null) return raw
        return when (val o = sealer.open(raw)) {
            is KeystoreSealer.Opened.Bytes -> o.value.decodeToString()
            KeystoreSealer.Opened.Gone -> {
                // The key is gone for good (the lock was removed): the entry is useless and is dropped.
                prefs.edit().remove(key).apply()
                null
            }
            KeystoreSealer.Opened.Unavailable -> throw TokenStoreException("sealed store unavailable right now")
        }
    }

    override fun put(key: String, value: String) {
        val stored = if (sealer == null) value else try {
            sealer.seal(value.encodeToByteArray())
        } catch (e: Exception) {
            throw TokenStoreException("could not seal", e)
        }
        if (!prefs.edit().putString(key, stored).commit()) throw TokenStoreException("could not save")
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}
