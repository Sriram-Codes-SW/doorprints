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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The house list's search rule ([HouseSearch]), the same cases as the website's `map-list.spec.ts` ("searchText"),
 * kept in step by hand: a case added here is added there (docs/06 TC-U-93).
 */
class HouseSearchTest {

    private val green = HouseSearch.fields(
        label = "Green View 2BHK", address = "12, 5th Cross", street = "5th Cross", locality = "Indiranagar",
        notes = "Water 24x7, near the metro", contactName = "Ravi Kumar",
    )
    private val lake = HouseSearch.fields(
        label = "Lake Road flat", address = null, street = null, locality = "हिन्दी नगर", notes = null, contactName = null,
    )

    // The linked broker's name, agency and fee terms (slice 1b), as `Broker.searchText` writes them.
    private val brokered = HouseSearch.fields(
        label = "Beach Road", address = null, street = null, locality = null, notes = null, contactName = "Meena",
        brokerText = Broker(name = "Meena Iyer", agency = "Beach Road Realty", feeTerms = "15 days' rent, once").searchText,
    )

    // The rooms' names and notes (slice 1c).
    private val roomy = HouseSearch.fields(
        label = "Hill View", address = null, street = null, locality = null, notes = null, contactName = null,
        rooms = listOf(
            HouseRoom(id = "r1", type = "BEDROOM", name = "Master bedroom", notes = "Damp patch near the window"),
            HouseRoom(id = "r2", type = "KITCHEN", sort = 1),
        ),
    )

    // The questions asked and their answers (slice 3a).
    private val asked = HouseSearch.fields(
        label = "Palm Court", address = null, street = null, locality = null, notes = null, contactName = null,
        answers = listOf(
            HouseAnswer(id = "a1", text = "Is the terrace open to tenants?", sort = 0),
            HouseAnswer(id = "a2", questionId = "qd_water", text = "Water supply hours?", answer = "Borewell, twice a day", status = "ANSWERED", sort = 1),
        ),
    )

    // The texts of the area notes that reach the house (slice 4a): `AreaNotes.reaching` picks them.
    private val noted = HouseSearch.fields(
        label = "Sea Breeze", address = null, street = "MG Road", locality = null, notes = null, contactName = null,
        noteTexts = AreaNotes.reaching(
            HousePoint(13.0067, 80.2574, "mg road"),
            listOf(Area("a_1", "Adyar", 13.0067, 80.2574)),
            listOf(AreaNote("n_1", areaId = "a_1", text = "Water tanker every morning"), AreaNote("n_2", street = "MG Road", text = "Floods in the monsoon")),
        ).map { it.text },
    )

    // The move-in items' texts and its notes (slice 5).
    private val movingIn = HouseSearch.fields(
        label = "Fern Villa", address = null, street = null, locality = null, notes = null, contactName = null,
        moveIn = MoveIn(notes = "Electricity meter reads 4521", items = listOf(MoveInItem("mi_keys", "Keys received", true, 0))),
    )

    // The floor (S4b-BL-87), as `HouseSearch.floorText` words it.
    private val high = HouseSearch.fields(
        label = "Oak Tower", address = null, street = null, locality = null, notes = null, contactName = null, floor = 3,
    )

    private fun matching(query: String) =
        listOf(
            "green" to green, "lake" to lake, "beach" to brokered, "hill" to roomy, "palm" to asked, "sea" to noted,
            "fern" to movingIn, "oak" to high,
        )
        .filter { HouseSearch.matches(query, it.second) }.map { it.first }

