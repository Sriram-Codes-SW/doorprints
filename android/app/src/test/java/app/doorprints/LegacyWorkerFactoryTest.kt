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

import androidx.work.ListenableWorker
import app.doorprints.data.SyncWorker
import app.doorprints.export.AutoBackupWorker
import app.doorprints.export.ExportWorker
import app.doorprints.export.ImportWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jobs WorkManager stored under the pre-rename class names still find their worker ([LegacyWorkerFactory]). */
class LegacyWorkerFactoryTest {
    private val workers =
        listOf(SyncWorker::class.java, AutoBackupWorker::class.java, ExportWorker::class.java, ImportWorker::class.java)

    @Test
    fun everyWorkerStoredUnderTheOldPackageMapsToItsClass() {
        for (worker in workers) {
            val stored = "com.househunt.app." + worker.name.removePrefix("app.doorprints.")
            val name = LegacyWorkerFactory.currentName(stored)
            assertEquals(worker.name, name)
            assertTrue(ListenableWorker::class.java.isAssignableFrom(Class.forName(name!!)))
        }
    }

    @Test
    fun otherNamesAreLeftToTheDefaultFactory() {
        assertNull(LegacyWorkerFactory.currentName(SyncWorker::class.java.name))
        assertNull(LegacyWorkerFactory.currentName("androidx.work.impl.workers.ConstraintTrackingWorker"))
    }
}
