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
 * A built-in kind as the screens read it: its labels, its typed extras, and whether the listing extract fills it (the
 * kind file's `ai.extract` is not empty: *Paste listing text*).
 */
data class BuiltInKind(val labels: KindLabels, val fields: KindFields, val extracts: Boolean) {
    val id: String get() = labels.kind

    companion object {
        val HOUSE = BuiltInKind(KindLabels.HOUSE, KindFields.HOUSE, extracts = true)
    }
}

/**
 * The developer flag's kinds (kinds PR 5, S4b-BL-209; docs/03 §18.9), the twin of the website's `core/dev-kinds.ts`.
 * The flag itself is the app's (`BuildConfig.DEV_KINDS` on Android: true only in a debug build made with
 * `-Pdoorprints.devKinds=true`; the iPhone app passes false), handed to the screens; this object only answers which kinds
 * it allows. With it off nothing changes: a new candidate is a house, and a row of another kind (synced from a device with
 * the flag on) is drawn as a house is today, its extras kept and written back unread (`HouseEntity.extrasOther`).
 */
object DevKinds {
    // The listing extract fills a school since kinds PR 7 (S4b-BL-211): its kind file's `ai.extract` is not empty.
    private val SCHOOL = BuiltInKind(SchoolKind.LABELS, SchoolKind.FIELDS, extracts = true)

    /** The kinds a test registered (the conformance suite's synthetic kind, [KindFileKinds]): empty in every build. */
    private val TEST_ONLY = ArrayList<BuiltInKind>()

    /** Adds a kind of a test to the kinds the flag allows; returns the call that removes it. Only a test may ([TestOnlyKinds]). */
    @TestOnlyKinds
    fun registerForTest(kind: BuiltInKind): () -> Unit {
        TEST_ONLY.removeAll { it.id == kind.id }
        TEST_ONLY.add(kind)
        return { TEST_ONLY.remove(kind) }
    }

    /**
     * The kinds a new candidate may be: the house, and while [on] the school (and, in a test, the kinds it registered), then the
     * kinds this phone added from a kind file ([UserKinds], docs/03 ADR-39). The built-in ones come first, so a kind a person adds
     * can never stand in for one (its id starts `u_` anyway).
     */
    fun creatable(on: Boolean): List<BuiltInKind> =
        if (on) builtIn(true) + UserKinds.ids().mapNotNull(UserKinds::kind) else listOf(BuiltInKind.HOUSE)

    /** The built-in kinds while [on] (the house, the school, a test's), without the kinds a person added. */
    fun builtIn(on: Boolean): List<BuiltInKind> = if (on) listOf(BuiltInKind.HOUSE, SCHOOL) + TEST_ONLY else listOf(BuiltInKind.HOUSE)

    /** The kind a candidate is drawn as: its own when the flag allows it, else the house (as today). */
    fun of(id: String?, on: Boolean): BuiltInKind = creatable(on).firstOrNull { it.id == id } ?: BuiltInKind.HOUSE
}

/**
 * Schools, the second built-in kind (`docs/schemas/kinds/school.json`, Android keys), compiled in and off in the release builds; [DevKinds] is the developer flag that shows it beyond the shipped list: a copy of
 * the file's labels and extras, as [KindLabels.HOUSE] and [KindFields.HOUSE] are of `house.json` (`SchoolKindVectorsTest`
 * reads the file, so it cannot drift). A place the school leaves out (its money, the broker, the rooms, questions,
 * viewings and moving in) has no key, so the resolver says it is not shown.
 */
internal object SchoolKind {
    private val FIELDS_KEYS = mapOf(
        "label" to "school_name", "status" to "house_status", "rating" to "house_rating", "address" to "house_address",
        "street" to "house_street", "locality" to "house_locality", "contactName" to "school_contact_name",
        "contactPhone" to "house_phone", "listingUrl" to "school_website", "notes" to "house_notes",
        "board" to "school_board", "medium" to "school_medium", "grades" to "school_grades",
        "commuteMinutes" to "school_commute", "busService" to "school_bus",
        "fees" to "school_fees", "admissionFee" to "school_admission_fee",
    )
    private val LOCATION_KEYS = mapOf("location" to "house_location_heading", "locationSource" to "house_approx")

    /** The school's checklist (`school.json`, `checklist`, kinds PR 27, docs/03 §18.21): eight items scored 0 to 5, in the file's order. */
    private val CHECKS = listOf("dropoff", "grounds", "sanitation", "teachers", "classsize", "homework", "activities", "safety")

    val LABELS = KindLabels(
        kind = "school",
        capabilities = KindCapabilities.of("school"),
        keys = mapOf("kind.new" to "school_new_title", "kind.many" to "schools_title", "kind.delete" to "school_delete") +
            HouseStatus.entries.associate { "status.${it.name}" to "status_${it.name}" } +
            mapOf(
                "section.details" to null, "section.cost" to "school_fees_heading", "section.questions" to "school_questions_heading",
                "section.viewings" to "school_viewings_heading", "section.areaNotes" to "notes_heading",
                "section.distances" to "house_distances", "section.location" to "house_location_heading",
                "section.walks" to "trace_house_title", "section.checklist" to "house_checklist",
                "section.visits" to "house_visits", "section.photos" to "house_photos",
            ) +
            CHECKS.associate { "check.$it" to "school_check_$it" } +
            (FIELDS_KEYS + LOCATION_KEYS).mapKeys { "field.${it.key}" },
        byCadence = emptyMap(),
        sections = FIELDS_KEYS.mapValues { "details" } +
            mapOf("fees" to "cost", "admissionFee" to "cost", "location" to "location", "locationSource" to "location"),
    )

    private fun options(prefix: String, vararg ids: String) =
        ids.map { KindOption(it, "${prefix}_$it") } + KindOption("OTHER", "school_option_OTHER")

    val FIELDS = KindFields(
        LABELS,
        listOf(
            KindExtra("board", "enum", "details", 50, options = options("school_board", "CBSE", "ICSE", "STATE", "IB", "IGCSE")),
            KindExtra("medium", "enum", "details", 60, options = options("school_medium", "ENGLISH", "HINDI", "REGIONAL")),
            KindExtra("grades", "text", "details", 70, maxLength = 40),
            KindExtra("commuteMinutes", "number", "details", 72, integer = true, min = 0, max = 300, unit = "minute", better = "lower"),
            KindExtra("busService", "bool", "details", 74),
            KindExtra("fees", "money", "cost", 10, max = HouseCost.MAX_RUPEES, cadences = listOf("year"), better = "lower"),
            KindExtra("admissionFee", "money", "cost", 20, max = HouseCost.MAX_RUPEES, cadences = listOf("once"), better = "lower"),
        ),
        // Every extra is searched (kinds PR 6, S4b-BL-210).
        listOf("label", "address", "street", "locality", "notes", "contactName", "questions", "areaNotes") +
            listOf("board", "medium", "grades", "commuteMinutes", "busService", "fees", "admissionFee"),
    )
}
