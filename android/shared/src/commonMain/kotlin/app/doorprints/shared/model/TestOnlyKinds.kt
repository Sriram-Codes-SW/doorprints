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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The seam that lets a test run a kind no build ships (the conformance suite's synthetic kind, docs/03 section 18.26; the
 * website's `registerTestKind`, `registerTestCapabilities` and `registerTestAiKind`). Using one is a compile error unless the
 * caller opts in, and only test source sets do (`TestOnlyKindsTest` fails if a main source set opts in). A registration lives in
 * memory and the call that made it returns the call that takes it away.
 */
@RequiresOptIn("Registers a kind that no build ships; only a test may.", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class TestOnlyKinds

/**
 * A kind file read as a kind of a test (its Android keys and its typed extras), the phones' twin of the website's
 * `builtInKindFromFile` (`shared/testing/kind-flows.ts`): the labels, the extras, the capabilities and whether the listing fill
 * reads the kind. It reads any file of `docs/schemas/kinds`; the conformance suite reads the synthetic kind's.
 */
@TestOnlyKinds
object KindFileKinds {
    /** The kind a kind file describes, as the screens read a built-in kind; null when the file's capabilities are refused. */
    fun fromJson(text: String): BuiltInKind? {
        val file = Json.parseToJsonElement(text).jsonObject
        val id = file.text("id") ?: return null
        val capabilities = capabilitiesOf(file) ?: return null
        val keys = LinkedHashMap<String, String?>()
        fun key(label: JsonElement?): String? = (label as? JsonObject)?.text("android")
        fun put(ref: String, label: JsonElement?) {
            keys[ref] = key(label)
        }
        val names = file.getValue("labels").jsonObject
        for (name in listOf("new", "many", "delete")) put("kind.$name", names[name])
        file.getValue("statuses").jsonArray.map { it.jsonObject }.forEach { put("status.${it.text("id")}", it["label"]) }
        val sectionList = file.getValue("sections").jsonArray.map { it.jsonObject }
        sectionList.forEach { put("section.${it.text("id")}", it["label"]) }
        val hidden = sectionList.filter { it.flag("visible") == false }.mapTo(HashSet()) { "section.${it.text("id")}" }
        val sections = HashMap<String, String>()
        for (c in file.getValue("core").jsonArray.map { it.jsonObject }) {
            val cid = c.text("id")!!
            put("field.$cid", c["label"])
            sections[cid] = c.text("section")!!
            if (c.flag("visible") == false) hidden += "field.$cid"
            c["cadences"]?.jsonArray?.map { it.jsonObject }?.forEach { put("cadence.${it.text("id")}", it["label"]) }
        }
        val extras = file.getValue("extras").jsonArray.map { it.jsonObject }.map { e ->
            val eid = e.text("id")!!
            put("field.$eid", e["label"])
            sections[eid] = e.text("section")!!
            KindExtra(
                id = eid, type = e.text("type")!!, section = e.text("section")!!, order = e.long("order")!!.toInt(),
                integer = e.flag("integer") == true, min = e.long("min"), max = e.long("max"), unit = e.text("unit"),
                cadences = e.strings("cadences"), cadenceFrom = e.text("cadenceFrom"), forCadences = e.strings("forCadences"),
                was = e.text("was"), maxLength = e.long("maxLength")?.toInt(),
                options = e["options"]?.jsonArray?.map { it.jsonObject }?.map { KindOption(it.text("id")!!, key(it["label"]).orEmpty()) }.orEmpty(),
                better = e.text("better"),
            )
        }
        file.getValue("checklist").jsonArray.map { it.jsonObject }.forEach { put("check.${it.text("key")}", it["label"]) }
        val labels = KindLabels(kind = id, keys = keys, byCadence = emptyMap(), sections = sections, hidden = hidden, capabilities = capabilities)
        val search = file["search"]?.jsonArray?.map { (it as JsonPrimitive).content }.orEmpty()
        val extracts = file["ai"]?.jsonObject?.get("extract")?.jsonArray?.isNotEmpty() == true
        return BuiltInKind(labels, KindFields(labels, extras, search), extracts)
    }

    /** Registers the kind of a kind file (its capabilities and its entry in [DevKinds]); the result takes both away again. */
    fun register(text: String): () -> Unit {
        val kind = checkNotNull(fromJson(text)) { "the kind file's capabilities are refused" }
        val capabilities = checkNotNull(capabilitiesOf(Json.parseToJsonElement(text).jsonObject))
        val undoCapabilities = KindCapabilities.registerForTest(kind.id, capabilities)
        val undoKind = DevKinds.registerForTest(kind)
        return {
            undoKind()
            undoCapabilities()
        }
    }

    private fun capabilitiesOf(file: JsonObject): KindCapabilities? = file["capabilities"]?.let { KindCapabilities.parse(it) } ?: if ("capabilities" in file) null else KindCapabilities.DEFAULT

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.strings(key: String): List<String>? = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }
}
