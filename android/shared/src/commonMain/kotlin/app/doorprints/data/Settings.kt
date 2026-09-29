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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.export.decodeGrants
import app.doorprints.export.encodeGrants
import app.doorprints.export.retainNewestGrants

data class AppSettings(
    val serverUrl: String = "",
    /** Decrypted in memory only; stored protected by the platform's [SecretStore] (Android: the Keystore). */
    val apiKey: String = "",
    /** Alert when you pass within this many metres of a saved house. */
    val alertRadiusM: Int = 30,
    /** Staying roughly in one place this long counts as a visit. */
    val minStayMinutes: Int = 4,
    /** Upload and download photos only on unmetered networks (Wi-Fi). */
    val photosOnWifiOnly: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastSync: SyncOutcome? = null,
    /** Failed syncs in a row since the last one that worked (see [SyncHealth]); 0 while sync works. */
    val syncFailures: Int = 0,
    /** When that run of failures started; 0 while sync works. */
    val syncFailingSince: Long = 0,
    /** When a sync last worked; 0 if never. */
    val lastSyncOkAt: Long = 0,
    /** Weekly automatic backup (Sprint 4a, S4-07). Off until the user picks a folder. */
    val autoBackup: Boolean = false,
    /** `content://` tree the user granted with OpenDocumentTree, or empty. */
    val autoBackupFolder: String = "",
    /** How many backups to keep in that folder; older ones are deleted after a new one is written. */
    val autoBackupKeep: Int = 4,
    val lastAutoBackupAt: Long = 0,
    /** Empty when the last automatic backup worked; otherwise a short reason to show in Settings. */
    val lastAutoBackupError: String = "",
    /**
     * This phone's own *AI features* switch (docs/03 §12.1): off until the person turns it on, after reading what is
     * sent to Google. AI shows only when this, the server and the owner's switch for this device are all on.
     */
    val aiFeatures: Boolean = false,
    /** Who answers AI requests (docs/03 §13.1, ADR-26): the server, or Gemini directly with [geminiKey]. */
    val aiProvider: AiProviderChoice = AiProviderChoice.SERVER,
    /** The person's own Gemini key, decrypted in memory only; kept like [apiKey] by its own [SecretStore]. */
    val geminiKey: String = "",
    /**
     * The app lock (docs/11 5.19, S4b-FR-5): the phone's own screen lock (PIN, pattern, fingerprint or face) is asked
     * when the app opens and when it comes back after [appLockAfterSeconds] in the background. Off by default.
     */
    val appLock: Boolean = false,
    /** How long the app may be in the background before it locks again; 0 locks every time it is left. */
    val appLockAfterSeconds: Int = AppLockTimes.DEFAULT,
) {
    /** Last four characters of the Gemini key, for Settings' masked hint. */
    val geminiKeyHint get() = if (geminiKey.length >= 8) geminiKey.takeLast(4) else ""

    val serverConfigured get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** Last four characters of the key, for a masked hint in Settings (the full key is never shown again). */
    val apiKeyHint get() = if (apiKey.length >= 8) apiKey.takeLast(4) else ""

    /**
     * Never the keys (S4b-BL-29; readiness review 2026-09-29, docs/14 §8 finding 7a): a data class's generated
     * `toString()` would print [apiKey] and [geminiKey] into any log or crash report that prints the settings, and
     * sign-in tokens will join these fields. Only whether each is set.
     */
    override fun toString(): String =
        "AppSettings(serverUrl=$serverUrl, apiKey=${if (apiKey.isEmpty()) "none" else "set"}, " +
            "alertRadiusM=$alertRadiusM, minStayMinutes=$minStayMinutes, photosOnWifiOnly=$photosOnWifiOnly, " +
            "lastSyncAt=$lastSyncAt, lastSync=$lastSync, syncFailures=$syncFailures, syncFailingSince=$syncFailingSince, " +
            "lastSyncOkAt=$lastSyncOkAt, autoBackup=$autoBackup, autoBackupFolder=$autoBackupFolder, " +
            "autoBackupKeep=$autoBackupKeep, lastAutoBackupAt=$lastAutoBackupAt, lastAutoBackupError=$lastAutoBackupError, " +
            "aiFeatures=$aiFeatures, aiProvider=$aiProvider, geminiKey=${if (geminiKey.isEmpty()) "none" else "set"}, " +
            "appLock=$appLock, appLockAfterSeconds=$appLockAfterSeconds)"
}

