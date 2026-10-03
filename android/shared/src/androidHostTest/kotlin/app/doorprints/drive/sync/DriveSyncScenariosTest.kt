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

package app.doorprints.drive.sync

import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveOp
import app.doorprints.drive.NewFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared scenario vectors (`docs/schemas/drive-sync-scenarios.json`, S4b-BL-118): the same steps and the same
 * expectations run here and in the website's `drive-sync-scenarios.spec.ts`, over each stack's fake Drive and real
 * encryption core.
 */
class DriveSyncScenariosTest {
    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        val rel = "docs/schemas/drive-sync-scenarios.json"
        while (dir != null && !File(dir, rel).exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir) { "$rel not found" }, rel).readText()).jsonObject
            .also { assertEquals("doorprints-sync-scenarios/1", it.getValue("format").jsonPrimitive.content) }
    }

    @Test
    fun everyScenario() = runTest {
        val scenarios = root.getValue("scenarios").jsonArray
        assertTrue(scenarios.size >= 10)
        for (s in scenarios) {
            val o = s.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            try {
                run(o)
            } catch (e: Throwable) {
                throw AssertionError("scenario \"$name\": ${e.message}", e)
            }
        }
    }

    private suspend fun run(scenario: JsonObject) {
        val w = SyncWorld()
        val devices = scenario.getValue("devices").jsonArray.associate { it.jsonPrimitive.content to w.add(it.jsonPrimitive.content) }
        val snapshots = HashMap<String, ByteArray>()
        var plants = 0
        fun dev(o: JsonObject, key: String = "device") = devices.getValue(o.getValue(key).jsonPrimitive.content)
        for ((index, stepEl) in scenario.getValue("steps").jsonArray.withIndex()) {
            val step = stepEl.jsonObject
            val at = "step $index (${step.getValue("do").jsonPrimitive.content})"
            when (val op = step.getValue("do").jsonPrimitive.content) {
                "edit" -> dev(step).edit(step.str("house"), step.str("label"))
                "delete" -> dev(step).delete(step.str("house"))
                "advance" -> w.server.clock.advance(step.getValue("ms").jsonPrimitive.long)
                "skew" -> dev(step).skewMs = step.getValue("ms").jsonPrimitive.long
                "failDrive" -> {
                    val status = step.getValue("status").jsonPrimitive.int
                    val fault = if (status == 0) DriveFault.Offline else DriveFault.Server(status)
                    if (step.str("op") == "ALL") w.server.faults.always(fault) else w.server.faults.always(fault, DriveOp.valueOf(step.str("op")))
                }
                "healDrive" -> w.server.faults.clear()
                "corruptNextDownload" -> w.server.faults.next(
                    DriveFault.Interleave { s -> s.editByHand(s.allFiles().first { it.name.startsWith("sync-") }.id, ByteArray(300) { 7 }) },
                    DriveOp.DOWNLOAD,
                )
                "sync" -> {
                    val d = dev(step)
                    val expectError = step.str("expectError", "")
                    if (expectError.isNotEmpty()) {
                        try {
                            d.sync(step.bool("confirm"), step.bool("stale"), step.bool("force", true))
                            fail("$at: expected the error $expectError")
                        } catch (e: DriveException) {
                            assertEquals(at, expectError, e.kind.name)
                        }
                    } else {
                        val r = d.sync(step.bool("confirm"), step.bool("stale"), step.bool("force", true))
                        step["expect"]?.jsonObject?.let { expect(at, r, it) }
                    }
                }
                "expectLabels" -> assertEquals(at, step.getValue("labels").jsonObject.mapValues { it.value.jsonPrimitive.content }.toSortedMap(), dev(step).local.labels().mapValues { it.value!! })
                "expectLabel" -> assertEquals(at, step.str("label"), dev(step).local.label(step.str("house")))
                "expectDirty" -> assertEquals(at, step.getValue("count").jsonPrimitive.int, dev(step).local.dirty.size)
                "snapshot" -> snapshots[step.str("name")] = w.server.contentOf(dev(step).state.lastFileId!!)!!
                "trashLastFile" -> w.server.trashByHand(dev(step).state.lastFileId!!)
                "replay" -> w.server.putByHand(plantFile(w, dev(step).id, "50"), snapshots.getValue(step.str("name")))
                "revoke" -> w.revoke(dev(step, "by"), dev(step, "victim"))
                "plant" -> {
                    val from = devices.getValue(step.str("from"))
                    val good = w.server.contentOf(from.state.lastFileId!!)!!
                    val slot = if (step.str("slot") == "X") "f".repeat(32) else devices.getValue(step.str("slot")).id
                    val bytes = when (step.str("how")) {
                        "copy" -> good
                        "flip" -> good.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
                        "plain" -> "{\"format\":\"doorprints-sync/1\"}".encodeToByteArray()
                        else -> error("how")
                    }
                    w.server.putByHand(plantFile(w, slot, "99").copy(name = "x.dpx"), bytes)
                    plants++
                }
                "expectPlantsKept" -> assertEquals(at, step.getValue("count").jsonPrimitive.int, w.syncFiles().count { !it.trashed && it.name == "x.dpx" })
                else -> error("unknown step $op")
            }
        }
    }

    private fun plantFile(w: SyncWorld, deviceId: String, seq: String) = NewFile(
        "again.dpx", "application/octet-stream", listOf(w.syncFolderId()),
        mapOf(DriveLayout.KIND to KIND_SYNC, DriveLayout.DEVICE to deviceId, DriveLayout.STATE to DriveLayout.STATE_COMPLETE, "seq" to seq),
    )

    private fun expect(at: String, r: SyncPassResult, e: JsonObject) {
        val kind = when (r) {
            is SyncPassResult.Done -> "Done"
            is SyncPassResult.NeedsConfirmation -> "NeedsConfirmation"
            is SyncPassResult.Waiting -> "Waiting"
            SyncPassResult.Paused -> "Paused"
        }
        e["kind"]?.let { assertEquals("$at kind", it.jsonPrimitive.content, kind) }
        e["wrote"]?.let { assertEquals("$at wrote", it.jsonPrimitive.booleanOrNull, r.report!!.wrote) }
        e["held"]?.let { assertEquals("$at held", it.jsonPrimitive.int, r.report!!.held) }
        e["take"]?.let { assertEquals("$at take", it.jsonPrimitive.int, r.report!!.take.size) }
        e["skipped"]?.let { assertEquals("$at skipped", it.jsonArray.map { x -> x.jsonPrimitive.content }.sorted(), r.skippedReasons().map { x -> x.name }.sorted().distinct()) }
        if (r is SyncPassResult.NeedsConfirmation) {
            e["housesToDelete"]?.let { assertEquals("$at housesToDelete", it.jsonPrimitive.int, r.housesToDelete) }
            e["liveHouses"]?.let { assertEquals("$at liveHouses", it.jsonPrimitive.int, r.liveHouses) }
        }
    }

    private fun JsonObject.str(key: String, default: String? = null): String =
        (get(key) as? JsonPrimitive)?.contentOrNull ?: default ?: error("missing $key")

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: default
}
