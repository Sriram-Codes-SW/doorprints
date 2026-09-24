package app.doorprints

import app.doorprints.data.HouseEntity
import app.doorprints.location.Place
import app.doorprints.ui.LAST_FIX_MAX_AGE_MS
import app.doorprints.ui.ListingField
import app.doorprints.ui.PAIR_STACK_BELOW_DP
import app.doorprints.ui.PAIR_STACK_FONT_SCALE
import app.doorprints.ui.RECENT_VISIT_MS
import app.doorprints.ui.canAskAgain
import app.doorprints.ui.fillPlace
import app.doorprints.ui.lastFixUsable
import app.doorprints.ui.mergeListing
import app.doorprints.ui.parseCoordinate
import app.doorprints.ui.sortByPrice
import app.doorprints.ui.stackFieldPair
import app.doorprints.ui.visitIsRecent
import app.doorprints.shared.api.HouseDraftDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The house form's and the lists' pure decisions from the whole-app UX audit (2026-09-22): the late address fill of a
 * new house, the listing fill that no longer overwrites typed fields, typed coordinates, "Lowest price" with rents and
 * sales apart, the "I am here now" duplicate guard, and the age and accuracy a last-known fix needs.
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
    fun aListingThatAgreesWithTheFormReportsNothing() {
        val merged = mergeListing(house(contactName = "Ravi"), HouseDraftDto(contactName = "Ravi"), labelIsPlaceholder = true)
        assertTrue(merged.filled.isEmpty())
        assertTrue(merged.kept.isEmpty())
    }

    @Test
    fun coordinatesMustParseAndBeInRange() {
        assertEquals(12.9716, parseCoordinate("12.9716", 90.0)!!, 0.0)
        assertEquals(12.5, parseCoordinate(" 12,5 ", 90.0)!!, 0.0)
        assertEquals(-33.9, parseCoordinate("-33.9", 90.0)!!, 0.0)
        assertEquals(180.0, parseCoordinate("180", 180.0)!!, 0.0)
        assertNull(parseCoordinate("91", 90.0))
        assertNull(parseCoordinate("-180.5", 180.0))
        assertNull(parseCoordinate("", 90.0))
        assertNull(parseCoordinate("abc", 90.0))
        assertNull(parseCoordinate("NaN", 90.0))
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

    @Test
    fun locationCanBeAskedUntilAndroidStopsShowingItsPrompt() {
        // Never asked (by any screen): Android shows its prompt.
        assertTrue(canAskAgain(asked = false, rationale = false))
        // Refused once: Android asks again and says a rationale may be shown.
        assertTrue(canAskAgain(asked = true, rationale = true))
        // Refused twice, or "Don't ask again": only the app's settings can turn it on.
        assertFalse(canAskAgain(asked = true, rationale = false))
    }
}
