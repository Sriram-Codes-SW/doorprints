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

package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.doorprints.data.AppDatabase
import app.doorprints.data.CommonRepository
import app.doorprints.data.ServerSyncBackend
import app.doorprints.data.Repository
import app.doorprints.data.iosAppDatabase
import app.doorprints.data.iosDataDirectory
import app.doorprints.data.iosSettingsStore
import app.doorprints.export.CopyRecord
import app.doorprints.export.CopyUndoOutcome
import app.doorprints.location.HuntState
import app.doorprints.location.Place
import app.doorprints.drive.wiring.DriveSyncChoice
import app.doorprints.drive.wiring.FileDrivePrefs
import app.doorprints.drive.wiring.DriveSyncRoute
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.IosApiHttp
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.shared.location.PlaceLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIFontMetrics

/**
 * The iOS app's data and services, one per process (ADR-23 CMP-8b), as Android's `AppContainer` in `DoorprintsApp`:
 * the database and settings of CMP-8a ([iosAppDatabase], [iosSettingsStore], both in the backup-excluded data folder
 * [iosDataDirectory]), the shared HTTP client ([IosApiHttp]) and the [CommonRepository] on them. Created on first use,
 * on the main thread ([MainViewController]).
 *
 * iOS has no WorkManager: a sync asked for ([CommonRepository]'s `syncSoon`, after each edit) runs in [appScope] a few
 * seconds later, while the app is open, and one runs at each launch. There is no background or periodic sync yet, and
 * photos wait whenever the user asked for Wi-Fi only, since the network type is not checked here.
 */
object IosAppContainer {
    /** The application's scope. Every job launched in it catches its own failures, so none can end the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One HTTP client for the whole app, so connections are pooled (as `:app`'s `Api.client`). */
    internal val http by lazy { IosApiHttp.create() }

    /** The database, opened once: the repository and the Drive sync rows read the same one. */
    internal val database: AppDatabase by lazy {
        // Breadcrumbs in the unified log: a native crash while the data opens leaves no Kotlin trace, and these say
        // which step it was in (CMP-8b, the first launches in CI).
        startupStep("database")
        iosAppDatabase()
    }

    /** Carries the Drive backend into the repository's sync loop for one Drive pass (S4b-BL-70's seam, docs/15 §1.3). */
    internal val driveRoute = DriveSyncRoute()

    /** Sync requests; conflated, so edits made while one waits or runs lead to one more sync, not one each. */
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

    val repository: CommonRepository by lazy {
        val db = database
        startupStep("settings")
        val settings = iosSettingsStore()
        startupStep("repository")
        CommonRepository(
            db,
            settings,
            photoDir = iosDataDirectory() + "/photos",
            syncSoon = { syncRequests.trySend(Unit) },
            apiFor = { url, key -> ApiClient(url, key, http) },
            geminiFor = { key -> app.doorprints.shared.ai.GeminiClient(http, key) },
            // While Drive is in use it replaces the server for sync (docs/15 §1.3): the Drive pass finds its own backend
            // through the route, the server's is chosen exactly as before when Drive is not in use.
            syncBackendFor = DriveSyncChoice.backendFor({ driveFlag.engaged }, driveRoute) { s ->
                if (s.serverConfigured) ServerSyncBackend(ApiClient(s.serverUrl, s.apiKey, http)) else null
            },
        ).also { startupStep("ready") }
    }

    /** Whether Drive is in use, read from one small file without building Drive (as Android's `driveFlag`). */
    internal val driveFlag: FileDrivePrefs by lazy { IosDriveServices.lightPrefs() }

    /** Google Drive backup and sync (docs/15); the graph is built when first used. */
    internal val drive: IosDriveServices by lazy { IosDriveServices(repository, database, http, driveRoute, appScope, driveFlag) }

    val services: AppServices by lazy { IosAppServices(repository, appScope, drive) }

    private var started = false

