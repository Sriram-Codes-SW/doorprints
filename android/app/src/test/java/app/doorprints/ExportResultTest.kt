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

import app.doorprints.export.ResultActions
import app.doorprints.export.backupProblemOf
import app.doorprints.shared.export.BackupProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind the export and import result lines (fourth Sprint 4a review round). */
class ExportResultTest {

    @Test
    fun aCacheCopyIsReadyToShareAndAPickedDocumentIsSaved() {
        // Never "Saved" for a file in the app's private cache: the user cannot reach it there.
        assertTrue(ResultActions.isShareCopy("/data/user/0/app.doorprints/cache/exports/Doorprints-2026-09-22.html"))
        assertFalse(
            ResultActions.isShareCopy("content://com.android.providers.downloads.documents/document/msf%3A1000001234")
        )
    }

    @Test
    fun anUnrecognisedImportFailureReadsAsNotABackup() {
        assertEquals(BackupProblem.WRITE_FAILED, backupProblemOf("WRITE_FAILED"))
        assertEquals(BackupProblem.CHECKSUM_MISMATCH, backupProblemOf("CHECKSUM_MISMATCH"))
        assertEquals(BackupProblem.NOT_A_BACKUP, backupProblemOf("java.io.IOException: ENOSPC"))
        assertEquals(BackupProblem.NOT_A_BACKUP, backupProblemOf(null))
    }
}
