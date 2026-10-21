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

/** A kind file a person adds that passed every check (docs/03 ADR-39): its parts as plain values, and the checked JSON itself. */
data class UserKindFile(
    val id: String,
    val version: Int,
    val note: String?,
    /** `labels.new`, `labels.many`, `labels.delete`: the person's own words. */
    val new: String,
    val many: String,
    val delete: String,
    /** The five statuses in the fixed order, with their words. */
    val statuses: List<Pair<String, String>>,
    val sections: List<Section>,
    val core: List<Core>,
    val extras: List<Extra>,
    /** The checklist's keys with their words, in the file's order. */
    val checklist: List<Pair<String, String>>,
    /** The question bank's seed path (never fetched) and its one scope's cadences. */
    val seed: String,
    val scopes: List<String>,
    val search: List<String>,
    /** The checked file as JSON: what the store and the backup keep, and what is read again every time. */
    val json: JsonObject,
) {
    data class Section(val id: String, val label: String, val visible: Boolean?)

    data class Core(
        val id: String,
        val label: String,
        val section: String,
        val order: Int,
        val visible: Boolean?,
        val forCadences: List<String>?,
        /** The money's cadences with their words (only the `money` field has them). */
        val cadences: List<Pair<String, String>>?,
    )

    data class Extra(
        val id: String,
        val type: String,
        val label: String,
        val section: String,
        val order: Int,
        val visible: Boolean?,
        val forCadences: List<String>?,
        val integer: Boolean? = null,
        val min: Long? = null,
        val max: Long? = null,
        val unit: String? = null,
        val better: String? = null,
        val cadences: List<String>? = null,
        val cadenceFrom: String? = null,
        val maxLength: Int? = null,
        /** An enum's options with their words, in the file's order. */
        val options: List<Pair<String, String>> = emptyList(),
    )

    /** What the list search reads for this kind: its `search`, then every typed extra it does not name (CLAUDE.md, *search grows with the values*). */
    val searched: List<String> get() = search + extras.map { it.id }.filter { it !in search }
}

/** The first fault of a kind file a person adds: its code (`user-kind-vectors.json` `codes`) and where it is (`$.extras[2].id`). */
data class UserKindFault(val code: String, val path: String)

/** What reading a kind file a person adds gives: the checked file and its capabilities, or the first fault. */
sealed interface UserKindRead {
    data class Ok(val file: UserKindFile, val capabilities: KindCapabilities) : UserKindRead
    data class Refused(val fault: UserKindFault) : UserKindRead
}

/**
 * The phones' reader of a kind file a person imports (docs/03 ADR-39; docs/02 T-T23), the twin of the website's
 * `core/user-kinds/user-kind-file.ts`: the rules of `kind.schema.json` the server's `KindFiles.parse` checks, with the user
 * profile of `user-kind.schema.json` on top, and the same answer (the fault's code and path) for every case of
 * `docs/schemas/kinds/user-kind-vectors.json` (`UserKindVectorsTest`). A kind file is data only: nothing in it is run, fetched,
 * parsed as markup or given to the map. It is refused, with the first fault and where it is, when anything is outside the
 * format: the size (64 KiB) before anything else, then the JSON itself ([JsonScan]: one document, no repeated key, nesting,
 * values, string length), then every key at every depth (no `__proto__`, `constructor` or `prototype`, no map key), then the
 * shape, in the file's order. Never throws.
 */
object UserKindFiles {
    const val MAX_BYTES = 65_536
    const val MAX_DEPTH = 8
    const val MAX_VALUES = 4000
    const val MAX_STRING = 2000
    const val MAX_TEXT = 60
    const val MAX_NOTE = 500
    const val MAX_SECTIONS = 13
    const val MAX_CORE = 14
    const val MAX_EXTRAS = 30
    const val MAX_OPTIONS = 30
    const val MAX_OPTIONS_TOTAL = 300
    const val MAX_CHECKLIST = 20
    const val MAX_SEARCH = 80

    /** At most this many kinds a person adds on one device. */
    const val MAX_KINDS = 8

    /** A kind a person adds: `u_`, a letter, then 1 to 29 letters, digits or `_` (32 characters at most, door 3's kind id). */
    val ID: Regex = Regex("^u_[a-z][a-z0-9_]{1,29}$")

