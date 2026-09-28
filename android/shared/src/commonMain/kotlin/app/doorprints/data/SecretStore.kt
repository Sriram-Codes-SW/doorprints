package app.doorprints.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * Where the server's API key is kept at rest (threat model F-03, SEC-010; CMP-4 P4b, ADR-23). [SettingsStore] reads
 * and writes the key only through this interface, so common code never sees how it is protected.
 *
 * Each call gets the settings being read or edited. On Android (`KeystoreSecretStore` in `:app`) the key is sealed
 * with an AES-GCM key that never leaves the Android Keystore and the sealed form is one entry of the settings file,
 * so saving a server's address and its key stays one DataStore transaction, as before. A store that keeps the key
 * elsewhere (the iOS Keychain, iosMain) keeps only a marker in the settings, so that a new key still makes the
 * settings emit.
 *
 * Implementations never log, print or throw the key, and never put the plain key in the settings.
 */
interface SecretStore {
    /**
     * The saved API key, or null when there is none or it cannot be read (a restored copy without its device key).
     *
     * @throws SecretUnavailableException when a key is saved but cannot be read now (iOS: the device has not been
     *   unlocked since it started, S4b-BL-30). That is not "no key": the caller changes nothing and tries later.
     */
    fun get(settings: Preferences): String?

    /** Saves [apiKey] (already trimmed and not blank) in place of any saved key. */
    fun put(settings: MutablePreferences, apiKey: String)

    /** Forgets the saved API key. */
    fun clear(settings: MutablePreferences)

    /**
     * Runs [block], a settings edit that may call [put] or [clear], so that the key and the settings stay in step
     * when the edit fails (S4b-BL-30). A store that keeps the key in the settings needs nothing: a failed edit writes
     * neither. A store that keeps it elsewhere (the iOS Keychain) puts its own item back when [block] throws.
     */
    suspend fun <T> editing(block: suspend () -> T): T = block()
}

/**
 * A saved API key that cannot be read right now, as opposed to no key (S4b-BL-30). The message names the platform's
 * status only, never the key.
 */
class SecretUnavailableException(message: String) : IllegalStateException(message)
