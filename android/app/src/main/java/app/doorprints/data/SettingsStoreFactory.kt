package app.doorprints.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Opens the app's settings on Android (CMP-4 P4b). [SettingsStore] and [SecretStore] live in `:shared` commonMain;
 * the Android-only part stays here: the [Context], the file and the Keystore ([KeystoreSecretStore]).
 *
 * Before P4b the store was `preferencesDataStore("settings")`, a property delegate that runs exactly
 * `PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
 * context.applicationContext.preferencesDataStoreFile("settings") }` once per process. [openSettingsDataStore] makes
 * the same call on the same file, and the app calls it once per process ([app.doorprints.AppContainer]); a second
 * DataStore on the file in one process would make DataStore throw. `SettingsUpgradeTest` pins the file and the keys.
 */
fun SettingsStore.Companion.create(context: Context): SettingsStore =
    SettingsStore(openSettingsDataStore(context), KeystoreSecretStore())

/** `files/datastore/settings.preferences_pb`: the settings file of every install since v0.1. Do not rename it. */
fun settingsDataStoreFile(context: Context): File =
    context.applicationContext.preferencesDataStoreFile(SettingsStore.FILE_NAME)

/** The settings DataStore on [settingsDataStoreFile], with [scope] as its lifetime (the process, in the app). */
fun openSettingsDataStore(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { settingsDataStoreFile(context) }
