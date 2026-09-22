package com.househunt.app

import android.app.Application
import com.househunt.app.data.AppDatabase
import com.househunt.app.data.Repository
import com.househunt.app.data.SettingsStore
import com.househunt.app.data.SyncWorker
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
    }
}
