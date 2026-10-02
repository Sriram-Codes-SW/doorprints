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

import app.doorprints.shared.api.IsoTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test helpers for the Drive merge (S4b-BL-130): a device with its rows in memory, which edits, writes its `sync/1` file
 * and merges others' through the real [SyncFiles] and [DriveMerge], and the shared random run of
 * `docs/schemas/sync-vectors.json` (`random`), written the same in the website's `drive-merge.spec.ts`.
 */
internal class SimDevice(val id: String, val skewMs: Long = 0) {
    /** Rows by `kind/key`. */
    val rows = LinkedHashMap<String, SyncRow>()
    var seq = 0L
    val highestSeq = HashMap<String, Long>()

    fun stamp(path: String): SyncStamp? = rows[path]?.stamp

    fun liveHouses(): Int = rows.values.count { it.kind == SyncKind.HOUSES && !it.stamp.deleted }

    /** An edit or delete made here at [clockMs] (this device's clock adds [skewMs]). */
    fun edit(path: String, clockMs: Long, deleted: Boolean, label: String = "edit") {
        val t = DriveMerge.nextStamp(clockMs + skewMs, rows[path]?.stamp?.updatedAt)
        rows[path] = simRow(path, t, id, deleted, label)
    }

    /** This device's sync file, written now (a new `seq`). */
    fun snapshot(clockMs: Long): String {
        seq++
        return SyncFiles.encode(SyncFile(id, seq, clockMs + skewMs, rows.values.groupBy { it.kind }))
    }

    /** Merges [text], the file of device [from], at [clockMs]; returns the plan it applied. */
    fun merge(text: String, from: String, clockMs: Long, confirmShrink: Boolean = false): MergePlan {
        val file = SyncFiles.parse(text, from)
        val plan = DriveMerge.plan(file, clockMs + skewMs, highestSeq[from], liveHouses(), confirmShrink) { kind, key ->
            rows["${kind.key}/$key"]?.stamp
        }
        if (!plan.stale) {
            for (row in plan.take) rows["${row.kind.key}/${row.key}"] = row
            highestSeq[from] = maxOf(highestSeq[from] ?: 0L, file.seq)
        }
        return plan
    }

    fun digest(): String = digestOf(rows.mapValues { it.value.stamp })
}

internal fun digestOf(stamps: Map<String, SyncStamp>): String =
    stamps.entries.sortedBy { it.key }.joinToString(";") { (k, s) -> "$k=${s.by}@${s.updatedAt}${if (s.deleted) "~" else ""}" }

internal fun kindOf(path: String): SyncKind = SyncKind.entries.first { path.startsWith(it.key + "/") }

/** A minimal row at [path] (`houses/h1`, `records/note/r1`, ...). */
internal fun simRow(path: String, updatedAt: Long, by: String, deleted: Boolean, label: String = "edit"): SyncRow {
    val kind = kindOf(path)
    val key = path.removePrefix(kind.key + "/")
    val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    when (kind) {
        SyncKind.RECORDS -> {
            fields["type"] = JsonPrimitive(key.substringBefore('/'))
            fields["id"] = JsonPrimitive(key.substringAfter('/'))
            fields["payload"] = JsonObject(if (deleted) emptyMap() else mapOf("name" to JsonPrimitive(label)))
        }
        SyncKind.HOUSES -> {
            fields["id"] = JsonPrimitive(key)
            fields["label"] = JsonPrimitive(if (deleted) "" else label)
            fields["lat"] = JsonPrimitive(12.97)
            fields["lon"] = JsonPrimitive(77.59)
        }
        SyncKind.VISITS -> {
            fields["id"] = JsonPrimitive(key)
            fields["houseId"] = JsonNull
            fields["lat"] = JsonPrimitive(12.97)
            fields["lon"] = JsonPrimitive(77.59)
            fields["arrivedAt"] = JsonPrimitive(IsoTime.format(updatedAt - 600_000))
        }
        SyncKind.PHOTOS -> {
            fields["id"] = JsonPrimitive(key)
            fields["houseId"] = JsonPrimitive("h0")
        }
    }
    fields["updatedAt"] = JsonPrimitive(IsoTime.format(updatedAt))
    fields["deleted"] = JsonPrimitive(deleted)
    fields["by"] = JsonPrimitive(by)
    return SyncRow.of(kind, JsonObject(fields))
}

/** xorshift32, the same in `drive-merge.spec.ts`: [next] is the unsigned state modulo n. */
internal class XorShift32(seed: Int) {
    private var x = seed

    fun next(n: Int): Int {
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return ((x.toLong() and 0xFFFF_FFFFL) % n).toInt()
    }
}

/**
 * The shared random run: three devices with skewed clocks edit, delete and read each other's files in an order drawn
 * from [seed], then every device reads every other's file twice. Returns the devices; all must hold the same rows.
 */
internal fun simulate(seed: Int, base: Long, ids: List<String>, skews: List<Long>, steps: Int, keys: List<String>): List<SimDevice> {
    val rng = XorShift32(seed)
    val devices = ids.indices.map { SimDevice(ids[it], skews[it]) }
    var clock = base
    for (step in 0 until steps) {
        clock += rng.next(3000)
        val d = rng.next(3)
        val action = rng.next(10)
        when {
            action < 5 -> devices[d].edit(keys[rng.next(keys.size)], clock, deleted = false, label = "s$step")
            action < 7 -> devices[d].edit(keys[rng.next(keys.size)], clock, deleted = true)
            else -> {
                val e = (d + 1 + rng.next(2)) % 3
                devices[d].merge(devices[e].snapshot(clock), devices[e].id, clock)
            }
        }
    }
    repeat(2) {
        for (d in 0 until 3) for (e in 0 until 3) {
            if (e == d) continue
            clock += 1
            devices[d].merge(devices[e].snapshot(clock), devices[e].id, clock)
        }
    }
    return devices
}
