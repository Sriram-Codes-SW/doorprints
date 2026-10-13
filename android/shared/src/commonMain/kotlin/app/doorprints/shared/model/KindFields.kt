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

/**
 * The control a typed extra is drawn with, the same on both stacks (`docs/schemas/kinds/kind-field-vectors.json`): an
 * integer field, a level (a floor: the field holds the level, a Basement switch its sign), whole rupees, a switch, a
 * calendar date, a line of text or a choice of options (text and enum since the schools kind, S4b-BL-209).
 */
enum class ExtraControl { NUMBER, LEVEL, MONEY, BOOL, DATE, TEXT, ENUM }

/** One option of an enum extra: its id (`UPPER_SNAKE`, what the candidate stores) and the Compose string it shows. */
data class KindOption(val id: String, val label: String)

/** One typed extra of a kind, as the kind file places it (`extras` of `docs/schemas/kinds/<kind>.json`). */
data class KindExtra(
    val id: String,
    /** `number`, `money`, `text`, `enum`, `date` or `bool` (door 5). */
    val type: String,
    val section: String,
    val order: Int,
    val integer: Boolean = false,
    val min: Long? = null,
    val max: Long? = null,
    val unit: String? = null,
    val cadences: List<String>? = null,
    val cadenceFrom: String? = null,
    /** Shown only while the candidate's money has one of these cadences, or none. */
    val forCadences: List<String>? = null,
    /**
     * Where the value lives in the backup family before the reset; `null` for a kind that was never stored there (the
     * schools kind, S4b-BL-209), whose values are the candidate's extras the house model does not know.
     */
    val was: String? = null,
    /** A text's most characters. */
    val maxLength: Int? = null,
    /** An enum's options, in the kind file's order. */
    val options: List<KindOption> = emptyList(),
) {
    val control: ExtraControl
        get() = when (type) {
            "number" -> if (unit == "level") ExtraControl.LEVEL else ExtraControl.NUMBER
            "money" -> ExtraControl.MONEY
            "bool" -> ExtraControl.BOOL
            "date" -> ExtraControl.DATE
            "text" -> ExtraControl.TEXT
            "enum" -> ExtraControl.ENUM
            else -> error("no control for the $type extra $id")
        }
}

/**
 * A kind's typed extras and where they go (kinds PR 4, S4b-BL-208; docs/03 ADR-36 §18.8), the twin of the website's
 * `core/kind-fields.ts`: the renderer of the phone's form (`:ui` `KindExtrasFields`) asks it for a section's extras for
 * the candidate's money cadence, in the kind file's order, labelled through [KindLabels]. It holds a copy of the kind
 * file's `extras`, as [KindLabels] holds its labels; `KindFieldsVectorsTest` reads `house.json`, so it cannot drift.
 */
class KindFields internal constructor(
    val labels: KindLabels,
    val extras: List<KindExtra>,
    /** What the list search reads (the kind file's `search`: core fields, related records and extra ids). */
    val search: List<String> = emptyList(),
) {
    val kind: String get() = labels.kind

    /**
     * The extras search and the readable copies read through the kind (kinds PR 6, S4b-BL-210): those with no `was`, the
     * candidate's other extras, in the kind file's order; with [searched], only those [search] names. A house has none:
     * its extras are written by hand in search (the floor) and in every copy, so its words and copies stay as they were.
     */
    fun others(searched: Boolean = false): List<KindExtra> = extras.filter { it.was == null && (!searched || it.id in search) }

    /** The extras of [section] shown for a candidate whose money has [cadence], by `order`. */
    fun shown(section: String, cadence: String?): List<KindExtra> = extras
        .filter { it.section == section && labels.isVisible("field.${it.id}") }
        .filter { cadence == null || it.forCadences == null || cadence in it.forCadences }
        .sortedBy { it.order }

    /** The Compose string name an extra is labelled with (the kind labels every field it has). */
    fun label(extra: KindExtra): String = checkNotNull(labels.field(extra.id)?.key) { "$kind labels no ${extra.id}" }

    companion object {
        private const val MONEY_MAX = HouseCost.MAX_RUPEES
        private val RENT_ONLY = listOf("month")
        private fun months(id: String, order: Int, forCadences: List<String>? = null) = KindExtra(
            id, "number", "cost", order, integer = true, min = 0, max = HouseCost.MAX_MONTHS.toLong(), unit = "month",
            forCadences = forCadences, was = "cost.$id",
        )
        private fun money(id: String, order: Int, cadences: List<String>? = null, forCadences: List<String>? = null) = KindExtra(
            id, "money", "cost", order, max = MONEY_MAX, cadences = cadences, cadenceFrom = if (cadences == null) "money" else null,
            forCadences = forCadences, was = "cost.$id",
        )

        /** Houses (`docs/schemas/kinds/house.json`, `extras`). */
        val HOUSE: KindFields = KindFields(
            KindLabels.HOUSE,
            listOf(
                KindExtra("bedrooms", "number", "details", 50, integer = true, min = 0, unit = "count", was = "bedrooms"),
                KindExtra("areaSqft", "number", "details", 60, integer = true, min = 1, max = HouseValues.MAX_AREA_SQFT.toLong(), unit = "sqft", was = "areaSqft"),
                KindExtra("floor", "number", "details", 70, integer = true, min = HouseValues.MIN_FLOOR.toLong(), max = HouseValues.MAX_FLOOR.toLong(), unit = "level", was = "floor"),
                money("deposit", 10, cadences = listOf("once"), forCadences = RENT_ONLY),
                months("depositMonths", 20, RENT_ONLY),
                money("maintenance", 30, cadences = listOf("month"), forCadences = RENT_ONLY),
                KindExtra("maintenanceIncluded", "bool", "cost", 40, forCadences = RENT_ONLY, was = "cost.maintenanceIncluded"),
                money("brokerage", 50, cadences = listOf("once")),
                months("brokerageMonths", 60),
                months("lockInMonths", 70, RENT_ONLY),
                months("noticeMonths", 80, RENT_ONLY),
                KindExtra("availableFrom", "date", "cost", 90, was = "cost.availableFrom"),
                money("myOffer", 100),
                money("agreedPrice", 110),
            ),
            listOf("label", "address", "street", "locality", "notes", "contactName", "brokerId", "rooms", "questions", "areaNotes", "moveIn", "floor"),
        )

        /**
         * The built-in kind with this id, `null` for one this build does not have. The schools kind is not here: it is
         * reached only through the developer flag ([DevKinds], S4b-BL-209).
         */
        fun of(kind: String): KindFields? = if (kind == HOUSE.kind) HOUSE else null
    }
}
