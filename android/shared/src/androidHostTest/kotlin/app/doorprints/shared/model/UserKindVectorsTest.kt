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

package app.doorprints.shared.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The phones' reader of a kind file a person adds (docs/03 ADR-39, docs/06 TC-U-295) against the shared vectors
 * `docs/schemas/kinds/user-kind-vectors.json`, which the website (`user-kind-file.spec.ts`) and the server
 * (`UserKindVectorsTest`) read too: every valid file is taken and resolves as pinned (its id, its plural, its extras, its
 * checklist, what search reads and the capabilities), and read again from the checked JSON it gives the same file; every
 * invalid case, the hostile ones among them (64 KiB, nesting, 4,000 values, long strings, `__proto__` keys, map keys, markup,
 * direction marks, huge option lists), is refused with the code and the place the vectors name. The limits are the vectors'.
 * Not in commonTest: it reads repository files.
 */
class UserKindVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/user-kind-vectors.json").readText()).jsonObject
    private val valid = vectors.getValue("valid").jsonArray.map { it.jsonObject }
    private val invalid = vectors.getValue("invalid").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    private fun sample(id: String): JsonObject = valid.first { it.getValue("file").jsonObject.text("id") == id }.getValue("file").jsonObject

    // ----- The texts of the cases, as the file's note defines its `json`, `generate` and `patch` forms -----

    /**
     * [root] with the value at [path] replaced by [value] (null: removed). Rebuilt, not changed in place: an object keeps its keys'
     * order and a new key comes last, as the website's objects do; a list item is replaced or removed at its index.
     */
    private fun change(root: JsonElement, path: List<JsonElement>, value: JsonElement?): JsonElement {
        val k = path.first().jsonPrimitive
        val rest = path.drop(1)
        return if (k.isString) {
            val o = root.jsonObject
            val out = LinkedHashMap<String, JsonElement>(o)
            val next = if (rest.isEmpty()) value else change(o.getValue(k.content), rest, value)
            if (next == null) out.remove(k.content) else out[k.content] = next
            JsonObject(out)
        } else {
            val a = root.jsonArray.toMutableList()
            val next = if (rest.isEmpty()) value else change(a[k.int], rest, value)
            if (next == null) a.removeAt(k.int) else a[k.int] = next
            JsonArray(a)
        }
    }

    private fun key(s: String) = JsonPrimitive(s)
    private fun index(i: Int) = JsonPrimitive(i)

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) -> k to (v as? JsonElement ?: if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString())) },
    )

    private fun label(text: String) = obj("text" to text)

    private fun caseText(c: JsonObject): String {
        c["json"]?.let { return it.jsonPrimitive.content }
        c["generate"]?.jsonObject?.let { g -> return g.text("prefix") + g.text("repeat").repeat(g.getValue("times").jsonPrimitive.int) + g.text("suffix") }
        val p = c.getValue("patch").jsonObject
        var file: JsonElement = sample(p.text("base"))
        p["set"]?.jsonArray?.forEach { pair -> file = change(file, pair.jsonArray[0].jsonArray, pair.jsonArray[1]) }
        p["remove"]?.jsonArray?.forEach { r -> file = change(file, r.jsonArray, null) }
        p["manyExtras"]?.jsonPrimitive?.int?.let { n ->
            file = change(file, listOf(key("extras")), JsonArray((1..n).map { i ->
                obj("id" to "x$i", "type" to "text", "maxLength" to 10, "section" to "details", "order" to i, "label" to label("Field $i"))
            }))
        }
        p["manyOptions"]?.jsonObject?.let { m ->
            val options = JsonArray((1..m.getValue("count").jsonPrimitive.int).map { i -> obj("id" to "O$i", "label" to label("Option $i")) })
            file = change(file, listOf(key("extras"), index(m.getValue("extra").jsonPrimitive.int), key("options")), options)
        }
        p["manyEnums"]?.jsonObject?.let { m ->
            val options = m.getValue("options").jsonPrimitive.int
            file = change(file, listOf(key("extras")), JsonArray((1..m.getValue("count").jsonPrimitive.int).map { i ->
                obj(
                    "id" to "e$i", "type" to "enum", "section" to "details", "order" to i, "label" to label("Field $i"),
                    "options" to JsonArray((1..options).map { j -> obj("id" to "O$j", "label" to label("Option $j")) }),
                )
            }))
        }
        p["manyChecks"]?.jsonPrimitive?.int?.let { n ->
            file = change(file, listOf(key("checklist")), JsonArray((1..n).map { i -> obj("key" to "c$i", "label" to label("Check $i")) }))
        }
        return file.toString()
    }

    /** The resolved capabilities in the vectors' shape: a feature left out is null. */
    private fun KindCapabilities.toJson(): JsonObject = JsonObject(
        mapOf(
            "map" to JsonPrimitive(map),
            "hunt" to (hunt?.let { JsonObject(mapOf("alertRadiusM" to JsonPrimitive(it.alertRadiusM), "stayCreates" to JsonPrimitive(it.stayCreates), "streetMemory" to JsonPrimitive(it.streetMemory), "areas" to JsonPrimitive(it.areas))) } ?: JsonNull),
            "rooms" to JsonPrimitive(rooms),
            "moveIn" to JsonPrimitive(moveIn),
            "contacts" to (contacts?.let { JsonObject(mapOf("role" to JsonPrimitive(it.role), "autoCreateFromPhone" to JsonPrimitive(it.autoCreateFromPhone))) } ?: JsonNull),
            "viewings" to (viewings?.let { JsonObject(mapOf("questionsSeed" to JsonPrimitive(it.questionsSeed))) } ?: JsonNull),
            "compare" to (compare?.let { JsonObject(mapOf("criteria" to JsonArray(it.criteria.map(::JsonPrimitive)))) } ?: JsonNull),
            "paths" to JsonPrimitive(paths),
            "identity" to (identity?.let { JsonObject(mapOf("icon" to JsonPrimitive(it.icon), "colour" to JsonPrimitive(it.colour))) } ?: JsonNull),
            "copyOrder" to JsonArray(copyOrder.map(::JsonPrimitive)),
            "baseline" to JsonPrimitive(baseline),
            "filters" to JsonArray(filters.map(::JsonPrimitive)),
        ),
    )

    @Test
    fun theLimitsAreTheVectors() {
        val l = vectors.getValue("limits").jsonObject
        val ours = mapOf(
            "maxBytes" to UserKindFiles.MAX_BYTES, "maxDepth" to UserKindFiles.MAX_DEPTH, "maxValues" to UserKindFiles.MAX_VALUES,
            "maxString" to UserKindFiles.MAX_STRING, "maxText" to UserKindFiles.MAX_TEXT, "maxNote" to UserKindFiles.MAX_NOTE,
            "maxSections" to UserKindFiles.MAX_SECTIONS, "maxCore" to UserKindFiles.MAX_CORE, "maxExtras" to UserKindFiles.MAX_EXTRAS,
            "maxOptions" to UserKindFiles.MAX_OPTIONS, "maxOptionsTotal" to UserKindFiles.MAX_OPTIONS_TOTAL, "maxChecklist" to UserKindFiles.MAX_CHECKLIST,
            "maxSearch" to UserKindFiles.MAX_SEARCH, "maxKinds" to UserKindFiles.MAX_KINDS,
        )
        for ((key, value) in ours) assertEquals(key, l.getValue(key).jsonPrimitive.int, value)
        assertEquals(l.text("idPattern"), UserKindFiles.ID.pattern)
        assertEquals(vectors.getValue("forbiddenKeys").strings(), UserKindFiles.PROTOTYPE_KEYS)
        // The built-in ids are reserved, and none of them has the shape of a kind a person adds.
        assertEquals(vectors.getValue("reserved").strings().toSet(), (KindSettingRules.BUILT_IN + "zz_all").toSet())
        assertTrue(vectors.getValue("reserved").strings().none(UserKindFiles::isUserKindId))
    }

    @Test
    fun takesEveryValidFileAndResolvesItAsPinned() {
        assertEquals(4, valid.size)
        for (v in valid) {
            val name = v.text("name")
            val read = UserKindFiles.read(v.getValue("file").toString(), KindSettingRules.BUILT_IN)
            if (read !is UserKindRead.Ok) fail("$name: refused with ${(read as UserKindRead.Refused).fault}") else {
                val r = v.getValue("resolved").jsonObject
                assertEquals(name, r.text("id"), read.file.id)
                assertEquals(name, r.text("many"), read.file.many)
                assertEquals(name, r.getValue("extras").strings(), read.file.extras.map { it.id })
                assertEquals(name, r.getValue("checklist").strings(), read.file.checklist.map { it.first })
                assertEquals(name, r.getValue("search").strings(), read.file.searched)
                assertEquals(name, r.getValue("capabilities").jsonObject, read.capabilities.toJson())
                // Read again from the checked JSON it gave (what the store and the backup keep): the same file.
                assertEquals(name, read, UserKindFiles.read(read.file.json.toString()))
            }
        }
    }

    @Test
    fun refusesEveryInvalidCaseWithTheCodeAndThePlaceTheVectorsName() {
        assertTrue(invalid.size > 100)
        val codes = vectors.getValue("codes").strings()
        val wrong = ArrayList<String>()
        for (c in invalid) {
            val name = c.text("name")
            assertTrue(name, c.text("code") in codes)
            when (val read = UserKindFiles.read(caseText(c), KindSettingRules.BUILT_IN + "zz_all")) {
                is UserKindRead.Ok -> wrong += "$name: taken"
                is UserKindRead.Refused -> if (read.fault != UserKindFault(c.text("code"), c.text("path"))) {
                    wrong += "$name: ${read.fault.code} at ${read.fault.path}, the vectors say ${c.text("code")} at ${c.text("path")}"
                }
            }
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun aHostileFileIsAnsweredQuickly() {
        // The largest texts of the vectors (64 KiB, 4,000 values, nesting): one pass each, well under a quarter of a second in all.
        val texts = invalid.map(::caseText)
        val start = System.nanoTime()
        repeat(3) { texts.forEach { UserKindFiles.read(it) } }
        val ms = (System.nanoTime() - start) / 1_000_000 / 3
        assertTrue("$ms ms for every case", ms < 2_000)
    }
}
