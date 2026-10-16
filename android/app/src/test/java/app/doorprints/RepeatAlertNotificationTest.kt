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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The repeated-path alert's notification (docs/11 5.27.5; TC-U-148 Android part): its own channel (default importance, the
 * phone's sound, no vibration, no badge, private), a notification that says only the sentence, goes by itself after two
 * minutes, is private on a locked screen with the Hunt alerts' public version and secret with the app lock on, and
 * opens the Map; posted only where notifications are allowed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class RepeatAlertNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        Notifications.createChannels(app)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test fun theChannelIsDefaultImportanceQuietOfVibrationAndBadgeAndPrivate() {
        val channel = checkNotNull(manager.getNotificationChannel(Notifications.CHANNEL_REPEAT_PATH))
        assertEquals("repeat_path", channel.id)
        assertEquals("Repeated path", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        // Not the Hunt alerts' channel: it can be muted on its own.
        assertTrue(Notifications.CHANNEL_REPEAT_PATH != Notifications.CHANNEL_ALERTS)
    }

    @Test fun theNotificationSaysOnlyTheSentenceAndGoesByItselfAfterTwoMinutes() {
        val n = Notifications.repeatPath(app, hideOnLockScreen = false)
        assertEquals("You have walked this way before.", title(n))
        assertEquals("This path is on your map from an earlier walk.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notifications.CHANNEL_REPEAT_PATH, n.channelId)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(120_000L, n.timeoutAfter)
        assertNull("no action buttons", n.actions)
        // Nothing of where she walked: no digits (a distance or a count) in the text.
        assertFalse(listOf(title(n), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).any { t -> t.any { it.isDigit() } })
    }

    @Test fun aLockedScreenShowsTheHuntAlertsPublicVersionAndTheAppLockHidesItAll() {
        val normal = Notifications.repeatPath(app, hideOnLockScreen = false)
        assertEquals(Notification.VISIBILITY_PRIVATE, normal.visibility)
        assertEquals("Doorprints alert", title(checkNotNull(normal.publicVersion)))
        assertEquals(Notification.VISIBILITY_SECRET, Notifications.repeatPath(app, hideOnLockScreen = true).visibility)
    }

    @Test fun aTapOpensTheMap() {
        val tap = shadowOf(checkNotNull(Notifications.repeatPath(app, false).contentIntent)).savedIntent
        assertEquals("map", tap.getStringExtra(Notifications.EXTRA_OPEN_SCREEN))
        assertTrue("map" in Notifications.SCREENS)
    }

    @Test fun itIsPostedWhenNotificationsAreAllowedAndNotWhenTheyAreNot() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Notifications.alertRepeatPath(app, false))
        assertNull(shadowOf(manager).getNotification(Notifications.REPEAT_PATH_ID))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(Notifications.alertRepeatPath(app, false))
        assertNotNull(shadowOf(manager).getNotification(Notifications.REPEAT_PATH_ID))
        // A second alert replaces the first: one notification.
        Notifications.alertRepeatPath(app, false)
        assertEquals(1, shadowOf(manager).activeNotifications.count { it.id == Notifications.REPEAT_PATH_ID })
    }
}
