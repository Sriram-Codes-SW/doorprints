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

import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.platformCryptoProvider
import app.doorprints.data.AppDatabase
import app.doorprints.data.CommonRepository
import app.doorprints.deviceauth.IosLockLostDetector
import app.doorprints.drive.HttpDriveClient
import app.doorprints.drive.auth.DriveTokenProvider
import app.doorprints.drive.auth.browser.BrowserAwareResolver
import app.doorprints.drive.auth.browser.BrowserGoogleAuthorizer
import app.doorprints.drive.auth.browser.BrowserOAuthConfig
import app.doorprints.drive.auth.browser.BrowserRedirect
import app.doorprints.drive.auth.browser.GoogleIosClient
import app.doorprints.drive.auth.browser.KtorTokenEndpoint
import app.doorprints.drive.connect.BackupSummary
import app.doorprints.drive.connect.DriveConnectController
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.Outcome
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.device.DeviceLockDetectors
import app.doorprints.drive.device.SecretOperationProver
import app.doorprints.drive.ios.IosDriveBackupSource
import app.doorprints.drive.ios.IosDriveImportFile
import app.doorprints.drive.ios.IosPathMonitor
import app.doorprints.drive.ios.IosWebAuthLauncher
import app.doorprints.drive.ios.SecurityDriveKeychain
import app.doorprints.drive.keychain.KeychainProtectedSecret
import app.doorprints.drive.keychain.KeychainRefreshTokenStore
import app.doorprints.drive.ios.SecureEnclaveDeviceKey
import app.doorprints.drive.store.stateFileAt
import app.doorprints.drive.wiring.ControllerBackgroundOps
import app.doorprints.drive.wiring.DriveAssembly
import app.doorprints.drive.wiring.DriveBackgroundRunner
import app.doorprints.drive.wiring.DriveCadenceGate
import app.doorprints.drive.wiring.DriveDeps
import app.doorprints.drive.wiring.DriveGraph
import app.doorprints.drive.wiring.DriveLockRules
import app.doorprints.drive.wiring.FileCadenceStore
import app.doorprints.drive.wiring.FileDriveLockStore
import app.doorprints.drive.wiring.FileDrivePrefs
import app.doorprints.drive.wiring.LockNotice
import app.doorprints.drive.wiring.ProverDeviceAuth
import app.doorprints.drive.wiring.RoomSyncRows
import app.doorprints.drive.wiring.RunOutcome
import app.doorprints.drive.wiring.SkipReason
import app.doorprints.drive.wiring.TokenDriveSignIn
import app.doorprints.data.iosDataDirectory
import app.doorprints.shared.api.IsoTime
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.lock_notice_ios_notif_title
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT
import platform.UIKit.UIDevice
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes

