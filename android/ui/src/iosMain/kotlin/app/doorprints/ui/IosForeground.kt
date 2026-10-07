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

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import kotlin.concurrent.Volatile

/**
 * Whether the app is in front, kept from UIKit's own notifications (`UIApplicationDidBecomeActive` and
 * `WillResignActive`) so any thread can ask without touching `UIApplication` (a main-thread API). The Drive passes use it
 * three ways (docs/15 §1.3): a pass at each return to the app, a 30-minute pass only while it is in front, and Google's
 * consent sheet offered only while it is in front, so no sign-in ever opens by itself behind the person's back.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosForeground {
    @Volatile
    var active: Boolean = false
        private set

    private var installed = false
    private val onActive = mutableListOf<() -> Unit>()

    /** Starts watching, once. Main thread. */
    fun install() {
        if (installed) return
        installed = true
        active = UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            active = true
            onActive.toList().forEach { it() }
        }
        center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            active = false
        }
    }

    /** Runs [action] on the main thread each time the app becomes active (after [install]). */
    fun whenActive(action: () -> Unit) {
        onActive += action
    }
}
