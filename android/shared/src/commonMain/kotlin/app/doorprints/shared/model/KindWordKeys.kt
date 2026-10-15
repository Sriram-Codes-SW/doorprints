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
 * The Android keys of a kind file's top-level `words` (S4b-BL-278; `docs/schemas/kinds/house.json`, `school.json`): the
 * Compose string a space's first screen and candidate page read for each of the sixteen ids, `null` where the phone shows no
 * text (the website's region hint, list label, loading and empty title). A copy of the files' `android` keys, as
 * [KindLabels.HOUSE] is of the labels (`HouseKindFileTest` and `SchoolKindVectorsTest` read the files, so it cannot drift);
 * `SpaceWords` and `PageWords` in `:ui` turn the keys into strings. A kind with no entry reads the house's.
 */
object KindWordKeys {
    /** The ids of `words` in the schema, in its order. */
    val IDS = listOf(
        "heading", "regionLabel", "regionHint", "add", "addHint", "addHintShort", "search", "listLabel", "shown", "loading",
        "emptyTitle", "emptyBody", "noMatch", "create", "areaNotesNone", "areaInReach",
    )

    private fun table(vararg keys: String?): Map<String, String?> = IDS.zip(keys.toList()).toMap().also { check(keys.size == IDS.size) }

    val HOUSE: Map<String, String?> = table(
        "houses_title", "map_region_desc", null, "common_add_on_map", "map_add_tip", "map_add_tip_a11y", "houses_search", null,
        "houses_shown", null, null, "houses_empty", "houses_no_match", "map_save_here", "house_area_notes_none", "note_area_here",
    )

    val SCHOOL: Map<String, String?> = table(
        "schools_title", "school_region_desc", null, "school_add_on_map", "school_add_tip", "school_add_tip_a11y", "school_search", null,
        "school_shown", null, null, "school_empty", "school_no_match", "school_save_here", "school_area_notes_none", "school_area_here",
    )

    /** The keys of [kind]; a kind with no table of its own reads the house's. */
    fun of(kind: String): Map<String, String?> = if (kind == "school") SCHOOL else HOUSE
}