    /** Start-up work, once per process (DoorprintsApp.startServices on Android). Main thread. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        // Whether the app is in front, for the Drive passes and Google's sheet (cheap; nothing else in Drive starts yet).
        IosForeground.install()
        // A saved walk never outlives its house: at start the walks of a tombstoned house go (docs/11 5.27.6).
        appScope.launch { catchFailures { repository.sweepWalksOfDeletedHouses() } }
        // Whether the server has AI features on, once per process, as on Android (Root does not ask again).
        appScope.launch { catchFailures { repository.refreshAiStatus() } }
        appScope.launch {
            for (request in syncRequests) {
                // WorkManager's 3-second delay on Android, so a burst of edits is sent together.
                delay(SYNC_DELAY_MS)
                // Caught per sync, so one failed sync does not end the loop.
                catchFailures { syncOnce() }
            }
        }
        // No periodic sync on iOS yet: one at each launch instead. With Drive in use its own passes run at the start, at
        // every return to the app and every 30 minutes while it is in front (IosDriveServices.start).
        syncRequests.trySend(Unit)
        if (driveFlag.engaged) drive.start()
        // The viewing reminders (slice 3b-2): set at start (the first emission) and after every change of the viewings,
        // the houses (their names are in the pending bodies) or the setting, a second after a burst of edits.
        appScope.launch {
            combine(
                repository.observeViewings().distinctUntilChanged(),
                repository.houses.map { list -> list.map { it.id to it.label } }.distinctUntilChanged(),
                repository.settings.viewingsRemind(),
                // The Hunt mode reminder's switch and lead time (slice 3c).
                repository.settings.huntRemind(),
                repository.settings.huntReminderMin(),
            ) { _, _, _, _, _ -> }
                .debounce(REMINDER_DEBOUNCE_MS)
                .collect { rescheduleReminders() }
        }
    }

    /** Sets every viewing reminder again from the stored data (start, resume, each change). */
    suspend fun rescheduleReminders() = catchFailures { IosViewingReminders.rescheduleFrom(repository, nowMillis()) }

    private const val REMINDER_DEBOUNCE_MS = 1_000L

    /** One sync, recorded in the settings the way Android's `SyncWorker` records it. */
    private suspend fun syncOnce() {
        // With Google Drive in use it replaces the server (docs/15 §1.3): one Drive pass, under the same lock and Wi-Fi
        // rules; the server's status in Settings is left as it was.
        if (driveFlag.engaged) {
            drive.syncAfterChange()
            return
        }
        val settings = repository.settings.current()
        val outcome = try {
            repository.sync(photosAllowed = !settings.photosOnWifiOnly)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.fromError(e)
        }
        repository.settings.saveSyncResult(outcome)
    }

    private const val SYNC_DELAY_MS = 3_000L
}

/**
 * Runs [block] and drops any failure except cancellation, which is rethrown so the job it runs in still stops when its
 * scope is cancelled (unlike `runCatching`, which would swallow it). For the jobs launched in [IosAppContainer.appScope];
 * inline, so [block] may suspend.
 */
internal inline fun catchFailures(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Dropped: the job's own state (the sync result, the AI status) already says what went wrong.
    }
}

/**
 * The common screens' [AppServices] on iOS (CMP-8b). The data ([repository]), the location fix ([IosLocationSource])
 * and, since S4b-BL-81, the copies and imports ([IosExportServices], [IosImportServices]) are real; the members for
 * features [PlatformFeatures.Ios] hides (adding photos, the weekly backup, the in-app language) are inert: they do
 * nothing, report nothing running, and any picker they return says there is none. No screen reaches them while the
 * feature is hidden.
 */
internal class IosAppServices(
    override val repository: CommonRepository,
    override val appScope: CoroutineScope,
    private val drive: IosDriveServices,
) : AppServices {
    override val location: LocationSource = IosLocationSource()

    override val copyImports: CopyImportUndoes = NoCopyImportUndoes

    override val settingsScreen: SettingsServices = IosSettingsServices(drive)

    override val houseForm: HouseFormServices = IosHouseFormServices(repository)

    /** *Save a copy* and *Import a backup* (S4b-BL-81): the common ZIP code with the Files app and the share sheet. */
    override val exportScreen: ExportServices = IosExportServices(repository, appScope)

    override val importScreen: ImportServices = IosImportServices(repository, appScope)

    override val mapScreen: MapServices = IosMapServices

    override val offlineMaps: OfflineMapsServices = IosOfflineMapsServices

    /** Region monitoring and the "Always" permission (S4b-BL-96). */
    override val areaWakeup: AreaWakeupServices = IosAreaWakeupServices

    /** On every resume (the common root): a clock change or an authorization granted in the Settings app. */
    override fun rescheduleReminders() {
        appScope.launch { IosAppContainer.rescheduleReminders() }
    }

    /** The language is iOS's per-app setting, changed outside the app: there is never a change to confirm. */
    override fun consumeLanguageChange(): LanguageChange? = null
}

/** A copy import cannot be undone on iOS yet: its runs write no undo record ([ImportRun.undoable] false). */
private object NoCopyImportUndoes : CopyImportUndoes {
    override val undoingRun: String? get() = null
    override val outcome: CopyUndoOutcome? get() = null
    override suspend fun load(runId: String): CopyRecord? = null
    override suspend fun latestUndoable(): CopyRecord? = null
    override fun hideRow(runId: String) = Unit
    override fun start(record: CopyRecord): Boolean = false
}

/**
 * Settings on iOS: the language follows iOS's own per-app language (Settings > Doorprints > Language), which the
 * common screen opens instead of a list ([PlatformFeatures.inAppLanguage] off); no weekly backup (hidden).
 */
