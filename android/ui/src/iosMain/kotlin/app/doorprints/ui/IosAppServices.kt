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
import androidx.compose.runtime.remember
import app.doorprints.data.CommonRepository
import app.doorprints.data.Repository
import app.doorprints.data.iosAppDatabase
import app.doorprints.data.iosDataDirectory
import app.doorprints.data.iosSettingsStore
import app.doorprints.export.CopyRecord
import app.doorprints.export.CopyUndoOutcome
import app.doorprints.export.ImportCheck
import app.doorprints.export.ImportRequest
import app.doorprints.export.ImportStaging
import app.doorprints.export.ImportStart
import app.doorprints.location.HuntState
import app.doorprints.location.Place
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.IosApiHttp
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIFontMetrics
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    private val http by lazy { IosApiHttp.create() }

    /** Sync requests; conflated, so edits made while one waits or runs lead to one more sync, not one each. */
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

    val repository: CommonRepository by lazy {
        // Breadcrumbs in the unified log: a native crash while the data opens leaves no Kotlin trace, and these say
        // which step it was in (CMP-8b, the first launches in CI).
        startupStep("database")
        val db = iosAppDatabase()
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
        ).also { startupStep("ready") }
    }

    val services: AppServices by lazy { IosAppServices(repository, appScope) }

    private var started = false

    /** Start-up work, once per process (DoorprintsApp.startServices on Android). Main thread. */
    fun start() {
        if (started) return
        started = true
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
        // No periodic sync on iOS yet: one at each launch instead.
        syncRequests.trySend(Unit)
    }

    /** One sync, recorded in the settings the way Android's `SyncWorker` records it. */
    private suspend fun syncOnce() {
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
 * The common screens' [AppServices] on iOS (CMP-8b). The data ([repository]) and the location fix
 * ([IosLocationSource]) are real; the members for features [PlatformFeatures.Ios] hides (Hunt mode, adding photos,
 * copies and imports, the weekly backup, the in-app language) are inert: they do nothing, report nothing running, and
 * any picker they return says there is none. No screen reaches them while the feature is hidden.
 */
internal class IosAppServices(
    override val repository: CommonRepository,
    override val appScope: CoroutineScope,
) : AppServices {
    override val location: LocationSource = IosLocationSource()

    override val copyImports: CopyImportUndoes = NoCopyImportUndoes

    override val settingsScreen: SettingsServices = IosSettingsServices

    override val houseForm: HouseFormServices = IosHouseFormServices(repository)

    override val exportScreen: ExportServices = NoExportServices

    override val importScreen: ImportServices = NoImportServices

    override val mapScreen: MapServices = IosMapServices

    /** The language is iOS's per-app setting, changed outside the app: there is never a change to confirm. */
    override fun consumeLanguageChange(): LanguageChange? = null
}

/** No copy imports on iOS yet (hidden), so nothing to undo. */
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
private object IosSettingsServices : SettingsServices {
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

    /** `CFBundleShortVersionString` of the app's Info.plist ("0.1.0"). */
    override fun appVersion(): String? =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
}

/**
 * The house form on iOS: the stored photos are shown; taking or picking one is hidden ([PlatformFeatures.addPhotos]),
 * so there are no sources and [addPhoto] refuses. There is no reverse geocoder yet (Core Location's `CLGeocoder` is a
 * later step): the address fields stay for the user to type, as on an Android phone without a geocoder.
 */
private class IosHouseFormServices(private val repository: CommonRepository) : HouseFormServices {
    override suspend fun reverseGeocode(lat: Double, lon: Double): Place? = null

    /** No visit alerts on iOS (Hunt mode is hidden). */
    override fun clearVisitAlert(visitId: String) = Unit

    @Composable
    override fun rememberPhotoSources(onPicked: (PickedPhoto) -> Unit): PhotoSources = NoPhotoSources

    override suspend fun addPhoto(houseId: String, photo: PickedPhoto): Repository.AddPhotoResult =
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

/** The Map's services on iOS: no Hunt mode (hidden) and no notifications; the system's motion and text size. */
private object IosMapServices : MapServices {
    private val idle = MutableStateFlow(HuntState.State())
    override val hunt: StateFlow<HuntState.State> = idle.asStateFlow()
    override fun startHunt(): Boolean = false
    override fun stopHunt() = Unit
    override fun clearHuntStopReason() = Unit
    override fun notificationsReachUser(): Boolean = false

    @Composable
    override fun rememberAllowNotifications(onAnswered: () -> Unit): () -> Unit = { onAnswered() }

    /** *Reduce Motion* (Settings > Accessibility > Motion), iOS's nearest to Android's *Remove animations*. */
    override fun animationsOff(): Boolean = UIAccessibilityIsReduceMotionEnabled()

    /**
     * The Dynamic Type size as a scale: how much iOS enlarges body text (17 pt at the default size), which is what
     * Android's font scale says.
     */
    override fun fontScale(): Float = (UIFontMetrics.defaultMetrics.scaledValueForValue(BODY_POINTS) / BODY_POINTS).toFloat()

    private const val BODY_POINTS = 17.0
}

/** No *Save a copy* on iOS yet (hidden): no runs, and no picker. */
@OptIn(ExperimentalUuidApi::class)
private object NoExportServices : ExportServices {
    override fun runs(): Flow<List<ExportRun>> = flowOf(emptyList())
    override fun start(format: ExportFormat, target: String, options: ExportOptions): String = Uuid.random().toString()
    override fun stop(run: ExportRun?) = Unit
    override fun shareCopyTarget(fileName: String): String = fileName
    override fun cleanShareCopies() = Unit
    override fun clearDoneNotification() = Unit
    override fun screenVisible(visible: Boolean) = Unit

    @Composable
    override fun rememberDefaultOptions(): () -> ExportOptions = remember { { ExportOptions(language = appLanguage()) } }

    @Composable
    override fun rememberSaveToPicker(onPicked: (target: String?) -> Unit): (String, String) -> Boolean =
        remember { { _, _ -> false } }

    @Composable
    override fun rememberFileActions(): ExportFileActions = NoFileActions
}

private object NoFileActions : ExportFileActions {
    override fun open(target: String, format: ExportFormat): Boolean = false
    override fun share(target: String, format: ExportFormat): Boolean = false
}

/** No *Import a backup* on iOS yet (hidden): no runs, no picker, and any file is refused. */
@OptIn(ExperimentalUuidApi::class)
private object NoImportServices : ImportServices {
    override fun runs(): Flow<List<ImportRun>> = flowOf(emptyList())
    override fun stop() = Unit
    override fun clearDoneNotification() = Unit
    override fun screenVisible(visible: Boolean) = Unit

    @Composable
    override fun rememberBackupPicker(onPicked: (file: String?) -> Unit): (String?) -> Boolean = remember { { false } }

    override suspend fun displayName(file: String): String? = null
    override suspend fun stage(file: String): ImportStaging = ImportStaging.Refused(BackupProblem.NOT_A_BACKUP)
    override suspend fun preview(stagedPath: String, displayName: String?): ImportCheck =
        ImportCheck.Refused(BackupProblem.NOT_A_BACKUP)

    override fun isStaged(path: String): Boolean = false
    override fun discard(path: String?) = Unit

    /** Never queued, so no screen waits for a run ([ImportStart.queued]). */
    override fun start(request: ImportRequest): ImportStart = ImportStart(Uuid.random().toString()) { false }
}

/** One `DOORPRINTS-STARTUP <step>` line in the unified log as the app's data opens; names a step only. */
private fun startupStep(step: String) {
    logLine("DOORPRINTS-STARTUP $step")
}