    @Test fun aBlankQueryMatchesEveryHouse() =
        assertEquals(listOf("green", "lake", "beach", "hill", "palm", "sea", "fern", "oak"), matching("  "))
    @Test fun aQueryMatchesTheFloor() {
        assertEquals(listOf("oak"), matching("floor 3"))
        assertEquals(emptyList(), matching("ground floor"))
        assertEquals(listOf("ground floor 0", "basement 2", "floor 12"), listOf(0, -2, 12).map(HouseSearch::floorText))
    }
    @Test fun aQueryMatchesTheFloorInTheAppLanguageToo() {
        // S4b-BL-104 b: the words of the app's language as well as English (web `floorLocalSearchText`, map-list.spec.ts).
        fun fields(floor: Int, language: String) = HouseSearch.fields(
            label = "Oak Tower", address = null, street = null, locality = null, notes = null, contactName = null,
            floor = floor, language = language,
        )
        assertTrue(HouseSearch.matches("भूतल", fields(0, "hi")))
        assertTrue(HouseSearch.matches("ground floor", fields(0, "hi")))
        assertTrue(HouseSearch.matches("बेसमेंट 2", fields(-2, "hi")))
        assertTrue(HouseSearch.matches("मंज़िल 3", fields(3, "hi")))
        assertTrue(HouseSearch.matches("floor 3", fields(3, "hi")))
        assertFalse(HouseSearch.matches("भूतल", fields(3, "hi")))
        assertFalse(HouseSearch.matches("भूतल", fields(0, "en")))
        assertEquals(fields(3, "en"), HouseSearch.fields(
            label = "Oak Tower", address = null, street = null, locality = null, notes = null, contactName = null, floor = 3,
        ))
        assertEquals(listOf("தரைத்தளம்", "அடித்தளம் 2", "தளம் 12"), listOf(0, -2, 12).map { HouseSearch.localFloorText(it, "ta") })
        assertEquals(listOf("నేల అంతస్తు", "బేస్‌మెంట్ 2", "అంతస్తు 12"), listOf(0, -2, 12).map { HouseSearch.localFloorText(it, "te") })
    }
    @Test fun aQueryMatchesTheMoveInItemsAndNotes() {
        assertEquals(listOf("fern"), matching("keys received"))
        assertEquals(listOf("fern"), matching("4521"))
    }
    @Test fun aQueryMatchesTheAreaNotesThatReachTheHouse() {
        assertEquals(listOf("sea"), matching("tanker"))
        assertEquals(listOf("sea"), matching("monsoon"))
    }
    @Test fun theLabelMatchesIgnoringCase() = assertEquals(listOf("green"), matching("green view"))
    @Test fun theAddressStreetAndLocalityMatch() {
        assertEquals(listOf("green"), matching("5th cross"))
        assertEquals(listOf("green"), matching("INDIRANAGAR"))
    }
    @Test fun theNotesMatch() = assertEquals(listOf("green"), matching("metro"))
    @Test fun theContactNameMatches() = assertEquals(listOf("green"), matching("ravi"))
    @Test fun aQueryMatchesTheBrokersAgency() = assertEquals(listOf("beach"), matching("realty"))
    @Test fun aQueryMatchesTheBrokersFeeTerms() = assertEquals(listOf("beach"), matching("days' rent"))
    @Test fun aQueryMatchesARoomsNote() = assertEquals(listOf("hill"), matching("damp patch"))
    @Test fun aQueryMatchesARoomsName() = assertEquals(listOf("hill"), matching("master bed"))
    @Test fun aQueryMatchesAnAnswersTextAndAnAnswer() {
        assertEquals(listOf("palm"), matching("terrace open"))
        assertEquals(listOf("palm"), matching("borewell"))
        assertEquals(emptyList(), matching("helipad"))
    }
    @Test fun indicTextMatches() = assertEquals(listOf("lake"), matching("हिन्दी"))
    @Test fun aQueryFoundNowhereMatchesNothing() = assertEquals(emptyList(), matching("penthouse"))
    @Test fun theQueryIsTrimmed() = assertEquals(listOf("lake"), matching(" lake "))
    @Test fun emptyValuesAreLeftOut() =
        assertEquals(listOf("Lake Road flat", "हिन्दी नगर"), lake)
}
