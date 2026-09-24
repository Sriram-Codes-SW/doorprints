package app.doorprints.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Merge by id with last-write-wins, or import as copies; never destroy anything silently (S4-04). */
class ImportPlanTest {

    private val data = BackupData.of(ExportFixture.bundle())
    private val zip = setOf("photos/p1.jpg")

    private var counter = 0
    private val newId: () -> String = { counter++; "new-$counter" }

    private fun preview(
        houses: Map<String, Long> = emptyMap(),
        visits: Map<String, Long> = emptyMap(),
        photoIds: Set<String> = emptySet(),
        mode: ImportMode = ImportMode.MERGE,
        entries: Set<String> = zip,
    ) = ImportPlan.preview(data, houses, visits, photoIds, entries, mode)

    private fun plan(
        houses: Map<String, Long> = emptyMap(),
        visits: Map<String, Long> = emptyMap(),
        photoIds: Set<String> = emptySet(),
        mode: ImportMode = ImportMode.MERGE,
        entries: Set<String> = zip,
    ) = ImportPlan.plan(data, houses, visits, photoIds, entries, mode, newId)

    @Test
    fun anEmptyPhoneTakesEverything() {
        val p = preview()
        assertEquals(2, p.newHouses)
        assertEquals(0, p.updatedHouses)
        assertEquals(1, p.newVisits)
        assertEquals(1, p.newPhotos)
        assertFalse(p.isEmpty)
        assertEquals(0, p.overwrites, "nothing existed, so nothing is overwritten")
        val actions = plan()
        assertEquals(listOf("h1", "h2"), actions.houses.map { it.id })
        assertEquals(mapOf("p1" to "photos/p1.jpg"), actions.photoSources)
    }

    @Test
    fun importingTheSameFileTwiceChangesNothing() {
        val local = data.houses.associate { it.id to it.updatedAt }
        val localVisits = data.visits.associate { it.id to it.updatedAt }
        val p = preview(local, localVisits, setOf("p1"))
        assertEquals(0, p.newHouses)
        assertEquals(0, p.updatedHouses)
        assertEquals(2, p.unchangedHouses)
        assertEquals(1, p.skippedPhotos)
        assertTrue(p.isEmpty)
        assertTrue(plan(local, localVisits, setOf("p1")).houses.isEmpty())
    }

    @Test
    fun aRowThatIsNewerInTheFileReplacesTheLocalOne() {
        val local = mapOf("h1" to ExportFixture.house1.updatedAt - 1, "h2" to ExportFixture.house2.updatedAt)
        val p = preview(local)
        assertEquals(0, p.newHouses)
        assertEquals(1, p.updatedHouses)
        assertEquals(1, p.unchangedHouses)
        assertEquals(1, p.overwrites)
        assertEquals(listOf("h1"), plan(local).houses.map { it.id })
    }

    @Test
    fun aRowThatIsNewerOnThePhoneIsKept() {
        val local = mapOf("h1" to ExportFixture.house1.updatedAt + 1, "h2" to ExportFixture.house2.updatedAt)
        val p = preview(local)
        assertEquals(1, p.newerHereHouses)
        assertEquals(0, p.updatedHouses)
        assertTrue(plan(local).houses.isEmpty(), "the newer local edit must not be overwritten")
    }

    @Test
    fun copyModeGivesEverythingNewIdsAndTouchesNothingExisting() {
        val local = data.houses.associate { it.id to it.updatedAt }
        val actions = plan(local, mode = ImportMode.COPY)
        assertEquals(2, actions.houses.size)
        assertTrue(actions.houses.none { it.id == "h1" || it.id == "h2" })
        // The visit still points at its house, under the house's new id.
        assertEquals(actions.houses.first().id, actions.visits.single().houseId)
        assertEquals(actions.houses.first().id, actions.photos.single().houseId)
        // ...and the bytes still come from the entry the backup named.
        assertEquals("photos/p1.jpg", actions.photoSources.getValue(actions.photos.single().id))
        val p = preview(local, mode = ImportMode.COPY)
        assertEquals(0, p.overwrites, "importing as a copy can never overwrite")
        assertEquals(2, p.newHouses)
    }

    @Test
    fun aPhotoRowWithoutItsFileIsReportedAndSkipped() {
        val p = preview(entries = emptySet())
        assertEquals(0, p.newPhotos)
        assertEquals(1, p.photosMissingFromFile)
        assertTrue(plan(entries = emptySet()).photos.isEmpty())
        val copy = plan(entries = emptySet(), mode = ImportMode.COPY)
        assertTrue(copy.photos.isEmpty())
    }