/**
 * Google Drive on the iPhone (docs/15 §5.5, §9.10, S4b-BL-117/-118/-126/-127/-135): the iPhone's seams around the
 * common graph that Android composes the same way (`DriveAssembly`, `DriveConnectController`, the stores, the sign-in,
 * the sync and backup services). What is iPhone-only is here and under `:shared`'s `drive/ios`: the Secure Enclave device
 * key, the Keychain refresh token, `ASWebAuthenticationSession`, the deletion proof's Keychain secret, `NWPathMonitor`, the
 * backup ZIP's files, and the triggers (below). The graph is built when first used.
 *
 * **Not available without a Google client**: the iOS client id comes from `GoogleIOSClientId` in Info.plist (build setting
 * `GOOGLE_IOS_CLIENT_ID`, `ios/Config/Drive.xcconfig`; none in the repository), and without one the card says Drive is not
 * available, as on Android without a client.
 *
 * **Triggers** (iOS gives no reliable background time, docs/15 §1.3): a pass at the start and at every return to the app, a
 * pass 3 seconds after a local change (the sync request channel, [syncAfterChange]), and one every 30 minutes while the app is
 * in front, backing off while nothing moves ([DriveCadenceGate]). Backups run when due at the start and each return.
 * `BGTaskScheduler` is not used: it needs `BGTaskSchedulerPermittedIdentifiers` and the `fetch` background mode in
 * Info.plist and a launch registration in Swift, and the Keychain items here are readable only while the phone is unlocked,
 * which a background wake rarely finds (docs/15 §5.5); it is a later, device-measured step. A pass found outside the unlocked
 * state fails with "needs unlock" and runs again at the next return.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosDriveServices(
    private val repository: CommonRepository,
    private val db: AppDatabase,
    private val http: HttpClient,
    private val route: app.doorprints.drive.wiring.DriveSyncRoute,
    private val scope: CoroutineScope,
    private val light: FileDrivePrefs = lightPrefs(),
) {
    private val dir = iosDataDirectory() + "/" + DIR
    private val lockMemory = FileDriveLockStore(stateFileAt("$dir/${DriveAssembly.LOCK_FILE}"))
    private val cadence = DriveCadenceGate(FileCadenceStore(stateFileAt("$dir/cadence.json")))

    /** The Google client of this build, or null: Drive is then not available. */
    private val client: GoogleIosClient? = googleClient()

    /** One automatic pass at a time, and none while the person is in the middle of a Drive action of their own. */
    private val passes = Mutex()

    @Volatile
    private var automatic = 0

    private var started = false

    /** Drive is in use on this phone (the folder was open and has not been disconnected): it replaces the server for sync. */
    val engaged: Boolean get() = light.engaged

    val graph: DriveGraph by lazy { build() }

    val controller: DriveConnectController get() = graph.controller

    /**
     * Assembles the Drive graph for this phone: Google sign-in through the browser sheet (offered only with the app in
     * front and never by an automatic pass), the Keychain and Secure Enclave for secrets and the device key, the
     * passcode-based device check, the network monitor, and the common sync and backup sources. The first time Drive is
     * in use, the triggers start.
     */
    private fun build(): DriveGraph {
        // Google's sheet is offered only with the app in front: the watching starts with the graph, not only with the triggers.
        IosForeground.install()
        val crypto = platformCryptoProvider()
        val redirect = BrowserRedirect(client?.redirectUri ?: BrowserRedirect.DEFAULT_REDIRECT_URI)
        val authorizer = BrowserGoogleAuthorizer(
            config = BrowserOAuthConfig(clientId = { client?.clientId }, redirectUri = redirect.redirectUri),
            redirect = redirect,
            launcher = IosWebAuthLauncher(client?.urlScheme.orEmpty(), redirect),
            endpoint = KtorTokenEndpoint(http),
            store = KeychainRefreshTokenStore(SecurityDriveKeychain),
            random = crypto::randomBytes,
        )
        // Google's sheet opens only for the person's own action, with the app in front: never from an automatic pass.
        val tokens = DriveTokenProvider(authorizer, resolver = { if (IosForeground.active && automatic == 0) BrowserAwareResolver(null) else null })
        val prover = SecretOperationProver(KeychainProtectedSecret(crypto, ::iosHasScreenLock, SecurityDriveKeychain), crypto)
        val graph = DriveAssembly.assemble(
            DriveDeps(
                dir = dir,
                crypto = crypto,
                keyBackend = SecureEnclaveDeviceKey(),
                deviceName = iosDeviceName(),
                platform = DevicePlatform.IOS,
                drive = HttpDriveClient(http, tokens),
                signIn = TokenDriveSignIn(tokens, canConnect = ::iosHasScreenLock),
                deviceAuth = ProverDeviceAuth(prover, ::iosHasScreenLock, IsoTime::nowMillis),
                lock = { keyUsable, onKeyFault -> DeviceLockDetectors.withKeyProbe(IosLockLostDetector(), keyUsable, onKeyFault) },
                network = IosPathMonitor.state(),
                localRows = { deviceId -> RoomSyncRows(db, deviceId) { id -> photoSize(id) } },
                backupSource = IosDriveBackupSource.swept(repository, ::appLanguage),
                syncPass = { backend, photosAllowed -> withContext(route.element(backend)) { repository.sync(photosAllowed) } },
                handBack = repository::resetForServer,
                configured = client != null,
                clock = IsoTime::nowMillis,
                utcOffsetMinutes = { (NSTimeZone.localTimeZone.secondsFromGMT / SECONDS_PER_MINUTE).toInt() },
                scope = scope,
            ),
        )
        // The first time Drive is in use (the folder opened) the triggers start; started again, they do nothing twice.
        graph.prefs.onChange = { key, _ ->
            if (key == FileDrivePrefs.KEY_ENGAGED) {
                scope.launch(Dispatchers.Main) {
                    start()
                    // In context: the person has just connected Drive, and a paused backup is the one notice Drive can send.
                    // iOS shows its prompt once; a "Don't allow" leaves the Settings notice as the only word.
                    if (engaged && IosNotifications.canAsk()) IosNotifications.request {}
                }
            }
        }
        return graph
    }

    /** The size of a photo's file for the sync row, or null when the file is gone. */
    private fun photoSize(photoId: String): Long? {
        val path = repository.photoFileOf(photoId).toString()
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }

    // ---- Settings -------------------------------------------------------------------------------------------------

    /** What Settings says about the passcode now; read again at each return to the screen (a passcode may have been set meanwhile). */
    fun lockNotice(): LockNotice = DriveLockRules.notice(engaged, iosHasScreenLock(), lockMemory.keyStoreFault)

    /** True when Drive can be offered here: a Google client is in this build. */
    val available: Boolean get() = client != null

    /**
     * *Import a backup* from Drive: the chosen backup is downloaded, opened and proven by the controller into a file, and the
     * existing Import screen takes it as it takes any picked file. Null on success; else the words for the failure.
     */
    suspend fun importFromDrive(backup: BackupSummary): DriveReason? {
        val staging = IosDriveImportFile()
        return when (val result = controller.importFromDrive(backup.id, staging)) {
            is Outcome.Failed -> {
                staging.discard()
                result.reason
            }
            is Outcome.Ok -> {
                if (!staging.finish()) {
                    staging.discard()
                    DriveReason.FAILED
                } else {
                    withContext(Dispatchers.Main) { openImportFile(staging.path) }
                    null
                }
            }
        }
    }

    /** *Save a copy first*: the Export screen. */
    fun openSaveCopy() {
        openDeepLink(DeepLink.OpenScreen(Routes.EXPORT))
    }

    // ---- Passes ---------------------------------------------------------------------------------------------------

    /** Starts the triggers once Drive is in use (the start, every return to the app, the 30-minute pass). Main thread. */
    fun start() {
        if (started) return
        started = true
        IosForeground.install()
        // The network monitor needs a moment to see the first path; start it before the first pass asks.
        IosPathMonitor.snapshot()
        IosForeground.whenActive { scope.launch { onReturn() } }
        scope.launch {
            while (true) {
                delay(PERIOD)
                if (IosForeground.active && engaged && cadence.due(IsoTime.nowMillis())) {
                    runAutomatic(sync = true, backup = false) { cadence.record(it, IsoTime.nowMillis()) }
                }
            }
        }
        if (IosForeground.active) scope.launch { onReturn() }
    }

    /** The app is in front: the cadence's back-off ends, a pass and a backup run when due. */
    private suspend fun onReturn() {
        if (!engaged) return
        cadence.reset()
        runAutomatic(sync = true, backup = true) { cadence.record(it, IsoTime.nowMillis()) }
    }

    /** A pass after a local change (the sync request channel's 3-second wait is the caller's): the back-off ends. */
    suspend fun syncAfterChange() {
        if (!engaged) return
        cadence.reset()
        runAutomatic(sync = true, backup = false) { cadence.record(it, IsoTime.nowMillis()) }
    }

    /** One automatic pass, alone: a second waits for the first, and Google's sheet is never offered during it. */
    private suspend fun runAutomatic(sync: Boolean, backup: Boolean, onSync: (SyncInfo) -> Unit = {}): RunOutcome =
        passes.withLock {
            automatic++
            try {
                runInBackground(sync, backup, onSync)
            } finally {
                automatic--
            }
        }

    /**
     * One run ([DriveBackgroundRunner]). Nothing is built, asked or touched when Drive is not in use. A pause for a removed
     * passcode is remembered in the lock file (the Settings notice reads it) and announced once by a local notification
     * ([notifyLockPaused], S4b-BL-145): the runner calls it only when the pause begins, and a pause that stands says nothing
     * again (`DriveBackgroundRunnerTest`).
     */
    suspend fun runInBackground(sync: Boolean, backup: Boolean, onSync: (SyncInfo) -> Unit = {}): RunOutcome {
        if (!engaged) return RunOutcome.Skipped(SkipReason.NOT_CONNECTED)
        val ops = ControllerBackgroundOps(
            controller = { controller },
            engaged = true,
            lock = { graph.gate.beforeRun() },
            notifyLock = ::notifyLockPaused,
            standing = { DriveLockRules.standingPause(lockMemory.paused, iosHasScreenLock(), lockMemory.keyStoreFault) },
        )
        return DriveBackgroundRunner(ops, onSync).run(sync, backup)
    }

    /**
     * The local notice of a pause (S4b-BL-145): the words of Settings' notice (`lock_notice_ios_paused`, or `_key_lost` when
     * the passcode is there but the key is gone) under the title "Google Drive backup paused", as Android's. Dropped when the
     * person has not allowed notifications (the Settings notice still shows it at the next visit); a second pause replaces
     * the first (one id). A tap opens Settings, where the Drive card is.
     */
    private fun notifyLockPaused() {
        scope.launch(Dispatchers.Main) {
            if (!IosNotifications.authorized()) return@launch
            val notice = DriveLockRules.pausedNotice(lockMemory.keyStoreFault && iosHasScreenLock())
            IosNotifications.post(
                ID_LOCK_PAUSED,
                getString(Res.string.lock_notice_ios_notif_title),
                getString(notice.iosMessage()),
                mapOf<Any?, Any?>(KEY_OPEN_SETTINGS to "1"),
            )
        }
    }

    companion object {
        /** The notification id of the pause notice: a second one replaces the first. */
        const val ID_LOCK_PAUSED = "drive-lock-paused"

        /** The `userInfo` key of a tap that opens Settings (the value is ignored). */
        const val KEY_OPEN_SETTINGS = "openSettings"

        /** `Application Support/Doorprints/drive`: the per-device files; the folder above is flagged out of backups. */
        const val DIR = "drive"
        private const val PERIOD_MINUTES = 30
        private val PERIOD = PERIOD_MINUTES.minutes
        private const val SECONDS_PER_MINUTE = 60L

        /** Drive's preferences, read without building Drive (whether it is in use is read at every start). */
        fun lightPrefs(): FileDrivePrefs = FileDrivePrefs(stateFileAt(iosDataDirectory() + "/$DIR/${DriveAssembly.PREFS_FILE}"))

        /** The Google client of this build: Info.plist's `GoogleIOSClientId`, null when empty, unset or not a client id. */
        fun googleClient(): GoogleIosClient? =
            GoogleIosClient.of(NSBundle.mainBundle.objectForInfoDictionaryKey("GoogleIOSClientId") as? String)

        /** "iPhone (Doorprints app)": the model as iOS names it without extra permission, as the device list shows it. */
        fun iosDeviceName(): String = "${UIDevice.currentDevice.model} (Doorprints app)"
    }
}
