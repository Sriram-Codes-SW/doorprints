package com.househunt.shared.export

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
