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

import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.KindWordKeys
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.houses_title
import app.doorprints.ui.res.nav_houses
import app.doorprints.ui.res.schools_title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The space on screen (kinds PR C, S4b-BL-274; docs/03 §18.12): which space a choice resolves to, what a kind shows (the
 * rows of the places its file has, so a school has no checklist, money, broker, questions, viewings or rooms), where Hunt mode
 * is, and that the house's words are the strings the house screens always read.
 */
class SpaceUiTest {
    private val both = KindSetting(listOf("house", "school"), experimental = "1", enabledText = "[\"house\",\"school\"]")
    private val houseOnly = KindSetting(listOf("house", "school"))

    @Test
    fun theChoiceIsTheSpaceWhenTwoKindsAreOnAndTheDefaultOtherwise() {
        assertEquals("school", SpaceUi.resolve(both, "school").kind)
        assertEquals("house", SpaceUi.resolve(both, "house").kind)
        assertEquals("house", SpaceUi.resolve(both, null).kind)
        assertEquals("house", SpaceUi.resolve(both, "clinic").kind) // a kind this build does not have
        assertEquals("school", SpaceUi.resolve(both.withDefault("school"), null).kind)
        // One kind on: the app is that kind's alone, whatever was chosen before.
        assertEquals("house", SpaceUi.resolve(houseOnly, "school").kind)
        assertFalse(SpaceUi.resolve(houseOnly, "school").multi)
    }

    @Test
    fun aHousesOnlyAppIsTheSpaceOfEveryScreenOutsideTheApp() {
        assertEquals("house", SpaceUi.HOUSE.kind)
        assertFalse(SpaceUi.HOUSE.multi)
        assertTrue(SpaceUi.HOUSE.view.holds("school") && SpaceUi.HOUSE.view.holds(null))
    }

    @Test
    fun theHouseShowsEveryPlaceAndHuntsAndSortsByScoreAndPrice() {
        val house = SpaceUi.resolve(both, "house")
        for (ref in listOf("field.brokerId", "section.checklist", "section.questions", "section.viewings", "section.rooms", "field.money", "field.bedrooms", "field.agreedPrice")) {
            assertTrue(house.shows(ref), ref)
        }
        assertTrue(house.huntHere)
        assertTrue(house.sortsByScoreAndPrice)
    }

    @Test
    fun aSchoolHasNoBrokerRoomsMovingInOrMoneyButHasHuntModeVisitsAndAChecklistOfItsOwn() {
        val school = SpaceUi.resolve(both, "school")
        for (ref in listOf("field.brokerId", "section.moveIn", "section.rooms", "field.money", "field.bedrooms", "field.agreedPrice")) {
            assertFalse(school.shows(ref), ref)
        }
        // Kinds PR 29 (docs/03 section 18.23): the school has viewings (its visits and open days) and, with them, its questions.
        for (ref in listOf("section.viewings", "section.questions")) assertTrue(school.shows(ref), ref)
        // What a school does have: the places the kind file lists.
        for (ref in listOf("field.contactName", "field.street", "field.rating", "section.visits", "section.distances", "section.checklist")) assertTrue(school.shows(ref), ref)
        assertTrue(school.huntHere, "Hunt mode is the school's capability (docs/03 section 18.20)")
        assertFalse(school.sortsByScoreAndPrice)
    }

    /** S4b-BL-278: ten of the space words and two page words are the ones the kind files' `android` keys name. */
    @Test
    fun theWordsTheKindFileHasAreReadFromItsAndroidKeys() {
        for ((kind, keys) in listOf("house" to KindWordKeys.HOUSE, "school" to KindWordKeys.SCHOOL)) {
            fun key(id: String) = kindStringResource(checkNotNull(keys[id])).key
            val w = SpaceWords.of(kind)
            assertEquals(
                listOf("heading", "search", "shown", "emptyBody", "noMatch", "add", "create", "regionLabel", "addHint", "addHintShort").map(::key),
                listOf(w.many.key, w.search.key, w.shown.key, w.empty.key, w.noMatch.key, w.addOnMap.key, w.saveHere.key, w.regionDesc.key, w.addTip.key, w.addTipA11y.key),
                kind,
            )
            assertEquals(listOf(key("areaNotesNone"), key("areaInReach")), listOf(PageWords.of(kind).areaNotesNone.key, PageWords.of(kind).areaHere.key), kind)
        }
    }

