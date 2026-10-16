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

package app.doorprints.shared.export

import app.doorprints.shared.model.KindSettingRules
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The backup family after the format reset (docs/03 ADR-36 §18.4 doors 1 and 2; S4b-BL-206, kinds step 3;
 * docs/schemas/README.md §1.1): `doorprints-backup/1` with `search` and `candidates`, each with `kind`, `money` and
 * `extras`, and `candidateId` on visits, photos and viewings. The twin of the website's `backup-family.ts` and the
 * server's `BackupWire`, rule for rule.
 *
 * The phone's model is still the house of the family before ([BackupData], [ExportHouse]; the Room migration is a later
 * step), so this is the one place the two meet: every writer encodes its rows as before and [encodeData] turns them into
 * the new family; every reader decodes through [decodeData], which reads a file of the new family (it has `candidates`)
 * back as the family before after the new family's own checks, and **reads a file of the family before as it always
 * did**, so an older Doorprints' backup still imports. The two `/1` are told apart by their shape, not their number.
 * An extra this phone does not know is checked and kept (S4b-BL-248): read into [ExportHouse.extrasOther] and written
 * back after the known extras, by id, as the website and the server do.
 */
object BackupWire {
    /** The format id of the new family: one so far, for a full copy and an update file alike. */
    const val CURRENT_FORMAT = "doorprints-backup/1"

    /** What a newer number of the new family reads as, for the reader of rows: newer than any it knows. */
    const val NEWER_FORMAT = "doorprints-backup/999"

    private const val FAMILY = "doorprints-backup/"
    private val KIND = Regex("^[a-z][a-z0-9_]{1,31}$")
    private val EXTRA_ID = Regex("^[a-z][A-Za-z0-9]{0,39}$")
    private const val MAX_OTHER = 30
    private const val MAX_TEXT = 2000
    private const val MAX_AMOUNT = 1_000_000_000_000L
    private val CADENCES = listOf("once", "day", "week", "month", "quarter", "year")
    private val VALUES = listOf("bedrooms", "areaSqft", "floor")
    private val COST = listOf(
        "deposit", "depositMonths", "maintenance", "maintenanceIncluded", "brokerage", "brokerageMonths", "lockInMonths",
        "noticeMonths", "availableFrom", "myOffer", "agreedPrice",
    )
    /** The house kind's money extras and the cadence each must have (`null`: as often as the candidate's money). */
    private val MONEY = mapOf("deposit" to "once", "maintenance" to "month", "brokerage" to "once", "myOffer" to null, "agreedPrice" to null)
    /** A candidate's keys in the contract's order (`backup-sample.json`); a key not listed follows them, as it came. */
    private val CANDIDATE_ORDER = listOf(
        "id", "kind", "label", "address", "street", "locality", "lat", "lon", "status", "money", "rating", "contactName",
        "contactPhone", "listingUrl", "notes", "locationSource", "extras", "rooms", "answers", "moveIn", "brokerId",
        "checklist", "createdAt", "updatedAt",
    )
    /** A house's keys in the family before, in its order (`backup-sample-houses.json`). */
    private val HOUSE_ORDER = listOf(
        "id", "kind", "label", "address", "street", "locality", "lat", "lon", "status", "price", "priceType", "bedrooms",
        "rating", "contactName", "contactPhone", "listingUrl", "notes", "areaSqft", "locationSource", "cost", "rooms",
        "answers", "moveIn", "floor", "brokerId", "checklist", "createdAt", "updatedAt", "extrasOther",
    )
    private val MOVED = setOf("price", "priceType", "bedrooms", "areaSqft", "floor", "cost", "extrasOther", "kind")
    private val ROWS_WITH_HOUSE = setOf("visits", "photos", "viewings")

    /**
     * `data.json` as the file holds it: the rows of [data] in the new family. [kinds] and [default] are the kinds the phone
     * has on and its default (kinds PR C, S4b-BL-274): the `search` object names them, and is the house's
     * `{"kind":"house"}` for the house alone, byte for byte the file before.
     */
    fun encodeData(data: BackupData, kinds: List<String> = listOf("house"), default: String = "house"): String =
        currentOf(BackupFormat.json.encodeToJsonElement(BackupData.serializer(), data) as JsonObject, kinds, default).toString()