    @Test
    fun aPhotoThePhoneAlreadyHasIsNotDownloadedAgain() {
        val p = preview(photoIds = setOf("p1"))
        assertEquals(0, p.newPhotos)
        assertEquals(1, p.skippedPhotos)
    }

    @Test
    fun aVisitWhoseHouseIsInNeitherPlaceIsDropped() {
        val orphaned = data.copy(visits = listOf(ExportFixture.visit1.copy(id = "v9", houseId = "gone")))
        val p = ImportPlan.preview(orphaned, emptyMap(), emptyMap(), emptySet(), zip, ImportMode.MERGE)
        assertEquals(0, p.newVisits)
        val actions = ImportPlan.plan(orphaned, emptyMap(), emptyMap(), emptySet(), zip, ImportMode.MERGE, newId)
        assertTrue(actions.visits.isEmpty())
    }

    @Test
    fun aPhotoWhoseHouseIsInNeitherPlaceIsCountedAsSkippedNotAsNew() {
        // The preview must promise exactly what the plan delivers: an orphaned photo is dropped by `plan`, so
        // counting it under newPhotos told the user a number the import would not honour.
        val orphaned = data.copy(
            photos = data.photos + ExportPhoto(id = "p9", houseId = "gone", fileName = "p9.jpg", createdAt = 1),
        )
        val entries = zip + "photos/p9.jpg"
        val p = ImportPlan.preview(orphaned, emptyMap(), emptyMap(), emptySet(), entries, ImportMode.MERGE)
        val actions = ImportPlan.plan(orphaned, emptyMap(), emptyMap(), emptySet(), entries, ImportMode.MERGE, newId)
        assertEquals(1, p.newPhotos)
        assertEquals(1, p.skippedPhotos)
        assertEquals(p.newPhotos, actions.photos.size, "the preview must match what the plan writes")
        assertEquals(listOf("p1"), actions.photos.map { it.id })
    }

    @Test
    fun copyModeCountsOnlyTheOrphansItWillActuallyWrite() {
        // The parity rule is not a MERGE rule. `plan` in COPY mode drops a visit and a photo whose houseId is
        // not one of the *file's* own houses, so the preview must drop the same ones — this is the same defect as
        // [aPhotoWhoseHouseIsInNeitherPlaceIsCountedAsSkippedNotAsNew], one mode further along.
        val orphaned = data.copy(
            visits = data.visits + ExportFixture.visit1.copy(id = "v9", houseId = "gone"),
            photos = data.photos + ExportPhoto(id = "p9", houseId = "gone", fileName = "p9.jpg", createdAt = 1),
        )
        val entries = zip + "photos/p9.jpg"
        val p = ImportPlan.preview(orphaned, emptyMap(), emptyMap(), emptySet(), entries, ImportMode.COPY)
        val actions = ImportPlan.plan(orphaned, emptyMap(), emptyMap(), emptySet(), entries, ImportMode.COPY, newId)
        assertEquals(1, p.newPhotos)
        assertEquals(1, p.skippedPhotos)
        assertEquals(1, p.newVisits)
        assertEquals(p.newPhotos, actions.photos.size, "the preview must match what the plan writes")
        assertEquals(p.newVisits, actions.visits.size, "the preview must match what the plan writes")
    }

    @Test
    fun aPhotoOfAHouseDeletedOnThisPhoneIsCountedAsSkippedNotAsNew() {
        // The tombstone is newer than the file's row, so last edit wins keeps the house deleted and
        // `Repository.applyImport` will not write a photo into it. The preview has to say so before the user
        // agrees to the import, not leave them counting a photo line that never arrives.
        val local = mapOf("h1" to ExportFixture.house1.updatedAt + 1, "h2" to ExportFixture.house2.updatedAt)
        val deleted = setOf("h1")
        val p = ImportPlan.preview(data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, deleted)
        assertEquals(0, p.newPhotos)
        assertEquals(1, p.skippedPhotos)
        assertEquals(0, p.newVisits, "a visit of a house deleted here is not restored either")
        val actions = ImportPlan.plan(data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, newId, deleted)
        assertEquals(p.newPhotos, actions.photos.size, "the preview must match what the plan writes")
        assertEquals(p.newVisits, actions.visits.size, "the preview must match what the plan writes")
    }

