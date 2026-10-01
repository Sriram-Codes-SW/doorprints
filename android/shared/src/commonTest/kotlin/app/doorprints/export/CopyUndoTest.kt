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

package app.doorprints.export

import app.doorprints.data.HouseEntity
import app.doorprints.data.VisitEntity
import app.doorprints.export.CopyUndo.Decision
import app.doorprints.export.CopyUndo.HouseNow
import app.doorprints.export.CopyUndo.VisitNow
import app.doorprints.shared.sync.SyncRules
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Android review, round 17: *Undo this import* writes tombstones that sync, so a wrong keep-or-remove decision deletes
 * a house the user edited on every device. These pin every rule of [CopyUndo] that `Repository.undoCopyImport`
 * applies, and that the tombstones it writes are pushed before the house purge can unlink and revive them.
 */
class CopyUndoTest {

    private val writtenAt = 1_790_000_000_000L
    private val now = writtenAt + 60_000

    /** What the import recorded: house h1 with visits v1 and v2 and photos p1 and p2, and a loose visit v9. */
    private val recordedVisits = mapOf("v1" to writtenAt, "v2" to writtenAt + 1, "v9" to writtenAt + 2)
    private val recordedPhotos = setOf("p1", "p2")

    private fun h1(
        updatedAt: Long = writtenAt,
        deleted: Boolean = false,
        liveVisits: Map<String, Long> = mapOf("v1" to writtenAt, "v2" to writtenAt + 1),
        livePhotoIds: Collection<String> = listOf("p1", "p2"),
    ) = HouseNow(updatedAt, deleted, liveVisits, livePhotoIds)

    private fun decide(house: HouseNow?) = CopyUndo.decideHouse(house, writtenAt, recordedVisits, recordedPhotos)

    // ---- Houses ----

    @Test
    fun anUntouchedHouseIsRemoved() {
        assertEquals(Decision.REMOVE, decide(h1()))
        // No visits or photos at all is untouched too.
        assertEquals(Decision.REMOVE, decide(h1(liveVisits = emptyMap(), livePhotoIds = emptyList())))
    }

    @Test
    fun aHouseWhoseUpdatedAtChangedIsKept() {
        assertEquals(Decision.KEEP, decide(h1(updatedAt = writtenAt + 5_000)))
        // Any difference, also an earlier stamp (a pull of another device's edit made with a slower clock).
        assertEquals(Decision.KEEP, decide(h1(updatedAt = writtenAt - 1)))
    }

    @Test
    fun aHouseWithAnEditedRecordedVisitIsKept() {
        assertEquals(Decision.KEEP, decide(h1(liveVisits = mapOf("v1" to writtenAt + 9_000, "v2" to writtenAt + 1))))
    }

    @Test
    fun aHouseWithAVisitTheImportDidNotAddIsKept() {
        // A "been here" recorded after the import, or a visit moved into the copy: not in the record (a null lookup).
        assertEquals(
            Decision.KEEP,
            decide(h1(liveVisits = mapOf("v1" to writtenAt, "v2" to writtenAt + 1, "mine" to now))),
        )
        // Even when its updatedAt happens to equal the house's own.
        assertEquals(Decision.KEEP, decide(h1(liveVisits = mapOf("mine" to writtenAt))))
    }

    @Test
    fun aHouseWithAPhotoTheImportDidNotAddIsKept() {
        assertEquals(Decision.KEEP, decide(h1(livePhotoIds = listOf("p1", "p2", "taken-later"))))
    }

    @Test
    fun aHouseWhoseRecordedPhotoTheUserDeletedIsStillRemoved() {
        // Deleted photos are not live (a delete waiting for the server included); deleting is no reason to keep.
        assertEquals(Decision.REMOVE, decide(h1(livePhotoIds = listOf("p2"))))
        assertEquals(Decision.REMOVE, decide(h1(livePhotoIds = emptyList())))
    }

    @Test
    fun aHouseWhoseRecordedVisitTheUserDeletedIsStillRemoved() {
        assertEquals(Decision.REMOVE, decide(h1(liveVisits = mapOf("v2" to writtenAt + 1))))
    }

    @Test
    fun aHouseAlreadyDeletedOrGoneIsSkipped() {
        assertEquals(Decision.SKIP, decide(h1(deleted = true)))
        // Deleted and edited is still skipped, not counted as kept.
        assertEquals(Decision.SKIP, decide(h1(deleted = true, updatedAt = now)))
        assertEquals(Decision.SKIP, decide(null))
    }

    // ---- Visits ----

    private val removed = setOf("h1")

    @Test
    fun anUnchangedVisitOfARemovedHouseIsRemoved() {
        assertEquals(Decision.REMOVE, CopyUndo.decideVisit(VisitNow(writtenAt, false, "h1"), writtenAt, removed))
    }

    @Test
    fun anUnchangedLooseVisitIsRemoved() {
        assertEquals(Decision.REMOVE, CopyUndo.decideVisit(VisitNow(writtenAt, false, null), writtenAt, removed))
    }

    @Test
    fun anEditedLooseVisitIsKept() {
        assertEquals(Decision.KEEP, CopyUndo.decideVisit(VisitNow(now, false, null), writtenAt, removed))
    }

    @Test
    fun aVisitLinkedToAnotherHouseIsKept() {
        // Moved into a house of the user's (which re-stamps it), and even if the stamp matched: that house stays.
        assertEquals(Decision.KEEP, CopyUndo.decideVisit(VisitNow(now, false, "other"), writtenAt, removed))
        assertEquals(Decision.KEEP, CopyUndo.decideVisit(VisitNow(writtenAt, false, "other"), writtenAt, removed))
    }

