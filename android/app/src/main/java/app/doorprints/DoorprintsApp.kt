package app.doorprints

import android.app.Application
import android.content.res.Configuration
import androidx.work.Configuration as WorkConfiguration
import app.doorprints.data.AppDatabase
import app.doorprints.data.Repository
import app.doorprints.data.SettingsStore
import app.doorprints.data.SyncWorker
import app.doorprints.export.AutoBackupWorker
import app.doorprints.export.ImportUndo
import app.doorprints.export.Imports
import app.doorprints.i18n.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class AppContainer(app: Application) {
    val settings = SettingsStore(app)
    val repository = Repository(app, AppDatabase.create(app), settings)
}

// open for the screenshot tests' app (ScreenshotTestApp), which skips startServices(): no MapLibre (native code) and
// no WorkManager or notification set-up on the JVM.
open class DoorprintsApp : Application(), WorkConfiguration.Provider {
    lateinit var container: AppContainer
        private set

    // WorkManager starts on demand with this configuration (its start-up initializer is removed in the manifest), so
    // jobs queued under the pre-rename class names still find their worker (LegacyWorkerFactory).
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().setWorkerFactory(LegacyWorkerFactory).build()

    /** Application-lifetime scope for one-off start-up work. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The UI strings' language (Compose resources read the default locale): see AppLocale.applyDefault.
        AppLocale.wrap(this)
        container = AppContainer(this)
        startServices()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The framework has just reset the default locale to the system's (see AppLocale.applyDefault).
        AppLocale.wrap(this)
    }

    /** Platform services and start-up work; the data container above is all the screens need. */
    protected open fun startServices() {
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
                AutoBackupWorker.schedule(this@DoorprintsApp, settings.autoBackup)
                // Half-finished import staging from a previous run (see Imports.check).
                Imports.cleanOldStaging(this@DoorprintsApp)
                // Undo records of copy imports older than a day (see ImportUndo).
                ImportUndo.sweep(this@DoorprintsApp)
            }
        }
    }
}