    @Test
    fun aBackupRowNewerThanTheTombstoneBringsTheHouseAndItsPhotoBack() {
        // The other half of the rule: a tombstone does not veto anything, it only competes on timestamps.
        val local = mapOf("h1" to ExportFixture.house1.updatedAt - 1)
        val deleted = setOf("h1")
        val p = ImportPlan.preview(data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, deleted)
        assertEquals(1, p.updatedHouses, "the file's row is newer, so the house comes back")
        assertEquals(1, p.newPhotos)
        assertEquals(0, p.skippedPhotos)
        val actions = ImportPlan.plan(data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, newId, deleted)
        assertEquals(p.newPhotos, actions.photos.size, "the preview must match what the plan writes")
    }

    @Test
    fun aVisitWhoseHouseIsOnlyOnThePhoneIsStillImported() {
        val onlyVisit = BackupData(exportedAt = 0, visits = listOf(ExportFixture.visit1))
        val p = ImportPlan.preview(onlyVisit, mapOf("h1" to 1L), emptyMap(), emptySet(), zip, ImportMode.MERGE)
        assertEquals(1, p.newVisits)
    }

    /**
     * Android review, 2026-09-22: "already on this phone, will appear twice" counts live houses only. A house this
     * phone has deleted is in `localHouses` (its tombstone keeps an `updatedAt`), but a copy of it is its only
     * visible row, so it is not a duplicate; restoring deleted houses is exactly what a copy is for.
     */
    @Test
    fun copyDuplicatesCountsLiveHousesOnlyNotTombstonesOrNewOnes() {
        val fresh = ExportFixture.house1.copy(id = "h3")
        val three = BackupData(exportedAt = 0, houses = listOf(ExportFixture.house1, ExportFixture.house2, fresh))
        val local = mapOf("h1" to ExportFixture.house1.updatedAt, "h2" to ExportFixture.house2.updatedAt + 1)
        val deleted = setOf("h2")

        assertEquals(1, ImportPlan.copyDuplicates(three, local, deleted), "h1 is live here; h2 is deleted; h3 is new")
        assertEquals(2, ImportPlan.copyDuplicates(three, local), "with no tombstones both known houses are live")
        assertEquals(0, ImportPlan.copyDuplicates(three, emptyMap()), "an empty phone has nothing to duplicate")
        assertEquals(0, ImportPlan.copyDuplicates(three, local, setOf("h1", "h2")), "everything here is deleted")
        // The merge preview's "known here" sum is the number the screen used to show; it counts the tombstone.
        val merge = ImportPlan.preview(three, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, deleted)
        // (h2 is now counted as "deleted here", not as "newer here"; see the next test.)
        assertEquals(2, merge.updatedHouses + merge.newerHereHouses + merge.unchangedHouses + merge.deletedHereHouses)
        assertEquals(1, merge.deletedHereHouses)
        // And the copy itself writes every house in the file, the deleted one included, exactly once.
        val copy = ImportPlan.plan(three, local, emptyMap(), emptySet(), zip, ImportMode.COPY, newId, deleted)
        assertEquals(3, copy.houses.size)
    }

    /**
     * UX review, 2026-09-22: restoring houses deleted by mistake. Deleting bumps `updatedAt`, so the tombstone always
     * beats the backup's row and a merge leaves the house deleted. The preview must say *that* ("deleted on this
     * phone, stay deleted"), not count it as "kept, because this phone has a newer version", and the house's visit
     * and photo go with it; a copy, the way back, still writes all of it.
     */
    @Test
    fun aHouseDeletedOnThisPhoneIsCountedAsDeletedHereNotAsNewerHere() {
        val local = mapOf("h1" to ExportFixture.house1.updatedAt + 1, "h2" to ExportFixture.house2.updatedAt + 1)
        val merge = ImportPlan.preview(data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, setOf("h1"))
        assertEquals(1, merge.deletedHereHouses, "h1 is a tombstone here")
        assertEquals(1, merge.newerHereHouses, "h2 is live and newer here")
        assertEquals(1, merge.deletedHereVisits, "visit1 belongs to h1")
        assertEquals(1, merge.deletedHerePhotos, "p1 belongs to h1")
        assertEquals(1, merge.skippedPhotos, "and it is still one of the skipped photos")
        assertTrue(merge.isEmpty, "a merge writes nothing, which is why the screen points to copies")
        val actions = ImportPlan.plan(data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, newId, setOf("h1"))
        assertTrue(actions.houses.isEmpty() && actions.visits.isEmpty() && actions.photos.isEmpty())

        // Everything deleted: the whole backup is "deleted here", none of it "newer here".
        val allGone = ImportPlan.preview(data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, setOf("h1", "h2"))
        assertEquals(2, allGone.deletedHereHouses)
        assertEquals(0, allGone.newerHereHouses)

        // A copy brings them back and reports nothing as deleted here.
        val copy = ImportPlan.preview(data, local, emptyMap(), emptySet(), zip, ImportMode.COPY, setOf("h1", "h2"))
        assertEquals(0, copy.deletedHereHouses)
        assertEquals(2, copy.newHouses)
        assertEquals(1, copy.newPhotos)
        assertEquals(0, ImportPlan.copyDuplicates(data, local, setOf("h1", "h2")), "nothing is on screen twice")
    }

