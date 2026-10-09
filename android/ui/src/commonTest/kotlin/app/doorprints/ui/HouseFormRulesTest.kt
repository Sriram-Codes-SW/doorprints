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

import app.doorprints.data.HouseEntity
import app.doorprints.location.Place
import app.doorprints.shared.api.HouseDraftDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The house form's and the lists' pure decisions from the whole-app UX audit (2026-09-22): the late address fill of a
 * new house, the listing fill that no longer overwrites typed fields, typed coordinates, "Lowest price" with rents and
 * sales apart, the "I am here now" duplicate guard, and the age and accuracy a last-known fix needs. `kotlin.test` in
 * `:ui` commonTest since CMP-4 P4c (was a JUnit test in `:app`; the location prompt case is in `LocationAccessTest`).
 */
class HouseFormRulesTest {

    private val default = "New house"
    private fun streetLabel(street: String) = "House on $street"

    private fun house(
        label: String = default,
        street: String? = null,
        locality: String? = null,
        address: String? = null,
        price: Long? = null,
        priceType: String? = "RENT",
        contactName: String? = null,
        notes: String? = null,
    ) = HouseEntity(
        id = "h1", label = label, address = address, street = street, locality = locality,
        lat = 12.97, lon = 77.64, price = price, priceType = priceType, contactName = contactName, notes = notes,
        createdAt = 1L, updatedAt = 1L,
    )

    private val place = Place(street = "MG Road", locality = "Ashok Nagar", address = "12, MG Road, Bengaluru")

    @Test
    fun theAddressFillsAnUntouchedFormAndIsNotAnUnsavedChange() {
        val start = house()
        val (draft, baseline) = fillPlace(start, start, place, default, ::streetLabel)
        assertEquals("House on MG Road", draft.label)
        assertEquals("MG Road", draft.street)
        assertEquals("Ashok Nagar", draft.locality)
        assertEquals("12, MG Road, Bengaluru", draft.address)
        // The same values went into the baseline, so the form is not dirty.
        assertEquals(draft, baseline)
    }

    @Test
    fun theAddressNeverOverwritesWhatWasTypedMeanwhile() {
        val start = house()
        val typed = start.copy(label = "Blue gate", locality = "Domlur")
        val (draft, baseline) = fillPlace(typed, start, place, default, ::streetLabel)
        assertEquals("Blue gate", draft.label)
        assertEquals("Domlur", draft.locality)
        // Untouched fields are still filled, in both.
        assertEquals("MG Road", draft.street)
        assertEquals("MG Road", baseline.street)
        // What was typed stays an unsaved change: the baseline keeps the default name and no locality.
        assertEquals(default, baseline.label)
        assertNull(baseline.locality)
        assertEquals("12, MG Road, Bengaluru", baseline.address)
    }

    @Test
    fun anAddressWithoutAStreetKeepsTheDefaultName() {
        val start = house()
        val (draft, _) = fillPlace(start, start, Place(null, "Ashok Nagar", null), default, ::streetLabel)
        assertEquals(default, draft.label)
        assertNull(draft.street)
        assertEquals("Ashok Nagar", draft.locality)
    }

    @Test
    fun aListingFillsOnlyEmptyFieldsAndReportsTheRest() {
        val form = house(label = "Blue gate", contactName = "Ravi", price = null)
        val listing = HouseDraftDto(
            label = "2BHK near metro", price = 25_000, priceType = "RENT", contactName = "Suresh",
            contactPhone = "+91 98450 00000", amenities = listOf("lift", "parking"),
        )
        val merged = mergeListing(form, listing, labelIsPlaceholder = false)
        assertEquals("Blue gate", merged.house.label)
        assertEquals("Ravi", merged.house.contactName)
        assertEquals(25_000L, merged.house.price)
        assertEquals("+91 98450 00000", merged.house.contactPhone)
        assertEquals("lift, parking", merged.house.notes)
        assertEquals(listOf(ListingField.PRICE, ListingField.PHONE, ListingField.NOTES), merged.filled)
        assertEquals(listOf(ListingField.NAME, ListingField.CONTACT), merged.kept)
    }

    @Test
    fun theDefaultNameCountsAsEmptyAndTheSaleTypeGoesWithThePrice() {
        val merged = mergeListing(
            house(),
            HouseDraftDto(label = "Villa in Whitefield", price = 1_20_00_000, priceType = "SALE"),
            labelIsPlaceholder = true,
        )
        assertEquals("Villa in Whitefield", merged.house.label)
        assertEquals("SALE", merged.house.priceType)
        assertEquals(listOf(ListingField.NAME, ListingField.PRICE), merged.filled)
        assertTrue(merged.kept.isEmpty())
    }

