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
import app.doorprints.shared.records.RecordRules
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
    installAreaWakeup()
    startSelfCheckIfRequested()
    val platform = IosPlatformServices()
    val services = IosAppContainer.services
    // The deep links: a tapped Hunt alert (installNotifications) or a connect link from the owner page (handleOpenUrl).
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
 * Hunt mode's alerts as notifications (S4b-BL-69): sets the notification centre's delegate, so a tapped alert opens
 * its house or the new-house form. The Swift app calls it from its `init`, before the app finishes launching, as iOS
 * requires for a tap that starts the app; `MainViewControllerKt.installNotifications()`. Main thread.
 */
fun installNotifications() {
    IosNotifications.install { userInfo -> notificationDeepLink(userInfo)?.let { iosDeepLinks.value = it } }
}

/**
 * The area wake-up's region monitoring (S4b-BL-96): its location manager and delegate, made before the app finishes
 * launching, so iOS finds them when it relaunches the app in the background for an arrival in an area, and the
 * registration at start. The Swift app calls it from its `init` after [installNotifications];
 * `MainViewControllerKt.installAreaWakeup()`. Main thread.
 */
fun installAreaWakeup() {
    if (IosAreaWakeupServices.available) IosAreaWakeup.install()
}

/**
 * The deep link a tapped alert carries, checked as `:app`'s MainActivity checks its intent (threat model F-25): a
 * well-formed UUID for a house or visit, coordinates in range; anything else is ignored.
 */
internal fun notificationDeepLink(userInfo: Map<Any?, *>): DeepLink? {
    (userInfo[IosHunt.KEY_OPEN_HOUSE] as? String)?.let { return if (isUuid(it)) DeepLink.OpenHouse(it) else null }
    (userInfo[IosHunt.KEY_OPEN_VIEWING] as? String)?.let { return if (RecordRules.isValidId(it)) DeepLink.OpenViewing(it) else null }
    // A Hunt mode reminder (slice 3c) or an area wake-up (S4b-BL-96): the Map, which offers Hunt mode (S4b-BL-94c) and
    // on *Start Hunt mode* asks for location if needed and starts it, as Android's notification action does.
    (userInfo[IosHunt.KEY_OFFER_HUNT_VIEWING] as? String)?.let { return if (RecordRules.isValidId(it)) DeepLink.OfferHunt else null }
    (userInfo[IosAreaWakeup.KEY_START_HUNT_AREA] as? String)?.let { return if (RecordRules.isValidId(it)) DeepLink.OfferHunt else null }
    val lat = (userInfo[IosHunt.KEY_NEW_LAT] as? String)?.toDoubleOrNull() ?: return null
    val lon = (userInfo[IosHunt.KEY_NEW_LON] as? String)?.toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val visitId = (userInfo[IosHunt.KEY_VISIT_ID] as? String)?.takeIf(::isUuid)
    return DeepLink.NewHouse(lat, lon, visitId)
}

private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

/**
 * A `doorprints://connect?server=…&invite=…` link opened on this iPhone (the camera on the owner page's QR code), or a
 * backup or update file another app opened in Doorprints (S4b-BL-81: the document types in Info.plist; iOS hands over
 * a copy in `Documents/Inbox`): the Swift app's `onOpenURL` hands it over as `MainViewControllerKt.handleOpenUrl(url:)`.
 * Only a checked connect link is acted on ([ConnectLink.parse]); the app then asks before connecting. A file opens the
 * Import screen with it picked, which checks it as any picked file. Returns whether it was either. Main thread.
 */
fun handleOpenUrl(url: String): Boolean {
    if (url.startsWith("file://")) {
        iosDeepLinks.value = DeepLink.ImportFile(url)
        return true
    }
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
