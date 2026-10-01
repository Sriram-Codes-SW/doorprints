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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app lock on an emulator (docs/11 5.19, S4b-FR-5; S4b-BL-67), with the phone's own credential:
 * the test sets a device PIN from the shell (`locksettings set-pin`), turns *Lock Doorprints* on in Settings, which asks
 * for that PIN, leaves the app for less than the default minute (it stays open), picks *Right away*, leaves it again
 * (it locks on return) and unlocks it with the PIN on the system's prompt: BiometricPrompt's credential view from API
 * 29 (34 and 36 in android-emulator.yml), the keyguard's confirm-credential screen on API 26-28 (26 there). The prompt
 * is another app's window (System UI, Settings), so it is found and typed into with the platform's UiAutomation (its
 * accessibility windows and `input` from the shell; no UI Automator library). The PIN and the setting are removed
 * afterwards, so the other tests run on an unlocked phone. Where the platform cannot set a PIN from the shell (an image
 * without `locksettings`, a device policy), the test skips itself.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppLockEmulatorTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings get() = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.settings
    private var scenario: ActivityScenario<MainActivity>? = null
    private var pinSet = false

    @Before fun setUp() {
        // API 26-28 use the keyguard's confirm-credential activity, which this test does not drive reliably yet
        // (CI run of 2026-10-01: the lock never turned on after the PIN, S4b-BL-113); TC-M-29 covers it on a device.
        assumeTrue("The keyguard screen of API 26-28 is not driven by this test yet", Build.VERSION.SDK_INT >= 29)
        // The prompt is another app's window: let UiAutomation see every window, not only the active one.
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        runBlocking {
            settings.saveAppLock(false)
            settings.saveAppLockAfter(60)
        }
    }

    /**
     * Sets the device PIN. Called once the app is on screen, not in [setUp]: on API 26 a secure device that is not yet
     * unlocked keeps the app behind the keyguard, and the Compose rule then finds no hierarchy.
     */
    private fun setDevicePin() {
        val out = shell("locksettings set-pin $PIN")
        pinSet = keyguard().isDeviceSecure
        assumeTrue("No device PIN could be set from the shell ($out): the app lock needs one", pinSet)
    }

    @After fun tearDown() {
        // Every step on its own: the PIN and the setting must be removed even when an earlier step throws, or the
        // locked phone fails the tests that run after this one (ActivityScenario.close() throws when the launcher
        // intent of leaveAndReturn() has replaced the activity it tracks).
        runCatching { if (findPinField() != null) shell("input keyevent KEYCODE_BACK") }
        runCatching { scenario?.close() }
        runCatching { if (pinSet) shell("locksettings clear --old $PIN") }
        runCatching {
            runBlocking {
                settings.saveAppLock(false)
                settings.saveAppLockAfter(60)
            }
        }
    }

    @Test fun locksAfterTheChosenTimeAndUnlocksWithThePhonesPin() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("Settings") and hasClickAction(), TIMEOUT_MS)
        compose.onAllNodes(hasText("Settings") and hasClickAction()).onFirst().performClick()
        setDevicePin()

        // Turning the lock on asks for the phone's credential first (Confirm it's you).
        compose.onNode(hasText("Lock Doorprints") and isToggleable()).performScrollTo().performClick()
        enterPin()
        compose.waitUntilAtLeastOneExists(hasText("Right away"), TIMEOUT_MS)

        // Away for a few seconds, less than the default minute: it opens as it was.
        leaveAndReturn()
        compose.waitUntilAtLeastOneExists(hasText("Right away"), TIMEOUT_MS)
        check(compose.onAllNodes(hasText("Doorprints is locked")).fetchSemanticsNodes().isEmpty()) {
            "The app locked after less than the chosen minute"
        }

        // Right away: away past the chosen time, it locks, and the prompt opens by itself on return.
        compose.onNode(hasText("Right away")).performScrollTo().performClick()
        // Saved (DataStore) before the app leaves, or it would still use the minute.
        compose.waitUntilAtLeastOneExists(hasText("Right away") and isSelected(), TIMEOUT_MS)
        leaveAndReturn()
        compose.waitUntilAtLeastOneExists(hasText("Doorprints is locked"), TIMEOUT_MS)
        enterPin()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("Doorprints is locked")).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasText("Right away")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Home, a pause, and back through the launcher's intent (the task comes to the front, as from the icon). */
    private fun leaveAndReturn() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        SystemClock.sleep(AWAY_MS)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launch)
        instrumentation.waitForIdleSync()
    }

    /** Waits for the system's PIN field (another app's window), types [PIN] and confirms with Enter. */
    private fun enterPin() {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var field = findPinField()
        while (field == null || !field.isFocused) {
            check(SystemClock.uptimeMillis() < deadline) {
                "No PIN prompt within ${TIMEOUT_MS / 1000} s (API ${Build.VERSION.SDK_INT}): windows " +
                    instrumentation.uiAutomation.windows.map { it.root?.packageName }
            }
            // A field without focus yet (the prompt still coming in): focus it, so the typed keys go there.
            field?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            SystemClock.sleep(POLL_MS)
            field = findPinField()
        }
        shell("input text $PIN")
        shell("input keyevent KEYCODE_ENTER")
    }

    /** The password field of a window that is not Doorprints' (BiometricPrompt's, or the keyguard screen's). */
    private fun findPinField(): AccessibilityNodeInfo? {
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root } +
            listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        return roots.filter { it.packageName != context.packageName }.firstNotNullOfOrNull(::passwordIn)
    }

    private fun passwordIn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isPassword && node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let(::passwordIn)?.let { return it }
        }
        return null
    }

    private fun keyguard() = context.getSystemService(KeyguardManager::class.java)

    /** A shell command as the shell user (UiAutomation), its output. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString().trim() }

    private companion object {
        const val PIN = "1357"
        const val TIMEOUT_MS = 30_000L
        const val AWAY_MS = 3_000L
        const val POLL_MS = 250L
    }
}
