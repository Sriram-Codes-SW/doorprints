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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.AppLockSetting
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.app_lock_body
import app.doorprints.ui.res.app_lock_not_unlocked
import app.doorprints.ui.res.app_lock_prompt_title
import app.doorprints.ui.res.app_lock_title
import app.doorprints.ui.res.app_lock_unlock
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How the phone's own credential check ended ([rememberDeviceCredentialCheck]). */
enum class CredentialCheck {
    /** The person unlocked with the phone's PIN, pattern, password, fingerprint or face. */
    PASSED,

    /** Cancelled, or refused (too many tries): nothing changes. */
    CANCELLED,

    /** The phone has no screen lock, so there is nothing to check against. */
    NO_SCREEN_LOCK,
}

/**
 * Asks for the phone's own screen lock (docs/11 5.19, S4b-FR-5): returns a function that shows the system's prompt with
 * [title], and [onResult] when it ends. No PIN of Doorprints' own: the phone's credential is used, so there is nothing
 * new to forget or leak. Android: BiometricPrompt with the device credential allowed (API 29+), the keyguard's
 * confirm-credential screen below; iOS: LocalAuthentication's device-owner policy (Face ID, Touch ID or the passcode).
 */
@Composable
expect fun rememberDeviceCredentialCheck(title: String, onResult: (CredentialCheck) -> Unit): () -> Unit

/**
 * When the app lock covers the app (docs/11 5.19). One per process ([processAppLock]), so turning the phone or
 * switching the language (both recreate Android's activity) keeps an unlocked app unlocked; a new process starts
 * locked. Plain rules with the clock passed in, tested in `AppLockGateTest`; Compose state, so the cover follows.
 *
 * Until the setting is first read the app is covered ([covered]), so no house shows for a moment before the lock.
 */
class AppLockGate {
    /** The stored setting; null until first read. */
    var setting by mutableStateOf<AppLockSetting?>(null)
        private set

    private var locked by mutableStateOf(true)

    /** True while the system's prompt is up: it may send the app to the background (the keyguard's own screen). */
    var checking by mutableStateOf(false)
        private set

    /** When the app went to the background ([elapsedRealtimeMillis]); null while it is in the foreground. */
    private var leftAt: Long? = null

    /** True while the lock screen (or, before the setting is read, a plain cover) hides the app. */
    val covered: Boolean get() = setting.let { it == null || (it.on && locked) }

    /**
     * A new value of the setting. The first one decides the start: locked when the lock is on. Turned on later (in
     * Settings, just after the person proved it is them), the app stays open; turned off, it opens.
     */
    fun settingRead(value: AppLockSetting) {
        if (setting == null) locked = value.on else if (!value.on) locked = false
        setting = value
    }

    /** The app went to the background at [now]; the prompt's own screen does not count. */
    fun left(now: Long) {
        if (!checking && leftAt == null) leftAt = now
    }

    /** The app came back at [now]: it locks when it was away at least the chosen time. */
    fun returned(now: Long) {
        val at = leftAt ?: return
        leftAt = null
        val value = setting ?: return
        if (value.on && now - at >= value.afterSeconds * 1000L) locked = true
    }

    /** The prompt is about to show; false when one is already up (a second tap, or a start while one runs). */
    fun startCheck(): Boolean {
        if (checking) return false
        checking = true
        return true
    }

    /** The prompt ended; [result] PASSED opens the app. */
    fun checkEnded(result: CredentialCheck) {
        checking = false
        if (result == CredentialCheck.PASSED) locked = false
    }
}

/** The app lock of this process ([AppLockGate]). */
internal val processAppLock = AppLockGate()

/**
 * The app lock around the whole app (docs/11 5.19): [content] stays composed underneath, so a half-typed house is not
 * lost, but while [AppLockGate.covered] it is hidden from sight, touch and the screen reader, and the lock screen asks
 * for the phone's credential. Also hides the app from the recent-apps preview while the lock is on
 * ([AppLockWindowGuard]). MainActivity and the iOS view controller put it around the root.
 */
@Composable
fun AppLockHost(gate: AppLockGate = processAppLock, content: @Composable () -> Unit) {
    val services = LocalAppServices.current
    val platform = LocalPlatformServices.current
    val setting by services.repository.settings.appLockSetting.collectAsStateWithLifecycle(null)
    LaunchedEffect(setting) { setting?.let(gate::settingRead) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Backgrounded and back, from the activity's (the view controller's) own lifecycle. A recreation for a rotation or
    // a language switch is not leaving the app ([PlatformServices.isRecreating]).
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!platform.isRecreating()) gate.left(elapsedRealtimeMillis())
                Lifecycle.Event.ON_START -> gate.returned(elapsedRealtimeMillis())
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val lockOn = setting?.on == true
    AppLockWindowGuard(lockOn)
    // iOS takes the app switcher's picture as the app resigns active: cover it then ([PlatformServices.coverWhenInactive]).
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val inactiveCover = lockOn && platform.coverWhenInactive && !state.isAtLeast(Lifecycle.State.RESUMED)
    val covered = gate.covered
    Box(Modifier.fillMaxSize()) {
        Box(if (covered || inactiveCover) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
            content()
        }
        if (covered && setting != null) {
            DoorprintsTheme { LockScreen(gate) }
        } else if (covered || inactiveCover) {
            // Before the setting is read, and in the app switcher: the background only, nothing to read.
            DoorprintsTheme { Surface(Modifier.fillMaxSize()) {} }
        }
    }
}

/** "Doorprints is locked", with *Unlock*; the prompt opens by itself when the screen shows and when the app returns. */
@Composable
private fun LockScreen(gate: AppLockGate) {
    val platform = LocalPlatformServices.current
    val services = LocalAppServices.current
    var failed by remember { mutableStateOf(false) }
    val check = rememberDeviceCredentialCheck(stringResource(Res.string.app_lock_prompt_title)) { result ->
        gate.checkEnded(result)
        failed = result == CredentialCheck.CANCELLED
        // The phone's screen lock was removed while the app lock was on: removing it needed that credential, so the
        // person is the phone's owner; the app opens and the lock turns off (Settings then says why it cannot be on).
        if (result == CredentialCheck.NO_SCREEN_LOCK) {
            services.appScope.launch { services.repository.settings.saveAppLock(false) }
            gate.checkEnded(CredentialCheck.PASSED)
        }
    }
    fun ask() {
        if (gate.startCheck()) check()
    }
    // Once per time the lock screen is shown (a start, or a return after the chosen time), not on every recomposition.
    LaunchedEffect(Unit) { ask() }
    // Back leaves the app, as on the phone's own lock screen; it must not go back in the screens hidden underneath.
    PlatformBackHandler(enabled = true) { platform.leaveApp() }
    Surface(Modifier.fillMaxSize()) {
        LockScreenContent(
            failed = failed,
            onUnlock = ::ask,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).wrapContentSize(Alignment.Center),
        )
    }
}

/** The lock screen's text and *Unlock* ([LockScreen]); also in the screenshot tests. */
@Composable
fun LockScreenContent(failed: Boolean, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.app_lock_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.app_lock_body), textAlign = TextAlign.Center)
        Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.app_lock_unlock))
        }
        if (failed) {
            Text(
                stringResource(Res.string.app_lock_not_unlocked),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Keeps the app's screen out of the recent-apps preview while the app lock is [on] (docs/11 5.19). Android: the
 * activity's recents screenshot off (API 33+), `FLAG_SECURE` below; iOS: nothing here, [AppLockHost] covers the app as
 * it resigns active instead.
 */
@Composable
expect fun AppLockWindowGuard(on: Boolean)
