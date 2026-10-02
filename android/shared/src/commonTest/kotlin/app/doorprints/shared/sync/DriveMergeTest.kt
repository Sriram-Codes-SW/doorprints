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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Drive merge rule (S4b-BL-130) as properties: whatever the order of the files, merging one twice, or merging
 * through a third device, three devices end with the same rows; plus the races, skew, hold, shrink and rollback rules.
 * The shared cases are in [SyncVectorsTest] (`docs/schemas/sync-vectors.json`).
 */
class DriveMergeTest {
    private val a = "device-a01"
    private val b = "device-b02"
    private val c = "device-c03"
    private val now = 1_790_000_000_000L
    private val keys = listOf("houses/h0", "houses/h1", "houses/h2", "visits/v0", "records/note/r0", "photos/p0")

    /** Three devices that each edited and deleted at random, never synced, and their files. */
    private fun threeFiles(seed: Int): List<Pair<String, String>> {
        val rng = XorShift32(seed)
        val devices = listOf(SimDevice(a), SimDevice(b, -7_000), SimDevice(c, 4_000))
        var clock = now - 100_000
        repeat(30) {
            clock += rng.next(2_000)
            val d = devices[rng.next(3)]
            d.edit(keys[rng.next(keys.size)], clock, deleted = rng.next(4) == 0)
        }
        return devices.map { it.snapshot(clock) to it.id }
    }

    private fun permutations(n: Int): List<List<Int>> =
        if (n == 0) listOf(emptyList()) else permutations(n - 1).flatMap { p -> (0..p.size).map { p.take(it) + (n - 1) + p.drop(it) } }

    @Test
    fun theOrderIsTotalAndAntisymmetric() {
        val rng = XorShift32(7)
        val stamps = List(200) { SyncStamp(now + rng.next(3), listOf(a, b, c, "")[rng.next(4)], rng.next(2) == 0) }
        for (x in stamps) for (y in stamps) {
            val xy = DriveMerge.takesIncoming(x, y)
            val yx = DriveMerge.takesIncoming(y, x)
            if (x == y) assertFalse(xy || yx) else assertTrue(xy != yx, "$x vs $y")
        }
    }

    @Test
    fun threeDevicesConvergeInEveryOrder() {
        for (seed in 1..40) {
            val files = threeFiles(seed)
            val results = permutations(3).map { order ->
                val reader = SimDevice("device-r00")
                for (i in order) reader.merge(files[i].first, files[i].second, now)
                reader.digest()
            }
            assertEquals(1, results.toSet().size, "seed $seed: $results")
        }
    }

    @Test
    fun theMergeIsTheMaximumPerKey() {
        for (seed in 1..20) {
            val files = threeFiles(seed)
            val reader = SimDevice("device-r00")
            files.forEach { reader.merge(it.first, it.second, now) }
            val expected = HashMap<String, SyncStamp>()
            for ((text, from) in files) {
                for (kind in SyncKind.entries) for (row in SyncFiles.parse(text, from).rows(kind)) {
                    val path = "${kind.key}/${row.key}"
                    if (expected[path]?.let { it >= row.stamp } != true) expected[path] = row.stamp
                }
            }
            assertEquals(digestOf(expected), reader.digest(), "seed $seed")
        }
    }

    @Test
    fun mergingAFileTwiceChangesNothingTheSecondTime() {
        for (seed in 1..20) {
            val (text, from) = threeFiles(seed)[1]
            val reader = SimDevice("device-r00")
            threeFiles(seed + 100).forEach { reader.merge(it.first, it.second, now) }
            reader.merge(text, from, now)
            val once = reader.digest()
            val again = reader.merge(text, from, now)
            assertTrue(again.take.isEmpty(), "seed $seed")
            assertEquals(once, reader.digest())
        }
    }

    @Test
    fun mergingThroughAThirdDeviceIsTheSameAsDirectly() {
        for (seed in 1..20) {
            val (f1, f2, f3) = threeFiles(seed)
            // Relay: X reads f1 and f2 and writes its own file; Y reads that and f3.
            val x = SimDevice("device-x00")
            x.merge(f1.first, f1.second, now)
            x.merge(f2.first, f2.second, now)
            val relayed = x.snapshot(now)
            val y = SimDevice("device-y00")
            y.merge(f3.first, f3.second, now)
            y.merge(relayed, x.id, now)
            val direct = SimDevice("device-z00")
            listOf(f2, f3, f1).forEach { direct.merge(it.first, it.second, now) }
            assertEquals(direct.digest(), y.digest(), "seed $seed")
        }
    }

    @Test
    fun theSharedRandomRunsConverge() {
        for (seed in 1..60) {
            val devices = simulate(seed, now, listOf(a, b, c), listOf(0, -7_000, 4_000), 80, keys)
            assertEquals(1, devices.map { it.digest() }.toSet().size, "seed $seed")
        }
    }

    @Test
    fun aDeleteAndAnEditRace() {
        val deleted = SyncStamp(now, a, deleted = true)
        assertTrue(DriveMerge.takesIncoming(SyncStamp(now - 1, b, false), deleted), "a delete wins over an older edit")
        assertFalse(DriveMerge.takesIncoming(SyncStamp(now + 1, b, false), deleted), "and loses to a newer one")
        assertTrue(DriveMerge.takesIncoming(deleted, SyncStamp(now + 1, c, false)), "a newer edit brings the row back")
        assertTrue(DriveMerge.takesIncoming(SyncStamp(now, a, false), deleted), "same time and writer: the tombstone")
        assertFalse(DriveMerge.takesIncoming(SyncStamp(now, b, false), deleted), "same time, greater writer: the edit")
    }