/**
 * The finished export and import runs the user has already been shown ([exportTold], [importTold]) or has closed
 * ([exportDismissed], [importDismissed]), so the screens can keep showing a result nobody was told about — a
 * failed backup, or a Share copy finished while notifications were off — until it has been seen, without showing
 * it for ever (UX review, round 11). In the DataStore rather than `rememberSaveable`, which forgets them as soon as
 * the user backs out of the screen.
 */
data class ResultMarks(
    val exportDismissed: String? = null,
    val exportTold: String? = null,
    val importDismissed: String? = null,
    val importTold: String? = null,
)

/** Which screen a [ResultMarks] entry is for. */
enum class ResultScreen { EXPORT, IMPORT }

/**
 * The app's settings, in one Preferences DataStore (common code since CMP-4 P4b, ADR-23; was `:app`).
 *
 * The file and the key names below are stored names: an upgraded install reads the settings it already has only
 * because they did not change (ADR-24). Android opens the file in `:app` (`data/SettingsStoreFactory.kt`,
 * `files/datastore/settings.preferences_pb`, the path `preferencesDataStore("settings")` gave before);
 * `SettingsUpgradeTest` writes it the old way and reads it through this class.
 *
 * [dataStore] must be the only DataStore open on its file in the process. [secrets] keeps the API key; [now] is the
 * clock for sync times (epoch milliseconds).
 */
/** The app lock's setting alone ([SettingsStore.appLockSetting]). */
data class AppLockSetting(val on: Boolean, val afterSeconds: Int)

/** The app lock's choices of time in the background before it locks again (docs/11 5.19). */
object AppLockTimes {
    /** Right away, 1 minute, 5 minutes, 15 minutes. */
    val CHOICES = listOf(0, 60, 300, 900)
    const val DEFAULT = 60

    /** A stored value, or the default when it is missing or not one of [CHOICES] (an older or edited file). */
    fun valid(seconds: Int?): Int = seconds?.takeIf { it in CHOICES } ?: DEFAULT
}

/** Who answers AI requests: the connected server, or Gemini directly with the person's own key (ADR-26). */
enum class AiProviderChoice { SERVER, DEVICE }

