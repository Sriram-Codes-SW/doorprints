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

package app.doorprints

import android.app.Application
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import androidx.work.Configuration as WorkConfiguration
import app.doorprints.data.Api
import app.doorprints.data.AppDatabase
import app.doorprints.data.ServerSyncBackend
import app.doorprints.drive.wiring.ActivityProvider
import app.doorprints.drive.wiring.DriveServices
import app.doorprints.drive.wiring.DriveSyncChoice
import app.doorprints.drive.wiring.DriveSyncRoute
import app.doorprints.drive.wiring.FileDrivePrefs
import app.doorprints.data.create
import app.doorprints.data.AndroidRepository
import app.doorprints.data.SettingsStore
import app.doorprints.data.SyncWorker
import app.doorprints.export.AutoBackupWorker
import app.doorprints.export.ImportUndo
import app.doorprints.export.Imports
import app.doorprints.i18n.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import org.maplibre.android.MapLibre

class AppContainer(app: DoorprintsApp) {
    // One settings DataStore per process (see data/SettingsStoreFactory.kt), as the old property delegate gave.
    val settings = SettingsStore.create(app)
    private val db = AppDatabase.create(app)

    /** Carries the Drive backend into the repository's sync loop for one Drive pass (S4b-BL-70's seam, docs/15 §1.3). */
    private val driveRoute = DriveSyncRoute()

    /** The foreground Activity, for the device check and Google's consent screen (MainActivity registers itself). */
    val activities = ActivityProvider()

    /**
     * Whether Drive is in use is read from one small file, without building Drive: while it is, Drive replaces the server
     * for sync (both would share the rows' clean marks and cursors); otherwise the server is chosen exactly as before.
     */
    private val driveFlag = FileDrivePrefs(File(File(app.noBackupFilesDir, DriveServices.DIR), DriveServices.PREFS_FILE))

    val repository = AndroidRepository(
        app, db, settings,
        syncBackendFor = DriveSyncChoice.backendFor({ driveFlag.engaged }, driveRoute) { s ->
            if (s.serverConfigured) ServerSyncBackend(Api.client(s.serverUrl, s.apiKey)) else null
        },
    )

    /** Google Drive backup and sync (docs/15); the graph is built when first used. */
    val drive = DriveServices(app, repository, db, driveRoute, activities)

    /** The viewing reminders' alarms (docs/11 5.8, slice 3b-2). */
    val reminders = ViewingReminderScheduler(app, repository)

    /** The area wake-up's geofences (docs/11 slice 4b), with Play services' Geofencing API. */
    val areaWakeup = AreaGeofenceManager(repository, PlayGeofenceRegistrar(app)) { hasAreaWakeupPermissions(app) }

    /** What the common screens in :ui need from the app (ADR-23 CMP-5), provided by ProvideAppServices. */
    val services = AndroidAppServices(app, repository)
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

    /**
     * The viewing and Hunt mode reminders (slices 3b-2, 3c) are set again at start (the first emission) and after every
     * change of the viewings, of *Remind me about viewings*, or of the Hunt reminder's switch and lead time: a burst of
     * edits, an import or a sync is one reschedule a second later. The records table tells Room about every record type's writes, so equal lists are skipped first.
     */
    @OptIn(FlowPreview::class)
    @VisibleForTesting
    internal fun watchViewingReminders(): Job =
        appScope.launch {
            val settings = container.settings
            combine(
                container.repository.observeViewings().distinctUntilChanged(),
                settings.viewingsRemind(), settings.huntRemind(), settings.huntReminderMin(),
            ) { v, on, hunt, lead -> listOf(v, on, hunt, lead) }
                .debounce(REMINDER_DEBOUNCE_MS)
                .collect { runCatching { container.reminders.rescheduleAll() } }
        }

    /**
     * The area wake-up's geofences (slice 4b) are set at start (the first emission) and after every change of the areas
     * or of *Wake me in my hunting areas*, a second after a burst, as the reminders above. Nothing without Play services.
     */
    private fun watchAreaWakeup() {
        if (!hasPlayServices(this)) return
        appScope.launch { container.areaWakeup.watch() }
    }

    /** Platform services and start-up work; the data container above is all the screens need. */
    /**
     * A saved walk never outlives its house (docs/11 5.27.6): at app start every saved walk whose house is a tombstone or
     * missing is deleted (the other triggers are the Undo snackbar closing, Hunt start, sync end and import end). Not
     * cancelled by anything the screens do (the repository's sweep runs under NonCancellable).
     */
    @VisibleForTesting
    internal fun sweepWalksAtStart(): Job = appScope.launch { runCatching { container.repository.sweepWalksOfDeletedHouses() } }

    protected open fun startServices() {
        MapLibre.getInstance(this)
        sweepWalksAtStart()
        Notifications.createChannels(AppLocale.wrap(this))
        SyncWorker.schedulePeriodic(this)
        // Google Drive's regular run follows "in use and automatic backup on"; re-set at every start, as the weekly backup is.
        container.drive.rescheduleWork()
        // v0.1 kept the API key in plaintext; encrypt it with the Keystore key (threat model F-03).
        appScope.launch { runCatching { container.settings.migrateLegacyKey() } }
        // Whether the server has AI features on, once per process (UX review, whole-app audit): Root used to ask on
        // every activity recreation, and a rotation while offline hid the Assistant tab under the user's thumb.
        // Settings' "Save and test" and the Assistant's "Try again" ask again; see Repository.refreshAiStatus.
        appScope.launch { runCatching { container.repository.refreshAiStatus() } }
        watchViewingReminders()
        watchAreaWakeup()
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

private const val REMINDER_DEBOUNCE_MS = 1_000L
