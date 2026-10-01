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

import android.app.AlarmManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.Viewing
import app.doorprints.ui.AppServices
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The viewing and Hunt mode reminders are set again at start-up and on every resume (docs/11 5.8 and 5.16; S4b-BL-93c,
 * until now covered by review only): the app's watcher schedules the stored viewings' alarms when it starts and again
 * after a change, and the root asks for a reschedule each time the activity resumes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class ReminderRescheduleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo = app.container.repository
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val house = HouseEntity(
        id = "11111111-1111-4111-8111-111111111112", label = "Green View", lat = 12.97, lon = 77.59,
        createdAt = 1, updatedAt = 1,
    )

    @After fun tearDown() {
        ShadowAlarmManager.reset()
    }

    private fun viewing(id: String) =
        Viewing(id = id, houseId = house.id, startsAt = System.currentTimeMillis() + 3 * 3_600_000L, remindMin = 60)

    private fun waitForAlarms(n: Int) {
        val until = System.currentTimeMillis() + 10_000
        while (alarms.scheduledAlarms.size != n && System.currentTimeMillis() < until) Thread.sleep(50)
        assertEquals(n, alarms.scheduledAlarms.size)
    }

    @Test
    fun theWatcherSchedulesTheStoredViewingsAtStartAndAgainAfterAChange() {
        // ScreenshotTestApp skips the start-up services, so the watcher is started here as startServices starts it.
        runBlocking {
            repo.saveHouse(house)
            repo.saveViewing(viewing("v_00000011"))
        }
        val watcher = app.watchViewingReminders()
        try {
            waitForAlarms(1)
            runBlocking { repo.saveViewing(viewing("v_00000012")) }
            waitForAlarms(2)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun theRootReschedulesOnEveryResume() {
        var calls = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ProvideAppServices {
                    val real = LocalAppServices.current
                    val counting = remember(real) {
                        object : AppServices by real {
                            override fun rescheduleReminders() {
                                calls++
                            }
                        }
                    }
                    CompositionLocalProvider(LocalAppServices provides counting) {
                        DoorprintsRoot(MutableStateFlow<DeepLink?>(null), onDeepLinkHandled = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("at start-up", 1, calls)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals("after coming back", 2, calls)
    }
}
