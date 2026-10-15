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
 * `docs/schemas/kinds/capabilities-vectors.json`. Schema and resolver only: no screen reads [KindCapabilities.of] yet, so
 * nothing in the app changes. A feature a kind leaves out is null (false for a plain switch). A kind this build does not
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
) {
    /** Hunt mode: the alert radius in metres, and whether a stay at an unsaved place offers to save it. */
    data class Hunt(val alertRadiusM: Int, val stayCreates: Boolean)

    /** The candidate's contact: whose it is, and whether saving a phone number makes or links a broker entry. */
    data class Contacts(val role: String, val autoCreateFromPhone: Boolean)

    /** Viewings with their questions, seeded from the named built-in bank (only `house`). */
    data class Viewings(val questionsSeed: String)

    /** Compare's criteria in order: checklist keys or extra ids of the kind. */
    data class Compare(val criteria: List<String>)

    /** An icon and a colour from the bundled lists. */
    data class Identity(val icon: String, val colour: String)

    companion object {
        /** Everything off except the map and the paths: a kind with no `capabilities`, or a kind this build does not know. */
        val DEFAULT = KindCapabilities()

        const val MIN_HUNT_RADIUS_M = 20
        const val MAX_HUNT_RADIUS_M = 500
        const val MAX_COMPARE_CRITERIA = 10
        val ROLES = listOf("broker", "office", "agent")
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
                hunt = Hunt(alertRadiusM = 30, stayCreates = true),
                rooms = true,
                moveIn = true,
                contacts = Contacts("broker", autoCreateFromPhone = true),
                viewings = Viewings("house"),
                compare = Compare(
                    listOf("water", "power", "parking", "sunlight", "ventilation", "noise", "security", "maintenance", "neighbourhood", "commute"),
                ),
            ),
            // The school has a contact (the admissions office) that never makes a broker; no Hunt, rooms, moving in,
            // viewings or Compare. Its walks stay.
            "school" to KindCapabilities(contacts = Contacts("office", autoCreateFromPhone = false)),
        )

        /** The kinds that have capabilities, for the tests. */
        val KINDS: Set<String> get() = BUILT_IN.keys

        /** The capabilities of a kind by its id: the built-in set, or [DEFAULT] for a kind this build does not know. */
        fun of(kindId: String?): KindCapabilities = if (kindId == null) DEFAULT else BUILT_IN[kindId] ?: DEFAULT

        /**
         * The strict reader of a `capabilities` object, the schema's twin in code: the resolved set, or null when anything is
         * outside the schema (an unknown key at any level, a wrong type, a radius outside 20..500, more than ten or a repeated or
         * malformed criterion, an icon or colour not on the lists). A missing key takes the default. Never throws.
         */
        fun parse(raw: JsonElement?): KindCapabilities? {
            val obj = raw as? JsonObject ?: return null
            if (!obj.keys.all { it in KEYS }) return null
            val map = obj.flag("map", true) ?: return null
            val rooms = obj.flag("rooms", false) ?: return null
            val moveIn = obj.flag("moveIn", false) ?: return null
            val paths = obj.flag("paths", true) ?: return null
            val hunt = obj.block("hunt", listOf("alertRadiusM", "stayCreates")) { h ->
                val radius = (h["alertRadiusM"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                val stay = h.flag("stayCreates", null)
                if (radius == null || radius !in MIN_HUNT_RADIUS_M..MAX_HUNT_RADIUS_M || stay == null) null else Hunt(radius, stay)
            }
            val contacts = obj.block("contacts", listOf("role", "autoCreateFromPhone")) { c ->
                val role = c.text("role")
                val auto = c.flag("autoCreateFromPhone", null)
                if (role == null || role !in ROLES || auto == null) null else Contacts(role, auto)
            }
            val viewings = obj.block("viewings", listOf("questionsSeed")) { v ->
                if (v.text("questionsSeed") == "house") Viewings("house") else null
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
            return KindCapabilities(map, hunt, rooms, moveIn, contacts, viewings, compare, paths, identity)
        }

        private val KEYS = setOf("map", "hunt", "rooms", "moveIn", "contacts", "viewings", "compare", "paths", "identity")

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