    @Test
    fun theHousesWordsAreTheStringsTheHouseScreensAlwaysRead() {
        assertEquals(Res.string.houses_title.key, SpaceWords.of("house").many.key)
        assertEquals(Res.string.houses_title.key, SpaceWords.HOUSE.many.key)
        assertEquals(Res.string.schools_title.key, SpaceWords.of("school").many.key)
        assertSame(SpaceWords.HOUSE, SpaceWords.of("clinic")) // a kind with no table of its own reads the house's
        // The bar's list tab is the house's own string in the house space.
        assertEquals("nav_houses", Res.string.nav_houses.key)
    }

    @Test
    fun theAppWideTextsAreNeutralOnceMoreThanTheHouseIsInUse() {
        // Kinds PR 31 (docs/03 section 18.25): the house alone reads the house's Settings texts; two kinds, or another default kind, say entries.
        assertFalse(SpaceUi.HOUSE.neutral)
        assertFalse(SpaceUi(houseOnly, "house").neutral)
        assertTrue(SpaceUi(both, "house").neutral)
        assertTrue(SpaceUi(both, "school").neutral)
        val schoolOnly = both.withDefault("school").withKindEnabled("house", false)
        assertTrue(SpaceUi.resolve(schoolOnly, null).neutral)
    }

    @Test
    fun theWordsOfAKindWithNoFileAreTheHousesAndEveryKindFileHasItsOwn() {
        assertEquals(listOf("house", "school"), KindWordKeys.KINDS)
        assertSame(PageWords.HOUSE, PageWords.of("zz_none"))
        assertSame(HuntWords.HOUSE, HuntWords.of("zz_none"))
        assertEquals(KindWordKeys.HOUSE, KindWordKeys.of("zz_none"))
        assertEquals(KindWordKeys.SCHOOL, KindWordKeys.of("school"))
    }

    @Test
    fun aSpaceIsHiddenOnlyWhenTheKindIsOffButOpenedAnyway() {
        assertFalse(SpaceUi(both, "school").hidden)
        assertFalse(SpaceUi(houseOnly, "school").hidden) // one kind on: no spaces, so nothing is hidden
    }

    @Test
    fun theSchoolTheDefaultWithTheHouseOffIsOneSpaceAndEveryEntryOfIsHeldAndANewEntryIsASchool() {
        // docs/03 §18.18: the house turned off keeps its entries, listed in the one space; the default is always a kind that is on.
        val schoolOnly = both.withDefault("school").withKindEnabled("house", false)
        assertEquals(listOf("school"), schoolOnly.enabled)
        assertEquals("school", schoolOnly.default)
        val space = SpaceUi.resolve(schoolOnly, "house")
        assertEquals("school", space.kind) // a restored choice of the house is ignored
        assertFalse(space.multi)
        assertTrue(space.view.holds(null) && space.view.holds("school"))
        assertEquals("school", space.creating.id)
        assertTrue(space.huntHere, "the school alone has Hunt mode of its own")
    }

    @Test
    fun withTwoKindsOnANewEntryIsOfTheSpaceItIsMadeIn() {
        // A space that is off takes no new entry (`SpaceScope.createKind`, the vectors); with two built-in kinds none can be off while two are on.
        assertEquals("school", SpaceUi.resolve(both, "school").creating.id)
        assertEquals("house", SpaceUi.resolve(both, "house").creating.id)
        assertEquals("school", SpaceUi.resolve(both.withDefault("school"), null).creating.id)
        assertEquals("house", SpaceUi.HOUSE.creating.id)
    }
}
