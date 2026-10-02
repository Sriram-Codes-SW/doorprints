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

package app.doorprints.shared.sync

/**
 * What one sync file changes here (S4b-BL-130, [DriveMerge.plan]): the rows to write ([take]), the rows held back
 * because they are stamped too far ahead of this device's clock ([held]), and the house deletions held for the
 * person's confirmation by the shrink guard ([deferred]). A [stale] file (a lower `seq` than one already seen from
 * that device: a file rolled back by a Drive revision) changes nothing.
 */
class MergePlan(
    val stale: Boolean,
    val take: List<SyncRow>,
    val held: List<SyncRow>,
    val deferred: List<SyncRow>,
    /** Live houses here that the file's tombstones would delete (whether or not [deferred]). */
    val housesDeleted: Int,
    /** Live houses here before the file. */
    val liveHouses: Int,
) {
    /** The shrink guard stopped the house deletions: ask "This would delete N of your M houses" (L1). */
    val needsConfirmation: Boolean get() = deferred.isNotEmpty()
}

/**
 * The Drive merge rule (S4b-BL-130, docs/15 §5.1, docs/03 ADR-33): **last write wins on `updatedAt`, ties broken by the
 * writing device's id, a tombstone above a live row on a full tie of both** ([SyncStamp]'s order), whatever the row's
 * `dirty` flag. Not [SyncRules.keepLocal]: with one snapshot file per device, an older snapshot from a device that has
 * not caught up must not overwrite a clean, newer row here. Because the order is total on what it compares, merging is
 * a per-key maximum: the order of the files, merging one twice, and merging through a third device all give the same
 * rows (the property tests; `docs/schemas/sync-vectors.json`). The website's twin is `drive-merge.ts`.
 */
object DriveMerge {
    /**
     * A row stamped more than this after the reader's clock is **held**, not applied and not dropped, until the clock
     * is within it. A clamp would rewrite the stamp differently on each device and they would never agree; a hold
     * converges, and a device's wrong clock (or a malicious one) cannot win for ever with a far-future stamp.
     */
    const val MAX_AHEAD_MS = 86_400_000L

    /** The shrink guard trips when one file would delete at least this many live houses ... */
    const val SHRINK_MIN_HOUSES = 10
    // ... and more than half of the live houses here (docs/15 §1.4 item 4's "less than half", for sync).

    /**
     * The `updatedAt` of an edit or a delete made here: `max(now, previous + 1)`, so a device whose clock is behind
     * (or a row stamped ahead within [MAX_AHEAD_MS]) still moves the row forward and the edit wins.
     */
    fun nextStamp(nowMs: Long, previousUpdatedAt: Long?): Long =
        if (previousUpdatedAt == null || previousUpdatedAt == Long.MAX_VALUE) nowMs else maxOf(nowMs, previousUpdatedAt + 1)

    /** True when [incoming] replaces [local]: there is none, or it is strictly greater ([SyncStamp]'s order). */
    fun takesIncoming(local: SyncStamp?, incoming: SyncStamp): Boolean = local == null || incoming > local

    /** True when [stamp] is more than [MAX_AHEAD_MS] after [nowMs] (this device's clock). */
    fun isHeld(stamp: SyncStamp, nowMs: Long): Boolean = stamp.updatedAt > nowMs + MAX_AHEAD_MS

    /**
     * The rule as the sync loop's [MergeRule] (`CommonRepository.sync`), over stored rows: a row's writer is
     * [SyncRecord.writer], `""` when the store does not know it yet (S4b-BL-118 stores it).
     */
    val rule: MergeRule = MergeRule { local, incoming ->
        local != null && !takesIncoming(local.stamp(), incoming.stamp())
    }

    /**
     * What [file] changes here. [local] answers this device's version of a row (null when it has none), [liveHouses]
     * the number of live houses here, [highestSeq] the highest `seq` already merged from that device (null: none).
     * [confirmShrink] is the person's yes to the shrink guard's question, and lets the deletions through.
     */
    fun plan(
        file: SyncFile,
        nowMs: Long,
        highestSeq: Long?,
        liveHouses: Int,
        confirmShrink: Boolean = false,
        local: (SyncKind, String) -> SyncStamp?,
    ): MergePlan {
        if (highestSeq != null && file.seq < highestSeq) {
            return MergePlan(stale = true, take = emptyList(), held = emptyList(), deferred = emptyList(), housesDeleted = 0, liveHouses = liveHouses)
        }
        val take = ArrayList<SyncRow>()
        val held = ArrayList<SyncRow>()
        val houseDeletes = ArrayList<SyncRow>()
        for (kind in SyncKind.entries) {
            for (row in file.rows(kind)) {
                if (isHeld(row.stamp, nowMs)) {
                    held += row
                    continue
                }
                val here = local(kind, row.key)
                if (!takesIncoming(here, row.stamp)) continue
                if (kind == SyncKind.HOUSES && row.stamp.deleted && here != null && !here.deleted) houseDeletes += row else take += row
            }
        }
        val trips = !confirmShrink && houseDeletes.size >= SHRINK_MIN_HOUSES && houseDeletes.size * 2 > liveHouses
        if (!trips) take += houseDeletes
        return MergePlan(
            stale = false,
            take = take,
            held = held,
            deferred = if (trips) houseDeletes else emptyList(),
            housesDeleted = houseDeletes.size,
            liveHouses = liveHouses,
        )
    }

    private fun SyncRecord.stamp() = SyncStamp(updatedAt, writer.orEmpty(), deleted)
}
