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

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.location.HuntService
import app.doorprints.location.HuntState
import app.doorprints.screenshots.ScreenshotTestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * *Finish walk* reaches the service as an intent (docs/11 5.27.6). When Hunt mode is not running (the service was
 * stopping, the person pressed *Stop*, the permission went), the intent must not leave a stray service that never went
 * foreground: it stops itself and is not restarted (review B-6).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class HuntServiceFinishWalkTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()

    @Test
    fun aFinishWalkIntentWhileHuntModeIsOffStopsTheServiceAndIsNotRestarted() {
        assertTrue("Hunt mode is off", !HuntState.state.value.active)
        val intent = Intent(app, HuntService::class.java).setAction(HuntService.ACTION_FINISH_WALK)
        val controller = Robolectric.buildService(HuntService::class.java, intent).create()
        val result = controller.get().onStartCommand(intent, 0, 7)
        assertEquals("not sticky: a killed stray instance is not brought back", android.app.Service.START_NOT_STICKY, result)
        assertTrue("the instance that never went foreground stops itself", shadowOf(controller.get()).isStoppedBySelf)
    }
}
