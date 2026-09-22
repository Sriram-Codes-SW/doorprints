package com.househunt.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.househunt.shared.sync.SyncOutcome

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val serverUrl: String = "",
    /** Decrypted in memory only; stored encrypted with a Keystore key (see [ApiKeyCipher]). */
    val apiKey: String = "",
    /** Alert when you pass within this many metres of a saved house. */
    val alertRadiusM: Int = 30,
    /** Staying roughly in one place this long counts as a visit. */
    val minStayMinutes: Int = 4,
    /** Upload and download photos only on unmetered networks (Wi-Fi). */
    val photosOnWifiOnly: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastSync: SyncOutcome? = null,
) {
    val serverConfigured get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** Last four characters of the key, for a masked hint in Settings (the full key is never shown again). */
    val apiKeyHint get() = if (apiKey.length >= 8) apiKey.takeLast(4) else ""
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("serverUrl")
        /** Legacy plaintext key (v0.1). Migrated to [apiKeyEnc] and removed on first start. */
        val apiKeyPlain = stringPreferencesKey("apiKey")
        val apiKeyEnc = stringPreferencesKey("apiKeyEnc")
        val alertRadius = intPreferencesKey("alertRadius")
        val minStay = intPreferencesKey("minStay")
        val photosOnWifiOnly = booleanPreferencesKey("photosOnWifiOnly")
        val lastSyncAt = longPreferencesKey("lastSyncAt")
        val lastSyncOutcome = stringPreferencesKey("lastSyncOutcome")
        val houseCursor = longPreferencesKey("houseCursor")
        val visitCursor = longPreferencesKey("visitCursor")
        val photoCursor = longPreferencesKey("photoCursor")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.serverUrl] ?: "",
            apiKey = ApiKeyCipher.decrypt(p[Keys.apiKeyEnc]) ?: p[Keys.apiKeyPlain] ?: "",
            alertRadiusM = p[Keys.alertRadius] ?: 30,
            minStayMinutes = p[Keys.minStay] ?: 4,
            photosOnWifiOnly = p[Keys.photosOnWifiOnly] ?: true,
            lastSyncAt = p[Keys.lastSyncAt] ?: 0,
            lastSync = SyncOutcome.decode(p[Keys.lastSyncOutcome]),
        )
    }

    suspend fun current() = settings.first()

    /** Moves a plaintext key from v0.1 into the encrypted slot. Safe to call on every start. */
    suspend fun migrateLegacyKey() {
        context.dataStore.edit {
            val plain = it[Keys.apiKeyPlain] ?: return@edit
            if (plain.isNotBlank()) it[Keys.apiKeyEnc] = ApiKeyCipher.encrypt(plain)
            it.remove(Keys.apiKeyPlain)
        }
    }

    /**
     * Saves the server. [url] must already be validated with [ServerUrl.check]. A blank [key] keeps the saved key,
     * so the key never has to be shown in the text field again (threat model AB-02).
     */
    suspend fun saveServer(url: String, key: String) = context.dataStore.edit {
        val clean = url.trim().trimEnd('/')
        val changed = it[Keys.serverUrl] != clean
        it[Keys.serverUrl] = clean
        if (key.isNotBlank()) {
            it[Keys.apiKeyEnc] = ApiKeyCipher.encrypt(key.trim())
            it.remove(Keys.apiKeyPlain)
        }
        // A different server means a fresh full download.
        if (changed) {
            it[Keys.houseCursor] = 0
            it[Keys.visitCursor] = 0
            it[Keys.photoCursor] = 0
        }
    }

    suspend fun saveTracking(alertRadiusM: Int, minStayMinutes: Int) = context.dataStore.edit {
        it[Keys.alertRadius] = alertRadiusM
        it[Keys.minStay] = minStayMinutes
    }

    suspend fun savePhotosOnWifiOnly(value: Boolean) = context.dataStore.edit { it[Keys.photosOnWifiOnly] = value }

    suspend fun saveSyncResult(outcome: SyncOutcome) = context.dataStore.edit {
        it[Keys.lastSyncAt] = System.currentTimeMillis()
        it[Keys.lastSyncOutcome] = outcome.encode()
    }

    data class Cursors(val house: Long, val visit: Long, val photo: Long)

    suspend fun cursors(): Cursors {
        val p = context.dataStore.data.first()
        return Cursors(p[Keys.houseCursor] ?: 0L, p[Keys.visitCursor] ?: 0L, p[Keys.photoCursor] ?: 0L)
    }

    suspend fun saveCursors(house: Long, visit: Long) = context.dataStore.edit {
        it[Keys.houseCursor] = house
        it[Keys.visitCursor] = visit
    }

    suspend fun savePhotoCursor(photo: Long) = context.dataStore.edit { it[Keys.photoCursor] = photo }
}