    /** Whether [id] is the id of a kind a person adds; a built-in id never starts with `u_`. */
    fun isUserKindId(id: String?): Boolean = id != null && ID.matches(id)

    /** The keys that configure a map; none is accepted anywhere in a kind file (India's boundaries apply to every kind, ADR-22). */
    val FORBIDDEN_MAP_KEYS: List<String> = listOf(
        "style", "tileUrl", "tiles", "sprite", "glyphs", "boundary", "boundaries", "geojson", "attribution", "maxzoom", "bounds", "center",
        "mapStyleUrl", "geo",
    )
    val PROTOTYPE_KEYS: List<String> = listOf("__proto__", "constructor", "prototype")

    private val CORE_IDS = listOf(
        "label", "status", "rating", "money", "address", "street", "locality", "contactName", "contactPhone", "brokerId", "listingUrl", "notes",
        "location", "locationSource",
    )

    /** The house's own extras, which the apps and the server read by id: no kind a person adds may reuse one. */
    private val HOUSE_EXTRA_IDS = listOf(
        "bedrooms", "areaSqft", "floor", "deposit", "depositMonths", "maintenance", "maintenanceIncluded", "brokerage", "brokerageMonths",
        "lockInMonths", "noticeMonths", "availableFrom", "myOffer", "agreedPrice",
    )
    private val SECTION_IDS = listOf(
        "details", "cost", "rooms", "questions", "viewings", "moveIn", "areaNotes", "distances", "location", "walks", "checklist", "visits", "photos",
    )
    private val STATUS_IDS = listOf("NEW", "SHORTLISTED", "REJECTED", "TAKEN", "NOT_CHOSEN")
    private val CADENCES = listOf("once", "day", "week", "month", "quarter", "year")
    private val UNITS = listOf("count", "level", "sqft", "sqm", "cm", "m", "km", "minute", "day", "month", "year", "percent")
    private val TYPES = listOf("number", "money", "text", "enum", "date", "bool")
    private val BETTER = listOf("lower", "higher")
    private val SEEDS = listOf("docs/schemas/default-questions.json", "docs/schemas/kinds/school-questions.json")

    /** The related records a `search` may name besides the kind's own fields. */
    private val RELATED = listOf("rooms", "questions", "areaNotes", "moveIn")
    private val ROOT_KEYS = listOf(
        "format", "id", "version", "note", "labels", "words", "statuses", "sections", "core", "extras", "checklist", "questions", "search", "ai",
        "capabilities",
    )
    private val ROOT_REQUIRED = listOf("format", "id", "version", "labels", "statuses", "sections", "core", "extras", "checklist", "questions", "search", "ai")
    private val CORE_KEYS = listOf("id", "label", "section", "order", "visible", "forCadences", "was", "cadences")
    private val EXTRA_KEYS: Map<String, List<String>> = mapOf(
        "number" to listOf("integer", "min", "max", "unit", "better"), "money" to listOf("max", "better", "cadences", "cadenceFrom"),
        "text" to listOf("maxLength"), "enum" to listOf("options"), "date" to emptyList(), "bool" to emptyList(),
    )
    private val EXTRA_BASE = listOf("id", "type", "label", "section", "order", "visible", "forCadences", "was")
    private val AI_SLOTS = listOf("extract", "ask", "plan", "never")

    private val FIELD_ID = Regex("^[a-z][A-Za-z0-9]{0,39}$")
    private val CHECK_KEY = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val OPTION_ID = Regex("^[A-Z][A-Z0-9_]{0,31}$")

    /**
     * A character a person's words may not hold: markup and placeholder marks, controls, the zero-width space and the direction
     * marks, the line and paragraph separators and the overrides, the isolates, the BOM. The zero-width non-joiner and joiner
     * (U+200C, U+200D) stay: Hindi, Tamil and Telugu words need them.
     */
    private val FORBIDDEN_CHAR = Regex("[\\u0000-\\u001f\\u007f-\\u009f<>{}\\u200b\\u200e\\u200f\\u2028-\\u202e\\u2066-\\u2069\\ufeff]")
    private const val MAX_AMOUNT = 1_000_000_000_000L