    @Test
    fun theVisitOfAKeptHouseIsKept() {
        // h2 was kept (edited since), so it is not in the removed set; its unchanged visit stays with it.
        assertEquals(Decision.KEEP, CopyUndo.decideVisit(VisitNow(writtenAt, false, "h2"), writtenAt, removed))
    }

    @Test
    fun aVisitAlreadyDeletedOrGoneIsSkipped() {
        assertEquals(Decision.SKIP, CopyUndo.decideVisit(VisitNow(writtenAt, true, "h1"), writtenAt, removed))
        assertEquals(Decision.SKIP, CopyUndo.decideVisit(null, writtenAt, removed))
    }

    // ---- Photos ----

    @Test
    fun aPhotoGoesExactlyWithItsRemovedHouse() {
        assertTrue(CopyUndo.removesPhoto("h1", removed))
        assertFalse(CopyUndo.removesPhoto("h2", removed))
    }

    // ---- Tombstones ----

    private val house = HouseEntity(
        id = "h1", label = "Flat", lat = 12.97, lon = 77.64, createdAt = writtenAt, updatedAt = writtenAt,
        dirty = false,
    )
    private val visit = VisitEntity(
        id = "v1", houseId = "h1", lat = 12.97, lon = 77.64, street = "5th Cross", arrivedAt = writtenAt - 3_600_000,
        updatedAt = writtenAt, dirty = false,
    )

    @Test
    fun aRemovedHouseIsATombstoneThatSyncs() {
        val t = CopyUndo.houseTombstone(house, now)
        assertTrue(t.deleted)
        assertTrue(t.dirty)
        assertEquals(now, t.updatedAt)
        assertEquals(house.copy(deleted = true, dirty = true, updatedAt = now), t)
    }

    @Test
    fun removedVisitsLoseTheirHouseSoTheyArePushedBeforeTheHousePurge() {
        val t = CopyUndo.visitTombstone(visit, now)
        assertNull(t.houseId)
        assertTrue(t.deleted)
        assertTrue(t.dirty)
        assertEquals(now, t.updatedAt)
        // The id and everything else are the visit's own.
        assertEquals(visit.copy(houseId = null, deleted = true, dirty = true, updatedAt = now), t)
        // The whole point: the sync pushes it before any house, so the server's purge of h1 finds nothing to unlink.
        assertTrue(SyncRules.pushesBeforeHouses(t.deleted, t.houseId))
        val (first, after) = SyncRules.visitsByPushOrder(listOf(t), { it.deleted }, { it.houseId })
        assertEquals(listOf(t), first)
        assertTrue(after.isEmpty())
    }

    @Test
    fun aTombstoneIsStampedPastTheRowWhateverTheClockSays() {
        assertEquals(now, CopyUndo.tombstoneStamp(writtenAt, now))
        // A clock behind the row's own stamp: one past it, so the delete is still the newest edit.
        assertEquals(writtenAt + 1, CopyUndo.tombstoneStamp(writtenAt, writtenAt - 10_000))
        assertEquals(writtenAt + 1, CopyUndo.visitTombstone(visit, writtenAt - 10_000).updatedAt)
        assertEquals(writtenAt + 1, CopyUndo.houseTombstone(house, writtenAt).updatedAt)
    }

    // ---- The copy's own stamp ----

    @Test
    fun aCopyIsNeverStampedInTheFuture() {
        // A backup from a phone whose clock ran fast: the server would clamp the stamp, the pull would write it back,
        // and the undo would take the copy for one the user edited. Stamped now instead.
        assertEquals(now, CopyUndo.copyStamp(now + 3_600_000, now))
        // A backup from the past keeps its own time, as before.
        assertEquals(writtenAt, CopyUndo.copyStamp(writtenAt, now))
        assertEquals(now, CopyUndo.copyStamp(now, now))
    }

    @Test
    fun aCopyStampedNowIsRecognisedAsUntouchedByTheUndo() {
        // The record holds the stamp the row was written with, so an untouched future-dated copy is still removed.
        val stamp = CopyUndo.copyStamp(now + 3_600_000, now)
        val decision = CopyUndo.decideHouse(
            HouseNow(stamp, false, emptyMap(), emptyList()), stamp, emptyMap(), emptySet(),
        )
        assertEquals(Decision.REMOVE, decision)
    }

    // ---- the records a copy created (S4b-BL-92e, 90c) ----

    @Test
    fun aCreatedRecordGoesOnlyWhenUnchangedAndNotInUse() {
        val now = CopyUndo.RecordNow(updatedAt = 5, deleted = false)
        assertEquals(Decision.REMOVE, CopyUndo.decideRecord(now, recordedAt = 5, inUse = false))
        assertEquals(Decision.KEEP, CopyUndo.decideRecord(now, recordedAt = 5, inUse = true), "a house that stays uses it")
        assertEquals(Decision.KEEP, CopyUndo.decideRecord(now.copy(updatedAt = 6), recordedAt = 5, inUse = false), "edited since")
        assertEquals(Decision.SKIP, CopyUndo.decideRecord(now.copy(deleted = true), recordedAt = 5, inUse = false))
        assertEquals(Decision.SKIP, CopyUndo.decideRecord(null, recordedAt = 5, inUse = false))
    }

    @Test
    fun aRecordKeyReadsBackAndABadOneIsNone() {
        assertEquals("viewing" to "v_1a2b3c4d", CopyUndo.recordOf(CopyUndo.recordKey("viewing", "v_1a2b3c4d")))
        for (bad in listOf("", "viewing", "/v_1", "viewing/")) assertNull(CopyUndo.recordOf(bad), bad)
    }
}
