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

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.export.ExportBuilder
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.export.ExportOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the school compiled in and off is read as a school (owner, 2026-10-15; TC-U-264): a candidate of a kind other than the house
 * is read as that kind by the on-device AI, a readable copy and the list's search only while the developer flag or the person's
 * switch allows it (`SettingsStore.kindsOn`). With the switch off it is read as a house, so the shipped app is the house build.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class KindsReadGateTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val kinds = TwoKinds(app)

    @After fun close() = kinds.close()

    @Test
    fun theSwitchIsTheOnlyWayInAReleaseAndTheDeveloperFlagIsTheOther() = runBlocking {
        assertFalse("off by default", kinds.store.kindsOn())
        assertTrue("the developer flag", kinds.store.kindsOn(devKinds = true))
        kinds.enableSchool()
        assertTrue("the switch on, a second kind offered", kinds.store.kindsOn())
        kinds.store.editKindSetting { it.withExperimental(false) }
        assertFalse("off again", kinds.store.kindsOn())
        // A build that lists the house alone has nothing to switch.
        TwoKinds(app, listOf("house")).use { houseOnly ->
            runBlocking { houseOnly.store.editKindSetting { it.withExperimental(true) } }
            assertFalse(runBlocking { houseOnly.store.kindsOn() })
        }
    }

    @Test
    fun aSchoolIsCopiedAsAHouseUntilTheSwitchIsOn() = runBlocking {
        app.container.repository.saveHouse(SchoolSample.entity())
        val options = ExportOptions(exportedAtMillis = 1_790_569_800_000)
        val off = ExportBuilder.bundle(kinds.repository, options)
        assertEquals("no school column in a copy while the switch is off", emptyList<Any>(), off.extraColumns)
        kinds.enableSchool()
        val on = ExportBuilder.bundle(kinds.repository, options)
        assertTrue("with the switch on the school's own fields are copied", on.extraColumns.isNotEmpty())
    }
}
