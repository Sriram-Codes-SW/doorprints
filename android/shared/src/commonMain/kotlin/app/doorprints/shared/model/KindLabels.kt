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
 * One place a kind labels: the Compose string's name in `:ui` (`null`: the phone shows no text there, as the form's
 * *Details* heading) and whether the kind shows the place at all.
 */
data class KindLabel(val key: String?, val visible: Boolean)

/**
 * The label and visibility resolver of a kind (kinds PR 3, S4b-BL-207; docs/03 ADR-36 §18.7): which string a screen
 * shows for a place of the candidate, and whether the kind shows that place. A place is a ref, as
 * `docs/schemas/kinds/kind-vectors.json` names them: `kind.new`, `status.NEW`, `section.cost`, `field.money`,
 * `cadence.month`, `check.water`. A money cadence is an argument: `field("money", "month")` is the rent's label.
 *
 * The Android keys of the built-in kind file `docs/schemas/kinds/house.json`, copied here as the default questions are
 * (`KindLabelsVectorsTest` reads that file and `kind-label-vectors.json`, so the copy cannot drift); the iPhone app reads
 * the same object through `:ui`. The website's twin is `web/src/app/core/kind-labels.ts`. A ref the kind does not have
 * resolves to `null`, and the caller shows nothing. Labels that are templates (*Visits (%1$d)*) take the arguments the
 * screens pass today.
 */
class KindLabels internal constructor(
    /** The kind id (`house`). */
    val kind: String,
    /** Every ref of the kind and its key (`null`: no text on the phone). */
    private val keys: Map<String, String?>,
    /** `field.<id>@<cadence>` to the key a field shows while the candidate's money has that cadence. */
    private val byCadence: Map<String, String>,
    /** Each field's section id. */
    private val sections: Map<String, String>,
    /** The refs the kind keeps but hides (`visible: false` in the kind file). */
    private val hidden: Set<String> = emptySet(),
) {
    /** Every ref the kind labels (its copy of the kind file is checked against the file by this). */
    internal val refs: Set<String> get() = keys.keys

    /** The label of [ref], the [cadence]'s one where the kind has it; `null` when the kind has no such place. */
    fun resolve(ref: String, cadence: String? = null): KindLabel? {
        if (ref !in keys) return null
        val key = cadence?.let { byCadence["$ref@$it"] } ?: keys[ref]
        return KindLabel(key, isVisible(ref))
    }

    /** Whether the kind shows [ref]: a place it has and does not hide, and for a field, in a section it shows. */
    fun isVisible(ref: String): Boolean {
        if (ref !in keys || ref in hidden) return false
        val section = if (ref.startsWith(FIELD)) sections[ref.removePrefix(FIELD)] else null
        return section == null || isVisible("section.$section")
    }

    /** A field's label (`field.<id>`), with the money [cadence] when the screen knows it. */
    fun field(id: String, cadence: String? = null): KindLabel? = resolve(FIELD + id, cadence)

    /** A section's heading (`section.<id>`). */
    fun section(id: String): KindLabel? = resolve("section.$id")

    /** A status's label: every kind labels the five fixed names (door 8), so this always has a key. */
    fun status(status: HouseStatus): String = checkNotNull(keys["status.${status.name}"]) { "$kind labels no ${status.name}" }

    /** A built-in checklist criterion's label, `null` for a key the kind does not have. */
    fun check(key: String): String? = keys["check.$key"]

    companion object {
        private const val FIELD = "field."

        /** Houses, the first and so far only kind (`docs/schemas/kinds/house.json`, Android keys). */
        val HOUSE: KindLabels = KindLabels(
            kind = "house",
            keys = mapOf(
                "kind.new" to "house_new_title", "kind.many" to "houses_title", "kind.delete" to "house_delete",
                "status.NEW" to "status_NEW", "status.SHORTLISTED" to "status_SHORTLISTED",
                "status.REJECTED" to "status_REJECTED", "status.TAKEN" to "status_TAKEN",
                "status.NOT_CHOSEN" to "status_NOT_CHOSEN",
                "section.details" to null, "section.cost" to "house_cost", "section.rooms" to "house_rooms",
                "section.questions" to "house_questions", "section.viewings" to "viewings_cardHeading",
                "section.moveIn" to "movein_title", "section.areaNotes" to "notes_heading",
                "section.distances" to "house_distances", "section.location" to "house_location_heading",
                "section.walks" to "trace_house_title", "section.checklist" to "house_checklist",
                "section.visits" to "house_visits", "section.photos" to "house_photos",
                "field.label" to "house_name", "field.status" to "house_status", "field.rating" to "house_rating",
                "field.money" to "house_price_sale", "field.address" to "house_address", "field.street" to "house_street",
                "field.locality" to "house_locality", "field.contactName" to "house_contact_name",
                "field.contactPhone" to "house_phone", "field.brokerId" to "house_broker",
                "field.listingUrl" to "house_listing", "field.notes" to "house_notes",
                "field.location" to "house_location_heading", "field.locationSource" to "house_approx",
                "field.bedrooms" to "house_bhk", "field.areaSqft" to "house_area", "field.floor" to "house_floor",
                "field.deposit" to "house_deposit", "field.depositMonths" to "house_deposit_months",
                "field.maintenance" to "house_maintenance", "field.maintenanceIncluded" to "house_maintenance_included",
                "field.brokerage" to "house_brokerage", "field.brokerageMonths" to "house_brokerage_months",
                "field.lockInMonths" to "house_lock_in", "field.noticeMonths" to "house_notice",
                "field.availableFrom" to "house_available_from", "field.myOffer" to "house_my_offer",
                "field.agreedPrice" to "house_agreed_price",
                "cadence.month" to "house_rent", "cadence.once" to "house_buy",
                "check.water" to "check_water", "check.power" to "check_power", "check.parking" to "check_parking",
                "check.sunlight" to "check_sunlight", "check.ventilation" to "check_ventilation",
                "check.noise" to "check_noise", "check.security" to "check_security",
                "check.maintenance" to "check_maintenance", "check.neighbourhood" to "check_neighbourhood",
                "check.commute" to "check_commute",
            ),
            byCadence = mapOf("field.money@month" to "house_price_rent"),
            sections = mapOf(
                "label" to "details", "status" to "details", "rating" to "details", "money" to "details",
                "bedrooms" to "details", "areaSqft" to "details", "floor" to "details", "address" to "details",
                "street" to "details", "locality" to "details", "contactName" to "details", "contactPhone" to "details",
                "brokerId" to "details", "listingUrl" to "details", "notes" to "details",
                "location" to "location", "locationSource" to "location",
            ) + listOf(
                "deposit", "depositMonths", "maintenance", "maintenanceIncluded", "brokerage", "brokerageMonths",
                "lockInMonths", "noticeMonths", "availableFrom", "myOffer", "agreedPrice",
            ).associateWith { "cost" },
        )

        /** The built-in kind with this id, `null` for one this build does not have. */
        fun of(kind: String): KindLabels? = if (kind == HOUSE.kind) HOUSE else null
    }
}
