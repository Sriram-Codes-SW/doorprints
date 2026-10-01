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
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingReminders
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The viewing reminders on Android (docs/11 5.8, slice 3b-2; the alarm rules of 5.16 *Scheduling*, TC-U-38) on the
 * app's real repository and Robolectric's AlarmManager: exact alarms only while allowed, otherwise the early 10-minute
 * window; nothing for a past, DONE or CANCELLED viewing; a deleted one's alarm cancelled; the setting off schedules
 * none; the cap of 60; the fallbacks; the notification (channel, public version, actions, never `withWhom`); and the
 * system broadcasts that reschedule.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class ViewingRemindersTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo = app.container.repository
    private val min = 60_000L
    private val now = 1_790_569_800_000L // 2026-09-28 10:00 IST
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val scheduler = ViewingReminderScheduler(app, repo) { now }
    private val house = HouseEntity(
        id = "11111111-1111-4111-8111-111111111111", label = "Green View", street = "MG Road",
        lat = 12.97, lon = 77.59, createdAt = now, updatedAt = now, locationSource = LocationSource.GPS,
    )

    @Before fun setUp() {
        runBlocking { repo.saveHouse(house) }
    }

    @After fun tearDown() {
        ShadowAlarmManager.reset()
    }

    private fun viewing(id: String, startsAt: Long = now + 120 * min, remindMin: Int = 60, status: String = "PLANNED", withWhom: String? = null) =
        Viewing(id = id, houseId = house.id, startsAt = startsAt, remindMin = remindMin, status = status, withWhom = withWhom)

    private fun seed(vararg list: Viewing) = runBlocking { list.forEach { repo.saveViewing(it) } }
    private fun reschedule() = runBlocking { scheduler.rescheduleAll().map { it.id to it.how } }

    // ---- scheduling (TC-U-38) ----

    @Test
    fun rescheduleAll_setsAnExactAlarmAtFireAtWhenAllowed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001"))
        assertEquals(listOf("v_00000001" to ViewingReminderScheduler.How.EXACT), reschedule())
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(now + 60 * min, alarm.triggerAtTime)
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        // Immutable, explicit to the non-exported receiver, one per viewing (T-E8).
        val op = shadowOf(alarm.operation)
        assertTrue(op.isImmutable)
        assertTrue(op.isBroadcast)
        assertEquals(ViewingReminderReceiver::class.java.name, op.savedIntent.component?.className)
        assertEquals("v_00000001", op.savedIntent.getStringExtra(ViewingReminderScheduler.EXTRA_VIEWING_ID))
    }

    @Test
    fun rescheduleAll_setsTheEarlyTenMinuteWindowAndNoExactCallWhenNotAllowed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.exact = { _, _ -> throw AssertionError("an exact call without the permission") }
        seed(viewing("v_00000001"))
        assertEquals(listOf("v_00000001" to ViewingReminderScheduler.How.WINDOW), reschedule())
        val alarm = alarms.scheduledAlarms.single()
        // [fireAt - 10 min, fireAt]: early, never late.
        assertEquals(now + 50 * min, alarm.triggerAtTime)
        assertEquals(10 * min, alarm.windowLengthMs)
    }

    @Test
    fun rescheduleAll_startsTheWindowNowWhenItsStartHasPassed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        // fireAt is 4 minutes away: the window cannot open 10 minutes before it.
        seed(viewing("v_00000001", startsAt = now + 19 * min, remindMin = 15))
        reschedule()
        assertEquals(now, alarms.scheduledAlarms.single().triggerAtTime)
    }

    @Test
    fun rescheduleAll_setsNothingForAPastDoneOrCancelledViewingOrOneWithoutAReminder() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(
            viewing("v_00000001", startsAt = now + 30 * min), // fireAt 30 min ago
            viewing("v_00000002", status = "DONE"),
            viewing("v_00000003", status = "CANCELLED"),
            viewing("v_00000004", remindMin = 0),
        )
        assertTrue(reschedule().isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun rescheduleAll_cancelsTheAlarmOfADeletedOrCancelledViewing() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001"), viewing("v_00000002"))
        reschedule()
        assertEquals(2, alarms.scheduledAlarms.size)
        runBlocking {
            repo.deleteViewing("v_00000001")
            repo.saveViewing(viewing("v_00000002", status = "CANCELLED"))
        }
        assertTrue(reschedule().isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        // The deleted one's PendingIntent is gone too, not only its alarm.
        assertNull(ViewingReminderScheduler.operation(app, "v_00000001", android.app.PendingIntent.FLAG_NO_CREATE))
    }

    @Test
    fun rescheduleAll_withTheSettingOffCancelsAndSchedulesNone() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001"))
        reschedule()
        runBlocking { repo.settings.setViewingsRemind(false) }
        assertTrue(reschedule().isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun rescheduleAll_setsAtMostSixty() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(*(0 until 70).map { i -> viewing("v_" + i.toString(16).padStart(8, '0'), startsAt = now + (120 + i) * min) }.toTypedArray())
        assertEquals(ViewingReminders.LIMIT, reschedule().size)
        assertEquals(60, alarms.scheduledAlarms.size)
        // The earliest 60: the last ten are left out.
        assertEquals(now + 60 * min, alarms.scheduledAlarms.minOf { it.triggerAtTime })
        assertEquals(now + (60 + 59) * min, alarms.scheduledAlarms.maxOf { it.triggerAtTime })
    }

    @Test
    fun rescheduleAll_fallsBackToTheWindowOnSecurityExceptionAndToWorkManagerWhenNoAlarmCanBeSet() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.exact = { _, _ -> throw SecurityException("revoked") }
        seed(viewing("v_00000001"))
        assertEquals(ViewingReminderScheduler.How.WINDOW, reschedule().single().second)
        scheduler.window = { _, _, _ -> throw IllegalStateException("Maximum limit of concurrent alarms 500 reached") }
        assertEquals(ViewingReminderScheduler.How.WORK, reschedule().single().second)
        val work = WorkManager.getInstance(app).getWorkInfosByTag(ViewingReminderScheduler.WORK_TAG).get()
        assertEquals(1, work.count { !it.state.isFinished })
    }

    // ---- the notification ----

    private fun texts(n: Notification) = listOfNotNull(
        n.extras.getCharSequence(Notification.EXTRA_TITLE), n.extras.getCharSequence(Notification.EXTRA_TEXT),
        n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
    ).joinToString(" | ")

    @Test
    fun theReminderIsWorkedOutWhenShownWithAPublicVersionItsActionsAndNoWithWhom() {
        val v = viewing("v_00000001", startsAt = now + 25 * min, withWhom = "Ravi Kumar")
        val n = Notifications.viewingReminder(app, v, house, now)
        assertEquals("Viewing at Green View in 25 min", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(texts(n), texts(n).contains("Ravi"))
        assertEquals(Notifications.CHANNEL_VIEWINGS, n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Doorprints reminder", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Open house", "Directions to the house", "Questions to ask"), n.actions.map { it.title.toString() })
        for (a in n.actions) assertTrue(a.title.toString(), shadowOf(a.actionIntent).isImmutable)
        assertTrue(shadowOf(n.contentIntent).isImmutable)
        // Open house and Questions open the house through the existing deep link; Directions is a geo: link.
        assertEquals(house.id, shadowOf(n.actions[0].actionIntent).savedIntent.getStringExtra(Notifications.EXTRA_OPEN_HOUSE))
        assertEquals(house.id, shadowOf(n.actions[2].actionIntent).savedIntent.getStringExtra(Notifications.EXTRA_OPEN_HOUSE))
        // Questions opens it scrolled to its questions (S4b-BL-93b); Open house does not.
        assertTrue(shadowOf(n.actions[2].actionIntent).savedIntent.getBooleanExtra(Notifications.EXTRA_OPEN_QUESTIONS, false))
        assertFalse(shadowOf(n.actions[0].actionIntent).savedIntent.getBooleanExtra(Notifications.EXTRA_OPEN_QUESTIONS, false))
        val geo = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, geo.action)
        assertEquals("geo:12.970000,77.590000?q=12.970000,77.590000", geo.dataString)
    }

    @Test
    fun directionsAreOfferedOnlyForAHouseWithARealPosition() {
        val v = viewing("v_00000001", startsAt = now + 25 * min)
        val approx = Notifications.viewingReminder(app, v, house.copy(locationSource = LocationSource.APPROX), now)
        assertEquals(listOf("Open house", "Questions to ask"), approx.actions.map { it.title.toString() })
        // A house that is gone: no house actions, the tap opens the app.
        val gone = Notifications.viewingReminder(app, v, null, now)
        assertEquals("Viewing at a house that is gone in 25 min", gone.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(gone.actions)
        assertNotNull(gone.contentIntent)
    }

    @Test
    fun aReminderADayAheadGivesTheStartInsteadOfMinutes() {
        val v = viewing("v_00000001", startsAt = now + 1440 * min, remindMin = 1440)
        val n = Notifications.viewingReminder(app, v, house, now)
        assertEquals("Viewing at Green View", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Starts "))
    }

    @Test
    fun theViewingsChannelIsDefaultImportanceAndPrivateOnTheLockScreen() {
        Notifications.createChannels(app)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(Notifications.CHANNEL_VIEWINGS)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertEquals("Viewing reminders", channel.name.toString())
    }

    @Test
    fun theReceiverShowsTheStoredViewingOnlyWhileItIsStillDue() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val fireAt = now + 60 * min
        seed(viewing("v_00000001", startsAt = now + 120 * min))
        assertTrue(runBlocking { ViewingReminderScheduler.show(app, repo, "v_00000001", fireAt) })
        val posted = nm.getNotification(Notifications.viewingTag("v_00000001"), Notifications.VIEWING_ID)
        assertEquals("Viewing at Green View in 60 min", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Cancelled since the alarm was set, deleted, or the setting turned off: nothing is shown.
        seed(viewing("v_00000002", startsAt = now + 120 * min, status = "CANCELLED"))
        assertFalse(runBlocking { ViewingReminderScheduler.show(app, repo, "v_00000002", fireAt) })
        assertFalse(runBlocking { ViewingReminderScheduler.show(app, repo, "v_00000099", fireAt) })
        runBlocking { repo.settings.setViewingsRemind(false) }
        assertFalse(runBlocking { ViewingReminderScheduler.show(app, repo, "v_00000001", fireAt) })
    }

    @Test
    fun theReceiverShowsNothingWithoutTheNotificationPermission() {
        seed(viewing("v_00000001", startsAt = now + 120 * min))
        assertFalse(runBlocking { ViewingReminderScheduler.show(app, repo, "v_00000001", now + 60 * min) })
    }

    @Test
    fun theAlarmBroadcastReachesTheReceiverAndPostsTheReminder() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        // The receiver reads the real clock, so this viewing is due now.
        val t = System.currentTimeMillis()
        seed(viewing("v_00000001", startsAt = t + 30 * min, remindMin = 30))
        val op = ViewingReminderScheduler.operation(app, "v_00000001", android.app.PendingIntent.FLAG_UPDATE_CURRENT)!!
        app.sendBroadcast(shadowOf(op).savedIntent)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        waitUntil { nm.getNotification(Notifications.viewingTag("v_00000001"), Notifications.VIEWING_ID) != null }
    }

    // ---- the system broadcasts ----

    @Test
    fun bootTimeTimezonePackageAndExactAlarmBroadcastsRescheduleAll() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val t = System.currentTimeMillis()
        seed(viewing("v_00000001", startsAt = t + 180 * min))
        val am = app.getSystemService(AlarmManager::class.java)
        for (action in ViewingReminderRescheduleReceiver.ACTIONS) {
            alarms.scheduledAlarms.forEach { am.cancel(it.operation!!) }
            assertTrue(alarms.scheduledAlarms.isEmpty())
            app.sendBroadcast(Intent(action).setPackage(app.packageName))
            waitUntil { alarms.scheduledAlarms.size == 1 }
        }
        assertEquals(
            setOf(
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            ),
            ViewingReminderRescheduleReceiver.ACTIONS,
        )
    }

    @Test
    fun theReceiversAreNotExportedAndTheManifestDeclaresTheAlarmPermissions() {
        val pm = app.packageManager
        for (cls in listOf(ViewingReminderReceiver::class.java, ViewingReminderRescheduleReceiver::class.java)) {
            assertFalse(cls.name, pm.getReceiverInfo(ComponentName(app, cls), 0).exported)
        }
        val requested = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in requested)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in requested)
        assertFalse("USE_EXACT_ALARM is for alarm-clock apps", Manifest.permission.USE_EXACT_ALARM in requested)
    }

    /** Runs the main looper until [condition] holds (the receivers finish on an IO thread), at most 5 s. */
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
