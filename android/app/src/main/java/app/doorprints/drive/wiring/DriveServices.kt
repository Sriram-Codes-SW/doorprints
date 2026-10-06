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

package app.doorprints.drive.wiring

import android.app.Activity
import android.app.KeyguardManager
import android.net.Uri
import app.doorprints.DoorprintsApp
import app.doorprints.Notifications
import app.doorprints.R
import app.doorprints.hasPlayServices
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.Api
import app.doorprints.crypto.platformCryptoProvider
import app.doorprints.deviceauth.AndroidDeviceAuth
import app.doorprints.deviceauth.ConfirmCredentialLauncher
import app.doorprints.drive.HttpDriveClient
import app.doorprints.drive.auth.AndroidDriveTokenProvider
import app.doorprints.drive.auth.PlayGoogleAuthorizer
import app.doorprints.drive.connect.BackupSummary
import app.doorprints.drive.connect.DriveConnectController
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.Outcome
import app.doorprints.drive.device.AndroidDeviceKeys
import app.doorprints.drive.device.DeviceLockDetectors
import app.doorprints.drive.device.OperationProvers
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.DeepLink
import app.doorprints.ui.Routes
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/**
 * Google Drive on Android, built once per process from the app (S4b-BL-117/-118/-127): the real object graph
 * ([DriveAssembly]) over the Android parts (Play services' sign-in, the Keystore device key, the keyguard and
 * BiometricPrompt, `ConnectivityManager`, the Room rows and the app's HTTP client), plus what the app does around it
 * (the lock notice, the background run, the hand-offs to the Import and Export screens).
 *
 * Cheap until used: [engaged] and [lockNotice] read one small file and the keyguard; the graph ([graph]) is built the
 * first time a screen or a worker needs it.
 */
