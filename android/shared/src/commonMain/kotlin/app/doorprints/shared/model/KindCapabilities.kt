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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a kind can do beyond its fields (docs/03 ADR-36 section 18.19): the `capabilities` block of a kind file
 * (`docs/schemas/kinds/kind.schema.json`), resolved; the twin of the website's `core/kind-capabilities.ts`, held to
 * `docs/schemas/kinds/capabilities-vectors.json`. The phones read the Hunt block (kinds PR 26,
 * section 18.20: `HuntSpace`, the Hunt card, the engine and the alerts) and Compare reads `compare` through [CompareCriteria]
 * (kinds PR 27, section 18.21); no other capability is read by a screen yet. A feature a kind leaves out is null (false for a plain switch). A kind this build does not
 * know has [DEFAULT]: the map and the paths, nothing else. (Where an unknown stored kind is drawn as a house, section 18.9,
 * the caller resolves the drawn kind first; this class does not guess.)
 */
data class KindCapabilities(
    val map: Boolean = true,
    val hunt: Hunt? = null,
    val rooms: Boolean = false,
    val moveIn: Boolean = false,
    val contacts: Contacts? = null,
    val viewings: Viewings? = null,
    val compare: Compare? = null,
    val paths: Boolean = true,
    val identity: Identity? = null,
    /**
     * The kind whose paths, wire shape, copy file names and words are the app's original, from before there were kinds (the house;
     * kinds PR 30, docs/03 section 18.24). Only the house has it; a kind this build does not know does not.
     */
    val baseline: Boolean = false,
    /** The extras the list and the map can be filtered by, in the order the rows are shown (ids of enum, switch, number or amount extras of the kind). */
    val filters: List<String> = emptyList(),
    /** The blocks of a copy that come first after the details, in this order; the rest follow in [COPY_BLOCKS]'s order (docs/03 section 18.28). None: the fixed order. */
    val copyOrder: List<String> = emptyList(),
) {
    /**
     * Hunt mode (docs/03 section 18.20): the alert radius in metres (30 to 300; the default of the kind's radius), whether a stay
     * at an unsaved place offers to save it as an entry of this kind, whether the walk tells about streets this kind has
     * saved places or visits on, and whether an area wake-up may start a walk of this kind.
     */
    data class Hunt(val alertRadiusM: Int, val stayCreates: Boolean, val streetMemory: Boolean, val areas: Boolean)

    /** The candidate's contact: whose it is, and whether saving a phone number makes or links a broker entry. */
    data class Contacts(val role: String, val autoCreateFromPhone: Boolean)

    /** Viewings with their questions, seeded from the named built-in bank (`house` or, since kinds PR 29, `school`). */
    data class Viewings(val questionsSeed: String)

    /** Compare's criteria in order: checklist keys or extra ids of the kind. */
    data class Compare(val criteria: List<String>)

    /** An icon and a colour from the bundled lists. */
    data class Identity(val icon: String, val colour: String)

    /**
     * Whether the capabilities allow the place [ref] (kinds PR 29, docs/03 section 18.23): the rooms and moving in follow
     * [rooms] and [moveIn], the viewings and their questions follow [viewings], and the broker field is the [contacts] of a
     * `broker` role (an office or an agent has a name and a phone and no broker record). Every other place is allowed. The
     * website's twin is `capabilityAllows` in `kind-labels.ts`.
     */
    fun allows(ref: String): Boolean = when (ref) {
        "section.rooms" -> rooms
        "section.moveIn" -> moveIn
        "section.viewings", "section.questions" -> viewings != null
        "field.brokerId" -> contacts?.role == "broker"
        else -> true
    }

    companion object {
        /** Everything off except the map and the paths: a kind with no `capabilities`, or a kind this build does not know. */
        val DEFAULT = KindCapabilities()

        const val MIN_HUNT_RADIUS_M = 30
        const val MAX_HUNT_RADIUS_M = 300
        const val MAX_COMPARE_CRITERIA = 10
        const val MAX_FILTERS = 6
        val ROLES = listOf("broker", "office", "agent")

        /** The built-in question banks that seed a kind's viewings (the `<kind>-questions.json` files in `docs/schemas/kinds` and, for the house, `docs/schemas/default-questions.json`). */
        val QUESTION_SEEDS = listOf("house", "school")
        val ICONS = listOf(
            "home", "school", "building", "apartment", "shop", "hospital", "office", "park", "car", "bike", "bus", "train",
            "cart", "hotel", "restaurant", "gym", "book", "heart", "star", "flag", "key", "pin", "briefcase", "paw",
        )
        val COLOURS = listOf("terracotta", "indigo", "teal", "forest", "amber", "rose", "slate", "plum")
        private val CRITERION = Regex("^[a-z][A-Za-z0-9_]{0,39}$")

        /** The built-in kinds' capabilities, copies of `house.json` and `school.json` (the vectors test reads the files). */
        private val BUILT_IN: Map<String, KindCapabilities> = mapOf(
            // The house's set is today's hard-coded behaviour: Hunt at 30 m, the rooms, moving in, the broker made from a
            // phone, the house questions, Compare's ten checklist keys, the paths.
            "house" to KindCapabilities(
                baseline = true,
                hunt = Hunt(alertRadiusM = 30, stayCreates = true, streetMemory = true, areas = true),
                rooms = true,
                moveIn = true,
                contacts = Contacts("broker", autoCreateFromPhone = true),
                viewings = Viewings("house"),
                compare = Compare(
                    listOf("water", "power", "parking", "sunlight", "ventilation", "noise", "security", "maintenance", "neighbourhood", "commute"),
                ),
            ),
            // The school has a contact (the admissions office) that never makes a broker, Hunt at 100 m, and viewings (visits,
            // open days) seeded by the school's own questions (kinds PR 29, section 18.23); no rooms or moving in. Its walks stay. Compare scores its eight checklist items and two extras, the fees and the commute
            // (kinds PR 27, section 18.21).
            "school" to KindCapabilities(
                hunt = Hunt(alertRadiusM = 100, stayCreates = true, streetMemory = true, areas = true),
                contacts = Contacts("office", autoCreateFromPhone = false),
                viewings = Viewings("school"),
                filters = listOf("board", "medium", "busService"),
                compare = Compare(listOf("dropoff", "grounds", "sanitation", "teachers", "classsize", "homework", "activities", "safety", "fees", "commuteMinutes")),
            ),
        )

        /** The kinds that have capabilities, for the tests. */
        val KINDS: Set<String> get() = BUILT_IN.keys

        /** The blocks of a house's copy after its details, in the copies' fixed order (`kind.schema.json`, `capabilities.copyOrder`; docs/03 section 18.28). */
        val COPY_BLOCKS: List<String> =
            listOf("cost", "rooms", "questions", "viewings", "areaNotes", "distances", "moveIn", "checklist", "visits", "notes", "photos")

        /** The order a kind's copy writes its blocks in: the kind's `copyOrder` first, then the rest in the fixed order. */
        fun copyBlockOrder(kindId: String?): List<String> {
            val first = of(kindId).copyOrder
            return if (first.isEmpty()) COPY_BLOCKS else first + COPY_BLOCKS.filter { it !in first }
        }

        /** Capabilities a test registered for a kind of its own (the conformance suite's synthetic kind): empty in every build. */
        private val TEST_ONLY = HashMap<String, KindCapabilities>()

        /** Gives a kind capabilities for a test; returns the call that takes them away again. Only a test may ([TestOnlyKinds]). */
        @TestOnlyKinds
        fun registerForTest(kindId: String, capabilities: KindCapabilities): () -> Unit {
            TEST_ONLY[kindId] = capabilities
            return { TEST_ONLY.remove(kindId) }
        }

        /** The capabilities of a kind by its id: the built-in set, or [DEFAULT] for a kind this build does not know. */
        fun of(kindId: String?): KindCapabilities = if (kindId == null) DEFAULT else BUILT_IN[kindId] ?: TEST_ONLY[kindId] ?: DEFAULT

        /**
         * The strict reader of a `capabilities` object, the schema's twin in code: the resolved set, or null when anything is
         * outside the schema (an unknown key at any level, a wrong type, a radius outside 30..300, more than ten or a repeated or
         * malformed criterion, an icon or colour not on the lists). A missing key takes the default. Never throws.
         */
        fun parse(raw: JsonElement?): KindCapabilities? {
            val obj = raw as? JsonObject ?: return null
            if (!obj.keys.all { it in KEYS }) return null
            val map = obj.flag("map", true) ?: return null
            val rooms = obj.flag("rooms", false) ?: return null
            val moveIn = obj.flag("moveIn", false) ?: return null
            val paths = obj.flag("paths", true) ?: return null
            val baseline = obj.flag("baseline", false) ?: return null
            val filters = if ("filters" in obj) {
                val list = (obj["filters"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return null
                if (list.size > MAX_FILTERS || list.any { it == null || !CRITERION.matches(it) } || list.toSet().size != list.size) return null
                list.filterNotNull()
            } else emptyList()
            val hunt = obj.block("hunt", listOf("alertRadiusM", "stayCreates", "streetMemory", "areas")) { h ->
                val radius = (h["alertRadiusM"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                val stay = h.flag("stayCreates", null)
                val street = h.flag("streetMemory", null)
                val areas = h.flag("areas", null)
                if (radius == null || radius !in MIN_HUNT_RADIUS_M..MAX_HUNT_RADIUS_M || stay == null || street == null || areas == null) null
                else Hunt(radius, stay, street, areas)
            }
            val contacts = obj.block("contacts", listOf("role", "autoCreateFromPhone")) { c ->
                val role = c.text("role")
                val auto = c.flag("autoCreateFromPhone", null)
                if (role == null || role !in ROLES || auto == null) null else Contacts(role, auto)
            }
            val viewings = obj.block("viewings", listOf("questionsSeed")) { v ->
                v.text("questionsSeed")?.takeIf { it in QUESTION_SEEDS }?.let { Viewings(it) }
            }
            val compare = obj.block("compare", listOf("criteria")) { c ->
                val list = (c["criteria"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (list == null || list.size !in 1..MAX_COMPARE_CRITERIA || list.any { it == null || !CRITERION.matches(it) } ||
                    list.toSet().size != list.size
                ) null else Compare(list.filterNotNull())
            }
            val identity = obj.block("identity", listOf("icon", "colour")) { i ->
                val icon = i.text("icon")
                val colour = i.text("colour")
                if (icon == null || icon !in ICONS || colour == null || colour !in COLOURS) null else Identity(icon, colour)
            }
            // A block that is present but refused is a refusal of the whole object, not a quiet default.
            if (listOf("hunt" to hunt, "contacts" to contacts, "viewings" to viewings, "compare" to compare, "identity" to identity)
                    .any { (key, value) -> key in obj && value == null }
            ) return null
            val copyOrder = if ("copyOrder" in obj) {
                val list = (obj["copyOrder"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return null
                if (list.size > COPY_BLOCKS.size || list.any { it == null || it !in COPY_BLOCKS } || list.toSet().size != list.size) return null
                list.filterNotNull()
            } else emptyList()
            return KindCapabilities(map, hunt, rooms, moveIn, contacts, viewings, compare, paths, identity, baseline, filters, copyOrder)
        }

        private val KEYS = setOf("map", "hunt", "rooms", "moveIn", "contacts", "viewings", "compare", "paths", "identity", "baseline", "filters", "copyOrder")

        /** A boolean member: its value, [absent] when missing, null when present and not a boolean. */
        private fun JsonObject.flag(key: String, absent: Boolean?): Boolean? {
            val v = this[key] ?: return absent
            return (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A block member read by [read] when it is an object with exactly these (all required) keys; null when absent or refused. */
        private fun <T> JsonObject.block(key: String, keys: List<String>, read: (JsonObject) -> T?): T? {
            val o = this[key] as? JsonObject ?: return null
            if (o.keys != keys.toSet()) return null
            return read(o)
        }
    }
}
