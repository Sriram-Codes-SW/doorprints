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
    /** The saved API key, or null when there is none or it cannot be read (a restored copy without its device key). */
    fun get(settings: Preferences): String?

    /** Saves [apiKey] (already trimmed and not blank) in place of any saved key. */
    fun put(settings: MutablePreferences, apiKey: String)

    /** Forgets the saved API key. */
    fun clear(settings: MutablePreferences)
}