private class IosSettingsServices(private val drive: IosDriveServices) : SettingsServices {
    override val supportedLanguages: List<String> = listOf("en", "hi", "ta", "te")

    override fun currentLanguage(): String? = null

    /** Whatever the choice, iOS changes an app's language in its settings page (and restarts the app itself). */
    @Composable
    override fun rememberLanguageSwitch(): (String?) -> Unit {
        val platform = LocalPlatformServices.current
        return remember(platform) { { _ -> platform.openAppSettings() } }
    }

    @Composable
    override fun rememberBackupFolderPicker(onPicked: (folder: String?) -> Unit): () -> Unit = remember { {} }

    override suspend fun backupFolderLabel(folder: String): String? = null
    override val backingUpNow: Flow<Boolean> = flowOf(false)
    override fun backUpNow() = Unit
    override fun scheduleBackup(enabled: Boolean) = Unit
    override suspend fun releaseBackupFolder(folder: String) = Unit
    override val backupNoFolderError: String = "no_folder"
    override fun settingsVisible(visible: Boolean) = Unit

    /** Settings > Google Drive (docs/15 §2, §10.3): the common Drive screens behind the passcode rule. */
    @Composable
    override fun DriveSection() = IosDriveSettingsSection(drive)

    /** `CFBundleShortVersionString` of the app's Info.plist ("0.1.0"). */
    override fun appVersion(): String? =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
}

/**
 * The house form on iOS: the stored photos are shown; taking or picking one is hidden ([PlatformFeatures.addPhotos]),
 * so there are no sources and [addPhoto] refuses. The address of a new house comes from Apple's geocoder
 * ([IosGeocoder], since S4b-BL-69, as Android's `ReverseGeocoder`); the fields stay editable, as there.
 */
private class IosHouseFormServices(private val repository: CommonRepository) : HouseFormServices {
    override suspend fun reverseGeocode(lat: Double, lon: Double): Place? = IosGeocoder.place(lat, lon)

    override suspend fun findPlace(query: String): PlaceLookup.Found? = IosGeocoder.find(query)

    /** The "are you at a house?" alert of [visitId] comes down once the visit is saved as a house (as on Android). */
    override fun clearVisitAlert(visitId: String) = IosNotifications.remove(IosHunt.stayAlertId(visitId))

    @Composable
    override fun rememberPhotoSources(onPicked: (PickedPhoto) -> Unit): PhotoSources = NoPhotoSources

    override suspend fun addPhoto(houseId: String, photo: PickedPhoto, tags: List<String>): Repository.AddPhotoResult =
        Repository.AddPhotoResult.UNREADABLE

    /**
     * The photo's file path as a string. Coil 3 reads a string with no scheme as a file path (its file fetcher takes a
     * `Uri` without a scheme), as Android's `File`; found from the id, never from the row's stored path (S4b-BL-52).
     */
    override fun photoModel(photoId: String): Any = repository.photoFileOf(photoId).toString()
}

private object NoPhotoSources : PhotoSources {
    override fun takePhoto() = Unit
    override fun pickFromGallery() = Unit
}

/**
 * The Map's services on iOS: Hunt mode through [IosHunt] (S4b-BL-69), its alerts through [IosNotifications], and the
 * system's motion and text size.
 */
private object IosMapServices : MapServices {
    override val hunt: StateFlow<HuntState.State> get() = HuntState.state
    override fun startHunt(): Boolean = IosHunt.start()
    override fun stopHunt() = IosHunt.stop()

    override fun finishWalk() = IosHunt.finishWalk()
    override fun clearHuntStopReason() = IosHunt.clearStopReason()
    override fun notificationsReachUser(): Boolean = IosNotifications.canPost()

    /** The system's prompt while iOS will still show it (once), otherwise the app's page in the Settings app. */
    @Composable
    override fun rememberAllowNotifications(onAnswered: () -> Unit): () -> Unit {
        val platform = LocalPlatformServices.current
        val latest by rememberUpdatedState(onAnswered)
        return remember(platform) {
            { if (IosNotifications.canAsk()) IosNotifications.request { latest() } else platform.openAppSettings() }
        }
    }

    /** *Reduce Motion* (Settings > Accessibility > Motion), iOS's nearest to Android's *Remove animations*. */
    override fun animationsOff(): Boolean = UIAccessibilityIsReduceMotionEnabled()

    /**
     * The Dynamic Type size as a scale: how much iOS enlarges body text (17 pt at the default size), which is what
     * Android's font scale says.
     */
    override fun fontScale(): Float = (UIFontMetrics.defaultMetrics.scaledValueForValue(BODY_POINTS) / BODY_POINTS).toFloat()

    private const val BODY_POINTS = 17.0
}

/** One `DOORPRINTS-STARTUP <step>` line in the unified log as the app's data opens; names a step only. */
private fun startupStep(step: String) {
    logLine("DOORPRINTS-STARTUP $step")
}