    /** `manifest.json` as the file holds it: `/1`, and `candidates` in place of `houses` in its counts. */
    fun encodeManifest(manifest: BackupManifest): String {
        val m = BackupFormat.json.encodeToJsonElement(BackupManifest.serializer(), manifest) as JsonObject
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in m) {
            out[k] = when (k) {
                "format" -> JsonPrimitive(CURRENT_FORMAT)
                "counts" -> rename(v as JsonObject, "houses", "candidates")
                else -> v
            }
        }
        return JsonObject(out).toString()
    }

    /** A `data.json` of either family; throws a [SerializationException] for a broken one (the reader's BROKEN_DATA). */
    fun decodeData(text: String): BackupData {
        val root = BackupFormat.json.parseToJsonElement(text)
        return decodeData(root as? JsonObject ?: throw SerializationException("data.json is not an object"))
    }

    /** [decodeData] of a parsed root. */
    fun decodeData(root: JsonObject): BackupData =
        BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), if ("candidates" in root) legacyOf(root) else root)

    /**
     * The kinds and the default a data.json's `search` object names, as a hint for a phone that never chose (kinds PR C,
     * S4b-BL-274), or null: the house's `{"kind":"house"}`, a first-version `{"kind":...}`, a value of the wrong type and a
     * list that is not well formed name none (`KindSettingRules.spacesOf`). Never a reason to refuse a file.
     */
    fun spacesOf(root: JsonObject): Pair<List<String>, String>? {
        val search = root["search"] as? JsonObject ?: return null
        val kinds = (search["kinds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        val default = (search["defaultKind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return KindSettingRules.spacesOf(kinds, default)
    }

    /** A manifest of either family: `candidates` counted as `houses`. */
    fun decodeManifest(text: String): BackupManifest {
        val m = BackupFormat.json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("manifest")
        val counts = m["counts"] as? JsonObject
        val legacy = if (counts != null && "houses" !in counts && "candidates" in counts) {
            JsonObject(m + ("counts" to rename(counts, "candidates", "houses")))
        } else m
        return BackupFormat.json.decodeFromJsonElement(BackupManifest.serializer(), legacy)
    }

    // ---- Writing ----

    /** A `data.json` of the family before as the new family writes it. */
    fun currentOf(legacy: JsonObject, kinds: List<String> = listOf("house"), default: String = "house"): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in legacy) {
            when (key) {
                "format" -> out["format"] = JsonPrimitive(CURRENT_FORMAT)
                "exportedAt" -> {
                    out["exportedAt"] = value
                    out["search"] = BackupFormat.json.parseToJsonElement(KindSettingRules.searchOf(kinds, default))
                }
                "houses" -> out["candidates"] = JsonArray(rows(value).map(::candidateOf))
                in ROWS_WITH_HOUSE -> out[key] = JsonArray(rows(value).map { rename(it, "houseId", "candidateId") })
                "deleted" -> out[key] = JsonArray(
                    rows(value).map { d ->
                        if (d.str("kind") == "house") JsonObject(d + ("kind" to JsonPrimitive("candidate"))) else d
                    },
                )
                else -> out[key] = value
            }
        }
        return JsonObject(out)
    }

    /**
     * A house row of the family before (`price`, `priceType`, `bedrooms`, `areaSqft`, `floor`, `cost`) as the candidate
     * it is, its other keys kept after the candidate's: also how a Drive sync file from before the reset is read
     * (`DriveSyncBackend`, kinds step 4).
     */
    internal fun candidateOfHouse(h: JsonObject): JsonObject = candidateOf(h)

    private fun candidateOf(h: JsonObject): JsonObject {
        val money = moneyOf(h["price"], h.str("priceType"))
        val out = LinkedHashMap<String, JsonElement>()
        out["kind"] = h["kind"]?.takeIf { it is JsonPrimitive && it.isString } ?: JsonPrimitive("house")
        if (money != null) out["money"] = money
        extrasOf(h, money?.get("cadence")?.let { (it as JsonPrimitive).content } ?: "month")?.let { out["extras"] = it }
        for ((key, value) in h) if (key !in MOVED) out[key] = value
        return ordered(out, CANDIDATE_ORDER)
    }

    /** [row] with the keys of [order] first, in that order, then the others as they came. */
    private fun ordered(row: Map<String, JsonElement>, order: List<String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (key in order) row[key]?.let { out[key] = it }
        for ((key, value) in row) if (key !in out) out[key] = value
        return JsonObject(out)
    }

    /** The server's `Money.of`: absent when neither is known; a price without a type keeps no cadence (owner, 2026-10-12). */
    private fun moneyOf(price: JsonElement?, priceType: String?): JsonObject? {
        val cadence = when (priceType) { "RENT" -> "month"; "SALE" -> "once"; else -> null }
        val amount = (price as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }
        if (amount == null && cadence == null) return null
        val out = LinkedHashMap<String, JsonElement>()
        if (amount != null) out["amount"] = amount
        if (cadence != null) out["cadence"] = JsonPrimitive(cadence)
        return JsonObject(out)
    }

    private fun extrasOf(h: JsonObject, ownCadence: String): JsonObject? {
        val cost = h["cost"] as? JsonObject ?: JsonObject(emptyMap())
        val out = LinkedHashMap<String, JsonElement>()
        for (id in VALUES + COST) {
            val value = (if (id in VALUES) h[id] else cost[id]) ?: continue
            if (value is JsonNull) continue
            out[id] = if (id in MONEY) {
                JsonObject(mapOf("amount" to value, "cadence" to JsonPrimitive(MONEY[id] ?: ownCadence)))
            } else value
        }
        (h["extrasOther"] as? JsonObject)?.let { other -> for (id in other.keys.sorted()) out[id] = other.getValue(id) }
        return if (out.isEmpty()) null else JsonObject(out)
    }

    // ---- Reading ----

    /**
     * A `data.json` of the new family as the family before holds it, after the new family's own checks (a kind id,
     * money of whole rupees with a cadence of the vocabulary, a house's `month` or `once`, a money extra's amount and
     * cadence, an unknown extra as a field id with a value of an extras type and at most 30 of them); a broken one throws.
     * A well-formed file with a cadence this reader does not support (one it does not know, or one a house's money or a
     * fixed money extra may not have) reads as a newer file ([NEWER_FORMAT]): "update the app", not broken data.
     */
    fun legacyOf(root: JsonObject): JsonObject = try {
        legacyRows(root)
    } catch (_: Newer) {
        JsonObject(
            mapOf(
                "format" to JsonPrimitive(NEWER_FORMAT), "exportedAt" to (root["exportedAt"] ?: JsonNull),
                "houses" to JsonArray(emptyList()), "visits" to JsonArray(emptyList()), "photos" to JsonArray(emptyList()),
            ),
        )
    }

    /** A well-formed value this reader does not support: the file is a newer app's. */
    private class Newer : Exception()

    private fun legacyRows(root: JsonObject): JsonObject {
        if ("houses" in root) broken("candidates and houses")
        val format = root.str("format")
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in root) {
            when {
                key == "format" -> out[key] = when {
                    format == CURRENT_FORMAT -> JsonPrimitive(if (root["deleted"].let { it != null && it !is JsonNull }) "doorprints-backup/3" else "doorprints-backup/2")
                    format != null && format.startsWith(FAMILY) -> JsonPrimitive(NEWER_FORMAT)
                    else -> value
                }
                key == "search" -> Unit
                key == "candidates" -> out["houses"] = when (value) {
                    is JsonNull -> value
                    is JsonArray -> JsonArray(value.map(::houseOf))
                    else -> broken("candidates")
                }
                key in ROWS_WITH_HOUSE && value is JsonArray ->
                    out[key] = JsonArray(value.map { if (it is JsonObject) rename(it, "candidateId", "houseId") else it })
                key == "deleted" && value is JsonArray -> out[key] = JsonArray(
                    value.map { d ->
                        if (d is JsonObject && d.str("kind") == "candidate") JsonObject(d + ("kind" to JsonPrimitive("house"))) else d
                    },
                )
                else -> out[key] = value
            }
        }
        return JsonObject(out)
    }

    private fun houseOf(c: JsonElement): JsonObject {
        if (c !is JsonObject) broken("candidate")
        val kind = c.str("kind")
        if (kind == null || !KIND.matches(kind)) broken("kind")
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in c) {
            when (key) {
                "kind" -> if (kind != "house") out["kind"] = value
                "money" -> out.putAll(legacyMoney(value, kind))
                "extras" -> out.putAll(legacyExtras(value))
                else -> out[key] = value
            }
        }
        return ordered(out, HOUSE_ORDER)
    }

    private fun legacyMoney(value: JsonElement, kind: String): Map<String, JsonElement> {
        if (value is JsonNull) return emptyMap()
        val (amount, cadence) = money(value, amountRequired = false, cadenceRequired = false)
        if (kind == "house" && cadence != null && cadence != "month" && cadence != "once") throw Newer()
        val out = LinkedHashMap<String, JsonElement>()
        if (amount != null) out["price"] = JsonPrimitive(amount)
        when (cadence) {
            "month" -> out["priceType"] = JsonPrimitive("RENT")
            "once" -> out["priceType"] = JsonPrimitive("SALE")
        }
        return out
    }

    private fun legacyExtras(value: JsonElement): Map<String, JsonElement> {
        if (value is JsonNull) return emptyMap()
        if (value !is JsonObject) broken("extras")
        val out = LinkedHashMap<String, JsonElement>()
        val cost = LinkedHashMap<String, JsonElement>()
        val other = LinkedHashMap<String, JsonElement>()
        for ((id, v) in value) {
            when {
                id in VALUES -> out[id] = v
                id in MONEY -> {
                    if (v is JsonNull) continue
                    val (amount, cadence) = money(v, amountRequired = true, cadenceRequired = true)
                    val fixed = MONEY[id]
                    if (fixed != null && cadence != fixed) throw Newer()
                    cost[id] = JsonPrimitive(amount)
                }
                id in COST -> cost[id] = v
                else -> {
                    if (!EXTRA_ID.matches(id) || !isExtraValue(v)) broken("extras.$id")
                    other[id] = v
                }
            }
        }
        if (other.size > MAX_OTHER) broken("extras")
        if (cost.isNotEmpty()) out["cost"] = JsonObject(cost)
        // A newer app's extras, kept (S4b-BL-248): `ExportHouse.extrasOther`, written back by [extrasOf].
        if (other.isNotEmpty()) out["extrasOther"] = JsonObject(other)
        return out
    }

    /**
     * A money object: an amount of whole rupees and a cadence (a text; one this reader does not know is a newer file's),
     * each required for a money extra, at least one of the two for the candidate's own money.
     */
    private fun money(value: JsonElement, amountRequired: Boolean, cadenceRequired: Boolean): Pair<Long?, String?> {
        if (value !is JsonObject) broken("money")
        val rawCadence = value["cadence"]?.takeIf { it !is JsonNull }
        val cadence = rawCadence?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: broken("money.cadence") }
        if (cadence == null && cadenceRequired) broken("money.cadence")
        val raw = value["amount"]?.takeIf { it !is JsonNull }
        val amount = raw?.let { amountOf(it) ?: broken("money.amount") }
        if (amount == null && (amountRequired || cadence == null)) broken("money.amount")
        if (cadence != null && cadence !in CADENCES) throw Newer()
        return amount to cadence
    }

    /** A value of one of the six extras types as the wire spells it (door 5). */
    private fun isExtraValue(v: JsonElement): Boolean = when (v) {
        is JsonNull -> false
        is JsonPrimitive -> when {
            v.isString -> v.content.length <= MAX_TEXT
            v.booleanOrNull != null -> true
            else -> v.doubleOrNull?.isFinite() == true
        }
        is JsonObject -> v.size == 2 && v["amount"]?.let(::amountOf) != null && v.str("cadence") in CADENCES
        is JsonArray -> false
    }

    /** A whole number of rupees in range, from a JSON number (not a string, not a fraction). */
    private fun amountOf(v: JsonElement): Long? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        val n = p.longOrNull ?: return null
        return n.takeIf { it in 0..MAX_AMOUNT && !p.content.contains('.') && !p.content.contains('e', ignoreCase = true) }
    }

    private fun rows(v: JsonElement): List<JsonObject> = (v as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

    private fun rename(row: JsonObject, from: String, to: String): JsonObject =
        JsonObject(row.entries.associate { (k, v) -> (if (k == from) to else k) to v })

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun broken(why: String): Nothing = throw SerializationException("not a backup of the format: $why")
}