    /** A merge's plan says which written rows replace one on the phone, so the result can say "added" and "updated". */
    @Test
    fun aMergePlanSaysWhichRowsAreUpdates() {
        val houses = mapOf("h1" to ExportFixture.house1.updatedAt - 1)
        val visits = mapOf(ExportFixture.visit1.id to ExportFixture.visit1.updatedAt - 1)
        val actions = ImportPlan.plan(data, houses, visits, emptySet(), zip, ImportMode.MERGE, newId)
        assertEquals(setOf("h1"), actions.updatedHouseIds, "h1 is newer in the file; h2 is new")
        assertEquals(setOf(ExportFixture.visit1.id), actions.updatedVisitIds)
        val p = ImportPlan.preview(data, houses, visits, emptySet(), zip, ImportMode.MERGE)
        assertEquals(p.updatedHouses, actions.updatedHouseIds.size, "the result must use the preview's numbers")
        assertEquals(p.newHouses, actions.houses.size - actions.updatedHouseIds.size)
        assertEquals(p.updatedVisits, actions.updatedVisitIds.size)
        val copy = ImportPlan.plan(data, houses, visits, emptySet(), zip, ImportMode.COPY, newId)
        assertTrue(copy.updatedHouseIds.isEmpty() && copy.updatedVisitIds.isEmpty(), "a copy updates nothing")
    }

    /**
     * docs/schemas/README.md section 4.4: a newer row with no checklist (absent, `null` or `{}` all read as `{}`)
     * replaces the whole house, so scores this phone had are cleared. The preview says so, as the server's does.
     * The fixture's h2 has no checklist and h1 has scores.
     */
    @Test
    fun theWarningCountsOnlyScoredHousesANewerEmptyChecklistWillClear() {
        val older = data.houses.associate { it.id to it.updatedAt - 1 }
        val scoredHere = setOf("h1", "h2")
        fun cleared(houses: Map<String, Long>, scored: Set<String>, mode: ImportMode = ImportMode.MERGE) =
            ImportPlan.preview(data, houses, emptyMap(), emptySet(), zip, mode, emptySet(), scored).checklistsCleared

        assertEquals(1, cleared(older, scoredHere), "h2 is newer in the file and has no scores; h1 keeps its own")
        assertEquals(0, cleared(older, setOf("h1")), "h2 had no scores here, so nothing is lost")
        assertEquals(0, cleared(data.houses.associate { it.id to it.updatedAt + 1 }, scoredHere), "kept: newer here")
        assertEquals(0, cleared(data.houses.associate { it.id to it.updatedAt }, scoredHere), "same row: no write")
        assertEquals(0, cleared(emptyMap(), scoredHere), "a new house clears nothing")
        assertEquals(0, cleared(older, scoredHere, ImportMode.COPY), "a copy never overwrites")
        assertEquals(0, preview(older).checklistsCleared, "callers that pass no scores get no warning")
    }
    /**
     * UX review, round 11: restoring houses deleted by mistake. A 40-house backup with 3 houses deleted here used to
     * offer only a copy of all 40. `restoreDeleted` brings back exactly the deleted ones, with their own ids, and
     * their visit and photo with them; nothing else is written and nothing is overwritten.
     */
    @Test
    fun restoreDeletedBringsBackOnlyTheDeletedHouseWithItsVisitAndPhoto() {
        val local = mapOf("h1" to ExportFixture.house1.updatedAt + 1, "h2" to ExportFixture.house2.updatedAt)
        val deleted = setOf("h1")
        fun preview(restore: Boolean) = ImportPlan.preview(
            data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, deleted, restoreDeleted = restore,
        )

        val plain = preview(restore = false)
        assertTrue(plain.isEmpty, "without the opt-in the tombstone wins, as before")
        assertEquals(1, plain.deletedHereHouses)
        assertEquals(0, plain.restoredHouses)

        val p = preview(restore = true)
        assertFalse(p.isEmpty)
        assertEquals(1, p.restoredHouses)
        assertEquals(0, p.deletedHereHouses, "a restored house is no longer 'deleted here'")
        assertEquals(0, p.deletedHereVisits)
        assertEquals(0, p.deletedHerePhotos)
        assertEquals(0, p.newHouses)
        assertEquals(1, p.unchangedHouses, "h2 is live and the same: untouched")
        assertEquals(1, p.newVisits, "visit1 belongs to h1 and comes back with it")
        assertEquals(1, p.newPhotos, "p1 belongs to h1 and comes back with it")
        assertEquals(0, p.overwrites, "an undelete replaces nothing the user can see")

        val actions = ImportPlan.plan(
            data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, newId, deleted, restoreDeleted = true,
        )
        assertEquals(listOf("h1"), actions.houses.map { it.id }, "the original id, not a copy")
        assertEquals(setOf("h1"), actions.restoredHouseIds)
        assertTrue(actions.updatedHouseIds.isEmpty())
        assertEquals(p.newVisits, actions.visits.size, "the preview must match what the plan writes")
        assertEquals(p.newPhotos, actions.photos.size, "the preview must match what the plan writes")

        // COPY ignores the flag: it never looks at tombstones.
        val copy = ImportPlan.preview(
            data, local, emptyMap(), emptySet(), zip, ImportMode.COPY, deleted, restoreDeleted = true,
        )
        assertEquals(0, copy.restoredHouses)
        assertEquals(2, copy.newHouses)
    }