    @Test
    fun aTypedPriceKeepsItsRentOrBuyChoice() {
        val merged = mergeListing(
            house(price = 30_000, priceType = "RENT"),
            HouseDraftDto(price = 90_00_000, priceType = "SALE"),
            labelIsPlaceholder = true,
        )
        assertEquals(30_000L, merged.house.price)
        assertEquals("RENT", merged.house.priceType)
        assertEquals(listOf(ListingField.PRICE), merged.kept)
    }

    @Test
    fun aListingsAreaFillsTheEmptyFieldAndIsKeptOtherwise() {
        val filled = mergeListing(house(), HouseDraftDto(areaSqft = 1150), labelIsPlaceholder = true)
        assertEquals(1150, filled.house.areaSqft)
        assertEquals(listOf(ListingField.AREA), filled.filled)
        val kept = mergeListing(house().copy(areaSqft = 900), HouseDraftDto(areaSqft = 1150), labelIsPlaceholder = true)
        assertEquals(900, kept.house.areaSqft)
        assertEquals(listOf(ListingField.AREA), kept.kept)
    }

    @Test
    fun aListingThatAgreesWithTheFormReportsNothing() {
        val merged = mergeListing(house(contactName = "Ravi"), HouseDraftDto(contactName = "Ravi"), labelIsPlaceholder = true)
        assertTrue(merged.filled.isEmpty())
        assertTrue(merged.kept.isEmpty())
    }

    @Test
    fun coordinatesMustParseAndBeInRange() {
        assertEquals(12.9716, parseCoordinate("12.9716", 90.0))
        assertEquals(12.5, parseCoordinate(" 12,5 ", 90.0))
        assertEquals(-33.9, parseCoordinate("-33.9", 90.0))
        assertEquals(180.0, parseCoordinate("180", 180.0))
        assertNull(parseCoordinate("91", 90.0))
        assertNull(parseCoordinate("-180.5", 180.0))
        assertNull(parseCoordinate("", 90.0))
        assertNull(parseCoordinate("abc", 90.0))
        assertNull(parseCoordinate("NaN", 90.0))
    }

    /** S4b-BL-33: the JVM and Kotlin/Native take the same text, so only plain decimals pass. */
    @Test
    fun coordinatesTakeOnlyASignAsciiDigitsAndOneSeparator() {
        assertEquals(12.0, parseCoordinate("+12", 90.0))
        assertEquals(0.5, parseCoordinate(".5", 90.0))
        assertEquals(-0.5, parseCoordinate("-,5", 90.0))
        assertEquals(3.0, parseCoordinate("3.", 90.0))
        assertEquals(-7.25, parseCoordinate("\t-7,25\n", 90.0))
        assertEquals(12.971599, parseCoordinate("0012.971599", 90.0))
        assertEquals(-90.0, parseCoordinate("-90.000", 90.0))
        // Exponents, type suffixes, hex and the special values: the JVM's toDoubleOrNull takes most of these.
        listOf("1e1", "1E1", "1.5e-3", "12d", "12D", "12f", "12F", "0x1p3", "Infinity", "-Infinity", "NaN", "-NaN")
            .forEach { assertNull(parseCoordinate(it, 180.0), it) }
        // Non-ASCII digits: Devanagari, Tamil, Telugu, full-width.
        listOf("१२.५", "௧௨.௫", "౧౨.౫", "１２.５").forEach { assertNull(parseCoordinate(it, 90.0), it) }
        // A space inside, two signs, a trailing sign, two separators or both kinds, a bare sign or separator.
        listOf("12 .5", "- 12", "--12", "+-12", "12-", "1.2.3", "1,2,3", "1,234.5", "12.5,3", "-", "+", ".", ",", "-.")
            .forEach { assertNull(parseCoordinate(it, 180.0), it) }
    }

    @Test
    fun lowestPricePutsRentsBeforeSalesEachFromLowToHigh() {
        val rentHigh = house(price = 40_000, priceType = "RENT").copy(id = "r40")
        val rentLow = house(price = 25_000, priceType = "RENT").copy(id = "r25")
        val saleLow = house(price = 60_00_000, priceType = "SALE").copy(id = "s60")
        val saleHigh = house(price = 1_00_00_000, priceType = "SALE").copy(id = "s100")
        val rentNone = house(price = null, priceType = "RENT").copy(id = "rX")
        val saleNone = house(price = null, priceType = "SALE").copy(id = "sX")
        val sorted = sortByPrice(listOf(saleHigh, rentNone, saleLow, rentHigh, saleNone, rentLow)).map { it.id }
        assertEquals(listOf("r25", "r40", "rX", "s60", "s100", "sX"), sorted)
    }

    @Test
    fun iAmHereNowSkipsAVisitRecordedMinutesAgo() {
        val now = 1_000_000_000L
        assertFalse(visitIsRecent(null, now))
        assertTrue(visitIsRecent(now - 60_000, now))
        assertTrue(visitIsRecent(now - RECENT_VISIT_MS + 1, now))
        assertFalse(visitIsRecent(now - RECENT_VISIT_MS, now))
        // A visit dated in the future (clock change) does not block a new one.
        assertFalse(visitIsRecent(now + 60_000, now))
    }

