package app.doorprints

import app.doorprints.export.CopyRecord
import app.doorprints.export.ImportUndo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * UX review, round 16: a copy import records the ids it added so that it can be undone for a day. These pin the
 * record file: it round-trips, it is offered for [ImportUndo.KEEP_MS] and no longer, a record that names another
 * run is not trusted, and a run id that is not a UUID can never name a file outside the records folder.
 */
class ImportUndoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_790_000_000_000L

    private fun record(runId: String = UUID.randomUUID().toString(), finishedAt: Long = now) = CopyRecord(
        runId = runId,
        finishedAt = finishedAt,
        houses = mapOf("h1" to 100L, "h2" to 200L),
        visits = mapOf("v1" to 300L),
        photos = listOf("p1", "p2"),
    )

    @Test
    fun aSavedRecordLoadsBackWhole() {
        val dir = File(tmp.root, "imports")
        val r = record()
        assertTrue(ImportUndo.save(dir, r))
        assertEquals(r, ImportUndo.load(dir, r.runId, now + 1_000))
        // Written aside and renamed: no temp file is left behind.
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aRecordIsOfferedForADayOnly() {
        val dir = tmp.newFolder("imports")
        val r = record()
        ImportUndo.save(dir, r)
        assertEquals(r, ImportUndo.load(dir, r.runId, now + ImportUndo.KEEP_MS))
        assertNull(ImportUndo.load(dir, r.runId, now + ImportUndo.KEEP_MS + 1))
    }

    @Test
    fun aRecordThatNamesAnotherRunIsNotTrusted() {
        val dir = tmp.newFolder("imports")
        val runId = UUID.randomUUID().toString()
        ImportUndo.save(dir, record(runId))
        val other = UUID.randomUUID().toString()
        File(dir, "$runId.json").renameTo(File(dir, "$other.json"))
        assertNull(ImportUndo.load(dir, other, now))
    }

    @Test
    fun onlyAUuidNamesAFile() {
        val dir = tmp.newFolder("imports")
        assertNull(ImportUndo.fileOf(dir, "../../databases/doorprints"))
        assertNull(ImportUndo.fileOf(dir, ""))
        assertFalse(ImportUndo.save(dir, record(runId = "../evil")))
        val id = UUID.randomUUID().toString()
        assertEquals(File(dir, "$id.json"), ImportUndo.fileOf(dir, id))
    }

    @Test
    fun aDamagedFileIsNoRecord() {
        val dir = tmp.newFolder("imports")
        val id = UUID.randomUUID().toString()
        File(dir, "$id.json").writeText("{not json")
        assertNull(ImportUndo.load(dir, id, now))
    }

    @Test
    fun anUndoThatKeptHousesLeavesOnlyThemAndNoUndo() {
        // UX review, round 18: the houses an undo kept stay findable behind the "Just imported" chip.
        val dir = tmp.newFolder("imports")
        val kept = record().keptOnly(setOf("h2"))
        assertEquals(mapOf("h2" to 200L), kept.houses)
        assertTrue(kept.visits.isEmpty())
        assertTrue(kept.photos.isEmpty())
        assertTrue(kept.undone)
        assertTrue(ImportUndo.save(dir, kept))
        assertEquals(kept, ImportUndo.load(dir, kept.runId, now))
    }

    @Test
    fun aRecordWrittenBeforeTheUndoneFlagCanStillBeUndone() {
        val dir = tmp.newFolder("imports")
        val id = UUID.randomUUID().toString()
        File(dir, "$id.json").writeText("""{"runId":"$id","finishedAt":$now,"houses":{"h1":100}}""")
        val loaded = ImportUndo.load(dir, id, now)
        assertEquals(mapOf("h1" to 100L), loaded?.houses)
        assertFalse(loaded!!.undone)
        assertFalse(loaded.rowHidden)
    }

    @Test
    fun theHouseListOffersTheNewestRecordThatCanStillBeUndone() {
        val dir = tmp.newFolder("imports")
        val older = record(finishedAt = now - 1_000)
        val newer = record(finishedAt = now)
        // Newest of all, but what an undo left behind: it offers no undo.
        val leftByAnUndo = record(finishedAt = now + 500).keptOnly(setOf("h1"))
        listOf(older, newer, leftByAnUndo).forEach { assertTrue(ImportUndo.save(dir, it)) }
        assertEquals(newer, ImportUndo.latestUndoable(dir, now + 1_000))
        assertNull(ImportUndo.latestUndoable(dir, now + 1_000 + ImportUndo.KEEP_MS))
        assertNull(ImportUndo.latestUndoable(File(tmp.root, "absent"), now))
    }

    /**
     * UX review, round 19: closing the house list's undo row keeps the record (the chip and the Import screen's undo
     * stay) and is remembered with it; a record an undo left behind, or one already gone, is not written again.
     */
    @Test
    fun hidingTheListRowKeepsTheRecordAndItsUndo() {
        val dir = tmp.newFolder("imports")
        val r = record()
        ImportUndo.save(dir, r)
        assertTrue(ImportUndo.hideRow(dir, r.runId, now))
        val hidden = ImportUndo.load(dir, r.runId, now)
        assertEquals(r.copy(rowHidden = true), hidden)
        assertEquals(hidden, ImportUndo.latestUndoable(dir, now))
        // Already hidden: nothing to write.
        assertFalse(ImportUndo.hideRow(dir, r.runId, now))
        // What an undo left behind offers no undo, so there is no row to hide.
        val left = record().keptOnly(setOf("h1"))
        ImportUndo.save(dir, left)
        assertFalse(ImportUndo.hideRow(dir, left.runId, now))
        assertFalse(ImportUndo.load(dir, left.runId, now)!!.rowHidden)
        // A record that is gone is not brought back by hiding its row.
        val gone = UUID.randomUUID().toString()
        assertFalse(ImportUndo.hideRow(dir, gone, now))
        assertNull(ImportUndo.load(dir, gone, now))
    }

    @Test
    fun sweepRemovesOldRecordsAndTempFiles() {
        val dir = tmp.newFolder("imports")
        val fresh = record()
        val old = record()
        ImportUndo.save(dir, fresh)
        ImportUndo.save(dir, old)
        val clock = System.currentTimeMillis()
        File(dir, "${old.runId}.json").setLastModified(clock - ImportUndo.KEEP_MS - 60_000)
        File(dir, "x.json.tmp").writeText("half")
        ImportUndo.sweep(dir, clock)
        val left = dir.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(setOf("${fresh.runId}.json"), left)
    }
}
