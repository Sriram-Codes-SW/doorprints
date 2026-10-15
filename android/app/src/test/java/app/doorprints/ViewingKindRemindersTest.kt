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
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.Viewing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A viewing reminder takes the words and the channel of the kind of its candidate (docs/03 section 18.23, kinds PR 29, TC-U-278): a
 * house's is word for word what it was, on the `viewings` channel it always used; a school's says *visit* and *school*, on a channel of
 * its own named in the school's words; the lock-screen public text is "Doorprints reminder" for both and names no kind.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class ViewingKindRemindersTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val now = 1_760_100_000_000L

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun viewing(minutesAhead: Int) = Viewing("v_00000001", "h1", now + minutesAhead * 60_000L)
    private fun entity(kind: String) = HouseEntity(id = "h1", label = "Pine Hill", lat = 12.9, lon = 77.6, kind = kind, createdAt = now, updatedAt = now)

    @Test fun aHousesReminderIsTheWordsAndTheChannelItAlwaysHad() {
        assertNull("the house has no texts of its own: the app's strings", ViewingReminderScheduler.viewingTexts(app, "house"))
        assertNull("an unknown kind is read as a house", ViewingReminderScheduler.viewingTexts(app, "clinic"))
        val n = Notifications.viewingReminder(app, viewing(25), entity("house"), now, ViewingReminderScheduler.viewingTexts(app, "house"))
        assertEquals("Viewing at Pine Hill in 25 min", title(n))
        assertEquals(Notifications.CHANNEL_VIEWINGS, n.channelId)
        assertEquals("Doorprints reminder", title(checkNotNull(n.publicVersion)))
    }

    @Test fun aSchoolsReminderSaysVisitOnAChannelOfItsOwnNamedInTheSchoolsWords() {
        val texts = checkNotNull(ViewingReminderScheduler.viewingTexts(app, "school"))
        val soon = Notifications.viewingReminder(app, viewing(25), entity("school"), now, texts)
        assertEquals("Visit to Pine Hill in 25 min", title(soon))
        assertEquals("viewings_school", soon.channelId)
        val later = Notifications.viewingReminder(app, viewing(24 * 60), entity("school"), now, texts)
        assertEquals("Visit to Pine Hill", title(later))
        val channel = checkNotNull(manager.getNotificationChannel("viewings_school"))
        assertEquals("School visit reminders", channel.name.toString())
        assertEquals("Before a school visit you have planned", channel.description)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertEquals(listOf("Open school", "Directions to the school", "Questions to ask"), soon.actions.map { it.title.toString() })
        // The lock screen names no kind: the same public text as the house's.
        assertEquals("Doorprints reminder", title(checkNotNull(soon.publicVersion)))
    }

    @Test fun aSchoolThatIsGoneIsNamedInTheSchoolsWords() {
        val texts = checkNotNull(ViewingReminderScheduler.viewingTexts(app, "school"))
        assertTrue(title(Notifications.viewingReminder(app, viewing(25), null, now, texts)).contains("a school that is gone"))
        assertTrue(title(Notifications.viewingReminder(app, viewing(25), null, now, null)).contains("a house that is gone"))
    }
}
