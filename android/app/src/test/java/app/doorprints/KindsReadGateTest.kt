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
 * Where a school is read as a school (owner, 2026-10-15 and 2026-10-20; TC-U-264, ADR-13b): a candidate of a kind other than the house
 * is read as that kind by the on-device AI, a readable copy and the list's search while the developer flag is on or a second kind is
 * offered (`SettingsStore.kindsOn`); the school is released, so it is, as on the website. A build that lists the house alone reads
 * every candidate as a house.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class KindsReadGateTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val kinds = TwoKinds(app)

    @After fun close() = kinds.close()

    @Test
    fun aSecondOfferedKindOrTheDeveloperFlagTurnsTheReadingOn() = runBlocking {
        assertTrue("the school is released, so a second kind is offered", kinds.store.kindsOn())
        assertTrue("the developer flag", kinds.store.kindsOn(devKinds = true))
        kinds.enableSchool()
        assertTrue("shown too", kinds.store.kindsOn())
        // A build that lists the house alone has nothing to read as another kind.
        TwoKinds(app, listOf("house")).use { houseOnly ->
            runBlocking { houseOnly.store.editKindSetting { it.withExperimental(true) } }
            assertFalse(runBlocking { houseOnly.store.kindsOn() })
            assertTrue(runBlocking { houseOnly.store.kindsOn(devKinds = true) })
        }
    }

    @Test
    fun aSchoolIsCopiedAsASchoolSinceItsReleaseAndAsAHouseInAHouseOnlyBuild() = runBlocking {
        app.container.repository.saveHouse(SchoolSample.entity())
        val options = ExportOptions(exportedAtMillis = 1_790_569_800_000)
        TwoKinds(app, listOf("house")).use { houseOnly ->
            val off = ExportBuilder.bundle(houseOnly.repository, options)
            assertEquals("no school column in a copy of a house-only build", emptyList<Any>(), off.extraColumns)
        }
        val released = ExportBuilder.bundle(kinds.repository, options)
        assertTrue("the school is released, so its own fields are copied", released.extraColumns.isNotEmpty())
    }
}
