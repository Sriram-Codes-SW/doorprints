package app.doorprints.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The common half of the formatting (ADR-23 CMP-3, docs/06 TC-U-62): amounts with lakh grouping, and scores. */
class FormatsTest {
    @Test
    fun amountsAreGroupedTheIndianWay() {
        assertEquals("₹0", Formats.rupees(0))
        assertEquals("₹999", Formats.rupees(999))
        assertEquals("₹1,000", Formats.rupees(1_000))
        assertEquals("₹25,000", Formats.rupees(25_000))
        assertEquals("₹1,00,000", Formats.rupees(1_00_000))
        assertEquals("₹12,50,000", Formats.rupees(12_50_000))
        assertEquals("₹1,00,00,000", Formats.rupees(1_00_00_000))
        assertEquals("₹1,00,00,00,00,000", Formats.rupees(100_000_000_000))
        // The form keeps at most 12 digits; a negative amount never happens but still reads.
        assertEquals("₹9,99,99,99,99,999", Formats.rupees(999_999_999_999))
        assertEquals("-₹5,000", Formats.rupees(-5_000))
        assertEquals("-₹92,23,37,20,36,85,47,75,808", Formats.rupees(Long.MIN_VALUE))
    }

    @Test
    fun aRentGetsThePerMonthWordingAndASaleDoesNot() {
        val perMonth = { base: String -> "$base/month" }
        assertEquals("₹25,000/month", Formats.price(25_000, "RENT", perMonth))
        assertEquals("₹1,00,00,000", Formats.price(1_00_00_000, "SALE", perMonth))
        assertEquals("₹1,00,00,000", Formats.price(1_00_00_000, null, perMonth))
        assertNull(Formats.price(null, "RENT", perMonth))
    }

    @Test
    fun scoresHaveOneDecimalRoundedHalfUp() {
        assertEquals("–", Formats.score(null))
        assertEquals("0.0", Formats.score(0.0))
        assertEquals("4.0", Formats.score(4.0))
        assertEquals("3.3", Formats.score(3.25))
        assertEquals("3.2", Formats.score(3.249))
        assertEquals("0.2", Formats.score(0.15))
        assertEquals("2.4", Formats.score(2.35))
        assertEquals("3.7", Formats.score(11.0 / 3))
        assertEquals("10.0", Formats.score(9.96))
    }
}
