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
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.location.HuntService
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.location.StreetAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Hunt mode's alerts take the words and the channel of the kind of the walk (docs/03 section 18.19, kinds PR 26, TC-U-269): a
 * house walk is word for word what it was, on the channel it always used; a school walk says *school* and *schools*, on a channel of
 * its own named in the school's words; the "stay here?" alert carries the walk's kind; the lock-screen public text is
 * "Doorprints alert" for both and names no kind. The service is built without starting a walk (a JVM test has no foreground
 * service); the walk's kind is what its start sets.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class HuntKindAlertsTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
    }

    private fun service(kind: String): HuntService {
        val service = Robolectric.buildService(HuntService::class.java).create().get()
        HuntService::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(service, kind)
        return service
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    /** Runs the main looper until the notification [id] is posted: the words come from the Compose resources, off the main thread. */
    private fun posted(id: Int): Notification {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(manager).getNotification(id)?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("notification $id was not posted")
    }

    @Test fun aHouseWalksStreetAlertIsTheWordsAndTheChannelItAlwaysHad() {
        service("house").alertStreet("MG Road", 2, 3, null)
        val n = posted(StreetAlerts.key("MG Road").hashCode())
        assertEquals("You have been on MG Road before", title(n))
        assertEquals("Saved houses: 2, visits: 3", text(n))
        assertEquals(Notifications.CHANNEL_ALERTS, n.channelId)
        assertEquals("Doorprints alert", title(checkNotNull(n.publicVersion)))
    }

    @Test fun aSchoolWalksStreetAlertSaysSchoolsOnAChannelOfItsOwnNamedInTheSchoolsWords() {
        service("school").alertStreet("MG Road", 2, 3, 1_700_000_000_000)
        val n = posted(StreetAlerts.key("MG Road").hashCode())
        assertEquals("You have been on MG Road before", title(n))
        assertTrue(text(n), text(n).startsWith("Saved schools: 2, visits: 3, first on "))
        assertEquals("alerts_school", n.channelId)
        val channel = checkNotNull(manager.getNotificationChannel("alerts_school"))
        assertEquals("School and street alerts", channel.name.toString())
        assertEquals("When you pass a school or street you have visited before", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        // The lock screen names no kind: the same public text as the house's.
        assertEquals("Doorprints alert", title(checkNotNull(n.publicVersion)))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        // The house's channel is untouched.
        assertEquals("House and street alerts", checkNotNull(manager.getNotificationChannel(Notifications.CHANNEL_ALERTS)).name.toString())
    }

    @Test fun aSchoolWalksStreetAlertWithNoEarlierVisitSaysSchools() {
        service("school").alertStreet("Ring Road", 1, 0, null)
        val n = posted(StreetAlerts.key("Ring Road").hashCode())
        assertEquals("Saved schools: 1, visits: 0", text(n))
        assertEquals("alerts_school", n.channelId)
    }

    @Test fun aStayAlertIsWordedForTheKindAndCarriesTheWalksKindForTheEntryItMakes() {
        val id = "7d6f4b52-0c1e-4a7f-9b3d-2e8a1c5f6d40"
        service("school").alertStay(id, 26.14, 91.78)
        val school = posted(id.hashCode())
        assertEquals("Are you at a school?", title(school))
        assertEquals("alerts_school", school.channelId)
        val extras = shadowOf(checkNotNull(school.contentIntent)).savedIntent
        assertEquals("school", extras.getStringExtra(Notifications.EXTRA_NEW_KIND))
        assertEquals(id, extras.getStringExtra(Notifications.EXTRA_VISIT_ID))
        assertEquals(26.14, extras.getDoubleExtra(Notifications.EXTRA_NEW_LAT, 0.0), 0.0)
        manager.cancelAll()

        service("house").alertStay(id, 12.97, 77.59)
        val house = posted(id.hashCode())
        assertEquals("Are you at a house?", title(house))
        assertEquals("You have been here a few minutes. Tap to save it with notes and photos.", text(house))
        assertEquals(Notifications.CHANNEL_ALERTS, house.channelId)
        assertEquals("house", shadowOf(checkNotNull(house.contentIntent)).savedIntent.getStringExtra(Notifications.EXTRA_NEW_KIND))
    }

    @Test fun aPassedPlaceIsWordedForTheKind() {
        val at = 1_760_000_000_000
        val school = HouseEntity(id = "6c2f5b1e-8a3d-4c7e-9b05-1d4e7a9c3f21", label = "Pine Hill School", lat = 26.14, lon = 91.78, createdAt = at, updatedAt = at, kind = "school")
        service("school").alertHouse(school, 80)
        val n = posted(school.id.hashCode())
        assertEquals("You have seen this school: Pine Hill School", title(n))
        assertTrue(text(n), text(n).endsWith("80 m away"))
        manager.cancelAll()
        val house = school.copy(id = "0b8f6a52-3c1e-4d7a-9f4e-2a6c5d1b7e90", label = "Green View", kind = "house")
        service("house").alertHouse(house, 20)
        assertEquals("You have seen this one: Green View", title(posted(house.id.hashCode())))
    }

    @Test fun onlyAKindWithHuntModeStartsAWalk() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(false, HuntService.start(app, "clinic"))
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(true, HuntService.start(app, "school"))
        assertEquals("school", shadowOf(app).nextStartedService.getStringExtra("huntKind"))
    }
}