    /**
     * UX review, round 11: the Replace dialog's "Keep mine, add only what's new". Rows the file has a newer version
     * of are left alone and counted as kept; only new rows are written, so nothing needs confirming.
     */
    @Test
    fun skipUpdatesWritesOnlyNewRowsAndCountsTheRestAsKept() {
        val houses = mapOf("h1" to ExportFixture.house1.updatedAt - 1)
        val visits = mapOf(ExportFixture.visit1.id to ExportFixture.visit1.updatedAt - 1)

        val normal = ImportPlan.preview(data, houses, visits, emptySet(), zip, ImportMode.MERGE)
        assertEquals(2, normal.overwrites, "h1 and visit1 would be replaced")

        val p = ImportPlan.preview(data, houses, visits, emptySet(), zip, ImportMode.MERGE, skipUpdates = true)
        assertEquals(0, p.overwrites, "nothing is replaced, so the Replace dialog has nothing to ask")
        assertEquals(0, p.updatedHouses)
        assertEquals(1, p.keptMineHouses)
        assertEquals(1, p.keptMineVisits)
        assertEquals(1, p.newHouses, "h2 is new")
        assertEquals(1, p.newPhotos, "p1 is new, and h1 stays on the phone for it")

        val actions = ImportPlan.plan(data, houses, visits, emptySet(), zip, ImportMode.MERGE, newId, skipUpdates = true)
        assertEquals(listOf("h2"), actions.houses.map { it.id })
        assertTrue(actions.visits.isEmpty())
        assertTrue(actions.updatedHouseIds.isEmpty() && actions.updatedVisitIds.isEmpty())
        assertEquals(p.newPhotos, actions.photos.size, "the preview must match what the plan writes")
    }

    /** A tombstone the file is newer than is an update too: "keep mine" keeps it deleted unless restore is on. */
    @Test
    fun skipUpdatesKeepsATombstoneTheFileIsNewerThanUnlessRestoreIsOn() {
        val local = mapOf("h1" to ExportFixture.house1.updatedAt - 1)
        val deleted = setOf("h1")
        val kept = ImportPlan.preview(
            data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, deleted, skipUpdates = true,
        )
        assertEquals(1, kept.deletedHereHouses)
        assertEquals(0, kept.updatedHouses)
        assertEquals(1, kept.deletedHerePhotos)

        val both = ImportPlan.preview(
            data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, deleted,
            restoreDeleted = true, skipUpdates = true,
        )
        assertEquals(1, both.restoredHouses)
        assertEquals(0, both.overwrites)
        val actions = ImportPlan.plan(
            data, local, emptyMap(), emptySet(), zip, ImportMode.MERGE, newId, deleted,
            restoreDeleted = true, skipUpdates = true,
        )
        assertEquals(setOf("h1"), actions.restoredHouseIds)
    }

