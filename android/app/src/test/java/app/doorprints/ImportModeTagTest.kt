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

import androidx.work.WorkInfo
import app.doorprints.export.ImportWorker
import app.doorprints.shared.export.ImportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * A stopped or failed import must say the right thing for its mode (UX review, 2026-09-22): "import the same file
 * again to finish" is safe only for a merge, because a copy gets new ids on every run and would add everything a
 * second time. A cancelled run has no output data, so the mode travels as a tag on the work request.
 */
class ImportModeTagTest {

    private fun run(state: WorkInfo.State, vararg tags: String) = WorkInfo(UUID.randomUUID(), state, tags.toSet())

    @Test
    fun theModeIsReadBackFromTheRunsTags() {
        for (mode in ImportMode.entries) {
            val info = run(WorkInfo.State.CANCELLED, "app.doorprints.export.ImportWorker", ImportWorker.modeTag(mode))
            assertEquals(mode, ImportWorker.modeOf(info))
        }
    }

    @Test
    fun aRunWithoutTheTagHasNoKnownMode() {
        assertNull(ImportWorker.modeOf(run(WorkInfo.State.FAILED, "app.doorprints.export.ImportWorker")))
        assertNull(ImportWorker.modeOf(run(WorkInfo.State.FAILED, "import-mode:SOMETHING_ELSE")))
    }

    // The wording for the mode ("nothing was added" for a copy) is :ui's importWriteFailedResource, which the
    // notification reads too since S4b-BL-106; BackupTextsTest.onlyACopySaysNothingWasAdded pins it.
}