class DriveServices(
    private val app: DoorprintsApp,
    private val repository: AndroidRepository,
    private val db: AppDatabase,
    val route: DriveSyncRoute,
    val activities: ActivityProvider,
) {
    private val dir = File(app.noBackupFilesDir, DIR)
    private val light = FileDrivePrefs(File(dir, PREFS_FILE))

    /** The lock's memory of a pause, read without building the graph (the graph's own store is over the same file). */
    private val lockMemory = FileDriveLockStore(File(dir, DriveAssembly.LOCK_FILE))

    private fun keyguardSecure(): Boolean = app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Drive is in use on this phone (the folder was open and has not been disconnected): it replaces the server for sync. */
    val engaged: Boolean get() = light.engaged

    val graph: DriveGraph by lazy { build() }

    val controller: DriveConnectController get() = graph.controller

    private fun build(): DriveGraph {
        val crypto = platformCryptoProvider()
        val google = PlayGoogleAuthorizer(app)
        val consent = ConsentBridge({ activities.launcher() }, google::fromActivityResult)
        val tokens = AndroidDriveTokenProvider(google, { consent.resolverOrNull() })
        val keyguard = { app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
        val credential = ConfirmCredentialLauncher { title -> confirmCredential(title) }
        val prompt = AndroidDeviceAuth({ activities.context() }, credential)
        val prover = OperationProvers.forThisDevice({ activities.context() }, prompt, crypto)
        val auth = ProverDeviceAuth(prover, keyguard, System::currentTimeMillis)
        val graph = DriveAssembly.assemble(
            DriveDeps(
                dir = dir,
                crypto = crypto,
                keyBackend = AndroidDeviceKeys.backend(app, crypto),
                deviceName = android.os.Build.MODEL.orEmpty().ifBlank { "Android phone" },
                drive = HttpDriveClient(Api.httpClient(), tokens),
                signIn = TokenDriveSignIn(tokens, canConnect = keyguard),
                deviceAuth = auth,
                lock = { keyUsable, onKeyFault -> DeviceLockDetectors.forContext({ app }, keyUsable, onKeyFault) },
                network = ConnectivityNetworkState(app),
                localRows = { deviceId -> RoomSyncRows(db, deviceId) { id -> repository.photoFile(id).takeIf { it.isFile }?.length() } },
                backupSource = AndroidDriveBackupSource(app, repository, repository::photoFile),
                syncPass = { backend, photosAllowed ->
                    withContext(route.element(backend)) { repository.sync(photosAllowed) }
                },
                handBack = repository::resetForServer,
                configured = hasPlayServices(app),
                clock = System::currentTimeMillis,
                utcOffsetMinutes = { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / MS_PER_MINUTE },
                scope = app.appScope,
            ),
        )
        graph.prefs.onChange = { key, _ -> if (key == KEY_AUTO || key == FileDrivePrefs.KEY_ENGAGED) rescheduleWork() }
        return graph
    }

    /** The keyguard's confirm-credential screen on the foreground Activity, for Android 8 to 9 (no BiometricPrompt there). */
    @Suppress("DEPRECATION")
    private suspend fun confirmCredential(title: String): Boolean {
        val launcher = activities.launcher() ?: return false
        val intent = app.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null) ?: return false
        return launcher.launch(intent).resultCode == Activity.RESULT_OK
    }

    // ---- Settings ---------------------------------------------------------------------------------------------------

    /** What Settings says about the screen lock now. Read on every resume of the screen (the person may have set a lock meanwhile). */
    fun lockNotice(): LockNotice {
        return DriveLockRules.notice(engaged, keyguardSecure(), lockMemory.keyStoreFault)
    }

    /**
     * *Import a backup* from Drive: the chosen backup is downloaded, opened and proven by the controller into a file, and the
     * existing Import screen takes it as it takes any picked file. Null on success; else the words for the failure.
     */
    suspend fun importFromDrive(backup: BackupSummary): DriveReason? {
        val staging = DriveImportFile(File(app.cacheDir, "imports"))
        return when (val result = controller.importFromDrive(backup.id, staging)) {
            is Outcome.Failed -> result.reason
            is Outcome.Ok -> {
                staging.finish()
                if (activities.openLink(DeepLink.ImportFile(Uri.fromFile(staging.file).toString()))) {
                    null
                } else {
                    staging.discard()
                    DriveReason.FAILED
                }
            }
        }
    }

    /** *Save a copy first*: the Export screen. */
    fun openSaveCopy() {
        activities.openLink(DeepLink.OpenScreen(Routes.EXPORT))
    }

    // ---- Background -------------------------------------------------------------------------------------------------

    /**
     * One background run ([DriveBackgroundRunner]). Nothing is built, asked or touched when Drive is not in use.
     */
    suspend fun runInBackground(sync: Boolean, backup: Boolean): RunOutcome {
        if (!engaged) return RunOutcome.Skipped(SkipReason.NOT_CONNECTED)
        val ops = ControllerBackgroundOps(
            controller = { controller },
            engaged = true,
            lock = { graph.gate.beforeRun() },
            notifyLock = ::notifyLockPaused,
            standing = { DriveLockRules.standingPause(lockMemory.paused, keyguardSecure(), lockMemory.keyStoreFault) },
        )
        return DriveBackgroundRunner(ops).run(sync, backup)
    }

    /** Sets the periodic work to match "in use and automatic backup on" (also at every app start: WorkManager can lose it). */
    fun rescheduleWork() {
        val auto = light.get(DriveConnectController.KEY_AUTO_BACKUP) == "1"
        DriveBackupWorker.schedule(app, DriveLockRules.shouldSchedule(engaged, auto))
    }

    private fun notifyLockPaused() {
        val localised = AppLocale.wrap(app)
        Notifications.result(
            localised, Notifications.DRIVE_LOCK_ID, localised.getString(R.string.drive_lock_notif_title),
            localised.getString(DriveLockRules.pausedNotice(lockMemory.keyStoreFault && keyguardSecure()).messageRes()), Notifications.openScreenIntent(localised, Notifications.SCREEN_SETTINGS),
        )
    }

    companion object {
        const val DIR = "drive"
        const val PREFS_FILE = "prefs.json"
        private const val KEY_AUTO = DriveConnectController.KEY_AUTO_BACKUP
        private const val MS_PER_MINUTE = 60_000
    }
}
