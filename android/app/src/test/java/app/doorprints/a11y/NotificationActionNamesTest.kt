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

package app.doorprints.a11y

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.DoorprintsApp
import app.doorprints.R
import app.doorprints.screenshots.ScreenshotTestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The notification actions' names (S4b-BL-94b, Wave D). An action button has no content description of its own (a
 * notification's actions are text only), and TalkBack reads it apart from the notification, often after the next
 * one: so each names what it does ("Dismiss reminder", "Stop Hunt mode"), never a bare "Stop", "Open" or "Dismiss",
 * and no two actions read the same.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class NotificationActionNamesTest {
    private val actions = listOf(
        R.string.notif_stop, R.string.notif_task_stop, R.string.notif_copy_open, R.string.notif_copy_share,
        R.string.notif_viewing_open, R.string.notif_viewing_directions, R.string.notif_viewing_questions,
        R.string.notif_hunt_reminder_start, R.string.notif_hunt_reminder_dismiss,
    )

    @Test
    fun everyActionNamesWhatItActsOn() {
        val context = ApplicationProvider.getApplicationContext<DoorprintsApp>()
        val names = actions.map { context.getString(it) }
        names.forEach { assertTrue("\"$it\" is a bare verb: name what it acts on", it.trim().split(Regex("\\s+")).size >= 2) }
        assertEquals("Two actions read the same: $names", names.size, names.toSet().size)
    }
}