    /**
     * Android review, round 12, checked against the backend: once the phone has **synced** a house delete, the
     * server's purge has unlinked the house's visits (`houseId = null`, `updatedAt` = the purge, so newer than the
     * backup's row) and tombstoned its photos, and the pull has applied both here (the visit is a loose street visit,
     * the photo row and file are gone). "Bring them back" must still bring the visit back *into* the house, and
     * the photo must go under a fresh id: the server never takes a tombstoned photo id back, so an upload under the
     * old id is accepted and dropped, and the web would never see the photo.
     */
    @Test
    fun restoreAfterASyncedDeleteRelinksTheVisitAndGivesThePhotoANewId() {
        val houses = mapOf("h1" to ExportFixture.house1.updatedAt + 1, "h2" to ExportFixture.house2.updatedAt)
        val deleted = setOf("h1")
        val unlinkedAt = ExportFixture.visit1.updatedAt + 1
        val visits = mapOf(ExportFixture.visit1.id to unlinkedAt)
        val unlinked = setOf(ExportFixture.visit1.id)

        // The defect: without knowing which visits are unlinked, the newer local copy wins and stays loose.
        val blind = ImportPlan.preview(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, deleted, restoreDeleted = true,
        )
        assertEquals(1, blind.newerHereVisits)
        assertEquals(0, blind.newVisits)

        val p = ImportPlan.preview(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, deleted, restoreDeleted = true,
            localUnlinkedVisitIds = unlinked,
        )
        assertEquals(1, p.restoredHouses)
        assertEquals(1, p.newVisits, "the visit comes back with its house")
        assertEquals(1, p.relinkedVisits)
        assertEquals(0, p.newerHereVisits, "not 'kept, newer here': the server's unlink is what made it newer")
        assertEquals(0, p.updatedVisits)
        assertEquals(0, p.overwrites, "an undelete still replaces nothing the user can see")
        assertEquals(1, p.newPhotos, "the photo was removed by the sync, so it is new to this phone")

        val a = ImportPlan.plan(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, newId, deleted, restoreDeleted = true,
            localUnlinkedVisitIds = unlinked,
        )
        assertEquals(setOf("h1"), a.restoredHouseIds)
        assertEquals(listOf(ExportFixture.visit1.id), a.visits.map { it.id }, "written, whatever the timestamps say")
        assertEquals(setOf(ExportFixture.visit1.id), a.relinkedVisitIds)
        assertTrue(a.updatedVisitIds.isEmpty())
        assertEquals("h1", a.visits.single().houseId)
        val photo = a.photos.single()
        assertTrue(photo.id != "p1", "the server keeps p1 as a tombstone; only a new id uploads")
        assertEquals("new-1", photo.id)
        assertEquals("h1", photo.houseId)
        assertEquals(mapOf("new-1" to "photos/p1.jpg"), a.photoSources, "the bytes still come from the backup's entry")
        assertEquals(p.newVisits, a.visits.size, "the preview must match what the plan writes")
        assertEquals(p.relinkedVisits, a.relinkedVisitIds.size)
        assertEquals(p.newPhotos, a.photos.size, "the preview must match what the plan writes")

        // Without the opt-in nothing changes: the house stays deleted and so do its visit and photo.
        val plain = ImportPlan.plan(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, newId, deleted, localUnlinkedVisitIds = unlinked,
        )
        assertTrue(plain.houses.isEmpty() && plain.visits.isEmpty() && plain.photos.isEmpty())
    }

    /** The same for a house resurrected by last edit wins (the file's row is newer than the synced tombstone). */
    @Test
    fun anUpdateOverASyncedTombstoneRelinksTooWithoutTheOptIn() {
        val houses = mapOf("h1" to ExportFixture.house1.updatedAt - 1)
        val visits = mapOf(ExportFixture.visit1.id to ExportFixture.visit1.updatedAt + 1)
        val unlinked = setOf(ExportFixture.visit1.id)
        val p = ImportPlan.preview(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, setOf("h1"), localUnlinkedVisitIds = unlinked,
        )
        assertEquals(1, p.updatedHouses)
        assertEquals(1, p.relinkedVisits)
        val a = ImportPlan.plan(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, newId, setOf("h1"),
            localUnlinkedVisitIds = unlinked,
        )
        assertEquals(setOf("h1"), a.updatedHouseIds)
        assertEquals(setOf(ExportFixture.visit1.id), a.relinkedVisitIds)
        assertTrue(a.photos.single().id != "p1")

        // A live house is not written over a tombstone: its photo keeps its id, and a loose visit stays loose.
        val live = ImportPlan.plan(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, newId, localUnlinkedVisitIds = unlinked,
        )
        assertEquals(listOf("p1"), live.photos.map { it.id })
        assertTrue(live.relinkedVisitIds.isEmpty())
        assertTrue(live.visits.isEmpty(), "the phone's copy is newer, and nothing was purged")
    }

