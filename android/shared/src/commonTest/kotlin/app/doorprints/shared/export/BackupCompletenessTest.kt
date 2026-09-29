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

package app.doorprints.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UX review, round 11: a "Full backup" made with narrowing options must say what it leaves out. */
class BackupCompletenessTest {

    @Test
    fun theDefaultOptionsMakeACompleteBackup() {
        assertTrue(BackupCompleteness.isComplete(ExportOptions()))
        assertEquals(emptyList<BackupGap>(), BackupCompleteness.gaps(ExportOptions()))
    }

    @Test
    fun everyNarrowingOptionIsNamed() {
        assertEquals(
            listOf(BackupGap.REJECTED_HOUSES, BackupGap.PHOTOS, BackupGap.CONTACTS),
            BackupCompleteness.gaps(
                ExportOptions(includeRejected = false, photos = PhotoScope.NONE, includeContacts = false),
            ),
        )
        assertEquals(
            listOf(BackupGap.PHOTOS_NOT_SHORTLISTED),
            BackupCompleteness.gaps(ExportOptions(photos = PhotoScope.SHORTLISTED)),
        )
        assertEquals(
            listOf(BackupGap.HOUSES_NOT_SELECTED),
            BackupCompleteness.gaps(ExportOptions(scope = ExportScope.SELECTED, selectedIds = setOf("h1"))),
        )
        assertFalse(BackupCompleteness.isComplete(ExportOptions(includeContacts = false)))
    }

    @Test
    fun aShortlistOnlyBackupDoesNotListWhatTheShortlistAlreadyLeavesOut() {
        // Rejected houses are never shortlisted, and every house in the file is shortlisted, so its photos are all in.
        assertEquals(
            listOf(BackupGap.HOUSES_NOT_SHORTLISTED),
            BackupCompleteness.gaps(
                ExportOptions(scope = ExportScope.SHORTLISTED, includeRejected = false, photos = PhotoScope.SHORTLISTED),
            ),
        )
    }
}
