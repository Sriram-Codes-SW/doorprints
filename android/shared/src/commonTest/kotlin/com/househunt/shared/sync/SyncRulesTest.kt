package com.househunt.shared.sync

import kotlin.test.Test
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
}
