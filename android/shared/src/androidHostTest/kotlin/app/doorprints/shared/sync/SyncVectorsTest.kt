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

import app.doorprints.data.toEntity
import app.doorprints.drive.photo.PhotoRef
import app.doorprints.drive.sync.SyncRows
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.export.BackupWire
import app.doorprints.shared.model.HouseCost
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared sync vectors (`docs/schemas/sync-vectors.json`, S4b-BL-130): the limits, the strict times, the stamps, the
 * merge order, the file checks, the converging sets of files (every order, each twice), the step-by-step plans and the
 * random runs, the same here as in the website's `sync-vectors.spec.ts`. With `random.cases` empty, the random test
 * writes the digests of seeds 1..24 to the temporary folder (to fill it after a deliberate change of the rule).
 */
class SyncVectorsTest {

    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "$VECTORS not found" }, VECTORS)
        Json.parseToJsonElement(file.readText()).jsonObject.also {
            assertEquals("doorprints-sync-vectors/1", it.getValue("format").jsonPrimitive.content)
        }
    }

    private fun cases(key: String): List<JsonObject> = root.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.name() = getValue("name").jsonPrimitive.content
    private fun JsonObject.stamp() = SyncStamp(
        getValue("updatedAt").jsonPrimitive.long,
        getValue("by").jsonPrimitive.content,
        getValue("deleted").jsonPrimitive.boolean,
    )
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun theLimitsAreTheCodes() {
        val l = root.getValue("limits").jsonObject
        assertEquals(SyncFiles.FORMAT, l.str("format"))
        assertEquals(SyncFiles.MAX_VERSION, l.getValue("maxVersion").jsonPrimitive.int)
        assertEquals(SyncFiles.MAX_BYTES, l.getValue("maxBytes").jsonPrimitive.int)
        assertEquals(SyncFiles.MAX_ROWS, l.getValue("maxRows").jsonPrimitive.int)
        assertEquals(SyncKind.HOUSES.maxRows, l.getValue("maxHouses").jsonPrimitive.int)
        assertEquals(SyncKind.VISITS.maxRows, l.getValue("maxVisits").jsonPrimitive.int)
        assertEquals(SyncKind.RECORDS.maxRows, l.getValue("maxRecords").jsonPrimitive.int)
        assertEquals(SyncKind.PHOTOS.maxRows, l.getValue("maxPhotos").jsonPrimitive.int)
        assertEquals(SyncFiles.MAX_SEQ, l.getValue("maxSeq").jsonPrimitive.long)
        assertEquals(SyncFiles.MAX_DEPTH, l.getValue("maxDepth").jsonPrimitive.int)
        assertEquals(SyncTime.EARLIEST_MS, l.getValue("earliestMs").jsonPrimitive.long)
        assertEquals(DriveMerge.MAX_AHEAD_MS, l.getValue("maxAheadMs").jsonPrimitive.long)
        assertEquals(DriveMerge.SHRINK_MIN_HOUSES, l.getValue("shrinkMinHouses").jsonPrimitive.int)
    }

    @Test
    fun theSchemaCarriesTheSameLimits() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, SCHEMA).exists()) dir = dir.parentFile
        val schema = Json.parseToJsonElement(File(checkNotNull(dir), SCHEMA).readText()).jsonObject
        val props = schema.getValue("properties").jsonObject
        assertEquals(SyncFiles.FORMAT, props.getValue("format").jsonObject.str("const"))
        for (kind in SyncKind.entries) {
            // The list under its name in the file and, for the houses, under its name before the format reset (S4b-BL-206).
            for (name in listOfNotNull(kind.fileKey, kind.formerFileKey)) {
                assertEquals(name, kind.maxRows, props.getValue(name).jsonObject.getValue("maxItems").jsonPrimitive.int)
            }
        }
        assertEquals(SyncFiles.MAX_SEQ, props.getValue("seq").jsonObject.getValue("maximum").jsonPrimitive.long)
    }

    @Test
    fun times() {
        for (c in cases("times")) {
            val text = c.getValue("text").jsonPrimitive.content
            assertEquals(text, c["ms"]?.jsonPrimitive?.longOrNull, SyncTime.parse(text))
        }
    }

    @Test
    fun stamps() {
        for (c in cases("stamps")) {
            val previous = c["previous"]?.jsonPrimitive?.longOrNull
            assertEquals(c.name(), c.getValue("stamp").jsonPrimitive.long, DriveMerge.nextStamp(c.getValue("now").jsonPrimitive.long, previous))
        }
    }

    @Test
    fun order() {
        for (c in cases("order")) {
            val local = (c["local"] as? JsonObject)?.stamp()
            val incoming = c.getValue("incoming").jsonObject.stamp()
            assertEquals(c.name(), c.getValue("takes").jsonPrimitive.boolean, DriveMerge.takesIncoming(local, incoming))
        }
    }

    @Test
    fun files() {
        for (c in cases("files")) {
            val text = c.str("text") ?: c.getValue("file").toString()
            val expected = c.str("problem")
            val parsed = try {
                SyncFiles.parse(text, c.getValue("expectedDeviceId").jsonPrimitive.content)
            } catch (e: SyncFileException) {
                assertEquals(c.name(), expected, e.problem.name)
                continue
            }
            assertNull("${c.name()}: read, expected $expected", expected)
            val counts = c.getValue("counts").jsonObject
            for (kind in SyncKind.entries) assertEquals(c.name(), counts.getValue(kind.key).jsonPrimitive.int, parsed.rows(kind).size)
            (c["keys"] as? JsonObject)?.forEach { (kind, keys) ->
                val k = SyncKind.entries.first { it.key == kind }
                assertEquals(c.name(), keys.jsonArray.map { it.jsonPrimitive.content }, parsed.rows(k).map { it.key })
            }
        }
    }

    /**
     * S4b-BL-246: a file the website writes (the cases with `from`, the rows it was made of in the shape before) is read
     * here as those rows: each candidate as the house its row of before reads as, each visit's and photo's
     * `candidateId` as its house. The website writes the same file byte for byte from `from` (`sync-vectors.spec.ts`).
     */
    @Test
    fun aFileTheWebsiteWritesReadsAsTheRowsItWasMadeOf() {
        val dto = Json { ignoreUnknownKeys = true }
        fun <T> read(serializer: KSerializer<T>, json: JsonObject): T = dto.decodeFromJsonElement(serializer, json)
        val written = cases("files").filter { "from" in it }
        assertTrue(written.isNotEmpty())
        for (c in written) {
            val file = SyncFiles.parse(c.getValue("file").toString(), c.getValue("expectedDeviceId").jsonPrimitive.content)
            val from = c.getValue("from").jsonObject
            fun rows(kind: SyncKind) = from.getValue(kind.key).jsonArray.map { it.jsonObject }
            for ((row, before) in file.rows(SyncKind.HOUSES).zip(rows(SyncKind.HOUSES), ::Pair)) {
                assertTrue(c.name(), "kind" in row.json)
                val house = read(HouseDto.serializer(), row.json)
                assertEquals(c.name(), read(HouseDto.serializer(), BackupWire.candidateOfHouse(before)), house)
                // This phone's writer gives the website's row byte for byte (review follow-up of #295).
                assertEquals("${c.name()}: ${row.key}", row.json.toString(), SyncRows.house(house, row.stamp.by).json.toString())
            }
            for ((row, before) in file.rows(SyncKind.VISITS).zip(rows(SyncKind.VISITS), ::Pair)) {
                assertFalse(c.name(), "houseId" in row.json)
                val visit = read(VisitDto.serializer(), row.json)
                assertEquals(c.name(), read(VisitDto.serializer(), before), visit)
                assertEquals("${c.name()}: ${row.key}", row.json.toString(), SyncRows.visit(visit, row.stamp.by).json.toString())
            }
            for ((row, before) in file.rows(SyncKind.PHOTOS).zip(rows(SyncKind.PHOTOS), ::Pair)) {
                assertFalse(c.name(), "houseId" in row.json)
                val photo = read(PhotoChangeDto.serializer(), row.json)
                assertEquals(c.name(), read(PhotoChangeDto.serializer(), before), photo)
                // Byte for byte, key order too: `by`, then the Drive file and its SHA-256 when the photo has one.
                val ref = c["photoRefs"]?.jsonObject?.get(row.key)?.jsonObject?.let { PhotoRef(it.str("driveFileId")!!, it.str("sha256")!!) }
                assertEquals("${c.name()}: ${row.key}", row.json.toString(), SyncRows.photo(photo, row.stamp.by, ref).json.toString())
                assertEquals("${c.name()}: ${row.key}", ref != null, "driveFileId" in row.json)
            }
        }
        // The first written case, as the phone keeps it: a sale with its cost and a newer app's extras, a price without a type.
        val houses = SyncFiles.parse(written.first().getValue("file").toString(), "device-a01").rows(SyncKind.HOUSES)
        val salt = read(HouseDto.serializer(), houses[0].json).toEntity()
        assertEquals(listOf<Any?>(9_500_000L, "SALE", 3, 1450, 4), listOf(salt.price, salt.priceType, salt.bedrooms, salt.areaSqft, salt.floor))
        assertEquals(HouseCost(deposit = 50_000, maintenance = 3_500, myOffer = 9_000_000), salt.cost)
        assertEquals(setOf("gateName", "liftCount"), salt.extrasOther?.keys)
        val typeless = read(HouseDto.serializer(), houses[1].json).toEntity()
        assertEquals(18_000L, typeless.price)
        assertNull(typeless.priceType)
        // A rent with no amount yet: money with its cadence alone, read as a rent without a price.
        val rentSoon = read(HouseDto.serializer(), houses[3].json).toEntity()
        assertNull(rentSoon.price)
        assertEquals("RENT", rentSoon.priceType)
    }

    /** A device holding the case's `local` rows (stamps only: the merge reads nothing else). */
    private fun state(c: JsonObject): HashMap<String, SyncStamp> = HashMap<String, SyncStamp>().apply {
        for (r in c.getValue("local").jsonArray.map { it.jsonObject }) put("${r.str("kind")}/${r.str("key")}", r.stamp())
    }

    private class Applied(val plan: MergePlan, val seq: Long)

    private fun apply(
        rows: HashMap<String, SyncStamp>,
        seqs: HashMap<String, Long>,
        file: JsonObject,
        now: Long,
        confirm: Boolean,
    ): Applied {
        val device = file.str("deviceId")!!
        val parsed = SyncFiles.parse(file.toString(), device)
        val live = rows.count { it.key.startsWith("houses/") && !it.value.deleted }
        val plan = DriveMerge.plan(parsed, now, seqs[device], live, confirm) { kind, key -> rows["${kind.key}/$key"] }
        if (!plan.stale) {
            for (row in plan.take) rows["${row.kind.key}/${row.key}"] = row.stamp
            seqs[device] = maxOf(seqs[device] ?: 0L, parsed.seq)
        }
        return Applied(plan, parsed.seq)
    }

    private fun digestOfCase(final: JsonObject): String =
        final.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.jsonPrimitive.content}" }

    @Test
    fun converge() {
        fun permutations(n: Int): List<List<Int>> =
            if (n == 0) listOf(emptyList()) else permutations(n - 1).flatMap { p -> (0..p.size).map { p.take(it) + (n - 1) + p.drop(it) } }
        for (c in cases("converge")) {
            val files = c.getValue("files").jsonArray.map { it.jsonObject }
            val now = c.getValue("now").jsonPrimitive.long
            val expected = digestOfCase(c.getValue("final").jsonObject)
            for (order in permutations(files.size)) {
                val rows = state(c)
                val seqs = HashMap<String, Long>()
                for (round in 0 until 2) for (i in order) apply(rows, seqs, files[i], now, false)
                assertEquals("${c.name()} $order", expected, digestOf(rows))
            }
        }
    }

    @Test
    fun plans() {
        for (c in cases("plans")) {
            val rows = state(c)
            val seqs = HashMap<String, Long>()
            for ((i, step) in c.getValue("steps").jsonArray.map { it.jsonObject }.withIndex()) {
                val now = (step["now"] ?: c.getValue("now")).jsonPrimitive.long
                val confirm = step["confirmShrink"]?.jsonPrimitive?.booleanOrNull ?: false
                val plan = apply(rows, seqs, step.getValue("file").jsonObject, now, confirm).plan
                val e = step.getValue("expect").jsonObject
                val name = "${c.name()}, step ${i + 1}"
                fun paths(list: List<SyncRow>) = list.map { "${it.kind.key}/${it.key}" }.sorted()
                assertEquals(name, e.getValue("stale").jsonPrimitive.boolean, plan.stale)
                assertEquals(name, e.strings("take").sorted(), paths(plan.take))
                assertEquals(name, e.strings("held").sorted(), paths(plan.held))
                assertEquals(name, e.strings("deferred").sorted(), paths(plan.deferred))
                e["housesDeleted"]?.let { assertEquals(name, it.jsonPrimitive.int, plan.housesDeleted) }
                e["liveHouses"]?.let { assertEquals(name, it.jsonPrimitive.int, plan.liveHouses) }
            }
            assertEquals(c.name(), digestOfCase(c.getValue("final").jsonObject), digestOf(rows))
        }
    }

    @Test
    fun randomRuns() {
        val r = root.getValue("random").jsonObject
        val ids = r.strings("devices")
        val skews = r.getValue("skewsMs").jsonArray.map { it.jsonPrimitive.long }
        val keys = r.strings("keys")
        val base = r.getValue("base").jsonPrimitive.long
        val steps = r.getValue("steps").jsonPrimitive.int
        val cases = r.getValue("cases").jsonArray.map { it.jsonObject }
        if (cases.isEmpty()) {
            val out = (1..24).map { seed -> """{"seed":$seed,"digest":"${simulate(seed, base, ids, skews, steps, keys)[0].digest()}"}""" }
            val file = File(System.getProperty("java.io.tmpdir"), "sync-random.json")
            file.writeText("[" + out.joinToString(",") + "]")
            fail("random.cases is empty: the digests of seeds 1..24 are in $file")
        }
        for (c in cases) {
            val seed = c.getValue("seed").jsonPrimitive.int
            val devices = simulate(seed, base, ids, skews, steps, keys)
            for (d in devices) assertEquals("seed $seed, ${d.id}", c.str("digest"), d.digest())
        }
    }

    private companion object {
        const val VECTORS = "docs/schemas/sync-vectors.json"
        const val SCHEMA = "docs/schemas/sync-1.schema.json"
    }
}
