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

package app.doorprints.drive.backup

/**
 * One finished, checked backup as retention sees it (S4b-BL-116; docs/15 §1.4 item 4). [createdAt] and [houses] are the
 * **authenticated** values of its metadata ([BackupMeta]), never Drive's times or the file name, so someone who can
 * write to the folder but holds no folder key cannot make an old backup look new or a large one look small.
 */
data class RetentionEntry(val id: String, val createdAt: Long, val houses: Int)

/**
 * The shrink guard's finding: the newest backup [backupId] holds [houses] live houses, fewer than half the
 * [previousHouses] of the largest backup before it ([previousId]). Until the person confirms the drop on this device
 * ("Your newest backup has 3 houses; an earlier one had 48. Keep the older backups?"), nothing is pruned.
 */
data class ShrinkHold(val backupId: String, val houses: Int, val previousId: String, val previousHouses: Int)

/** What [BackupRetention.select] decided: ids newest first; [prune] is empty whenever [hold] is set. */
data class RetentionResult(val keep: List<String>, val prune: List<String>, val hold: ShrinkHold?)

/**
 * Retention, 7 daily, 4 weekly and 6 monthly (docs/15 §1.4 item 4, decision 5), and the shrink guard, as one pure
 * function (web: `backup-retention.ts`; vectors: docs/schemas/backup-vectors.json `retention`).
 *
 * - Backups are ordered newest first by `createdAt`, ties by id (ascending), so both stacks agree.
 * - **Daily**: the newest backup of each of the newest [daily] local days that have one; **weekly**: the same for the
 *   newest [weekly] weeks (Monday to Sunday); **monthly**: the newest [monthly] calendar months. Days are local to
 *   [utcOffsetMinutes] (the device's offset when it prunes; India is +330). The newest backup is always kept. The union
 *   is at most 17 files; everything else is pruned (to Drive's bin, docs/15 §1.4 item 5).
 * - **Shrink guard**: when the newest backup holds fewer than half the houses (`houses * 2 < reference`) of the
 *   largest backup before it, all pruning is held ([RetentionResult.hold]) until the person confirms the drop on this
 *   device ([confirmedDrops] gets that backup's id). The backups before the newest confirmed one no longer count as a
 *   reference, so the confirmed level is not asked about again, but a further drop is. A wiped or broken phone that
 *   keeps making small daily backups therefore cannot push the history out.
 *
 * The entries must be distinct backups (the caller collapses copies of one file first, [BackupListing]).
 */
object BackupRetention {
    const val DAILY = 7
    const val WEEKLY = 4
    const val MONTHLY = 6

    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L

    /**
     * Decides which of [entries] to keep and which to prune; [confirmedDrops] are the backups whose drop in houses the
     * person confirmed on this device. [daily], [weekly] and [monthly] are the bucket counts and default to the product
     * values. Nothing is pruned while a [ShrinkHold] is set.
     */
    fun select(
        entries: List<RetentionEntry>,
        utcOffsetMinutes: Int,
        confirmedDrops: Set<String> = emptySet(),
        daily: Int = DAILY,
        weekly: Int = WEEKLY,
        monthly: Int = MONTHLY,
    ): RetentionResult {
        if (entries.isEmpty()) return RetentionResult(emptyList(), emptyList(), null)
        val newestFirst = entries.sortedWith(compareByDescending<RetentionEntry> { it.createdAt }.thenBy { it.id })
        val keep = linkedSetOf(newestFirst.first().id)
        fun bucket(count: Int, of: (Long) -> Long) {
            val seen = HashSet<Long>()
            for (e in newestFirst) {
                if (seen.size >= count) break
                if (seen.add(of(localDay(e.createdAt, utcOffsetMinutes)))) keep += e.id
            }
        }
        bucket(daily) { it }
        bucket(weekly) { weekOf(it) }
        bucket(monthly) { monthOf(it) }

        val hold = shrinkHold(newestFirst, confirmedDrops)
        val kept = newestFirst.filter { it.id in keep }.map { it.id }
        val prune = if (hold != null) emptyList() else newestFirst.filter { it.id !in keep }.map { it.id }
        return RetentionResult(kept, prune, hold)
    }

    /**
     * The newest backup against the largest of the backups before it, counted from the newest one the person confirmed
     * (that one included: its level is accepted, so later backups of the same size do not ask again).
     */
    private fun shrinkHold(newestFirst: List<RetentionEntry>, confirmedDrops: Set<String>): ShrinkHold? {
        val newest = newestFirst.first()
        if (newest.id in confirmedDrops) return null
        var reference: RetentionEntry? = null
        for (e in newestFirst.drop(1)) {
            if (reference == null || e.houses > reference.houses) reference = e
            if (e.id in confirmedDrops) break
        }
        val before = reference ?: return null
        return if (newest.houses.toLong() * 2 < before.houses) ShrinkHold(newest.id, newest.houses, before.id, before.houses) else null
    }

    /** Days since 1970-01-01 in local time ([utcOffsetMinutes] east of UTC). */
    fun localDay(at: Long, utcOffsetMinutes: Int): Long = (at + utcOffsetMinutes * MINUTE_MS).floorDiv(DAY_MS)

    /** The Monday-to-Sunday week of [day] (1970-01-01 was a Thursday, so day -3 is week 0's Monday). */
    fun weekOf(day: Long): Long = (day + 3).floorDiv(7L)

    /** `year * 12 + month - 1` of [day]. */
    fun monthOf(day: Long): Long {
        val (year, month) = civil(day)
        return year * 12 + month - 1
    }

    /** Year, month (1..12) and day (1..31) of [day] in the proleptic Gregorian calendar (H. Hinnant's days-to-civil). */
    fun civil(day: Long): Triple<Long, Long, Long> {
        val z = day + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return Triple(year, month, d)
    }
}