    @Test
    fun aClockBehindStillMovesTheRowForward() {
        val fast = SimDevice(a)
        val slow = SimDevice(b, skewMs = -3_600_000)
        fast.edit("houses/h1", now, deleted = false)
        slow.merge(fast.snapshot(now), a, now)
        slow.edit("houses/h1", now + 1_000, deleted = false, label = "later, on a slow clock")
        assertEquals(now + 1, slow.stamp("houses/h1")!!.updatedAt)
        fast.merge(slow.snapshot(now + 1_000), b, now + 1_000)
        assertEquals(b, fast.stamp("houses/h1")!!.by)
        assertEquals(DriveMerge.nextStamp(now, null), now)
        assertEquals(DriveMerge.nextStamp(now, now + 5), now + 6)
        assertEquals(DriveMerge.nextStamp(now, Long.MAX_VALUE), now, "no overflow")
    }

    @Test
    fun aFarFutureStampIsHeldAndALocalEditStillWinsOverANearFutureOne() {
        val liar = SimDevice(a, skewMs = 30L * 86_400_000)
        liar.edit("houses/h1", now, deleted = true)
        liar.edit("houses/h2", now - 30L * 86_400_000 + 23L * 3_600_000, deleted = false)
        val honest = SimDevice(b)
        honest.edit("houses/h1", now, deleted = false)
        val plan = honest.merge(liar.snapshot(now), a, now)
        assertEquals(listOf("h1"), plan.held.map { it.key }, "30 days ahead is held")
        assertEquals(b, honest.stamp("houses/h1")!!.by)
        assertEquals(listOf("h2"), plan.take.map { it.key }, "23 hours ahead is taken")
        honest.edit("houses/h2", now, deleted = false)
        assertTrue(honest.stamp("houses/h2")!!.updatedAt > now + 23L * 3_600_000, "the next edit stamps past it")
        val later = honest.merge(liar.snapshot(now), a, now + 30L * 86_400_000)
        assertEquals(listOf("h1"), later.take.map { it.key }, "applied once the clock is near it")
    }

    @Test
    fun theShrinkGuardHoldsAMassDeleteUntilConfirmed() {
        val here = SimDevice(b)
        val wiper = SimDevice(a)
        for (i in 0 until 40) here.edit("houses/h$i", now - 10_000, deleted = false)
        wiper.merge(here.snapshot(now), b, now)
        for (i in 0 until 40) wiper.edit("houses/h$i", now, deleted = true)
        wiper.edit("records/note/r1", now, deleted = false)
        val text = wiper.snapshot(now)
        val plan = here.merge(text, a, now)
        assertTrue(plan.needsConfirmation)
        assertEquals(40, plan.deferred.size)
        assertEquals(listOf("note/r1"), plan.take.map { it.key }, "the rest of the file goes in")
        assertEquals(40, here.liveHouses())
        val confirmed = here.merge(text, a, now, confirmShrink = true)
        assertEquals(40, confirmed.take.size)
        assertEquals(0, here.liveHouses())
    }

    @Test
    fun aRolledBackFileIsStaleAndWouldChangeNothingNewerAnyway() {
        val writer = SimDevice(a)
        writer.edit("houses/h1", now - 5_000, deleted = false)
        val old = writer.snapshot(now - 5_000)
        writer.edit("houses/h1", now, deleted = true)
        val reader = SimDevice(b)
        reader.merge(writer.snapshot(now), a, now)
        val plan = reader.merge(old, a, now)
        assertTrue(plan.stale)
        assertTrue(plan.take.isEmpty())
        // Even with no memory of the seq (a fresh reader that saw the new state some other way), LWW keeps the newer row.
        val fresh = SimDevice(c)
        fresh.merge(reader.snapshot(now), b, now)
        assertTrue(fresh.merge(old, a, now).take.isEmpty())
        assertTrue(fresh.stamp("houses/h1")!!.deleted)
    }

    @Test
    fun theLoopsRuleIgnoresDirtyAndBreaksTiesByWriter() {
        data class Row(
            override val updatedAt: Long,
            override val dirty: Boolean,
            override val deleted: Boolean = false,
            override val writer: String? = null,
        ) : SyncRecord
        val rule = DriveMerge.rule
        assertTrue(rule.keepLocal(Row(now, dirty = false), Row(now - 1, dirty = false)), "a clean newer row stays")
        assertFalse(SyncRules.serverMerge.keepLocal(Row(now, dirty = false), Row(now - 1, dirty = false)), "unlike the server's")
        assertFalse(rule.keepLocal(Row(now, dirty = true), Row(now + 1, dirty = false)), "a dirty older row loses")
        assertFalse(rule.keepLocal(null, Row(now, dirty = false)))
        assertFalse(rule.keepLocal(Row(now, false, writer = a), Row(now, false, writer = b)))
        assertTrue(rule.keepLocal(Row(now, false, writer = b), Row(now, false, writer = a)))
        assertFalse(rule.keepLocal(Row(now, false), Row(now, false, writer = a)), "an unknown writer is \"\"")
        assertFalse(rule.keepLocal(Row(now, false, writer = a), Row(now, false, deleted = true, writer = a)))
        assertTrue(rule.keepLocal(Row(now, false, writer = a), Row(now, false, writer = a)), "a full tie keeps the row here")
    }
}
