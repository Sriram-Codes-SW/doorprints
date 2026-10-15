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

import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.KindLabels
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.allStringResources
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The house words left on a school's pages, on the phones (kinds PR 16, phone half; S4b-BL-256, S4b-BL-259, S4b-BL-266,
 * S4b-BL-277; docs/03 §18.9; docs/06 TC-U-230), the twin of the website's `school-words.spec.ts`: a school's page words
 * ([PageWords.SCHOOL]) are strings of `:ui` in en, hi, ta and te, none of them says a house, every place the house words
 * name has its own, and the ones the website has a key for are the website's words, character for character (read from
 * `web/src/app/i18n/<lang>.ts`, so the two cannot drift); a house's page words are the strings its screens always read, and a
 * status is named through the kind's labels. Not in commonTest: it reads repository files.
 */
class SchoolPageWordsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val languages = listOf("en", "hi", "ta", "te")
    private val resources = mapOf("en" to "values", "hi" to "values-hi", "ta" to "values-ta", "te" to "values-te")

    /** The strings of `:ui` in [language], by name. */
    private fun phone(language: String): Map<String, String> =
        Regex("<string name=\"([a-z0-9_A-Z]+)\">(.*?)</string>")
            .findAll(repoFile("android/ui/src/commonMain/composeResources/${resources.getValue(language)}/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** The website's dictionary in [language], by key. */
    private fun web(language: String): Map<String, String> =
        Regex("'([A-Za-z0-9_.]+)': '((?:[^'\\\\]|\\\\.)*)',")
            .findAll(repoFile("web/src/app/i18n/$language.ts").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private val school: List<Pair<String, org.jetbrains.compose.resources.StringResource>> = listOf(
        "detailsTitle" to PageWords.SCHOOL.detailsTitle, "notFoundTitle" to PageWords.SCHOOL.notFoundTitle,
        "notFound" to PageWords.SCHOOL.notFound, "defaultLabel" to PageWords.SCHOOL.defaultLabel,
        "defaultLabelStreet" to PageWords.SCHOOL.defaultLabelStreet, "discardNew" to PageWords.SCHOOL.discardNew,
        "deleteTitle" to PageWords.SCHOOL.deleteTitle, "needsPrecise" to PageWords.SCHOOL.needsPrecise,
        "savePhotosFirst" to PageWords.SCHOOL.savePhotosFirst, "walkButton" to PageWords.SCHOOL.walkButton,
        "walkPlace" to PageWords.SCHOOL.walkPlace, "walkLabel" to PageWords.SCHOOL.walkLabel,
        "walkApprox" to PageWords.SCHOOL.walkApprox, "walksEmpty" to PageWords.SCHOOL.walksEmpty,
        "aiDisclosure" to PageWords.SCHOOL.aiDisclosure, "aiDisclosureOwnKey" to PageWords.SCHOOL.aiDisclosureOwnKey,
        "placeFound" to PageWords.SCHOOL.placeFound, "placeNotFound" to PageWords.SCHOOL.placeNotFound,
        "openDetails" to PageWords.SCHOOL.openDetails, "areasEmptyBody" to PageWords.SCHOOL.areasEmptyBody,
        "notesEmpty" to PageWords.SCHOOL.notesEmpty, "areaDeleteBody" to PageWords.SCHOOL.areaDeleteBody,
        "placeDeleteBody" to PageWords.SCHOOL.placeDeleteBody, "placesEmptyBody" to PageWords.SCHOOL.placesEmptyBody,
        "areaNotesNone" to PageWords.SCHOOL.areaNotesNone, "areaHere" to PageWords.SCHOOL.areaHere,
    )

    private val house: Map<String, org.jetbrains.compose.resources.StringResource> = mapOf(
        "detailsTitle" to PageWords.HOUSE.detailsTitle, "notFoundTitle" to PageWords.HOUSE.notFoundTitle,
        "notFound" to PageWords.HOUSE.notFound, "defaultLabel" to PageWords.HOUSE.defaultLabel,
        "defaultLabelStreet" to PageWords.HOUSE.defaultLabelStreet, "discardNew" to PageWords.HOUSE.discardNew,
        "deleteTitle" to PageWords.HOUSE.deleteTitle, "needsPrecise" to PageWords.HOUSE.needsPrecise,
        "savePhotosFirst" to PageWords.HOUSE.savePhotosFirst, "walkButton" to PageWords.HOUSE.walkButton,
        "walkPlace" to PageWords.HOUSE.walkPlace, "walkLabel" to PageWords.HOUSE.walkLabel,
        "walkApprox" to PageWords.HOUSE.walkApprox, "walksEmpty" to PageWords.HOUSE.walksEmpty,
        "aiDisclosure" to PageWords.HOUSE.aiDisclosure, "aiDisclosureOwnKey" to PageWords.HOUSE.aiDisclosureOwnKey,
        "placeFound" to PageWords.HOUSE.placeFound, "placeNotFound" to PageWords.HOUSE.placeNotFound,
        "openDetails" to PageWords.HOUSE.openDetails, "areasEmptyBody" to PageWords.HOUSE.areasEmptyBody,
        "notesEmpty" to PageWords.HOUSE.notesEmpty, "areaDeleteBody" to PageWords.HOUSE.areaDeleteBody,
        "placeDeleteBody" to PageWords.HOUSE.placeDeleteBody, "placesEmptyBody" to PageWords.HOUSE.placesEmptyBody,
        "areaNotesNone" to PageWords.HOUSE.areaNotesNone, "areaHere" to PageWords.HOUSE.areaHere,
    )

    /** The name of a Compose string resource of `:ui`. */
    private fun nameOf(r: org.jetbrains.compose.resources.StringResource): String =
        Res.allStringResources.entries.first { it.value == r }.key

    /** The words of a house in each language: a school's page must carry none of them. */
    private val houseWord = mapOf(
        "en" to Regex("house|\\bflat\\b|BHK", RegexOption.IGNORE_CASE),
        "hi" to Regex("मकान|घर"),
        "ta" to Regex("வீடு|வீட்"),
        "te" to Regex("ఇల్లు|ఇళ్లు|ఇంటి"),
    )

    @Test
    fun everySchoolPageWordIsAStringInTheFourLanguagesAndSaysNoHouse() {
        for (language in languages) {
            val strings = phone(language)
            for ((field, resource) in school) {
                val name = nameOf(resource)
                val text = assertNotNull(strings[name], "$language $field ($name)")
                assertTrue(text.isNotBlank(), "$language $name")
                // The empty Places line asks for the person's own *family home* (the website's line does too): not a house hunted.
                if (name != "school_places_empty_body") assertEquals(null, houseWord.getValue(language).find(text), "$language $name: $text")
                assertNotEquals(nameOf(house.getValue(field)), name, field)
                // The house's string and the school's take the same arguments.
                assertEquals(
                    Regex("%\\d\\$[sd]").findAll(phone(language).getValue(nameOf(house.getValue(field)))).map { it.value }.toList(),
                    Regex("%\\d\\$[sd]").findAll(text).map { it.value }.toList(),
                    "$language $name",
                )
            }
        }
    }

    @Test
    fun theWordsTheWebsiteHasAKeyForAreTheWebsitesWords() {
        // phone key to (website key, how the phone's sentence is cut from it): the website's title carries the product name, its
        // not-found line a full stop, and its empty Places line the heading the phone draws apart.
        val pairs = listOf(
            Triple("school_details_title", "school.titleDetails") { t: String -> t.removeSuffix(" · Doorprints") },
            Triple("school_not_found_title", "school.notFound") { t: String -> t.removeSuffix(".").removeSuffix("।") },
            Triple("school_save_first_photos", "school.saveFirstPhotos") { t: String -> t },
            Triple("school_walk_button", "school.walkTitle") { t: String -> t },
            Triple("school_walk_place", "school.walkPlace") { t: String -> t },
            Triple("school_walk_label", "school.walkLabel") { t: String -> t },
            Triple("school_walk_approx", "school.walkApprox") { t: String -> t },
            Triple("school_walks_empty", "school.walksEmpty") { t: String -> t },
            Triple("school_notes_empty", "school.areaNotesEmpty") { t: String -> t },
            Triple("school_default_label", "school.titleNew") { t: String -> t.removeSuffix(" · Doorprints") },
        )
        for (language in languages) {
            val phone = phone(language)
            val web = web(language)
            for ((phoneKey, webKey, cut) in pairs) {
                assertEquals(cut(assertNotNull(web[webKey], "$language $webKey")), phone[phoneKey], "$language $phoneKey")
            }
            // The empty Places line is the website's, after its first sentence (the phone's title is *No places yet.*).
            val webEmpty = assertNotNull(web["school.placesEmpty"], language)
            val phoneEmpty = assertNotNull(phone["school_places_empty_body"], language)
            assertTrue(webEmpty.endsWith(phoneEmpty), "$language: $phoneEmpty in $webEmpty")
        }
    }

    @Test
    fun aHousesPageWordsAreTheStringsItsScreensAlwaysRead() {
        assertEquals(Res.allStringResources["house_details_title"], PageWords.HOUSE.detailsTitle)
        assertEquals(Res.allStringResources["house_default_label"], PageWords.HOUSE.defaultLabel)
        assertEquals(Res.allStringResources["house_delete_confirm_title"], PageWords.HOUSE.deleteTitle)
        assertEquals(Res.allStringResources["trace_here_button_house"], PageWords.HOUSE.walkButton)
        assertEquals(Res.allStringResources["ai_disclosure"], PageWords.HOUSE.aiDisclosure)
        assertEquals(PageWords.HOUSE, PageWords.of("house"))
        assertEquals(PageWords.HOUSE, PageWords.of("clinic")) // a kind this build does not have reads the house's
        assertEquals(PageWords.SCHOOL, PageWords.of("school"))
        assertEquals(PageWords.SCHOOL, SpaceUi.resolve(app.doorprints.shared.model.KindSetting(listOf("house", "school"), experimental = "1", enabledText = "[\"house\",\"school\"]"), "school").pages)
    }

    @Test
    fun aStatusIsNamedThroughTheKindsLabels() {
        val school = app.doorprints.shared.model.DevKinds.of("school", on = true).labels
        for (s in HouseStatus.entries) {
            assertEquals(Res.allStringResources["status_${s.name}"], school.statusText(s), s.name)
            assertEquals(s.labelResource, KindLabels.HOUSE.statusText(s), s.name)
        }
        assertFalse(school.kind == KindLabels.HOUSE.kind)
    }

    /** S4b-BL-301: the map's location notes, *Save here* and the long press's row say school and no house in a school space, in four languages. */
    @Test
    fun theSchoolsMapLocationNotesSayNoHouse() {
        val words = with(SpaceWords.SCHOOL) { listOf(locationOff, needsPrecise, locationOffNoHunt, needsPreciseNoHunt, saveNeedsLocation, saveHere) }
        for (language in languages) {
            val strings = phone(language)
            for (word in words) {
                val name = nameOf(word)
                val text = assertNotNull(strings[name], "$language $name")
                assertTrue(text.isNotBlank(), "$language $name")
                assertEquals(null, houseWord.getValue(language).find(text), "$language $name: $text")
            }
        }
        // The school has Hunt mode of its own (docs/03 section 18.19): the note under its Hunt card says so, the iPhone's no-Hunt pair does not.
        assertTrue(phone("en").getValue(nameOf(SpaceWords.SCHOOL.locationOff)).contains("Hunt mode"), "the school's Hunt pair says Hunt mode")
        assertTrue(phone("en").getValue(nameOf(SpaceWords.SCHOOL.needsPrecise)).contains("Hunt mode"), "the school's Hunt pair says Hunt mode")
        assertFalse(phone("en").getValue(nameOf(SpaceWords.SCHOOL.locationOffNoHunt)).contains("Hunt"), "the no-Hunt pair names no Hunt mode")
        // The house's four are the strings the map always read.
        assertEquals(
            listOf("map_location_off", "map_needs_precise", "map_location_off_no_hunt", "map_needs_precise_no_hunt", "map_save_needs_location"),
            with(SpaceWords.HOUSE) { listOf(locationOff, needsPrecise, locationOffNoHunt, needsPreciseNoHunt, saveNeedsLocation) }.map(::nameOf),
        )
    }
}
