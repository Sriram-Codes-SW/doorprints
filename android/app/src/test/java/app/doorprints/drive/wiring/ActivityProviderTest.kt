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
import android.content.Intent
import android.app.PendingIntent
import app.doorprints.ui.DeepLink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The foreground Activity as the Drive wiring sees it: only while one is in front, and only the newest can unregister. */
class ActivityProviderTest {
    private class Starter : DeferredActivityLauncher.Starter {
        override fun start(consent: PendingIntent) = Unit
        override fun start(intent: Intent) = Unit
    }

    @Test
    fun noActivityMeansNothingToShowAScreenOn() {
        val p = ActivityProvider()
        assertNull(p.current())
        assertNull(p.context())
        assertNull(p.launcher())
        assertFalse(p.openLink(DeepLink.OpenScreen("export")))
    }

    @Test
    fun aRegisteredActivityIsFoundAndGivesTheLauncherAndLinks() {
        val p = ActivityProvider()
        val activity = Activity()
        val opened = mutableListOf<DeepLink>()
        p.register(activity, ActivityHooks(Starter()) { opened += it })
        assertSame(activity, p.current())
        assertNotNull(p.launcher())
        assertTrue(p.openLink(DeepLink.OpenScreen("export")))
        assertTrue(opened.single() == DeepLink.OpenScreen("export"))
    }

    @Test
    fun unregisteringTakesTheActivityAndItsHooksAway() {
        val p = ActivityProvider()
        val activity = Activity()
        p.register(activity, ActivityHooks(Starter()) { })
        p.unregister(activity)
        assertNull(p.current())
        assertNull(p.launcher())
        assertFalse(p.openLink(DeepLink.OpenScreen("export")))
    }

    @Test
    fun anOldActivityCannotUnregisterTheNewOne() {
        val p = ActivityProvider()
        val old = Activity()
        val fresh = Activity()
        p.register(old, ActivityHooks(Starter()) { })
        p.register(fresh, ActivityHooks(Starter()) { })
        p.unregister(old)
        assertSame(fresh, p.current())
        assertNotNull(p.launcher())
    }

    @Test
    fun withoutHooksThereIsNoLauncher() {
        val p = ActivityProvider()
        p.register(Activity())
        assertNull(p.launcher())
    }
}
