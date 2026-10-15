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


package app.doorprints.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import app.doorprints.shared.model.KindWordKeys
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * The house words left on a page of another kind (kinds PR 16, phone half; S4b-BL-256, S4b-BL-259, S4b-BL-266, S4b-BL-277;
 * docs/03 §18.9): the form's title and the new candidate's default name, the delete and discard questions, the not-found card,
 * the walk check, the saved-walks and photos lines, the AI disclosure, the map's find-a-place lines, Compare's open button and
 * the Areas and Places lines. The house's are today's strings, so a house page is word for word what it was; a school's are
 * its own (hi, ta and te are under review). Every word is read from the kind file's `words` through its `android` key
 * ([KindWordKeys]; S4b-BL-278, S4b-BL-316): the class holds no table of strings of its own.
 */
@Immutable
class PageWords(
    /** The form's title for a saved candidate (the new one's is the kind's `kind.new`). */
    val detailsTitle: StringResource,
    val notFoundTitle: StringResource,
    val notFound: StringResource,
    /** The name a new candidate starts with, and the one a street gives it. */
    val defaultLabel: StringResource,
    val defaultLabelStreet: StringResource,
    val discardNew: StringResource,
    val deleteTitle: StringResource,
    val needsPrecise: StringResource,
    val savePhotosFirst: StringResource,
    /** *Did I walk past this house?* with its place, label, approximate-spot line and the saved-walks empty line. */
    val walkButton: StringResource,
    val walkPlace: StringResource,
    val walkLabel: StringResource,
    val walkApprox: StringResource,
    val walksEmpty: StringResource,
    val aiDisclosure: StringResource,
    val aiDisclosureOwnKey: StringResource,
    val placeFound: StringResource,
    val placeNotFound: StringResource,
    val openDetails: StringResource,
    val areasEmptyBody: StringResource,
    val notesEmpty: StringResource,
    val areaDeleteBody: StringResource,
    val placeDeleteBody: StringResource,
    val placesEmptyBody: StringResource,
    val areaNotesNone: StringResource,
    val areaHere: StringResource,
) {
    companion object {
        /** The page words of the kind whose file keys are [keys] (`KindWordKeys`): every word is the file's `android` key. */
        private fun from(keys: Map<String, String?>): PageWords {
            fun w(id: String): StringResource = kindStringResource(checkNotNull(keys[id]) { "no Android word for $id" })
            return PageWords(
                detailsTitle = w("detailsTitle"), notFoundTitle = w("notFoundTitle"), notFound = w("notFound"),
                defaultLabel = w("defaultLabel"), defaultLabelStreet = w("defaultLabelStreet"), discardNew = w("discardNew"),
                deleteTitle = w("deleteTitle"), needsPrecise = w("pageNeedsPrecise"), savePhotosFirst = w("savePhotosFirst"),
                walkButton = w("walkButton"), walkPlace = w("walkPlace"), walkLabel = w("walkLabel"), walkApprox = w("walkApprox"),
                walksEmpty = w("walksEmpty"), aiDisclosure = w("aiDisclosure"), aiDisclosureOwnKey = w("aiDisclosureOwnKey"),
                placeFound = w("placeFound"), placeNotFound = w("placeNotFound"), openDetails = w("openDetails"),
                areasEmptyBody = w("areasEmptyBody"), notesEmpty = w("notesEmpty"), areaDeleteBody = w("areaDeleteBody"),
                placeDeleteBody = w("placeDeleteBody"), placesEmptyBody = w("placesEmptyBody"),
                areaNotesNone = w("areaNotesNone"), areaHere = w("areaInReach"),
            )
        }

        val HOUSE = from(KindWordKeys.HOUSE)
        val SCHOOL = from(KindWordKeys.SCHOOL)

        /** The words of [kind]; a kind with no entry of its own reads the house's. */
        fun of(kind: String): PageWords = if (kind == "school") SCHOOL else HOUSE
    }
}

/**
 * The page words of the candidate a screen shows (its own kind's), where a part of the screen is composed apart from the
 * screen's body: the walk check's sheet and its saved walks. The house's unless [HouseEditScreen] provides another.
 */
val LocalPageWords = staticCompositionLocalOf { PageWords.HOUSE }
