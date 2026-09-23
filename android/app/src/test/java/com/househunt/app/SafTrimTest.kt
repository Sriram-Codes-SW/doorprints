package com.househunt.app

import com.househunt.app.export.AutoBackupWorker
import com.househunt.app.export.oldestBeyondRetention
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which automatic backups retention deletes (S4-07).
 *
 * The case that made this a test rather than a one-liner: a document provider that is asked to create a name it
 * already has produces `Doorprints-backup-2026-09-22 (1).zip`. Sorting by display name puts that *before*
 * `Doorprints-backup-2026-09-22.zip`, because `' '` (0x20) sorts before `'.'` (0x2E) — so the newest backup
 * looked like the oldest one and retention deleted it. Ordering is by the provider's last-modified time instead.
 *
 * Retention also must never count a backup that was not finished: the automatic backup writes under a
 * `partial-` name and renames only a complete file, and [AutoBackupWorker.isFinishedBackup] is what `Saf.trim`
 * filters with, so a truncated file cannot push a good backup out.
 *
 * The function under test is generic and takes no Android types, so this runs as a plain JVM test; the fake row
 * below stands in for `Saf.Child`, which carries a `Uri` that a unit test has no way to build.
 */
class SafTrimTest {

    private data class Row(val name: String, val modified: Long)

    private fun toDelete(items: List<Row>, keep: Int): List<String> =
        oldestBeyondRetention(items, keep, { it.modified }, { it.name }).map { it.name }

    private val prefix = AutoBackupWorker.BACKUP_PREFIX

    @Test
    fun aDeDuplicatedNameIsNotMistakenForTheOldestFile() {
        val rows = listOf(
            Row("${prefix}2026-09-22 (1).zip", 3_000),
            Row("${prefix}2026-09-22.zip", 2_000),
            Row("${prefix}2026-09-08.zip", 1_000),
        )
        // Keeping two must drop the 8 September file, never the de-duplicated copy made moments ago.
        assertEquals(listOf("${prefix}2026-09-08.zip"), toDelete(rows, 2))
        assertEquals(listOf("${prefix}2026-09-08.zip", "${prefix}2026-09-22.zip"), toDelete(rows, 1))
    }

    @Test
    fun nothingIsDeletedWhileTheFolderIsWithinTheLimit() {
        val rows = listOf(Row("${prefix}2026-09-22.zip", 2_000), Row("${prefix}2026-09-08.zip", 1_000))
        assertEquals(emptyList<String>(), toDelete(rows, 2))
        assertEquals(emptyList<String>(), toDelete(rows, 4))
        assertEquals(emptyList<String>(), toDelete(emptyList(), 4))
    }

    @Test
    fun theNameIsTheTieBreakWhenAProviderReportsNoTimestamp() {
        // COLUMN_LAST_MODIFIED is optional; every row then carries 0 and the old name order is what is left.
        val rows = listOf(
            Row("${prefix}2026-09-22.zip", 0),
            Row("${prefix}2026-09-15.zip", 0),
            Row("${prefix}2026-09-08.zip", 0),
        )
        assertEquals(listOf("${prefix}2026-09-08.zip"), toDelete(rows, 2))
    }

    @Test
    fun aPartialBackupNeverTakesARetentionSlot() {
        // The truncated-file scenario: three good weekly backups and two partial files from runs that were
        // stopped when the phone was unplugged. The partials are the newest files in the folder; if retention
        // counted them, keeping three would delete two good backups and keep two unopenable files.
        val finished = { name: String -> AutoBackupWorker.isFinishedBackup(name) }
        val folder = listOf(
            Row(AutoBackupWorker.partialName("${prefix}2026-09-22.zip"), 5_000),
            Row(AutoBackupWorker.partialName("${prefix}2026-09-22 (1).zip"), 4_500),
            Row("${prefix}2026-09-15.zip", 3_000),
            Row("${prefix}2026-09-08.zip", 2_000),
            Row("${prefix}2026-09-01.zip", 1_000),
            Row("notes.txt", 6_000),
        )
        // Saf.trim filters with isFinishedBackup before ranking; the same filter here.
        val counted = folder.filter { finished(it.name) }
        assertEquals(
            listOf("${prefix}2026-09-15.zip", "${prefix}2026-09-08.zip", "${prefix}2026-09-01.zip"),
            counted.map { it.name },
        )
        assertEquals(emptyList<String>(), toDelete(counted, 3))
        assertEquals(listOf("${prefix}2026-09-01.zip"), toDelete(counted, 2))
    }

    @Test
    fun onlyFinishedBackupNamesAreCounted() {
        assertEquals(true, AutoBackupWorker.isFinishedBackup("${prefix}2026-09-22.zip"))
        assertEquals(true, AutoBackupWorker.isFinishedBackup("${prefix}2026-09-22 (1).zip"))
        assertEquals(false, AutoBackupWorker.isFinishedBackup(AutoBackupWorker.partialName("${prefix}2026-09-22.zip")))
        assertEquals(false, AutoBackupWorker.isFinishedBackup("${prefix}2026-09-22.html"))
        assertEquals(false, AutoBackupWorker.isFinishedBackup("Doorprints-2026-09-22.zip"))
    }

    @Test
    fun keepingNoneDeletesEverythingAndANegativeKeepDeletesNothing() {
        val rows = listOf(Row("${prefix}2026-09-22.zip", 2_000), Row("${prefix}2026-09-08.zip", 1_000))
        assertEquals(rows.map { it.name }.sorted(), toDelete(rows, 0).sorted())
        assertEquals(emptyList<String>(), toDelete(rows, -1))
    }
}