    private class Stop(val fault: UserKindFault) : Exception()

    private fun fail(code: String, path: String): Nothing = throw Stop(UserKindFault(code, path))

    /** The size of [text] in UTF-8 bytes, counted without building the bytes (a lone surrogate counts as the three bytes of U+FFFD). */
    fun utf8Bytes(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            when {
                c < 0x80 -> n += 1
                c < 0x800 -> n += 2
                c in 0xd800..0xdbff && i + 1 < text.length && (text[i + 1].code and 0xfc00) == 0xdc00 -> {
                    n += 4
                    i++
                }
                else -> n += 3
            }
            i++
        }
        return n
    }

    /**
     * Reads a kind file a person chose, from its text: the checks in order, the first fault, or the file. [reserved] are the
     * ids this build has as built-in kinds (a `u_` id never is one; the list is a second lock).
     */
    fun read(text: String, reserved: List<String> = emptyList()): UserKindRead = try {
        if (utf8Bytes(text) > MAX_BYTES) fail("tooBig", "$")
        val body = text.removePrefix("﻿")
        JsonScan(body, MAX_DEPTH, MAX_VALUES, MAX_STRING).firstFault()?.let { fail(it.code, it.path) }
        val root = Json.parseToJsonElement(body)
        scanKeys(root, "$")
        val file = check(root, reserved)
        val capabilities = KindCapabilities.parse(file.json["capabilities"] ?: JsonObject(emptyMap())) ?: fail("capabilities", "$.capabilities")
        UserKindRead.Ok(file, capabilities)
    } catch (e: Stop) {
        UserKindRead.Refused(e.fault)
    } catch (_: Exception) {
        UserKindRead.Refused(UserKindFault("notJson", "$"))
    }

    /** Every key at every depth, in the file's order: no prototype key, no map key. */
    private fun scanKeys(n: JsonElement, path: String) {
        when (n) {
            is JsonArray -> n.forEachIndexed { i, item -> scanKeys(item, "$path[$i]") }
            is JsonObject -> for ((key, value) in n) {
                if (key in PROTOTYPE_KEYS) fail("prototypeKey", "$path.$key")
                if (key in FORBIDDEN_MAP_KEYS) fail("mapKey", "$path.$key")
                scanKeys(value, "$path.$key")
            }
            else -> Unit
        }
    }

    private fun obj(v: JsonElement?, path: String): JsonObject = v as? JsonObject ?: fail("type", path)

    private fun array(v: JsonElement?, path: String, min: Int, max: Int): JsonArray {
        val a = v as? JsonArray ?: fail("type", path)
        if (a.size < min || a.size > max) fail("limit", path)
        return a
    }

    private fun only(o: JsonObject, path: String, keys: List<String>, notAllowed: List<String> = emptyList()) {
        for (k in o.keys) {
            if (k in notAllowed) fail("notAllowed", "$path.$k")
            if (k !in keys) fail("unknownKey", "$path.$k")
        }
    }

    private fun required(o: JsonObject, path: String, keys: List<String>) {
        for (k in keys) if (k !in o) fail("required", "$path.$k")
    }

    private fun stringOf(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun str(o: JsonObject, key: String, path: String): String = stringOf(o[key]) ?: fail("type", path)

    private fun oneOf(o: JsonObject, key: String, path: String, allowed: List<String>): String =
        str(o, key, path).also { if (it !in allowed) fail("value", path) }

    private fun matching(o: JsonObject, key: String, path: String, pattern: Regex): String =
        str(o, key, path).also { if (!pattern.matches(it)) fail("value", path) }

    private fun bool(o: JsonObject, key: String, path: String): Boolean =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull ?: fail("type", path)

    /** A whole number as the website reads one (a JSON number whose value is whole: `3`, `3.0` and `3e0` alike), in [min]..[max]. */
    private fun integer(o: JsonObject, key: String, path: String, min: Long, max: Long): Long {
        val p = (o[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull && it.booleanOrNull == null } ?: fail("type", path)
        val d = p.content.toDoubleOrNull()?.takeIf { it.isFinite() && it == kotlin.math.floor(it) } ?: fail("type", path)
        if (d < min || d > max) fail("range", path)
        return d.toLong()
    }

    /** Whether [s] has a lone surrogate (half of a pair), which no reader can show. */
    private fun loneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            if (c in 0xd800..0xdbff) {
                if (i + 1 < s.length && (s[i + 1].code and 0xfc00) == 0xdc00) i++ else return true
            } else if (c in 0xdc00..0xdfff) {
                return true
            }
            i++
        }
        return false
    }

    /** The white space JavaScript's `trim` takes away (the website's rule): a person's words neither start nor end with it. */
    private fun jsSpace(c: Char): Boolean =
        c in "\t\n\u000B\u000C\r       　﻿" || c in ' '..' '

    /** A person's words: a string of 1 to [max] characters, trimmed, with none of the forbidden characters. */
    private fun words(v: JsonElement?, path: String, max: Int): String {
        val s = stringOf(v) ?: fail("type", path)
        if (s.isEmpty() || s.length > max) fail("textLength", path)
        if (jsSpace(s.first()) || jsSpace(s.last()) || FORBIDDEN_CHAR.containsMatchIn(s) || loneSurrogate(s)) fail("textChars", path)
        return s
    }

    /** A label of a kind a person adds: exactly `{ "text": … }`; a key label (`web`, `android`) is not theirs to give. */
    private fun label(v: JsonElement?, path: String): String {
        val o = obj(v, path)
        if ("web" in o || "android" in o || "byCadence" in o) fail("notAllowed", path)
        only(o, path, listOf("text"))
        required(o, path, listOf("text"))
        return words(o["text"], "$path.text", MAX_TEXT)
    }

    private fun cadenceList(v: JsonElement?, path: String): List<String> {
        val out = ArrayList<String>()
        array(v, path, 1, 6).forEachIndexed { i, c ->
            val s = stringOf(c)
            if (s == null || s !in CADENCES) fail("value", "$path[$i]")
            if (s in out) fail("repeated", "$path[$i]")
            out += s
        }
        return out
    }

    private class Placement(val section: String, val order: Int, val visible: Boolean?, val forCadences: List<String>?)

    private fun placement(o: JsonObject, p: String): Placement {
        required(o, p, listOf("section", "order"))
        val section = oneOf(o, "section", "$p.section", SECTION_IDS)
        val order = integer(o, "order", "$p.order", 0, 10_000).toInt()
        val visible = if ("visible" in o) bool(o, "visible", "$p.visible") else null
        val forCadences = if ("forCadences" in o) cadenceList(o["forCadences"], "$p.forCadences") else null
        return Placement(section, order, visible, forCadences)
    }

    private fun check(raw: JsonElement, reserved: List<String>): UserKindFile {
        val root = obj(raw, "$")
        only(root, "$", ROOT_KEYS, listOf("words"))
        required(root, "$", ROOT_REQUIRED)
        if (str(root, "format", "$.format") != "doorprints-kind/1") fail("format", "$.format")
        val id = str(root, "id", "$.id")
        if (!ID.matches(id)) fail("id", "$.id")
        if (id in reserved) fail("reserved", "$.id")
        val version = integer(root, "version", "$.version", 1, 1000).toInt()
        val note = if ("note" in root) words(root["note"], "$.note", MAX_NOTE) else null

        val l = obj(root["labels"], "$.labels")
        only(l, "$.labels", listOf("new", "many", "delete"))
        required(l, "$.labels", listOf("new", "many", "delete"))
        val new = label(l["new"], "$.labels.new")
        val many = label(l["many"], "$.labels.many")
        val delete = label(l["delete"], "$.labels.delete")

        val statuses = array(root["statuses"], "$.statuses", 5, 5).mapIndexed { i, s ->
            val p = "$.statuses[$i]"
            val o = obj(s, p)
            only(o, p, listOf("id", "label"))
            required(o, p, listOf("id", "label"))
            if (str(o, "id", "$p.id") != STATUS_IDS[i]) fail("value", "$p.id")
            STATUS_IDS[i] to label(o["label"], "$p.label")
        }

        val sectionIds = ArrayList<String>()
        val sections = array(root["sections"], "$.sections", 1, MAX_SECTIONS).mapIndexed { i, s ->
            val p = "$.sections[$i]"
            val o = obj(s, p)
            only(o, p, listOf("id", "label", "visible"))
            required(o, p, listOf("id", "label"))
            val sid = oneOf(o, "id", "$p.id", SECTION_IDS)
            if (sid in sectionIds) fail("repeated", "$p.id")
            sectionIds += sid
            val text = label(o["label"], "$p.label")
            UserKindFile.Section(sid, text, if ("visible" in o) bool(o, "visible", "$p.visible") else null)
        }

        val coreIds = ArrayList<String>()
        val core = array(root["core"], "$.core", 2, MAX_CORE).mapIndexed { i, c ->
            val p = "$.core[$i]"
            val o = obj(c, p)
            only(o, p, CORE_KEYS, listOf("was"))
            required(o, p, listOf("id", "label"))
            val cid = oneOf(o, "id", "$p.id", CORE_IDS)
            if (cid in coreIds) fail("repeated", "$p.id")
            coreIds += cid
            val text = label(o["label"], "$p.label")
            val at = placement(o, p)
            if ((cid == "money") != ("cadences" in o)) fail(if (cid == "money") "required" else "notAllowed", "$p.cadences")
            val cadences = if ("cadences" in o) {
                val seen = ArrayList<String>()
                array(o["cadences"], "$p.cadences", 1, 6).mapIndexed { j, x ->
                    val cp = "$p.cadences[$j]"
                    val co = obj(x, cp)
                    only(co, cp, listOf("id", "label"), listOf("was"))
                    required(co, cp, listOf("id", "label"))
                    val cad = oneOf(co, "id", "$cp.id", CADENCES)
                    if (cad in seen) fail("repeated", "$cp.id")
                    seen += cad
                    cad to label(co["label"], "$cp.label")
                }
            } else null
            UserKindFile.Core(cid, text, at.section, at.order, at.visible, at.forCadences, cadences)
        }
        if ("label" !in coreIds || "location" !in coreIds) fail("required", "$.core")

        val extraIds = ArrayList<String>()
        var optionsTotal = 0
        val extras = array(root["extras"], "$.extras", 0, MAX_EXTRAS).mapIndexed { i, e ->
            val p = "$.extras[$i]"
            val o = obj(e, p)
            required(o, p, listOf("id"))
            val eid = matching(o, "id", "$p.id", FIELD_ID)
            if (eid in CORE_IDS || eid in HOUSE_EXTRA_IDS) fail("reserved", "$p.id")
            if (eid in extraIds) fail("repeated", "$p.id")
            extraIds += eid
            required(o, p, listOf("type"))
            val type = oneOf(o, "type", "$p.type", TYPES)
            only(o, p, EXTRA_BASE + EXTRA_KEYS.getValue(type), listOf("was"))
            required(o, p, listOf("label"))
            val text = label(o["label"], "$p.label")
            val at = placement(o, p)
            val better = if ("better" in o) oneOf(o, "better", "$p.better", BETTER) else null
            val base = UserKindFile.Extra(eid, type, text, at.section, at.order, at.visible, at.forCadences, better = better)
            when (type) {
                "number" -> {
                    required(o, p, listOf("integer", "unit"))
                    val integer = bool(o, "integer", "$p.integer")
                    val unit = oneOf(o, "unit", "$p.unit", UNITS)
                    val min = if ("min" in o) integer(o, "min", "$p.min", -MAX_AMOUNT, MAX_AMOUNT) else null
                    val max = if ("max" in o) integer(o, "max", "$p.max", -MAX_AMOUNT, MAX_AMOUNT) else null
                    if (min != null && max != null && min > max) fail("range", "$p.min")
                    base.copy(integer = integer, unit = unit, min = min, max = max)
                }
                "money" -> {
                    required(o, p, listOf("max"))
                    val max = integer(o, "max", "$p.max", 1, MAX_AMOUNT)
                    if (("cadences" in o) == ("cadenceFrom" in o)) fail("value", "$p.cadenceFrom")
                    if ("cadences" in o) {
                        base.copy(max = max, cadences = cadenceList(o["cadences"], "$p.cadences"))
                    } else {
                        if (stringOf(o["cadenceFrom"]) != "money") fail("value", "$p.cadenceFrom")
                        base.copy(max = max, cadenceFrom = "money")
                    }
                }
                "text" -> {
                    required(o, p, listOf("maxLength"))
                    base.copy(maxLength = integer(o, "maxLength", "$p.maxLength", 1, 2000).toInt())
                }
                "enum" -> {
                    required(o, p, listOf("options"))
                    val seen = ArrayList<String>()
                    val options = array(o["options"], "$p.options", 2, MAX_OPTIONS).mapIndexed { j, x ->
                        val op = "$p.options[$j]"
                        val oo = obj(x, op)
                        only(oo, op, listOf("id", "label"))
                        required(oo, op, listOf("id", "label"))
                        val oid = matching(oo, "id", "$op.id", OPTION_ID)
                        if (oid in seen) fail("repeated", "$op.id")
                        seen += oid
                        oid to label(oo["label"], "$op.label")
                    }
                    optionsTotal += options.size
                    base.copy(options = options)
                }
                else -> base
            }
        }
        if (optionsTotal > MAX_OPTIONS_TOTAL) fail("limit", "$.extras")

        val checkKeys = ArrayList<String>()
        val checklist = array(root["checklist"], "$.checklist", 0, MAX_CHECKLIST).mapIndexed { i, c ->
            val p = "$.checklist[$i]"
            val o = obj(c, p)
            only(o, p, listOf("key", "label"))
            required(o, p, listOf("key", "label"))
            val key = matching(o, "key", "$p.key", CHECK_KEY)
            if (key in checkKeys) fail("repeated", "$p.key")
            checkKeys += key
            key to label(o["label"], "$p.label")
        }

        val q = obj(root["questions"], "$.questions")
        only(q, "$.questions", listOf("seed", "scopes"))
        required(q, "$.questions", listOf("seed", "scopes"))
        val seed = oneOf(q, "seed", "$.questions.seed", SEEDS)
        val scopes = array(q["scopes"], "$.questions.scopes", 1, 1).mapIndexed { i, s ->
            val p = "$.questions.scopes[$i]"
            val o = obj(s, p)
            only(o, p, listOf("id", "cadences"))
            required(o, p, listOf("id", "cadences"))
            if (str(o, "id", "$p.id") != "BOTH") fail("value", "$p.id")
            cadenceList(o["cadences"], "$p.cadences")
            "BOTH"
        }

        val searchable = coreIds + extraIds + RELATED
        val search = ArrayList<String>()
        array(root["search"], "$.search", 0, MAX_SEARCH).forEachIndexed { i, s ->
            val p = "$.search[$i]"
            val v = stringOf(s) ?: fail("type", p)
            if (!FIELD_ID.matches(v)) fail("value", p)
            if (v in search) fail("repeated", p)
            if (v !in searchable) fail("ref", p)
            search += v
        }

        val ai = obj(root["ai"], "$.ai")
        only(ai, "$.ai", AI_SLOTS, listOf("words"))
        required(ai, "$.ai", AI_SLOTS)
        for (slot in AI_SLOTS) {
            val list = ai[slot] as? JsonArray ?: fail("type", "$.ai.$slot")
            if (list.isNotEmpty()) fail("notAllowed", "$.ai.$slot")
        }

        if ("capabilities" in root) {
            val c = obj(root["capabilities"], "$.capabilities")
            if (KindCapabilities.parse(c) == null) fail("capabilities", "$.capabilities")
            if ((c["baseline"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true) fail("notAllowed", "$.capabilities.baseline")
            ((c["compare"] as? JsonObject)?.get("criteria") as? JsonArray)?.forEachIndexed { i, k ->
                val key = stringOf(k)
                if (key !in checkKeys && key !in extraIds) fail("ref", "$.capabilities.compare.criteria[$i]")
            }
            (c["filters"] as? JsonArray)?.forEachIndexed { i, f ->
                val e = extras.firstOrNull { it.id == stringOf(f) }
                if (e == null || (e.type != "enum" && e.type != "bool")) fail("ref", "$.capabilities.filters[$i]")
            }
        }

        return UserKindFile(
            id, version, note, new, many, delete, statuses, sections, core, extras, checklist, seed, scopes, search, root,
        )
    }
}
