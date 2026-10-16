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
import android.content.Context
import app.doorprints.ui.DeepLink
import java.lang.ref.WeakReference

/** What the foreground Activity can do for the Drive wiring beyond being a [Context]: start screens for a result, open a link. */
class ActivityHooks(val starter: DeferredActivityLauncher.Starter, val openLink: (DeepLink) -> Unit)

/**
 * The foreground [Activity], for what only an Activity can show: the device check (BiometricPrompt) and Google's consent
 * screen. `MainActivity` registers itself in `onStart` and unregisters in `onStop`, so a background run (a worker) finds
 * none and those two ask nothing: the device check answers "not available" and the token provider answers "consent
 * required" (docs/15 §10.2, §5.5). Holds the Activity weakly and never keeps a destroyed one.
 */
class ActivityProvider {
    private var ref: WeakReference<Activity>? = null
    private var hooks: ActivityHooks? = null

    /** The one launcher of the process: it outlives the Activity so an answer can reach a new one after a rotation. */
    val results = DeferredActivityLauncher()

    /** Makes [activity] the foreground one (its [hooks] replace the previous Activity's), called from onStart. */
    @Synchronized
    fun register(activity: Activity, hooks: ActivityHooks? = null) {
        ref = WeakReference(activity)
        this.hooks?.let { results.detach(it.starter) }
        this.hooks = hooks
        hooks?.let { results.attach(it.starter) }
    }

    /** Only the Activity that is registered now can unregister (a new one may already have taken over on a rotation). */
    @Synchronized
    fun unregister(activity: Activity) {
        if (ref?.get() === activity) {
            ref = null
            hooks?.let { results.detach(it.starter) }
            hooks = null
        }
    }

    /** Starts a screen for a result on the foreground Activity; null in the background. */
    @Synchronized
    fun launcher(): ActivityLauncher? = if (current() != null && results.isAttached) results else null

    /** Hands a link to the app's root (the Export screen, the Import screen with a file); false in the background. */
    @Synchronized
    fun openLink(link: DeepLink): Boolean {
        val open = (if (current() != null) hooks?.openLink else null) ?: return false
        open(link)
        return true
    }

    /** The foreground Activity, or null in the background or once it is finishing or destroyed. */
    @Synchronized
    fun current(): Activity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** [current] as the plain context the device-check classes take. */
    fun context(): Context? = current()
}
