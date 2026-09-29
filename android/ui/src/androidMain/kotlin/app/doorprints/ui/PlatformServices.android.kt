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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * [PlatformServices] on Android. [context] is the composition's context (MainActivity's, or the screenshot test's
 * activity): the location rationale needs the activity ([canAskLocation]), and the dialler and the browser are started
 * from it ([dial], [openUrl]); everything else reads the application context. Created per composition by
 * [ProvidePlatformServices]; never held by a view model or a service.
 */
class AndroidPlatformServices(private val context: Context) : PlatformServices {
    private val app = context.applicationContext

    /** TalkBack (or another touch-exploration service) is on: what `isTouchExploring(context)` read before CMP-3. */
    override fun isScreenReaderOn(): Boolean =
        app.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    override fun locationAccess(): LocationAccess = currentLocationAccess(app)

    override fun locationAsked(): Boolean = locationAsked(app)

    override fun markLocationAsked() = markLocationAsked(app)

    override fun canAskLocation(): Boolean = canAskLocation(context)

    override fun openAppSettings() = openAppSettings(context)

    override fun canPostNotifications(): Boolean = canPostNotifications(app)

    /** From the activity, as the house form did before CMP-6. */
    override fun dial(number: String) {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    override fun openUrl(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Provides [LocalPlatformServices] for [content]: MainActivity's content, and each screenshot test's. */
@Composable
fun ProvidePlatformServices(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val services = remember(context) { AndroidPlatformServices(context) }
    CompositionLocalProvider(LocalPlatformServices provides services, content = content)
}
