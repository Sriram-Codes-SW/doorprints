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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * An amount of whole rupees and how often it is paid (docs/03 ADR-36 §18.4 door 7; S4b-BL-206). The candidate's own
 * money may have a cadence and no amount (a house marked as a rent before the rent is known) or an amount and no
 * cadence (a price whose type is *Not set*: owner decision of 2026-10-12, never read as a monthly rent), never neither;
 * a money extra always has both. On the wire, in Room (`money_amount`, `money_cadence`) and in a backup alike; a key
 * left out is unknown.
 */
@Serializable
data class Money(val amount: Long? = null, val cadence: String? = null)

/**
 * The house's values a candidate keeps in its extras ([Candidates.houseExtrasOf]): the three values, the cost and the
 * extras this app does not know ([other], kept and written back after the known ones).
 */
data class HouseExtras(
    val bedrooms: Int? = null,
    val areaSqft: Int? = null,
    val floor: Int? = null,
    val cost: HouseCost? = null,
    val other: JsonObject? = null,
)

/**
 * The candidate shape of the format reset (docs/03 ADR-36 §18.4 doors 3, 5 and 7; S4b-BL-206, kinds step 4): `kind`,
 * `money` and `extras` in place of the house's `price`, `priceType`, `bedrooms`, `areaSqft`, `floor` and `cost`. It is
 * what Room's `houses` table holds since database version 12, what `/api/candidates` sends and takes and what a Drive
 * `sync/1` file's `candidates` hold.
 *
 * The screens still read a house ([app.doorprints.data.HouseEntity] keeps its values), so this is the one place the
 * two shapes meet, rule for rule the twin of the website's `core/candidate.ts` and the server's `Money.of`,
 * `CandidateExtras.of` and its `HouseDto` readers. An extra id this app does not know is kept when it is a field id
 * with a value of an extras type (at most [MAX_OTHER_EXTRAS], texts of at most [MAX_EXTRA_TEXT] characters) and written
 * back after the known ones, so a newer app's field survives this phone (door 5).
 */
object Candidates {
    /** The only kind there is today (`docs/schemas/kinds/house.json`); one kind per data set (§18.1). */
    const val HOUSE_KIND = "house"

    /** The kind of a record that says none (door 3) and the default of a parameter that is given none: the baseline kind (`capabilities.baseline`; the one place that names it). */
    const val BASELINE_KIND = HOUSE_KIND

    /** The kind a record is of: its own, else the baseline kind's (a record from before kinds says none). */
    fun kindOf(kind: String?): String = kind ?: BASELINE_KIND

    /** At most this many extras this app does not know, on one candidate (the server's `MAX_OTHER`). */
    const val MAX_OTHER_EXTRAS = 30

    /** The longest text value of an extra this app does not know (the server's `MAX_TEXT`). */
    const val MAX_EXTRA_TEXT = 2000

    /** A kind id (door 3). */
    val KIND_PATTERN = Regex("^[a-z][a-z0-9_]{1,31}$")

    /** An extra id (the kind schema's `fieldId`, the server's `CandidateExtras.ID_PATTERN`). */
    val EXTRA_ID_PATTERN = Regex("^[a-z][A-Za-z0-9]{0,39}$")

    /** The cadence vocabulary of the kind schema (`docs/schemas/kinds/kind.schema.json`, `$defs.cadence`). */
    val CADENCES: List<String> = listOf("once", "day", "week", "month", "quarter", "year")

    /** The house kind's extra ids in the kind file's order: the three values, then the eleven cost fields. */
    val HOUSE_EXTRAS: List<String> = listOf(
        "bedrooms", "areaSqft", "floor", "deposit", "depositMonths", "maintenance", "maintenanceIncluded", "brokerage",
        "brokerageMonths", "lockInMonths", "noticeMonths", "availableFrom", "myOffer", "agreedPrice",
    )

    /** The house kind's money extras and the cadence each has (`null`: as often as the candidate's own money). */
    private val MONEY_EXTRAS: Map<String, String?> =
        mapOf("deposit" to "once", "maintenance" to "month", "brokerage" to "once", "myOffer" to null, "agreedPrice" to null)

    /** `RENT` is `month`, `SALE` is `once` (door 7); anything else is unknown. */
    fun cadenceOf(priceType: String?): String? = when (priceType) {
        "RENT" -> "month"
        "SALE" -> "once"
        else -> null
    }

    /** `month` is `RENT`, `once` is `SALE`; another cadence is not a house's and reads as no type. */
    fun priceTypeOf(cadence: String?): String? = when (cadence) {
        "month" -> "RENT"
        "once" -> "SALE"
        else -> null
    }

    /**
     * The house's money from its two values (the server's `Money.of`): null when neither is known; a price without a
     * type keeps no cadence (owner, 2026-10-12: a 1.2 crore price of type *Not set* is never "per month").
     */
    fun moneyOf(price: Long?, priceType: String?): Money? = moneyOrNull(price?.takeIf(::isRupees), cadenceOf(priceType))

    /** [money] as read (untrusted): an amount of whole rupees and a cadence of the vocabulary, each when valid; null when neither is left. */
    fun moneyValue(money: Money?): Money? =
        money?.let { moneyOrNull(it.amount?.takeIf(::isRupees), it.cadence?.takeIf { c -> c in CADENCES }) }

