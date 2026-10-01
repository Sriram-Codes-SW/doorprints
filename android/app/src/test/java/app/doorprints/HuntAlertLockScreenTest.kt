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

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.screenshots.ScreenshotTestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Hunt mode's alerts on a locked screen (S4b-BL-68, threat model T-I29): private, with the public version "Doorprints
 * alert", which names no house; with the app lock on, secret, so a phone set to show all notification content does
 * not name the house on its locked screen either.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class HuntAlertLockScreenTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
    }

    private fun post(id: Int, hide: Boolean): Notification {
        Notifications.alert(app, id, "You saw Green View before", "Green View: 4 of 5, 40 m away", null, hideOnLockScreen = hide)
        return nm.getNotification(id)
    }

    @Test
    fun anAlertIsPrivateWithAPublicVersionThatNamesNoHouse() {
        val n = post(1, hide = false)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = checkNotNull(n.publicVersion)
        assertEquals("Doorprints alert", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(public.extras.keySet().any { public.extras.get(it)?.toString()?.contains("Green View") == true })
    }

    @Test
    fun withTheAppLockOnAnAlertIsSecretOnALockedScreen() {
        val n = post(2, hide = true)
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
        // Unlocked, it still says which house.
        assertEquals("You saw Green View before", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
