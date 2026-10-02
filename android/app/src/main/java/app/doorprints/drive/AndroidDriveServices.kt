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

package app.doorprints.drive

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.doorprints.BuildConfig
import app.doorprints.DoorprintsApp
import app.doorprints.data.AndroidRepository
import app.doorprints.data.NetworkState
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.P256PrivateKey
import app.doorprints.crypto.platformCryptoProvider
import app.doorprints.deviceauth.AndroidDeviceAuth
import app.doorprints.deviceauth.AndroidLockLostDetector
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.LockLossActions
import app.doorprints.drive.backup.DeviceIdentity
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.connect.DeviceKeyStore
import app.doorprints.drive.connect.DriveConnectPrefs
import app.doorprints.drive.connect.DriveDeps
import app.doorprints.drive.connect.DriveGateAuthorizer
import app.doorprints.drive.connect.DriveSettingsController
import app.doorprints.drive.connect.GoogleAuthConfig
import app.doorprints.drive.connect.KtorOAuthTransport
import app.doorprints.drive.connect.KvDeletionStore
import app.doorprints.drive.connect.KvDriveStateStore
import app.doorprints.drive.connect.KvFolderTrustStores
import app.doorprints.drive.connect.KvRefreshTokenStore
import app.doorprints.drive.connect.PkceGoogleSignIn
import app.doorprints.drive.connect.RecoveryStatus
import app.doorprints.drive.delete.DriveDeletionService
import app.doorprints.shared.api.AndroidApiHttp
import app.doorprints.ui.DriveServices
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.drive_dlg_auth
import org.jetbrains.compose.resources.getString
import java.util.TimeZone

/**
 * Google Drive on Android (S4b-BL-117): assembles the common state holder over this phone's pieces. It exists only when
 * the build has a Google OAuth client id (`-Pdoorprints.google.androidClientId=...`, see `app/build.gradle.kts`); without
 * one [controller] is null and Settings shows nothing about Drive.
 *
 *  - sign-in: the system browser with PKCE ([AndroidAuthBrowser], redirect in `MainActivity`), refresh token sealed in
 *    the Keystore ([KeystoreSealer]: dies when the screen lock is removed);
 *  - the device key: a random P-256 scalar sealed the same way ([DeviceKeyStore]); lost with the lock, then this phone
 *    joins again with the recovery key (docs/15 §9.5);
 *  - the lock: [DriveGate] with [AndroidDeviceAuth] and [AndroidLockLostDetector] (keyguard and the key probe).
 */
class AndroidDriveServices(private val app: DoorprintsApp, private val repository: AndroidRepository) : DriveServices {
    private val config = GoogleAuthConfig(BuildConfig.GOOGLE_ANDROID_CLIENT_ID, BuildConfig.GOOGLE_REDIRECT_URI)

    private val importHandoff by lazy { AndroidDriveImportHandoff(app) }

    override val controller: DriveSettingsController? by lazy { if (config.isConfigured) build() else null }

    private fun build(): DriveSettingsController {
        val p = platformCryptoProvider()
        val clock = { System.currentTimeMillis() }
        val sealer = KeystoreSealer(KEY_ALIAS)
        val plain = PrefsKeyValueStore(app.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE))
        val sealed = PrefsKeyValueStore(app.getSharedPreferences(SEALED_PREFS, Context.MODE_PRIVATE), sealer)
        val http = AndroidApiHttp.create()
        val signIn = PkceGoogleSignIn(config, AndroidAuthBrowser(app), KtorOAuthTransport(http), KvRefreshTokenStore(sealed), p, clock)
        val drive = HttpDriveClient(http, signIn)
        val deviceKeys = DeviceKeyStore(sealed, p)
        val identity = object : DeviceIdentity {
            @Volatile private var cached: P256PrivateKey? = null
            override val key: P256PrivateKey get() = cached ?: deviceKeys.loadOrCreate().also { cached = it }
            override val name: String get() = Build.MODEL?.take(40)?.ifBlank { null } ?: "Android"
            override val platform = DevicePlatform.ANDROID
            fun invalidate() {
                cached = null
            }
        }
        val actions = object : LockLossActions {
            // The platform has already invalidated the sealed key; the local copies go too. The houses stay.
            override fun dropLocalKeys() {
                signIn.forgetLocally()
                deviceKeys.forget()
                identity.invalidate()
            }

            override fun requireReenrolment() {
                plain.put("drive.reenrol", "1")
            }
        }
        val auth = AndroidDeviceAuth({ ForegroundActivity.current ?: app })
        val gate = DriveGate(AuthPlatform.PHONE, auth, AndroidLockLostDetector({ app }, keyProbe = sealer::keyStillUsable), actions, clock)
        val authorizer = DriveGateAuthorizer(gate, p, clock)
        val stateStore = KvDriveStateStore(plain)
        val online = { NetworkState.current(app).let { it.connected && it.validated } }
        val deletionStore = KvDeletionStore(plain, stateStore)
        val deletion = DriveDeletionService(drive, authorizer, deletionStore, online, clock)
        val service = DriveBackupService(
            drive, p, identity, stateStore, KvFolderTrustStores(plain), clock,
            utcOffsetMinutes = { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000 }
        )
        val deps = DriveDeps(
            signIn = signIn, gate = gate, authorizer = authorizer, drive = drive, service = service, deletion = deletion,
            deletionStore = deletionStore, stateStore = stateStore,
            backupSource = AndroidDriveBackupSource(app, repository), importHandoff = importHandoff,
            prefs = SharedDrivePrefs(plain), platform = AuthPlatform.PHONE,
            deviceLock = { auth.isDeviceLockEnabled() }, webPrf = { false }, isOnline = online, clock = clock,
            authReason = { getString(Res.string.drive_dlg_auth) }, randomBytes = { n -> p.randomBytes(n) },
        )
        return DriveSettingsController(deps, app.appScope)
    }

    override fun copyRecoveryKey(text: String) {
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("", text)
        // Keeps the key out of the clipboard preview and suggestions (Android 13+; older versions ignore the extra).
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
    }

    override fun takeImportFile(): String? = importHandoff.take()

    override fun openLockSettings() {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { (ForegroundActivity.current ?: app).startActivity(intent) }
    }

    @Composable
    override fun SecureScreen(active: Boolean) {
        val activity = LocalContext.current as? Activity ?: return
        DisposableEffect(active) {
            if (active) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (active) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    private companion object {
        const val KEY_ALIAS = "doorprints_drive_v1"
        const val PLAIN_PREFS = "doorprints_drive"
        const val SEALED_PREFS = "doorprints_drive_sealed"
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

/** The switches and the recovery note, on this phone only. */
private class SharedDrivePrefs(private val kv: app.doorprints.drive.connect.KeyValueStore) : DriveConnectPrefs {
    override var autoBackup: Boolean
        get() = kv.get("drive.auto") == "1"
        set(value) = kv.put("drive.auto", if (value) "1" else "0")
    override var photosWifiOnly: Boolean
        get() = kv.get("drive.photos-wifi") != "0"
        set(value) = kv.put("drive.photos-wifi", if (value) "1" else "0")
    override var recoveryStatus: RecoveryStatus
        get() = kv.get("drive.recovery")?.let { n -> RecoveryStatus.entries.firstOrNull { it.name == n } } ?: RecoveryStatus.UNKNOWN
        set(value) = kv.put("drive.recovery", value.name)
}
