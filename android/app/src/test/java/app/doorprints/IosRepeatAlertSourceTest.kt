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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/11 5.27.5: the iPhone's repeated-path notification is posted under the category and the thread `repeat-path`. The
 * iOS code cannot run on this host, so the call is read from the source.
 */
class IosRepeatAlertSourceTest {
    @Test fun theRepeatPathNotificationCarriesItsCategoryAndThread() {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val text = dir.resolve("ui/src/iosMain/kotlin/app/doorprints/ui/IosHunt.kt").readText()
        val call = text.substringAfter("override fun alertRepeat(").substringBefore("RepeatAlerts.signal()")
        assertTrue("category", "category = \"repeat-path\"" in call)
        assertTrue("thread", "thread = \"repeat-path\"" in call)
    }
}