    @Test
    fun aLastKnownFixMustBeRecentAndAccurate() {
        assertTrue(lastFixUsable(30_000, 12f, 50f))
        assertTrue(lastFixUsable(LAST_FIX_MAX_AGE_MS, 50f, 50f))
        assertFalse(lastFixUsable(LAST_FIX_MAX_AGE_MS + 1, 12f, 50f))
        assertFalse(lastFixUsable(30_000, 80f, 50f))
        assertFalse(lastFixUsable(30_000, null, 50f))
        assertFalse(lastFixUsable(-1, 12f, 50f))
    }

    @Test
    fun fieldPairsStayTogetherOnABudgetPhoneAtNormalFont() {
        // A 360 dp phone leaves 328 dp inside the form's 16 dp gutters: side by side (round 2 of the audit; 1.24
        // stacked everything under 392 dp).
        assertFalse(stackFieldPair(328f, 1.0f))
        assertFalse(stackFieldPair(PAIR_STACK_BELOW_DP, 1.15f))
        // Narrower than 300 dp of room, or from 1.3x font, they stack.
        assertTrue(stackFieldPair(PAIR_STACK_BELOW_DP - 1f, 1.0f))
        assertTrue(stackFieldPair(288f, 1.0f))
        assertTrue(stackFieldPair(600f, PAIR_STACK_FONT_SCALE))
        assertTrue(stackFieldPair(328f, 2.0f))
    }

    /** *Open* is only for an http(s) link with a host (CMP-6 P6a; `LinkParityTest` in `:app` pins Android's rule). */
    @Test
    fun onlyAnHttpLinkWithAHostCanBeOpened() {
        assertTrue(isWebLink("https://www.99acres.com/listing?id=1"))
        assertTrue(isWebLink("HTTP://Example.com"))
        assertTrue(isWebLink("http://user:pw@example.com:8080/x"))
        assertFalse(isWebLink("www.example.com"))
        assertFalse(isWebLink("ftp://example.com"))
        assertFalse(isWebLink("javascript:alert(1)"))
        assertFalse(isWebLink("http:/example.com"))
        assertFalse(isWebLink("http://"))
        assertFalse(isWebLink("http://:80/x"))
        // The host is percent-decoded before the blank check; a malformed escape is not blank.
        assertFalse(isWebLink("http://%20%20/x"))
        assertTrue(isWebLink("http://%zz"))
    }

    /** The form's floor (S4b-BL-87): an optional minus and up to three digits within -5..200, else unknown. */
    @Test
    fun theFloorFieldReadsMinusFiveToTwoHundred() {
        assertEquals(listOf(0, 3, -2, 200, -5), listOf("0", " 3 ", "-2", "200", "-5").map(::floorOf))
        for (bad in listOf("", "-", "201", "-6", "1.5", "2-", "--1", "1000")) assertNull(floorOf(bad), bad)
    }

    /** The Basement switch (S4b-BL-104 c): the digits are a level 1 to 5 below the ground, no minus needed. */
    @Test
    fun theBasementSwitchMakesTheDigitsALevelBelowTheGround() {
        assertEquals(listOf(-1, -2, -5, -3), listOf("1", " 2 ", "5", "-3").map { floorOf(it, basement = true) })
        for (bad in listOf("", "0", "6", "200", "1.5", "--1")) assertNull(floorOf(bad, basement = true), bad)
        // Off, the field reads as before.
        assertEquals(listOf(0, 3, -2), listOf("0", "3", "-2").map { floorOf(it, basement = false) })
    }

    /** A checklist score (the form's rows): set, replaced, cleared by the same value again or by "–", others untouched. */
    @Test
    fun aChecklistScoreIsSetReplacedAndClearedByTheSameValueOrByDash() {
        val start = mapOf("water" to 3, "security" to 0)
        // Set, and a new value replaces the old one (0 is a score, not "none").
        assertEquals(mapOf("water" to 3, "security" to 0, "parking" to 5), start.withScore("parking", 5))
        assertEquals(mapOf("water" to 4, "security" to 0), start.withScore("water", 4))
        assertEquals(mapOf("water" to 3, "security" to 2), start.withScore("security", 2))
        // The chosen score again clears it, zero included.
        assertEquals(mapOf("security" to 0), start.withScore("water", 3))
        assertEquals(mapOf("water" to 3), start.withScore("security", 0))
        // "–" clears; a criterion without a score stays without one.
        assertEquals(mapOf("security" to 0), start.withScore("water", null))
        assertEquals(start, start.withScore("parking", null))
        assertEquals(emptyMap(), emptyMap<String, Int>().withScore("water", null))
        // The scores of other criteria (an archived one's among them) are left as they are.
        assertEquals(mapOf("old" to 1, "water" to 2), mapOf("old" to 1).withScore("water", 2))
    }
}
