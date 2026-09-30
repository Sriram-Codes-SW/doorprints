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
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.location.HuntService
import app.doorprints.location.HuntState
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.HuntReminders.Kind
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingReminders
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * The Hunt mode reminder on Android (docs/11 5.16, slice 3c) on the app's real repository and Robolectric's
 * AlarmManager: the Hunt kind's alarm at fireAt (exact or the early window), one alarm for a merged pair, none with the
 * setting off, the cap of 60 over both kinds, both kinds cancelled for a deleted viewing; the notification (channel,
 * public version, *Start Hunt mode* only as the permission and Hunt mode allow, *Dismiss*, never `withWhom`); and the
 * re-check when it is shown.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class HuntRemindersAndroidTest {
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
        HuntState.update { HuntState.State() }
    }

    private fun viewing(id: String, startsAt: Long = now + 120 * min, remindMin: Int = 0, hunt: Boolean = true, status: String = "PLANNED", withWhom: String? = null) =
        Viewing(id = id, houseId = house.id, startsAt = startsAt, remindMin = remindMin, huntReminder = hunt, status = status, withWhom = withWhom)

    private fun seed(vararg list: Viewing) = runBlocking { list.forEach { repo.saveViewing(it) } }
    private fun reschedule() = runBlocking { scheduler.rescheduleAll() }

    // ---- scheduling ----

    @Test
    fun theHuntKindIsScheduledExactlyAtFireAtWithItsOwnImmutableIntent() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001"))
        assertEquals(listOf(ViewingReminderScheduler.Scheduled("v_00000001", Kind.HUNT, ViewingReminderScheduler.How.EXACT)), reschedule())
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(now + 105 * min, alarm.triggerAtTime)
        assertTrue(alarm.isAllowWhileIdle)
        val op = shadowOf(alarm.operation)
        assertTrue(op.isImmutable)
        assertTrue(op.isBroadcast)
        assertEquals(ViewingReminderReceiver::class.java.name, op.savedIntent.component?.className)
        assertEquals(ViewingReminderScheduler.ACTION_HUNT_REMIND, op.savedIntent.action)
        assertEquals("doorprints-hunt:v_00000001", op.savedIntent.dataString)
    }

    @Test
    fun theHuntKindUsesTheEarlyWindowWithoutExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.exact = { _, _ -> throw AssertionError("an exact call without the permission") }
        seed(viewing("v_00000001"))
        assertEquals(ViewingReminderScheduler.How.WINDOW, reschedule().single().how)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(now + 95 * min, alarm.triggerAtTime)
        assertEquals(10 * min, alarm.windowLengthMs)
    }

    @Test
    fun theLeadTimeSettingMovesTheAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001"))
        runBlocking { repo.settings.setHuntReminderMin(45) }
        reschedule()
        assertEquals(now + 75 * min, alarms.scheduledAlarms.single().triggerAtTime)
        runBlocking { repo.settings.setHuntReminderMin(15) }
    }

    @Test
    fun twoKindsFarApartAreTwoAlarmsWithDistinctIntents() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001", remindMin = 60))
        assertEquals(listOf(Kind.VIEWING, Kind.HUNT), reschedule().map { it.kind })
        val data = alarms.scheduledAlarms.map { shadowOf(it.operation).savedIntent.dataString }.toSet()
        assertEquals(setOf("doorprints-viewing:v_00000001", "doorprints-hunt:v_00000001"), data)
    }

    @Test
    fun aMergedPairIsOneAlarmAtTheEarlierTime() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // The viewing reminder 15 min before and the Hunt reminder 15 min before: one notification.
        seed(viewing("v_00000001", remindMin = 15))
        assertEquals(listOf(Kind.BOTH), reschedule().map { it.kind })
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(now + 105 * min, alarm.triggerAtTime)
        assertEquals("doorprints-hunt:v_00000001", shadowOf(alarm.operation).savedIntent.dataString)
    }

    @Test
    fun theHuntSettingOffSchedulesNoHuntKind() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001", remindMin = 60), viewing("v_00000002"))
        runBlocking { repo.settings.setHuntRemind(false) }
        assertEquals(listOf("v_00000001" to Kind.VIEWING), reschedule().map { it.id to it.kind })
        runBlocking { repo.settings.setViewingsRemind(false) }
        assertTrue(reschedule().isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun theCapOfSixtyHoldsAcrossBothKinds() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(*(0 until 40).map { i -> viewing("v_" + i.toString(16).padStart(8, '0'), startsAt = now + (120 + i * 120) * min, remindMin = 60) }.toTypedArray())
        val got = reschedule()
        assertEquals(ViewingReminders.LIMIT, got.size)
        assertEquals(60, alarms.scheduledAlarms.size)
        assertEquals(30, got.count { it.kind == Kind.HUNT })
    }

    @Test
    fun aDeletedViewingHasBothKindsCancelled() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seed(viewing("v_00000001", remindMin = 60))
        reschedule()
        assertEquals(2, alarms.scheduledAlarms.size)
        runBlocking { repo.deleteViewing("v_00000001") }
        assertTrue(reschedule().isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(ViewingReminderScheduler.operation(app, "v_00000001", PendingIntent.FLAG_NO_CREATE))
        assertNull(ViewingReminderScheduler.huntOperation(app, "v_00000001", PendingIntent.FLAG_NO_CREATE))
    }

    // ---- the notification ----

    private fun texts(n: Notification) = listOfNotNull(
        n.extras.getCharSequence(Notification.EXTRA_TITLE), n.extras.getCharSequence(Notification.EXTRA_TEXT),
        n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
    ).joinToString(" | ")

    private fun hunt(v: Viewing, merged: Boolean = false, fine: Boolean = true, running: Boolean = false) =
        Notifications.huntReminder(app, v, house, now, merged, fine, running)

    @Test
    fun theHuntReminderIsWorkedOutWhenShownPrivateAndNeverWithWhom() {
        val v = viewing("v_00000001", startsAt = now + 15 * min, withWhom = "Ravi Kumar")
        val n = hunt(v)
        assertEquals("Viewing at Green View in 15 min. Start Hunt mode?", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(texts(n), texts(n).contains("Ravi"))
        assertEquals(Notifications.CHANNEL_HUNT_REMINDERS, n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Doorprints reminder", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(n.fullScreenIntent)
        assertEquals(listOf("Start Hunt mode", "Dismiss"), n.actions.map { it.title.toString() })
        for (a in n.actions) assertTrue(a.title.toString(), shadowOf(a.actionIntent).isImmutable)
        // The body opens the viewing.
        val body = shadowOf(n.contentIntent)
        assertTrue(body.isImmutable)
        assertEquals("v_00000001", body.savedIntent.getStringExtra(Notifications.EXTRA_OPEN_VIEWING))
        assertEquals(MainActivity::class.java.name, body.savedIntent.component?.className)
    }

    @Test
    fun startHuntModeStartsTheServiceWithLocationOtherwiseOpensTheLocationQuestion() {
        val v = viewing("v_00000001", startsAt = now + 15 * min)
        val withLocation = shadowOf(hunt(v, fine = true).actions[0].actionIntent)
        assertTrue(withLocation.isForegroundService)
        assertEquals(HuntService::class.java.name, withLocation.savedIntent.component?.className)
        assertEquals("v_00000001", withLocation.savedIntent.getStringExtra(Notifications.EXTRA_START_HUNT))
        val without = shadowOf(hunt(v, fine = false).actions[0].actionIntent)
        assertTrue(without.isActivity)
        assertEquals(MainActivity::class.java.name, without.savedIntent.component?.className)
        assertEquals("v_00000001", without.savedIntent.getStringExtra(Notifications.EXTRA_START_HUNT))
    }

    @Test
    fun noStartActionWhileHuntModeRunsAndAMergedOneAlsoOpensTheHouse() {
        val v = viewing("v_00000001", startsAt = now + 15 * min)
        assertEquals(listOf("Dismiss"), hunt(v, running = true).actions.map { it.title.toString() })
        val merged = hunt(v, merged = true)
        assertEquals(listOf("Start Hunt mode", "Open house", "Dismiss"), merged.actions.map { it.title.toString() })
        assertEquals(house.id, shadowOf(merged.actions[1].actionIntent).savedIntent.getStringExtra(Notifications.EXTRA_OPEN_HOUSE))
    }

    @Test
    fun theHuntRemindersChannelIsDefaultImportancePrivateAndNoDndBypass() {
        Notifications.createChannels(app)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(Notifications.CHANNEL_HUNT_REMINDERS)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertFalse(channel.canBypassDnd())
        assertEquals("Hunt mode reminders", channel.name.toString())
    }

    @Test
    fun theReceiverReChecksTheStoredViewingWhenShown() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val fireAt = now + 105 * min
        seed(viewing("v_00000001"))
        assertTrue(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", fireAt, fineLocation = true, huntRunning = false) })
        val posted = nm.getNotification(Notifications.huntTag("v_00000001"), Notifications.HUNT_REMINDER_ID)
        assertEquals("Viewing at Green View in 15 min. Start Hunt mode?", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Its switch turned off, cancelled, started, deleted, or the setting off since the alarm was set: nothing.
        seed(viewing("v_00000002", hunt = false), viewing("v_00000003", status = "CANCELLED"))
        assertFalse(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000002", fireAt) })
        assertFalse(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000003", fireAt) })
        assertFalse(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", now + 120 * min) })
        assertFalse(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000099", fireAt) })
        runBlocking { repo.settings.setHuntRemind(false) }
        assertFalse(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", fireAt) })
    }

    @Test
    fun theReceiverReadsThePermissionAndHuntModeWhenShown() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        seed(viewing("v_00000001"))
        fun posted() = nm.getNotification(Notifications.huntTag("v_00000001"), Notifications.HUNT_REMINDER_ID)
        // No location permission: *Start Hunt mode* opens the app at the location question.
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", now + 105 * min) })
        assertTrue(shadowOf(posted().actions[0].actionIntent).isActivity)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", now + 105 * min) })
        assertTrue(shadowOf(posted().actions[0].actionIntent).isForegroundService)
        // Hunt mode already on: no *Start Hunt mode*.
        HuntState.update { it.copy(active = true) }
        assertTrue(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", now + 105 * min) })
        assertEquals(listOf("Dismiss"), posted().actions.map { it.title.toString() })
    }

    @Test
    fun aMergedReminderIsPostedOnceAsTheHuntOne() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        seed(viewing("v_00000001", remindMin = 15))
        val at = now + 105 * min
        assertTrue(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", at, fineLocation = true, huntRunning = false) })
        assertTrue("Open house" in nm.getNotification(Notifications.huntTag("v_00000001"), Notifications.HUNT_REMINDER_ID).actions.map { it.title.toString() })
        // A viewing-kind alarm left behind shows nothing: the Hunt one carries it.
        assertFalse(runBlocking { ViewingReminderScheduler.show(app, repo, "v_00000001", at) })
    }

    @Test
    fun dismissRemovesTheReminder() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        seed(viewing("v_00000001"))
        assertTrue(runBlocking { ViewingReminderScheduler.showHunt(app, repo, "v_00000001", now + 105 * min, fineLocation = true, huntRunning = false) })
        val dismiss = shadowOf(ViewingReminderScheduler.dismissIntent(app, "v_00000001"))
        assertTrue(dismiss.isImmutable)
        assertTrue(dismiss.isBroadcast)
        app.sendBroadcast(dismiss.savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(nm.getNotification(Notifications.huntTag("v_00000001"), Notifications.HUNT_REMINDER_ID))
    }

    @Test
    fun theHuntAlarmBroadcastReachesTheReceiverAndPostsIt() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        val t = System.currentTimeMillis()
        seed(viewing("v_00000001", startsAt = t + 15 * min))
        val op = ViewingReminderScheduler.huntOperation(app, "v_00000001", PendingIntent.FLAG_UPDATE_CURRENT)!!
        app.sendBroadcast(shadowOf(op).savedIntent)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        waitUntil { nm.getNotification(Notifications.huntTag("v_00000001"), Notifications.HUNT_REMINDER_ID) != null }
    }

    @Test
    fun theNotificationIdsDoNotCollideAndTheExportsAreUnchanged() {
        assertNotEquals(Notifications.VIEWING_ID, Notifications.HUNT_REMINDER_ID)
        assertTrue(Notifications.HUNT_REMINDER_ID !in 1..9)
        val pm = app.packageManager
        assertFalse(pm.getReceiverInfo(ComponentName(app, ViewingReminderReceiver::class.java), 0).exported)
        assertFalse(pm.getServiceInfo(ComponentName(app, HuntService::class.java), 0).exported)
        assertNotNull(Intent(app, MainActivity::class.java).resolveActivityInfo(pm, 0))
    }

    /** Runs the main looper until [condition] holds (the receiver finishes on an IO thread), at most 5 s. */
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