class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val secrets: SecretStore,
    /** Where the person's own Gemini key is kept (docs/03 §13.1); null where a platform has none (tests). */
    private val geminiSecrets: SecretStore? = null,
    private val now: () -> Long = { IsoTime.nowMillis() },
) {
    companion object {
        /** The DataStore's name: Android's file is `datastore/settings.preferences_pb`. Do not change it. */
        const val FILE_NAME = "settings"
    }

    private object Keys {
        val serverUrl = stringPreferencesKey("serverUrl")
        /** Legacy plaintext key (v0.1). Moved into the [SecretStore] and removed on first start. */
        val apiKeyPlain = stringPreferencesKey("apiKey")
        val alertRadius = intPreferencesKey("alertRadius")
        val minStay = intPreferencesKey("minStay")
        val photosOnWifiOnly = booleanPreferencesKey("photosOnWifiOnly")
        val lastSyncAt = longPreferencesKey("lastSyncAt")
        val lastSyncOutcome = stringPreferencesKey("lastSyncOutcome")
        val syncFailures = intPreferencesKey("syncFailures")
        val syncFailingSince = longPreferencesKey("syncFailingSince")
        val lastSyncOkAt = longPreferencesKey("lastSyncOkAt")
        val houseCursor = longPreferencesKey("houseCursor")
        val visitCursor = longPreferencesKey("visitCursor")
        val photoCursor = longPreferencesKey("photoCursor")
        val autoBackup = booleanPreferencesKey("autoBackup")
        val autoBackupFolder = stringPreferencesKey("autoBackupFolder")
        val autoBackupKeep = intPreferencesKey("autoBackupKeep")
        val lastAutoBackupAt = longPreferencesKey("lastAutoBackupAt")
        val lastAutoBackupError = stringPreferencesKey("lastAutoBackupError")
        /** The "Save to…" documents whose persisted grant is still held, newest first; see `ExportGrants` (`:app`). */
        val exportGrants = stringPreferencesKey("exportGrants")
        val exportDismissed = stringPreferencesKey("exportDismissedRun")
        val exportTold = stringPreferencesKey("exportToldRun")
        val importDismissed = stringPreferencesKey("importDismissedRun")
        val importTold = stringPreferencesKey("importToldRun")
        /** True once the app has asked for POST_NOTIFICATIONS from Export or Import; see `rememberNotificationAsk`. */
        val notificationsAsked = booleanPreferencesKey("notificationsAsked")
        /** [AppSettings.aiFeatures]. */
        val aiFeatures = booleanPreferencesKey("aiFeatures")
        /** [AppSettings.aiProvider], by name. */
        val aiProvider = stringPreferencesKey("aiProvider")
        /** [AppSettings.appLock] and [AppSettings.appLockAfterSeconds]. */
        val appLock = booleanPreferencesKey("appLock")
        val appLockAfter = intPreferencesKey("appLockAfterSeconds")
    }

    /** Throws [SecretUnavailableException] while a saved key cannot be read (see [SecretStore.get]). */
    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.serverUrl] ?: "",
            apiKey = secrets.get(p) ?: p[Keys.apiKeyPlain] ?: "",
            alertRadiusM = p[Keys.alertRadius] ?: 30,
            minStayMinutes = p[Keys.minStay] ?: 4,
            photosOnWifiOnly = p[Keys.photosOnWifiOnly] ?: true,
            lastSyncAt = p[Keys.lastSyncAt] ?: 0,
            lastSync = SyncOutcome.decode(p[Keys.lastSyncOutcome]),
            syncFailures = p[Keys.syncFailures] ?: 0,
            syncFailingSince = p[Keys.syncFailingSince] ?: 0,
            lastSyncOkAt = p[Keys.lastSyncOkAt] ?: 0,
            autoBackup = p[Keys.autoBackup] ?: false,
            autoBackupFolder = p[Keys.autoBackupFolder] ?: "",
            autoBackupKeep = p[Keys.autoBackupKeep] ?: 4,
            lastAutoBackupAt = p[Keys.lastAutoBackupAt] ?: 0,
            lastAutoBackupError = p[Keys.lastAutoBackupError] ?: "",
            aiFeatures = p[Keys.aiFeatures] ?: false,
            aiProvider = AiProviderChoice.entries.firstOrNull { it.name == p[Keys.aiProvider] } ?: AiProviderChoice.SERVER,
            geminiKey = geminiSecrets?.get(p) ?: "",
            appLock = p[Keys.appLock] ?: false,
            appLockAfterSeconds = AppLockTimes.valid(p[Keys.appLockAfter]),
        )
    }

    suspend fun current() = settings.first()

    /**
     * Only the app lock's two values (docs/11 5.19), for the lock itself: it reads no secret, so it neither decrypts the
     * keys on every change nor fails while a key cannot be read. A settings file that cannot be read locks (fail
     * closed): the phone's own credential still opens the app.
     */
    val appLockSetting: Flow<AppLockSetting> = dataStore.data
        .map { p -> AppLockSetting(p[Keys.appLock] ?: false, AppLockTimes.valid(p[Keys.appLockAfter])) }
        .catch { emit(AppLockSetting(on = true, afterSeconds = AppLockTimes.DEFAULT)) }
        .distinctUntilChanged()

    /** See [ResultMarks]. */
    val resultMarks: Flow<ResultMarks> = dataStore.data.map { p ->
        ResultMarks(p[Keys.exportDismissed], p[Keys.exportTold], p[Keys.importDismissed], p[Keys.importTold])
    }

    /** Records that the result of [runId] on [screen] has been shown to the user. */
    suspend fun markResultTold(screen: ResultScreen, runId: String) = dataStore.edit {
        it[if (screen == ResultScreen.EXPORT) Keys.exportTold else Keys.importTold] = runId
    }

    /** Records that the user closed or moved on from the result of [runId] on [screen]; it is not shown again. */
    suspend fun markResultDismissed(screen: ResultScreen, runId: String) = dataStore.edit {
        it[if (screen == ResultScreen.EXPORT) Keys.exportDismissed else Keys.importDismissed] = runId
        it[if (screen == ResultScreen.EXPORT) Keys.exportTold else Keys.importTold] = runId
    }

    /** Whether the in-context notification request has been made already (it is made once). */
    val notificationsAsked: Flow<Boolean> = dataStore.data.map { it[Keys.notificationsAsked] ?: false }

    suspend fun setNotificationsAsked() = dataStore.edit { it[Keys.notificationsAsked] = true }

    /** Moves a plaintext key from v0.1 into the encrypted slot. Safe to call on every start. */
    suspend fun migrateLegacyKey() {
        secrets.editing {
            dataStore.edit {
                val plain = it[Keys.apiKeyPlain] ?: return@edit
                if (plain.isNotBlank()) secrets.put(it, plain)
                it.remove(Keys.apiKeyPlain)
            }
        }
    }

    /**
     * Saves the server. [url] must already be validated with [ServerUrl.check]. A blank [key] keeps the saved key,
     * so the key never has to be shown in the text field again (threat model AB-02). Inside [SecretStore.editing],
     * so a key kept outside the settings is put back if the settings cannot be written (S4b-BL-30).
     */
    suspend fun saveServer(url: String, key: String) = secrets.editing { saveServerEdit(url, key) }

    private suspend fun saveServerEdit(url: String, key: String) = dataStore.edit {
        val clean = url.trim().trimEnd('/')
        val changed = it[Keys.serverUrl] != clean
        it[Keys.serverUrl] = clean
        if (key.isNotBlank()) {
            secrets.put(it, key.trim())
            it.remove(Keys.apiKeyPlain)
        }
        // A different server means a fresh full download.
        if (changed) {
            it[Keys.houseCursor] = 0
            it[Keys.visitCursor] = 0
            it[Keys.photoCursor] = 0
        }
        // A new server or key starts a new record: the failures counted against the old setup say nothing about it.
        if (changed || key.isNotBlank()) {
            it.remove(Keys.syncFailures)
            it.remove(Keys.syncFailingSince)
            it.remove(Keys.lastSyncOkAt)
        }
    }

    suspend fun saveTracking(alertRadiusM: Int, minStayMinutes: Int) = dataStore.edit {
        it[Keys.alertRadius] = alertRadiusM
        it[Keys.minStay] = minStayMinutes
    }

    suspend fun savePhotosOnWifiOnly(value: Boolean) = dataStore.edit { it[Keys.photosOnWifiOnly] = value }

    suspend fun saveAiFeatures(on: Boolean) = dataStore.edit { it[Keys.aiFeatures] = on }

    /** Turns the app lock on or off; the caller has checked the phone's credential first (AppLockGate). */
    suspend fun saveAppLock(on: Boolean) = dataStore.edit { it[Keys.appLock] = on }

    /** How long the app may stay in the background before it locks; one of [AppLockTimes.CHOICES]. */
    suspend fun saveAppLockAfter(seconds: Int) = dataStore.edit { it[Keys.appLockAfter] = AppLockTimes.valid(seconds) }

    suspend fun saveAiProvider(choice: AiProviderChoice) = dataStore.edit { it[Keys.aiProvider] = choice.name }

    /** Saves the person's own Gemini key ([key] trimmed, not blank) and chooses on-device AI. */
    suspend fun saveGeminiKey(key: String) {
        val store = checkNotNull(geminiSecrets) { "No place for a Gemini key on this platform" }
        store.editing {
            dataStore.edit {
                store.put(it, key.trim())
                it[Keys.aiProvider] = AiProviderChoice.DEVICE.name
            }
        }
    }

    /** Forgets the Gemini key; AI goes back to the server. */
    suspend fun removeGeminiKey() {
        val store = geminiSecrets ?: return
        store.editing {
            dataStore.edit {
                store.clear(it)
                it[Keys.aiProvider] = AiProviderChoice.SERVER.name
            }
        }
    }

    /** Records a sync's outcome, and counts failures in a row for the house list's warning ([SyncHealth]). */
    suspend fun saveSyncResult(outcome: SyncOutcome) = dataStore.edit {
        val at = now()
        val failures = it[Keys.syncFailures] ?: 0
        it[Keys.lastSyncAt] = at
        it[Keys.lastSyncOutcome] = outcome.encode()
        it[Keys.syncFailingSince] =
            SyncHealth.nextFailingSince(outcome.kind, failures, it[Keys.syncFailingSince] ?: 0L, at)
        it[Keys.syncFailures] = SyncHealth.nextFailures(outcome.kind, failures)
        if (outcome.kind == SyncOutcome.Kind.OK) it[Keys.lastSyncOkAt] = at
    }

    data class Cursors(val house: Long, val visit: Long, val photo: Long)

    suspend fun cursors(): Cursors {
        val p = dataStore.data.first()
        return Cursors(p[Keys.houseCursor] ?: 0L, p[Keys.visitCursor] ?: 0L, p[Keys.photoCursor] ?: 0L)
    }

    suspend fun saveCursors(house: Long, visit: Long) = dataStore.edit {
        it[Keys.houseCursor] = house
        it[Keys.visitCursor] = visit
    }

    suspend fun savePhotoCursor(photo: Long) = dataStore.edit { it[Keys.photoCursor] = photo }

    /**
     * Back to a full download (S4b-BL-20): the server was found behind this phone. The caller marks every row for
     * upload first, so a sync cut off in between finds the server behind again on its next run.
     */
    suspend fun resetCursors() = dataStore.edit {
        it[Keys.houseCursor] = 0
        it[Keys.visitCursor] = 0
        it[Keys.photoCursor] = 0
    }

    /**
     * Turns the weekly backup on or off. [folder] is the persisted `content://` tree from `OpenDocumentTree`;
     * a blank one always means "off", because there would be nowhere to write.
     *
     * Choosing a (different) folder, or switching the backup on, clears the last error: it described the old
     * setup, and a "The folder is no longer available" left on screen for a week after the user fixed exactly
     * that makes the fix look as if it failed.
     */
    suspend fun saveAutoBackup(enabled: Boolean, folder: String, keep: Int) = dataStore.edit {
        val on = enabled && folder.isNotBlank()
        val wasOn = it[Keys.autoBackup] ?: false
        val folderChanged = folder.isNotBlank() && folder != (it[Keys.autoBackupFolder] ?: "")
        if (folderChanged || (on && !wasOn)) it.remove(Keys.lastAutoBackupError)
        it[Keys.autoBackup] = on
        it[Keys.autoBackupFolder] = folder
        it[Keys.autoBackupKeep] = keep.coerceIn(1, 20)
    }

    suspend fun saveAutoBackupResult(at: Long, error: String) = dataStore.edit {
        it[Keys.lastAutoBackupAt] = at
        it[Keys.lastAutoBackupError] = error
    }

    /**
     * Records [uri] as the newest held export grant and returns the older ones that no longer fit in [keep], for
     * the caller to release. One `edit`, so two exports finishing together cannot both keep the same slot.
     */
    suspend fun holdExportGrant(uri: String, keep: Int): List<String> {
        var released = emptyList<String>()
        dataStore.edit {
            val retention = retainNewestGrants(decodeGrants(it[Keys.exportGrants]), uri, keep)
            it[Keys.exportGrants] = encodeGrants(retention.kept)
            released = retention.released
        }
        return released
    }

    /** Forgets [uri] as a held export grant (its grant has been, or is about to be, released). */
    suspend fun dropExportGrant(uri: String) {
        dataStore.edit {
            val held = decodeGrants(it[Keys.exportGrants])
            if (uri in held) it[Keys.exportGrants] = encodeGrants(held.filter { h -> h != uri })
        }
    }
}
