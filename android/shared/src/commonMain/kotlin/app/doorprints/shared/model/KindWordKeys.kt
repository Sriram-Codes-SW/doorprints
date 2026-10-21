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
 * The Android keys of a kind file's top-level `words` (S4b-BL-278, S4b-BL-316; `docs/schemas/kinds/house.json`, `school.json`): the
 * Compose string a space's first screen, its candidate page and its tools read for each id, `null` where the phone shows no
 * text (the website's region hint, list label, loading and empty title, and the words only the website has). The sixteen ids of
 * S4b-BL-278 come first, then the phone's other words that name the kind, the Hunt mode words (docs/03 section 18.20), the viewing
 * words (section 18.23) and, since kinds PR 31 (section 18.25), the website's other words. The maps are generated from the files by
 * `tools/gen-kind-words.mjs` ([KindWordData]; `--check` fails a stale one), so no table of words is written by hand;
 * `HouseKindFileTest`, `SchoolKindVectorsTest` and `KindWordVectorsTest` read the files and the vectors as well. `SpaceWords` and
 * `PageWords` in `:ui` turn the keys into strings. A kind with no entry reads the house's.
 */
object KindWordKeys {
    /** The ids of `words` in the schema, in its order. */
    val IDS: List<String> get() = KindWordData.IDS

    /** The kinds that have their own words (their kind files), the house first. */
    val KINDS: List<String> get() = KindWordData.BY_KIND.keys.toList()

    /** The baseline kind's keys (the first kind file). */
    val HOUSE: Map<String, String?> = KindWordData.BY_KIND.values.first()

    val SCHOOL: Map<String, String?> = KindWordData.BY_KIND.getValue("school")

    /**
     * The keys of [kind]; a kind this phone added from a kind file reads its own plural and *new* for the heading and the add
     * button and the neutral `ukind_*` words elsewhere ([UserKinds], docs/03 ADR-39); any other kind with no table reads the house's.
     */
    fun of(kind: String): Map<String, String?> = KindWordData.BY_KIND[kind] ?: UserKinds.wordKeys(kind) ?: HOUSE

    /** The Android key of word [id] in the kind of a stored candidate ([kind] null or one this build does not have: the house's). */
    fun key(kind: String?, id: String): String = checkNotNull(of(kind ?: KindWordData.BY_KIND.keys.first())[id]) { "no Android word for $id" }
}