    /**
     * A delete that was never synced keeps everything on the phone: the visit is still linked (so it is not in the
     * unlinked set) and the photo is still here. The restore then writes the house only, and no photo is added a
     * second time under a new id.
     */
    @Test
    fun restoreBeforeTheDeleteIsSyncedWritesTheHouseOnly() {
        val houses = mapOf("h1" to ExportFixture.house1.updatedAt + 1, "h2" to ExportFixture.house2.updatedAt)
        val visits = mapOf(ExportFixture.visit1.id to ExportFixture.visit1.updatedAt)
        val p = ImportPlan.preview(
            data, houses, visits, setOf("p1"), zip, ImportMode.MERGE, setOf("h1"), restoreDeleted = true,
        )
        val a = ImportPlan.plan(
            data, houses, visits, setOf("p1"), zip, ImportMode.MERGE, newId, setOf("h1"), restoreDeleted = true,
        )
        assertEquals(1, p.restoredHouses)
        assertEquals(1, p.unchangedVisits)
        assertEquals(0, p.newPhotos)
        assertEquals(listOf("h1"), a.houses.map { it.id })
        assertTrue(a.visits.isEmpty() && a.photos.isEmpty())
        assertEquals(0, counter, "no fresh id was needed")
    }

    /**
     * A delete still waiting to be pushed (the tombstone is dirty) has not been purged on the server: the house's
     * photos are live there under their old ids, including one this phone never downloaded (p1 here). Restoring it
     * must keep "p1", or the web would show that photo twice (Android review, round 13). Once the tombstone is
     * synced, the same restore gives it a fresh id; without the set (`null`) every tombstone counts as synced.
     */
    @Test
    fun aTombstoneThatWasNeverPushedKeepsItsPhotoIds() {
        val houses = mapOf("h1" to ExportFixture.house1.updatedAt + 1, "h2" to ExportFixture.house2.updatedAt)
        val visits = mapOf(ExportFixture.visit1.id to ExportFixture.visit1.updatedAt)
        fun planWith(synced: Set<String>?) = ImportPlan.plan(
            data, houses, visits, emptySet(), zip, ImportMode.MERGE, newId, setOf("h1"), restoreDeleted = true,
            syncedDeletedHouseIds = synced,
        )
        val dirty = planWith(emptySet())
        assertEquals(setOf("h1"), dirty.restoredHouseIds)
        assertEquals(listOf("p1"), dirty.photos.map { it.id }, "not purged on the server, so the old id still lands")
        assertEquals(mapOf("p1" to "photos/p1.jpg"), dirty.photoSources)
        assertEquals(0, counter, "no fresh id was needed")

        assertEquals(listOf("new-1"), planWith(setOf("h1")).photos.map { it.id })
        assertEquals(listOf("new-2"), planWith(null).photos.map { it.id })
    }

    private data class PhoneState(
        val houses: Map<String, Long>,
        val visits: Map<String, Long>,
        val deleted: Set<String>,
        val unlinked: Set<String> = emptySet(),
        val photoIds: Set<String> = emptySet(),
        /** The tombstones that reached the server; `null` = cannot tell, every tombstone counts as synced. */
        val synced: Set<String>? = null,
    )

