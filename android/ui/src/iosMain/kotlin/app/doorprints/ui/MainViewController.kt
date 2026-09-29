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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import app.doorprints.data.ConnectLink
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.UIViewController

/**
 * The iOS app's root view controller (ADR-23 CMP-8b), what MainActivity's `setContent` is on Android: the common root
 * ([DoorprintsRoot]) with the iOS seams ([IosPlatformServices], the process's [IosAppContainer] services) and the iOS
 * feature set ([PlatformFeatures.Ios]: the features not on iPhone yet are hidden). The Swift app (ios/) calls it once,
 * as `MainViewControllerKt.MainViewController()`, and makes it the window's root.
 *
 * Also starts the process's start-up work ([IosAppContainer.start]) and, in a debug build launched with
 * `-DoorprintsSelfCheck`, the launch self-check ([startSelfCheckIfRequested]). Main thread.
 */
fun MainViewController(): UIViewController {
    logUncaughtExceptions()
    IosAppContainer.start()
    startSelfCheckIfRequested()
    val platform = IosPlatformServices()
    val services = IosAppContainer.services
    // No notifications on iOS yet; the one deep link is a connect link from the owner page's QR code (handleOpenUrl).
    val deepLinks = iosDeepLinks
    return ComposeUIViewController {
        CompositionLocalProvider(
            LocalPlatformServices provides platform,
            LocalAppServices provides services,
            LocalPlatformFeatures provides PlatformFeatures.Ios,
        ) {
            // The app lock (docs/11 5.19) around everything, as on Android.
            AppLockHost {
                DoorprintsRoot(
                    deepLinks = deepLinks,
                    onDeepLinkHandled = { deepLinks.value = null },
                )
            }
        }
    }
}

/** The deep links of this process; main thread only. */
private val iosDeepLinks = MutableStateFlow<DeepLink?>(null)

/**
 * A `doorprints://connect?server=…&invite=…` link opened on this iPhone (the camera on the owner page's QR code): the
 * Swift app's `onOpenURL` hands it over as `MainViewControllerKt.handleOpenUrl(url:)`. Only a checked connect link is
 * acted on ([ConnectLink.parse]); the app then asks before connecting. Returns whether it was one. Main thread.
 */
fun handleOpenUrl(url: String): Boolean {
    val link = ConnectLink.parse(url) ?: return false
    iosDeepLinks.value = DeepLink.Connect(link)
    return true
}

/** Whether [logUncaughtExceptions] has installed its hook; main thread only. */
private var crashHookInstalled = false

/**
 * An uncaught Kotlin exception ends the app; before it does, its type, message and stack trace go to stdout and the
 * unified log as `DOORPRINTS-CRASH …` lines, so a crash in the simulator's launch smoke (or a tester's device log)
 * says what failed. Exceptions carry no keys or house data in this app; the stack trace names only code.
 */
@OptIn(ExperimentalNativeApi::class)
private fun logUncaughtExceptions() {
    if (crashHookInstalled) return
    crashHookInstalled = true
    setUnhandledExceptionHook { e ->
        val text = "DOORPRINTS-CRASH ${e::class.qualifiedName}: ${e.message}\n${e.stackTraceToString()}"
        println(text)
        logLine(text)
        terminateWithUnhandledException(e)
    }
}
