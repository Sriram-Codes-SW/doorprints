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
import app.doorprints.data.Repository
import app.doorprints.export.ExportBuilder
import app.doorprints.shared.export.ExportOptions
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.export.ExportTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * A copy's times are in UTC on the phone as on the website (S4b-BL-92c): the export screen's and the weekly backup's
 * options carry offset 0 whatever the phone's time zone, so a viewing at 10:00 IST reads 04:30 in both apps' copies.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class ExportDefaultsTest {
    private val zone = TimeZone.getDefault()

    @After fun restore() = TimeZone.setDefault(zone)

    @Test
    fun aCopyIsInUtcEvenOnAPhoneInIndia() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val options = ExportBuilder.defaults(ApplicationProvider.getApplicationContext(), now = 1_790_569_800_000)
        assertEquals(0, options.utcOffsetMinutes)
        assertEquals("+00:00", ExportTime.offsetLabel(options.utcOffsetMinutes))
        // 2026-09-28 10:00 IST, as the web's utcDateTime writes it.
        assertEquals("2026-09-28 04:30", ExportTime.dateTime(1_790_569_800_000, options.utcOffsetMinutes))
    }

    /**
     * Kinds PR 6 (S4b-BL-210): a copy reads a candidate of a hidden kind with its typed extras only in a debug build made
     * with the flag (`BuildConfig.DEV_KINDS`); this build has it off, so a school is copied as a house, whatever the
     * options say.
     */
    @Test
    fun aSchoolIsCopiedAsAHouseInABuildWithTheFlagOff() {
        val rows = Repository.LocalRows(listOf(SchoolSample.entity()), emptyList(), emptyList())
        val bundle = ExportBuilder.build(rows, ExportOptions(exportedAtMillis = 1_790_569_800_000, devKinds = true))
        assertEquals(BuildConfig.DEV_KINDS, bundle.options.devKinds)
        assertEquals(emptyList<Any>(), bundle.extrasOf(bundle.houses.single()))
        assertEquals(emptyList<Any>(), bundle.extraColumns)
    }
}
