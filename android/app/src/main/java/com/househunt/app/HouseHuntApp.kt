package com.househunt.app

import android.app.Application
import com.househunt.app.data.AppDatabase
import com.househunt.app.data.Repository
import com.househunt.app.data.SettingsStore
import com.househunt.app.data.SyncWorker
import com.househunt.app.export.AutoBackupWorker
import com.househunt.app.export.ImportUndo
import com.househunt.app.export.Imports
import com.househunt.app.i18n.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class AppContainer(app: Application) {
    val settings = SettingsStore(app)
    val repository = Repository(app, AppDatabase.create(app), settings)
}

class HouseHuntApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Application-lifetime scope for one-off start-up work. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MapLibre.getInstance(this)
        Notifications.createChannels(AppLocale.wrap(this))
        SyncWorker.schedulePeriodic(this)
        // v0.1 kept the API key in plaintext; encrypt it with the Keystore key (threat model F-03).
        appScope.launch { runCatching { container.settings.migrateLegacyKey() } }
        // Whether the server has AI features on, once per process (UX review, whole-app audit): Root used to ask on
        // every activity recreation, and a rotation while offline hid the Assistant tab under the user's thumb.
        // Settings' "Save and test" and the Assistant's "Try again" ask again; see Repository.refreshAiStatus.
        appScope.launch { runCatching { container.repository.refreshAiStatus() } }
        appScope.launch {
            runCatching {
                // The weekly backup (S4-07) is re-registered on every start: WorkManager keeps periodic work
                // across reboots, but re-enqueuing with UPDATE is what fixes a job lost to "clear data" or a
                // constraint that changed with an app update. Off unless the user turned it on.
                val settings = container.settings.current()
                AutoBackupWorker.schedule(this@HouseHuntApp, settings.autoBackup)
                // Half-finished import staging from a previous run (see Imports.check).
                Imports.cleanOldStaging(this@HouseHuntApp)
                // Undo records of copy imports older than a day (see ImportUndo).
                ImportUndo.sweep(this@HouseHuntApp)
            }
        }
    }
}