    /**
     * The file's one rule, for every combination of the two MERGE flags and a spread of phone states: every number
     * the preview shows is exactly what the plan writes. States six to eight are the round-12 ones: a delete that
     * was synced (visit unlinked and newer, photo gone), the same with a tombstone older than the file, and a delete
     * that was not synced (visit linked, photo still here). The last two are round 13's: a tombstone still waiting to
     * be pushed whose photo this phone never had, and the same tombstone marked as synced.
     */
    @Test
    fun thePreviewMatchesThePlanForEveryFlagCombination() {
        val h1 = ExportFixture.house1.updatedAt
        val h2 = ExportFixture.house2.updatedAt
        val v1 = ExportFixture.visit1.updatedAt
        val visit = ExportFixture.visit1.id
        val states = listOf(
            PhoneState(emptyMap(), emptyMap(), emptySet()),
            PhoneState(mapOf("h1" to h1 + 1, "h2" to h2 + 1), emptyMap(), setOf("h1", "h2")),
            PhoneState(mapOf("h1" to h1 - 1, "h2" to h2), mapOf(visit to v1 - 1), setOf("h1")),
            PhoneState(mapOf("h1" to h1 - 1, "h2" to h2 + 1), mapOf(visit to v1 + 1), emptySet()),
            PhoneState(mapOf("h1" to h1, "h2" to h2 - 1), mapOf(visit to v1), setOf("h2")),
            PhoneState(mapOf("h1" to h1 + 1, "h2" to h2), mapOf(visit to v1 + 1), setOf("h1"), unlinked = setOf(visit)),
            PhoneState(mapOf("h1" to h1 - 1, "h2" to h2), mapOf(visit to v1 + 1), setOf("h1"), unlinked = setOf(visit)),
            PhoneState(mapOf("h1" to h1 + 1, "h2" to h2), mapOf(visit to v1), setOf("h1"), photoIds = setOf("p1")),
            PhoneState(mapOf("h1" to h1 + 1, "h2" to h2), mapOf(visit to v1), setOf("h1"), synced = emptySet()),
            PhoneState(mapOf("h1" to h1 - 1, "h2" to h2), mapOf(visit to v1), setOf("h1"), synced = setOf("h1")),
        )
        for (s in states) {
            val houses = s.houses
            val visits = s.visits
            val deleted = s.deleted
            for (restore in listOf(false, true)) {
                for (skip in listOf(false, true)) {
                    val what = "$s restore=$restore skip=$skip"
                    val p = ImportPlan.preview(
                        data, houses, visits, s.photoIds, zip, ImportMode.MERGE, deleted,
                        restoreDeleted = restore, skipUpdates = skip, localUnlinkedVisitIds = s.unlinked,
                    )
                    val a = ImportPlan.plan(
                        data, houses, visits, s.photoIds, zip, ImportMode.MERGE, newId, deleted,
                        restoreDeleted = restore, skipUpdates = skip, localUnlinkedVisitIds = s.unlinked,
                        syncedDeletedHouseIds = s.synced,
                    )
                    assertEquals(p.newHouses + p.updatedHouses + p.restoredHouses, a.houses.size, what)
                    assertEquals(p.updatedHouses, a.updatedHouseIds.size, what)
                    assertEquals(p.restoredHouses, a.restoredHouseIds.size, what)
                    assertEquals(p.newVisits + p.updatedVisits, a.visits.size, what)
                    assertEquals(p.updatedVisits, a.updatedVisitIds.size, what)
                    assertEquals(p.relinkedVisits, a.relinkedVisitIds.size, what)
                    assertTrue(a.visits.map { it.id }.containsAll(a.relinkedVisitIds), what)
                    assertEquals(p.newPhotos, a.photos.size, what)
                    assertTrue(a.photos.all { it.id in a.photoSources }, what)
                    // A photo of a house written over a synced tombstone never keeps the backup's (server-tombstoned)
                    // id; one written over a tombstone that was never pushed always does.
                    val overTombstone = a.restoredHouseIds + a.updatedHouseIds.filter { it in deleted }
                    val purged = overTombstone.filter { s.synced == null || it in s.synced }
                    assertTrue(a.photos.none { it.houseId in purged && it.id == "p1" }, what)
                    val notPurged = a.photos.filter { it.houseId in overTombstone && it.houseId !in purged }
                    assertTrue(notPurged.all { it.id == "p1" }, what)
                    assertEquals(p.isEmpty, a.houses.isEmpty() && a.visits.isEmpty() && a.photos.isEmpty(), what)
                    if (skip) assertEquals(0, p.overwrites, what)
                    if (!restore) assertEquals(0, p.restoredHouses, what)
                    // Every house of the file is counted exactly once.
                    val counted = p.newHouses + p.updatedHouses + p.restoredHouses + p.deletedHereHouses +
                        p.keptMineHouses + p.newerHereHouses + p.unchangedHouses
                    assertEquals(data.houses.size, counted, what)
                }
            }
        }
    }
}
