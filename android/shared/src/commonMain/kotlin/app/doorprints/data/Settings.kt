package app.doorprints.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
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
) {
    val serverConfigured get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** Last four characters of the key, for a masked hint in Settings (the full key is never shown again). */
    val apiKeyHint get() = if (apiKey.length >= 8) apiKey.takeLast(4) else ""
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
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val secrets: SecretStore,
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
        /** The "Save to…" documents whose persisted grant is still held, newest first; see [ExportGrants]. */
        val exportGrants = stringPreferencesKey("exportGrants")
        val exportDismissed = stringPreferencesKey("exportDismissedRun")
        val exportTold = stringPreferencesKey("exportToldRun")
        val importDismissed = stringPreferencesKey("importDismissedRun")
        val importTold = stringPreferencesKey("importToldRun")
        /** True once the app has asked for POST_NOTIFICATIONS from Export or Import; see `rememberNotificationAsk`. */
        val notificationsAsked = booleanPreferencesKey("notificationsAsked")
    }

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
        )
    }

    suspend fun current() = settings.first()

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
        dataStore.edit {
            val plain = it[Keys.apiKeyPlain] ?: return@edit
            if (plain.isNotBlank()) secrets.put(it, plain)
            it.remove(Keys.apiKeyPlain)
        }
    }

    /**
     * Saves the server. [url] must already be validated with [ServerUrl.check]. A blank [key] keeps the saved key,
     * so the key never has to be shown in the text field again (threat model AB-02).
     */
    suspend fun saveServer(url: String, key: String) = dataStore.edit {
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
