package app.doorprints.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sync conflict rule: a local edit that is not pushed yet (dirty) and strictly newer wins; otherwise the server wins. */
class SyncRulesTest {

    private data class Row(override val updatedAt: Long, override val dirty: Boolean) : SyncRecord

    @Test
    fun dirtyAndNewerLocalWins() {
        assertTrue(SyncRules.keepLocal(localDirty = true, localUpdatedAt = 2_000, incomingUpdatedAt = 1_000))
        assertTrue(SyncRules.keepLocal(Row(2_000, dirty = true), Row(1_000, dirty = false)))
    }

    @Test
    fun olderOrEqualLocalLoses() {
        assertFalse(SyncRules.keepLocal(localDirty = true, localUpdatedAt = 1_000, incomingUpdatedAt = 2_000))
        // A tie goes to the server so every device ends up with the same row.
        assertFalse(SyncRules.keepLocal(localDirty = true, localUpdatedAt = 1_000, incomingUpdatedAt = 1_000))
        assertFalse(SyncRules.keepLocal(Row(1_000, dirty = true), Row(1_000, dirty = false)))
    }

    @Test
    fun cleanLocalAlwaysTakesTheServerCopy() {
        // Already synced, so a newer local timestamp is only clock skew and must not block the update.
        assertFalse(SyncRules.keepLocal(localDirty = false, localUpdatedAt = 9_000, incomingUpdatedAt = 1_000))
        assertFalse(SyncRules.keepLocal(Row(9_000, dirty = false), Row(1_000, dirty = false)))
    }

    @Test
    fun missingLocalRowTakesTheServerCopy() {
        assertFalse(SyncRules.keepLocal(null, Row(1_000, dirty = false)))
    }

    private data class Visit(val id: String, val deleted: Boolean, val houseId: String?)

    @Test
    fun onlyVisitTombstonesWithoutAHouseGoBeforeTheHouses() {
        // Android review, round 17: the house purge on the server unlinks (and re-stamps) any visit still linked to
        // it, so an undo's visit tombstones carry no house and must reach the server first.
        assertTrue(SyncRules.pushesBeforeHouses(deleted = true, houseId = null))
        // A tombstone that still names its house, and every live visit, may need that house row: after the houses.
        assertFalse(SyncRules.pushesBeforeHouses(deleted = true, houseId = "h1"))
        assertFalse(SyncRules.pushesBeforeHouses(deleted = false, houseId = null))
        assertFalse(SyncRules.pushesBeforeHouses(deleted = false, houseId = "h1"))
    }

    @Test
    fun visitsAreSplitIntoBeforeAndAfterTheHousesKeepingTheirOrder() {
        val visits = listOf(
            Visit("live-loose", deleted = false, houseId = null),
            Visit("undo-1", deleted = true, houseId = null),
            Visit("linked-tombstone", deleted = true, houseId = "h1"),
            Visit("live-linked", deleted = false, houseId = "h2"),
            Visit("undo-2", deleted = true, houseId = null),
        )
        val (first, after) = SyncRules.visitsByPushOrder(visits, { it.deleted }, { it.houseId })
        assertEquals(listOf("undo-1", "undo-2"), first.map { it.id })
        assertEquals(listOf("live-loose", "linked-tombstone", "live-linked"), after.map { it.id })
        // Nothing is lost or pushed twice.
        assertEquals(visits.map { it.id }.toSet(), (first + after).map { it.id }.toSet())
        assertEquals(visits.size, first.size + after.size)
    }

    @Test
    fun aServerIsBehindWhenItsHighestVersionIsBelowACursor() {
        // S4b-BL-20: a new, empty database (0) or an older dump (100) behind cursors up to 250.
        assertTrue(SyncRules.serverBehind(0, listOf(250, 0, 0)))
        assertTrue(SyncRules.serverBehind(100, listOf(90, 250, 80)))
        // A healthy server is at or above every cursor; nothing stored yet says nothing.
        assertFalse(SyncRules.serverBehind(250, listOf(250, 240, 0)))
        assertFalse(SyncRules.serverBehind(0, listOf(0, 0, 0)))
        // An older server without the field: unknown.
        assertFalse(SyncRules.serverBehind(null, listOf(250, 240, 230)))
    }

    @Test
    fun aPushAnsweredBelowTheCursorsShowsAResetOnlyWhenTheWriteWasAccepted() {
        val sent = 1_760_000_000_000
        // Accepted (the stored time is the sent one) with a version at or below the highest cursor: reset.
        assertTrue(SyncRules.pushShowsReset(sent, sent, answerVersion = 3, highestCursor = 250))
        assertTrue(SyncRules.pushShowsReset(sent, sent - 5, answerVersion = 250, highestCursor = 250))
        // A healthy server's new version is above every cursor.
        assertFalse(SyncRules.pushShowsReset(sent, sent, answerVersion = 251, highestCursor = 250))
        // Last-write-wins kept the server's newer row: its old version and a later time say nothing.
        assertFalse(SyncRules.pushShowsReset(sent, sent + 1, answerVersion = 3, highestCursor = 250))
        // Nothing to compare with, or an answer without a version or a time.
        assertFalse(SyncRules.pushShowsReset(sent, sent, answerVersion = 3, highestCursor = 0))
        assertFalse(SyncRules.pushShowsReset(sent, sent, answerVersion = 0, highestCursor = 250))
        assertFalse(SyncRules.pushShowsReset(sent, null, answerVersion = 3, highestCursor = 250))
    }
}
