package app.doorprints.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deletions in an update file (S4b-BL-82, docs/schemas §3.13): an update carries the houses deleted since in a `deleted`
 * list and is then `doorprints-backup/3`; a copy or a full share carries none and keeps its number; an update import
 * deletes a live house here that is older than the delete, and a restore never does. The shared cases are in
 * `ImportVectorsTest`.
 */
class BackupDeletionsTest {
    private val f = ExportFixture
    private val since = f.house1.updatedAt.coerceAtLeast(f.house2.updatedAt) + 1

    @Test
    fun anUpdateCarriesTheHousesDeletedSinceAndIsVersionThree() {
        val options = f.options().copy(since = since, sharedTo = "Priya")
        val tombstones = mapOf("h9" to since + 10, "h8" to since - 5, "h7" to since + 3)
        val data = BackupData.of(ExportBundle.build(options, f.houses, f.visits, f.photos, deletedHouses = tombstones))
        assertEquals(BackupFormat.ID_3, data.format)
        assertEquals(
            listOf(ExportDeletion("house", "h7", since + 3), ExportDeletion("house", "h9", since + 10)),
            data.deleted,
            "the deletes after since, oldest first; the one before it is old news",
        )
        assertNull(BackupValidation.checkData(data))
        assertEquals(2, BackupCounts.of(data).deleted)
        val text = BackupFormat.json.encodeToString(BackupData.serializer(), data)
        assertTrue(text.endsWith(",\"deleted\":[{\"kind\":\"house\",\"id\":\"h7\",\"updatedAt\":${since + 3}},{\"kind\":\"house\",\"id\":\"h9\",\"updatedAt\":${since + 10}}]}"), text)
    }

    @Test
    fun aCopyOrAnUpdateWithNoDeleteKeepsItsNumberAndHasNoList() {
        val tombstones = mapOf("h9" to since + 10)
        val copy = BackupData.of(ExportBundle.build(f.options(), f.houses, f.visits, f.photos, deletedHouses = tombstones))
        assertEquals(BackupFormat.ID, copy.format)
        assertNull(copy.deleted)
        assertNull(BackupCounts.of(copy).deleted)
        assertTrue("deleted" !in BackupFormat.json.encodeToString(BackupData.serializer(), copy))
        val quiet = BackupData.of(
            ExportBundle.build(f.options().copy(since = since), f.houses, f.visits, f.photos, deletedHouses = mapOf("h9" to since)),
        )
        assertEquals(BackupFormat.ID, quiet.format, "a delete at the instant of the last share went with it")
        assertNull(quiet.deleted)
    }

    @Test
    fun aHouseLiveAgainIsNotADeletion() {
        val options = f.options().copy(since = since)
        val data = BackupData.of(ExportBundle.build(options, f.houses, f.visits, f.photos, deletedHouses = mapOf("h1" to since + 1)))
        assertNull(data.deleted)
    }

    @Test
    fun readersTakeVersionThreeAndRefuseFour() {
        assertEquals(3, BackupFormat.MAX_VERSION)
        assertTrue(BackupFormat.accepts(BackupFormat.ID_3))
        assertEquals(BackupProblem.UNSUPPORTED_VERSION, BackupValidation.checkData(BackupData(format = "doorprints-backup/4", exportedAt = 1)))
        assertEquals(BackupFormat.ID_3, BackupFormat.idFor(brokers = 0, deletions = 1))
        assertEquals(BackupFormat.ID_3, BackupFormat.idFor(brokers = 2, deletions = 1))
    }

    @Test
    fun onlyAnUpdateFileDeletes() {
        val manifest = BackupManifest(createdAt = "2026-05-28T10:00:00Z", counts = BackupCounts(0, 0, 0))
        assertTrue(ImportPlan.isUpdate(manifest.copy(sharedSince = "2026-05-20T00:00:00Z", sharedTo = "Priya")))
        assertTrue(!ImportPlan.isUpdate(manifest.copy(sharedTo = "Priya")), "a full share is a restore")
        assertTrue(!ImportPlan.isUpdate(manifest))
        assertTrue(!ImportPlan.isUpdate(null), "a bare data.json is a restore")
    }

    @Test
    fun anUpdateImportDeletesWhatThePreviewSays() {
        val data = BackupData(format = BackupFormat.ID_3, exportedAt = 1, deleted = listOf(ExportDeletion("house", "h1", 300)))
        val local = mapOf("h1" to 100L, "h2" to 100L)
        val preview = ImportPlan.preview(data, local, emptyMap(), emptySet(), emptySet(), ImportMode.MERGE, applyDeletions = true)
        val plan = ImportPlan.plan(data, local, emptyMap(), emptySet(), emptySet(), ImportMode.MERGE, { "x" }, applyDeletions = true)
        assertEquals(1, preview.removedHouses)
        assertEquals(listOf("h1"), plan.removedHouseIds)
        assertTrue(!preview.isEmpty)
        val restore = ImportPlan.plan(data, local, emptyMap(), emptySet(), emptySet(), ImportMode.MERGE, { "x" })
        assertTrue(restore.removedHouseIds.isEmpty(), "a restore never deletes")
    }
}