    /**
     * The house's extras in the kind file's order, money with its cadence (the deposit and brokerage `once`,
     * maintenance `month`, the two offers as often as [ownCadence], or `month` when it has none), then the extras this
     * app does not know by id; null when there are none (never `{}`).
     */
    fun extrasOf(bedrooms: Int?, areaSqft: Int?, floor: Int?, cost: HouseCost?, ownCadence: String?, other: JsonObject?): JsonObject? {
        val c = cost ?: HouseCost()
        val values: Map<String, Any?> = mapOf(
            "bedrooms" to bedrooms, "areaSqft" to areaSqft, "floor" to floor, "deposit" to c.deposit,
            "depositMonths" to c.depositMonths, "maintenance" to c.maintenance, "maintenanceIncluded" to c.maintenanceIncluded,
            "brokerage" to c.brokerage, "brokerageMonths" to c.brokerageMonths, "lockInMonths" to c.lockInMonths,
            "noticeMonths" to c.noticeMonths, "availableFrom" to c.availableFrom, "myOffer" to c.myOffer,
            "agreedPrice" to c.agreedPrice,
        )
        val out = LinkedHashMap<String, JsonElement>()
        for (id in HOUSE_EXTRAS) {
            val value = values[id] ?: continue
            out[id] = when {
                id in MONEY_EXTRAS -> JsonObject(
                    mapOf(
                        "amount" to JsonPrimitive(value as Long),
                        "cadence" to JsonPrimitive(MONEY_EXTRAS[id] ?: ownCadence ?: "month"),
                    ),
                )
                value is Boolean -> JsonPrimitive(value)
                value is String -> JsonPrimitive(value)
                else -> JsonPrimitive(value as Number)
            }
        }
        cleanOtherExtras(other)?.let(out::putAll)
        return if (out.isEmpty()) null else JsonObject(out)
    }

    /**
     * The house's values out of a candidate's [extras] (untrusted: the server, a file, a stored row). A known extra
     * with a value of the wrong type is unknown (absent), as every coerced value; the ranges are the reader's to check.
     * A money extra's amount is kept whatever its cadence, so the next write does not lose it (it is written back with
     * the field's own cadence). An unknown extra is kept in [HouseExtras.other] by [cleanOtherExtras].
     */
    fun houseExtrasOf(extras: JsonObject?): HouseExtras {
        if (extras == null) return HouseExtras()
        fun int(id: String): Int? = extras[id]?.let(::wholeNumber)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        fun amount(id: String): Long? = (extras[id] as? JsonObject)?.let { m -> m["amount"]?.let(::wholeNumber)?.takeIf(::isRupees) }
        val cost = HouseCost(
            deposit = amount("deposit"),
            depositMonths = int("depositMonths"),
            maintenance = amount("maintenance"),
            maintenanceIncluded = (extras["maintenanceIncluded"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            brokerage = amount("brokerage"),
            brokerageMonths = int("brokerageMonths"),
            lockInMonths = int("lockInMonths"),
            noticeMonths = int("noticeMonths"),
            availableFrom = (extras["availableFrom"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            myOffer = amount("myOffer"),
            agreedPrice = amount("agreedPrice"),
        ).orNull()
        return HouseExtras(int("bedrooms"), int("areaSqft"), int("floor"), cost, cleanOtherExtras(extras))
    }

    /**
     * The extras this app does not know, as kept: a field id that is not one of the house kind's, with a value of an
     * extras type ([isExtraValue]); at most [MAX_OTHER_EXTRAS], the first by id (code-unit order, as the website sorts).
     * Null when none is left.
     */
    fun cleanOtherExtras(raw: JsonObject?): JsonObject? {
        if (raw == null) return null
        val out = LinkedHashMap<String, JsonElement>()
        for (id in raw.keys.filter { EXTRA_ID_PATTERN.matches(it) && it !in HOUSE_EXTRAS }.sorted()) {
            if (out.size >= MAX_OTHER_EXTRAS) break
            val value = raw.getValue(id)
            if (isExtraValue(value)) out[id] = value
        }
        return if (out.isEmpty()) null else JsonObject(out)
    }

    /**
     * A value of one of the six extras types as the wire spells it (door 5): a finite number, a text of at most
     * [MAX_EXTRA_TEXT] characters (also an enum option id or a date), a boolean, or a money object with exactly an
     * amount of whole rupees and a cadence of the vocabulary.
     */
    fun isExtraValue(value: JsonElement): Boolean = when (value) {
        is JsonNull -> false
        is JsonPrimitive -> when {
            value.isString -> value.content.length <= MAX_EXTRA_TEXT
            value.booleanOrNull != null -> true
            else -> value.doubleOrNull?.isFinite() == true
        }
        is JsonObject -> value.size == 2 && value["amount"]?.let(::wholeNumber)?.let(::isRupees) == true &&
            (value["cadence"] as? JsonPrimitive)?.takeIf { it.isString }?.content in CADENCES
        else -> false
    }

    private fun moneyOrNull(amount: Long?, cadence: String?): Money? =
        if (amount == null && cadence == null) null else Money(amount, cadence)

    private fun isRupees(amount: Long): Boolean = amount in 0..HouseCost.MAX_RUPEES

    /** A JSON number that is a whole number written as one (not a string, a fraction or an exponent), or null. */
    private fun wholeNumber(value: JsonElement): Long? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull || p.content.contains('.') || p.content.contains('e', ignoreCase = true)) return null
        return p.longOrNull
    }
}
