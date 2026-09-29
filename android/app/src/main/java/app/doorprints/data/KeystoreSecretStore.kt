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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The Android [SecretStore] (CMP-4 P4b): the API key sealed by [ApiKeyCipher] (AES-256-GCM with a key that never
 * leaves the Android Keystore, alias unchanged) and kept, sealed, in the settings entry `apiKeyEnc`, where it has been
 * since the key was first encrypted (threat model F-03, SEC-010). Only the stored form changes hands here; the plain
 * key is never written to the settings, logged or put in an exception.
 *
 * [seal] and [open] are [ApiKeyCipher]'s; `SettingsUpgradeTest` passes a stand-in, because the JVM tests have no
 * Android Keystore.
 */
class KeystoreSecretStore(
    private val seal: (String) -> String = ApiKeyCipher::encrypt,
    private val open: (String?) -> String? = ApiKeyCipher::decrypt,
) : SecretStore {

    /** A stored name (ADR-24): the entry that holds every saved key since v0.1's move off plaintext. Keep it. */
    private val apiKeyEnc = stringPreferencesKey("apiKeyEnc")

    override fun get(settings: Preferences): String? = open(settings[apiKeyEnc])

    override fun put(settings: MutablePreferences, apiKey: String) {
        settings[apiKeyEnc] = seal(apiKey)
    }

    override fun clear(settings: MutablePreferences) {
        settings.remove(apiKeyEnc)
    }
}
