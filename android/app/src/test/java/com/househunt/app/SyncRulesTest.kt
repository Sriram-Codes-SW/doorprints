package com.househunt.app

import com.househunt.app.data.HouseEntity
import com.househunt.app.data.SyncRules
import com.househunt.app.data.VisitEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sync conflict rule: a local edit that is not pushed yet (dirty) and strictly newer wins; otherwise the server wins. */
class SyncRulesTest {

    private fun house(updatedAt: Long, dirty: Boolean) =
        HouseEntity(id = "h1", label = "Flat", lat = 12.97, lon = 77.59, createdAt = 0, updatedAt = updatedAt, dirty = dirty)

    private fun visit(updatedAt: Long, dirty: Boolean) =
        VisitEntity(id = "v1", lat = 12.97, lon = 77.59, arrivedAt = 0, updatedAt = updatedAt, dirty = dirty)

    @Test
    fun dirtyAndNewerLocalWins() {
        assertTrue(SyncRules.keepLocal(localDirty = true, localUpdatedAt = 2_000, incomingUpdatedAt = 1_000))
        assertTrue(SyncRules.keepLocal(house(2_000, dirty = true), house(1_000, dirty = false)))
        assertTrue(SyncRules.keepLocal(visit(2_000, dirty = true), visit(1_000, dirty = false)))
    }

    @Test
    fun olderOrEqualLocalLoses() {
        assertFalse(SyncRules.keepLocal(localDirty = true, localUpdatedAt = 1_000, incomingUpdatedAt = 2_000))
        // A tie goes to the server so every device ends up with the same row.
        assertFalse(SyncRules.keepLocal(localDirty = true, localUpdatedAt = 1_000, incomingUpdatedAt = 1_000))
        assertFalse(SyncRules.keepLocal(house(1_000, dirty = true), house(1_000, dirty = false)))
    }

    @Test
    fun cleanLocalAlwaysTakesTheServerCopy() {
        // Already synced, so a newer local timestamp is only clock skew and must not block the update.
        assertFalse(SyncRules.keepLocal(localDirty = false, localUpdatedAt = 9_000, incomingUpdatedAt = 1_000))
        assertFalse(SyncRules.keepLocal(visit(9_000, dirty = false), visit(1_000, dirty = false)))
    }

    @Test
    fun missingLocalRowTakesTheServerCopy() {
        assertFalse(SyncRules.keepLocal(null as HouseEntity?, house(1_000, dirty = false)))
        assertFalse(SyncRules.keepLocal(null as VisitEntity?, visit(1_000, dirty = false)))
    }
}
