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

    @Test
    fun positionalPlaceholdersAreFilledAsComposeResourcesFillThem() {
        assertEquals("2 BHK", formatPositional("%1\$d BHK", 2))
        assertEquals("5 में से 3 स्टार", formatPositional("5 में से %1\$d स्टार", 3))
        assertEquals("Green Villa (best)", formatPositional("%1\$s (best)", "Green Villa"))
        // Order follows the numbers, and each argument is written as it is: a name with % or $ in it stays whole.
        assertEquals("b, a", formatPositional("%2\$s, %1\$s", "a", "b"))
        assertEquals("50% \$1 off (best)", formatPositional("%1\$s (best)", "50% \$1 off"))
        // Anything that is not a positional %d or %s is left alone.
        assertEquals("%d and %1\$f", formatPositional("%d and %1\$f", 1))
    }

    /** The house form's coordinates (CMP-6 P6a): six decimals and a dot, as `%.6f` in `Locale.ROOT` wrote them. */
    @Test
    fun coordinatesHaveSixDecimalsAndADot() {
        assertEquals("12.971600", Formats.coordinate(12.9716))
        assertEquals("77.594600", Formats.coordinate(77.5946))
        assertEquals("0.000000", Formats.coordinate(0.0))
        assertEquals("-45.500000", Formats.coordinate(-45.5))
        assertEquals("180.000000", Formats.coordinate(180.0))
    }

    /**
     * S4b-BL-40: a written half rounds up, as Java's `%.6f` rounds the shortest decimal, on every platform. C's `%.6f`
     * (iOS's `NSString` format) rounds the binary value, just below each of these halves, down.
     */
    @Test
    fun coordinateHalvesRoundUpAndNegativesKeepTheirSign() {
        assertEquals("12.345679", Formats.coordinate(12.3456785))
        assertEquals("-12.345679", Formats.coordinate(-12.3456785))
        assertEquals("0.123457", Formats.coordinate(0.1234565))
        assertEquals("0.000001", Formats.coordinate(0.0000005))
        assertEquals("-0.000001", Formats.coordinate(-0.0000005))
        assertEquals("0.000002", Formats.coordinate(0.0000015))
        assertEquals("1.000001", Formats.coordinate(1.0000005))
        assertEquals("77.594667", Formats.coordinate(77.5946665))
        // Below a half, and a carry through every digit.
        assertEquals("12.345678", Formats.coordinate(12.3456784))
        assertEquals("0.000000", Formats.coordinate(0.00000025))
        assertEquals("180.000000", Formats.coordinate(179.9999995))
        assertEquals("-90.000000", Formats.coordinate(-89.9999999))
        // Negative values, and a negative that rounds to zero keeps its sign (as both Java and C write it).
        assertEquals("-77.594600", Formats.coordinate(-77.5946))
        assertEquals("-0.000000", Formats.coordinate(-0.0000001))
        assertEquals("-0.000000", Formats.coordinate(-0.0))
        // Exponent forms of toString, large and small.
        assertEquals("12345678.900000", Formats.coordinate(12345678.9))
        assertEquals("0.000123", Formats.coordinate(1.23e-4))
    }

    /**
     * S4b-BL-40: every written half from 0.0000005 to 0.0180005 (every seventh) rounds up, and its negative down, on
     * the JVM and on Native alike. Each tie is the double nearest a decimal with at most 15 significant digits, so
     * its shortest decimal (what Java's `%.6f` rounds) is that tie exactly.
     */
    @Test
    fun everyWrittenHalfRoundsUp() {
        for (n in 0..180_000 step 7) {
            val up = n + 1L
            val expected = "${up / 1_000_000}.${(up % 1_000_000).toString().padStart(6, '0')}"
            val tie = (n * 10L + 5) / 10_000_000.0
            assertEquals(expected, Formats.coordinate(tie), "$tie")
            assertEquals("-$expected", Formats.coordinate(-tie), "-$tie")
        }
    }
}
