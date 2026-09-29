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

package app.doorprints.ui

import app.doorprints.export.ExportProblem
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ImportMode
import app.doorprints.ui.res.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Export and Import screens' texts for a finished run (CMP-6 P6b, S4b-BL-35), as Compose resources: a stopped or
 * failed import says the right thing for its mode (UX review, 2026-09-22: "import the same file again to finish" is
 * safe only for a merge, because a copy gets new ids on every run and would add everything a second time), every
 * failure code has its own reason, and only a `content://` document is "saved" (a cache file is "ready to share").
 */
class BackupTextsTest {
    @Test
    fun onlyACopySaysNothingWasAdded() {
        assertEquals(Res.string.import_stopped_copy, importStoppedResource(ImportMode.COPY))
        assertEquals(Res.string.import_write_failed_copy, importWriteFailedResource(ImportMode.COPY))
        // Merge, and a run of unknown mode, keep "import the same file again to finish".
        assertEquals(Res.string.import_stopped, importStoppedResource(ImportMode.MERGE))
        assertEquals(Res.string.import_write_failed, importWriteFailedResource(ImportMode.MERGE))
        assertEquals(Res.string.import_stopped, importStoppedResource(null))
        assertEquals(Res.string.import_write_failed, importWriteFailedResource(null))
    }

    @Test
    fun everyFailureCodeHasItsOwnReason() {
        assertEquals(ExportProblem.entries.size, ExportProblem.entries.map { it.messageResource }.toSet().size)
        assertEquals(BackupProblem.entries.size, BackupProblem.entries.map { it.messageResource }.toSet().size)
        assertEquals(Res.string.export_problem_no_space, ExportProblem.fromCode("no-space").messageResource)
        // An English message saved by an older build reads as the generic reason.
        assertEquals(Res.string.export_problem_unknown, ExportProblem.fromCode("Cannot write").messageResource)
    }

    @Test
    fun aCacheCopyIsReadyToShareAndAPickedDocumentIsSaved() {
        assertTrue(isShareCopy("/data/user/0/app.doorprints/cache/exports/Doorprints-2026-09-22.html"))
        assertFalse(isShareCopy("content://com.android.providers.downloads.documents/document/msf%3A1000001234"))
    }
}
