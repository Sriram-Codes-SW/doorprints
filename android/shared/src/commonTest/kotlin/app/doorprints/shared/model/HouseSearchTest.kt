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

    private fun matching(query: String) =
        listOf("green" to green, "lake" to lake, "beach" to brokered, "hill" to roomy, "palm" to asked, "sea" to noted, "fern" to movingIn)
        .filter { HouseSearch.matches(query, it.second) }.map { it.first }

    @Test fun aBlankQueryMatchesEveryHouse() = assertEquals(listOf("green", "lake", "beach", "hill", "palm", "sea", "fern"), matching("  "))
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
