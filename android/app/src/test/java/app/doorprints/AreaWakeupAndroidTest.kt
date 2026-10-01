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
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.location.HuntService
import app.doorprints.location.HuntState
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaCooldown
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The area wake-up on Android (docs/11 "Design of slice 4b", 5.17, 5.18) on the app's real repository, with a fake
 * [GeofenceRegistrar] in place of Play services: the registered set (exactly the enabled areas, at most 20), its
 * removal (setting off, an area disabled or deleted, the permission lost) and the triggers that set it again (the
 * collector, resume, boot, app update, `GEOFENCE_NOT_AVAILABLE`); the receiver's work on entering (the cooldown, Hunt
 * mode running, a disabled area, *Dismiss* counting as notified, never a start without a tap); the notification
 * (channel, private with the public text, the actions, immutable intents); and the manifest. The real geofencing and
 * the permission pages cannot run here (docs/06 TC-M).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class AreaWakeupAndroidTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo = app.container.repository
    private val settings = repo.settings
    private val now = 1_790_569_800_000L
    private val min = 60_000L
    private val adyar = Area("a_1f2e3d4c", "Adyar", 13.0067, 80.2574, 500)
    private val besant = Area("a_00000b0b", "Besant Nagar", 13.0002, 80.2668, 800)

    /** Remembers what Play services would hold. */
    private class FakeRegistrar : GeofenceRegistrar {
        val registered = LinkedHashMap<String, Area>()
        var removes = 0
        var adds = 0
        override suspend fun removeAll() {
            registered.clear()
            removes++
        }
        override suspend fun add(areas: List<Area>) {
            areas.forEach { registered[it.id] = it }
            adds++
        }
    }

    private val fake = FakeRegistrar()
    private var granted = true
    private val manager = app.container.areaWakeup

    @Before fun setUp() {
        manager.registrar = fake
        manager.permissionsGranted = { granted }
    }

    @After fun tearDown() {
        runBlocking {
            settings.setAreaWakeup(false)
            settings.clearAreaWakeupOffNotice()
            settings.pruneAreaLastNotified(emptySet())
        }
        HuntState.update { HuntState.State() }
    }

    private fun seed(vararg areas: Area) = runBlocking { areas.forEach { repo.saveArea(it) } }
    private fun on() = runBlocking { settings.setAreaWakeup(true) }
    private fun reregister() = runBlocking { manager.reregisterAll() }

    // ---- the registered set ----

    @Test
    fun registersExactlyTheEnabledAreasAtMostTwenty() {
        seed(*(0 until Area.MAX_AREAS).map { i -> adyar.copy(id = "a_" + i.toString(16).padStart(8, '0'), name = "Area $i", enabled = i != 5) }.toTypedArray())
        on()
        val set = reregister()
        assertEquals(19, set.size)
        assertEquals(set.map { it.id }.toSet(), fake.registered.keys)
        assertFalse("a_00000005" in fake.registered)
        assertTrue(fake.registered.size <= 20)
        // Every call starts from none: removed first, then added.
        assertEquals(1, fake.removes)
        assertEquals(1, fake.adds)
    }

    @Test
    fun nothingIsRegisteredWhileTheSettingIsOffOrThePermissionMissing() {
        seed(adyar)
        assertTrue(reregister().isEmpty())
        on()
        granted = false
        assertTrue(reregister().isEmpty())
        assertTrue(fake.registered.isEmpty())
        granted = true
        assertEquals(listOf(adyar.id), reregister().map { it.id })
    }

    @Test
    fun turningTheSettingOffDisablingOrDeletingAnAreaRemovesItsGeofence() {
        seed(adyar, besant)
        on()
        reregister()
        assertEquals(setOf(adyar.id, besant.id), fake.registered.keys)
        runBlocking { repo.saveArea(besant.copy(enabled = false)) }
        reregister()
        assertEquals(setOf(adyar.id), fake.registered.keys)
        runBlocking { repo.saveArea(besant) }
        runBlocking { repo.deleteArea(adyar.id) }
        reregister()
        assertEquals(setOf(besant.id), fake.registered.keys)
        runBlocking { settings.setAreaWakeup(false) }
        reregister()
        assertTrue(fake.registered.isEmpty())
    }

    @Test
    fun thePermissionLostOnResumeSwitchesTheSettingOffRemovesTheGeofencesAndLeavesTheNotice() {
        seed(adyar)
        on()
        reregister()
        assertEquals(1, fake.registered.size)
        granted = false
        assertTrue(runBlocking { manager.resumed() })
        assertTrue(fake.registered.isEmpty())
        assertFalse(runBlocking { settings.areaWakeup().first() })
        assertTrue(runBlocking { settings.areaWakeupOffNotice().first() })
        // Once: the next resume has nothing more to switch off.
        assertFalse(runBlocking { manager.resumed() })
    }

    @Test
    fun aResumeWithThePermissionRegistersAgain() {
        seed(adyar)
        on()
        assertFalse(runBlocking { manager.resumed() })
        assertEquals(setOf(adyar.id), fake.registered.keys)
        assertFalse(runBlocking { settings.areaWakeupOffNotice().first() })
    }

    @Test
    fun theCollectorRegistersAtStartAndAfterEveryChangeOfTheAreasOrTheSetting() {
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            seed(adyar)
            scope.launch { manager.watch(debounceMs = 50) }
            // At start, with the setting off: removed (nothing added).
            waitUntil { fake.removes >= 1 }
            on()
            waitUntil { fake.registered.keys == setOf(adyar.id) }
            runBlocking { repo.saveArea(besant) }
            waitUntil { fake.registered.keys == setOf(adyar.id, besant.id) }
            runBlocking { repo.saveArea(besant.copy(enabled = false)) }
            waitUntil { fake.registered.keys == setOf(adyar.id) }
            runBlocking { settings.setAreaWakeup(false) }
            waitUntil { fake.registered.isEmpty() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun bootAndAnAppUpdateRegisterAgain() {
        seed(adyar)
        on()
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            fake.registered.clear()
            AreaGeofenceReceiver().onReceive(app, Intent(action))
            waitUntil { fake.registered.keys == setOf(adyar.id) }
        }
        // Anything else sent to it does nothing.
        val before = fake.removes
        AreaGeofenceReceiver().onReceive(app, Intent("app.doorprints.action.SOMETHING_ELSE"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, fake.removes)
    }

    @Test
    fun geofenceNotAvailableRegistersAgain() {
        seed(adyar)
        on()
        // What Play services sends when location is switched off: the error code of the geofencing event.
        val intent = Intent(app, AreaGeofenceReceiver::class.java).setAction(AreaGeofenceReceiver.ACTION_GEOFENCE)
            .putExtra("gms_error_code", 1000)
        AreaGeofenceReceiver().onReceive(app, intent)
        waitUntil { fake.registered.keys == setOf(adyar.id) }
    }

    @Test
    fun theGeofenceIsTheAreasCircleEnteringOnlyForEver() {
        val g = PlayGeofenceRegistrar.geofence(besant)
        assertEquals(besant.id, g.requestId)
        assertEquals(800f, g.radius)
        assertEquals(besant.lat, g.latitude, 0.0)
        assertEquals(besant.lon, g.longitude, 0.0)
        assertEquals(Geofence.GEOFENCE_TRANSITION_ENTER, g.transitionTypes)
        assertEquals(Geofence.NEVER_EXPIRE, g.expirationTime)
    }

    @Test
    fun theGeofencingPendingIntentIsMutableExplicitAndToTheReceiver() {
        val pi = shadowOf(PlayGeofenceRegistrar.pendingIntent(app))
        assertTrue(pi.isBroadcast)
        assertFalse(pi.isImmutable)
        assertTrue((pi.flags and PendingIntent.FLAG_MUTABLE) != 0)
        assertTrue((pi.flags and PendingIntent.FLAG_UPDATE_CURRENT) != 0)
        assertEquals(AreaGeofenceReceiver::class.java.name, pi.savedIntent.component?.className)
        assertEquals(AreaGeofenceReceiver.ACTION_GEOFENCE, pi.savedIntent.action)
    }

    @Test
    fun deletingAnAreaForgetsItsStampAndAnAreaGoneBySyncIsPruned() {
        seed(adyar, besant)
        runBlocking {
            settings.setAreaLastNotified(adyar.id, now)
            settings.setAreaLastNotified(besant.id, now)
            settings.setAreaLastNotified("a_0000dead", now)
            repo.deleteArea(adyar.id)
        }
        assertNull(runBlocking { settings.areaLastNotified(adyar.id) })
        reregister()
        assertNull(runBlocking { settings.areaLastNotified("a_0000dead") })
        assertEquals(now, runBlocking { settings.areaLastNotified(besant.id) })
    }

    // ---- entering an area ----

    private fun allowPosting() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
    }

    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private fun posted(id: String): Notification? = nm.getNotification(Notifications.areaTag(id), Notifications.AREA_WAKEUP_ID)
    private fun enter(vararg ids: String, at: Long = now, running: Boolean = false, fine: Boolean = true) =
        runBlocking { AreaWakeupNotifier.onEnter(app, repo, ids.toList(), at, huntRunning = running, fineLocation = fine) }

    @Test
    fun enteringAnAreaPostsTheOfferAndStampsIt() {
        allowPosting()
        seed(adyar)
        on()
        assertEquals(listOf(adyar.id), enter(adyar.id))
        val n = posted(adyar.id)!!
        assertEquals("You're in Adyar. Start Hunt mode?", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(now, runBlocking { settings.areaLastNotified(adyar.id) })
    }

    @Test
    fun theCooldownHoldsForSixHoursInclusive() {
        allowPosting()
        seed(adyar)
        on()
        assertEquals(listOf(adyar.id), enter(adyar.id))
        app.getSystemService(NotificationManager::class.java).cancelAll()
        assertEquals(emptyList<String>(), enter(adyar.id, at = now + (5 * 60 + 59) * min))
        assertNull(posted(adyar.id))
        assertEquals(listOf(adyar.id), enter(adyar.id, at = now + AreaCooldown.COOLDOWN_MS))
    }

    @Test
    fun nothingWhileHuntModeRunsTheSettingIsOffOrTheAreaIsDisabledOrGone() {
        allowPosting()
        seed(adyar, besant.copy(enabled = false))
        assertEquals(emptyList<String>(), enter(adyar.id))
        on()
        assertEquals(emptyList<String>(), enter(adyar.id, running = true))
        assertEquals(emptyList<String>(), enter(besant.id))
        assertEquals(emptyList<String>(), enter("a_0000dead"))
        // An id that is not a record id is never read.
        assertEquals(emptyList<String>(), enter("../etc", "a b"))
        assertNull(posted(adyar.id))
        assertNull(runBlocking { settings.areaLastNotified(adyar.id) })
    }

    @Test
    fun dismissRemovesItAndCountsAsNotified() {
        allowPosting()
        seed(adyar)
        on()
        enter(adyar.id)
        runBlocking { settings.removeAreaLastNotified(adyar.id) }
        val dismiss = shadowOf(AreaGeofenceReceiver.dismissIntent(app, adyar.id))
        assertTrue(dismiss.isImmutable)
        assertTrue(dismiss.isBroadcast)
        assertEquals(AreaGeofenceReceiver::class.java.name, dismiss.savedIntent.component?.className)
        app.sendBroadcast(dismiss.savedIntent)
        waitUntil { posted(adyar.id) == null && runBlocking { settings.areaLastNotified(adyar.id) } != null }
        // Counted: entering again within 6 hours says nothing.
        assertEquals(emptyList<String>(), enter(adyar.id, at = System.currentTimeMillis() + 60 * min))
        // A dismiss for an id that is not a record id does nothing.
        AreaGeofenceReceiver().onReceive(app, Intent(app, AreaGeofenceReceiver::class.java).setAction(AreaGeofenceReceiver.ACTION_DISMISS).putExtra(AreaGeofenceReceiver.EXTRA_AREA_ID, "../x"))
    }

    @Test
    fun theNotificationIsPrivateWithThePublicTextAndImmutableActions() {
        val n = Notifications.areaWakeup(app, adyar, fineLocation = true, huntRunning = false)
        assertEquals(Notifications.CHANNEL_AREA_WAKEUP, n.channelId)
        assertEquals("area_wakeup", n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Doorprints reminder", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(n.fullScreenIntent)
        assertEquals(listOf("Start Hunt mode", "Dismiss"), n.actions.map { it.title.toString() })
        for (a in n.actions) assertTrue(a.title.toString(), shadowOf(a.actionIntent).isImmutable)
        assertTrue(shadowOf(n.contentIntent).isImmutable)
        assertEquals(MainActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)
        // The body opens the app and starts nothing.
        assertNull(shadowOf(n.contentIntent).savedIntent.getStringExtra(Notifications.EXTRA_START_HUNT_AREA))
    }

    @Test
    fun startHuntModeIsTheThreeCPathAndLeftOutWhileHuntModeRuns() {
        val withLocation = shadowOf(Notifications.areaWakeup(app, adyar, fineLocation = true, huntRunning = false).actions[0].actionIntent)
        assertTrue(withLocation.isForegroundService)
        assertEquals(HuntService::class.java.name, withLocation.savedIntent.component?.className)
        assertEquals(adyar.id, withLocation.savedIntent.getStringExtra(Notifications.EXTRA_START_HUNT_AREA))
        val without = shadowOf(Notifications.areaWakeup(app, adyar, fineLocation = false, huntRunning = false).actions[0].actionIntent)
        assertTrue(without.isActivity)
        assertEquals(MainActivity::class.java.name, without.savedIntent.component?.className)
        assertEquals(adyar.id, without.savedIntent.getStringExtra(Notifications.EXTRA_START_HUNT_AREA))
        assertEquals(listOf("Dismiss"), Notifications.areaWakeup(app, adyar, fineLocation = true, huntRunning = true).actions.map { it.title.toString() })
    }

    @Test
    fun enteringNeverStartsHuntModeWithoutATap() {
        allowPosting()
        seed(adyar)
        on()
        enter(adyar.id)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(HuntState.state.value.active)
    }

    @Test
    fun theAreaWakeupChannelIsDefaultImportancePrivateAndNoDndBypass() {
        Notifications.createChannels(app)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(Notifications.CHANNEL_AREA_WAKEUP)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertFalse(channel.canBypassDnd())
        assertEquals("Area wake-up", channel.name.toString())
    }

    @Test
    fun theIdTagSchemeTheReceiverAndThePermissionInTheManifest() {
        assertEquals(11, Notifications.AREA_WAKEUP_ID)
        assertTrue(Notifications.AREA_WAKEUP_ID !in 1..10)
        assertNotEquals(Notifications.HUNT_REMINDER_ID, Notifications.AREA_WAKEUP_ID)
        assertEquals("area:a_1f2e3d4c", Notifications.areaTag(adyar.id))
        val pm = app.packageManager
        assertFalse(pm.getReceiverInfo(ComponentName(app, AreaGeofenceReceiver::class.java), 0).exported)
        val requested = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue(Manifest.permission.ACCESS_BACKGROUND_LOCATION in requested)
    }

    @Test
    fun thePermissionsNeedPreciseAndBackgroundLocation() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertFalse(hasAreaWakeupPermissions(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(hasAreaWakeupPermissions(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertTrue(Build.VERSION.SDK_INT >= 29 && hasAreaWakeupPermissions(app))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(hasAreaWakeupPermissions(app))
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
