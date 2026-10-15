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

package app.doorprints.shared.listing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The no-AI listing parser (docs/11 5.29): the shapes of share text the portals send; the fixtures run in `ListingFixturesTest`. */
class ListingTextTest {
    @Test
    fun aRentListingFillsPriceBhkLocalityLinkAndPhone() {
        val text = """
            2 BHK Flat for Rent in Indiranagar, Bengaluru
            ₹ 32,000 per month · 1150 sq ft · Semi-furnished
            Contact: Ravi 98450 12345
            https://www.magicbricks.com/propertyDetails/2-BHK-1150-Sq-ft-Multistorey-Apartment-FOR-Rent-Indiranagar-in-Bangalore&id=4d4235?utm_source=share&utm_medium=whatsapp
        """.trimIndent()
        val d = ListingText.parse(text)
        assertEquals("2 BHK Flat for Rent in Indiranagar, Bengaluru", d.label)
        assertEquals(32_000L, d.price)
        assertEquals("RENT", d.priceType)
        assertEquals(2, d.bedrooms)
        assertEquals("Indiranagar", d.locality)
        assertEquals("98450 12345", d.contactPhone)
        assertEquals("https://www.magicbricks.com/propertyDetails/2-BHK-1150-Sq-ft-Multistorey-Apartment-FOR-Rent-Indiranagar-in-Bangalore&id=4d4235", d.listingUrl)
        assertEquals("MagicBricks", ListingText.portal(d.listingUrl))
        assertEquals(true, d.notes!!.startsWith("1150 sq ft, Semi-furnished\n\n2 BHK"))
        assertEquals(1150, d.areaSqft, "the area the notes line names is the draft's carpet area (slice 1a)")
    }

    @Test
    fun aSaleInCroresIsASaleAndAStudioIsZeroBedrooms() {
        val sale = ListingText.parse("3 BHK Apartment for Sale in Kondapur, Hyderabad\n1.2 Cr\nhttps://www.99acres.com/x-spid-1")
        assertEquals(12_000_000L, sale.price)
        assertEquals("SALE", sale.priceType)
        assertEquals("Kondapur", sale.locality)
        assertEquals("99acres", ListingText.portal(sale.listingUrl))
        val bare = ListingText.parse("Nice flat at Velachery, Chennai\n45 Lakh\nhttps://housing.com/in/buy/x")
        assertEquals("SALE", bare.priceType, "ten lakh and above without rent words is a sale")
        val studio = ListingText.parse("Studio for rent in Andheri West, Mumbai at Rs. 18,500/month\n+91 98200 00000")
        assertEquals(0, studio.bedrooms)
        assertEquals(18_500L, studio.price)
        assertEquals("Andheri West", studio.locality)
    }

    @Test
    fun aBareLinkGivesALinkAndAGeneratedLabelAndNothingInvented() {
        val d = ListingText.parse("https://www.nobroker.in/property/12345?utm_campaign=app")
        assertEquals("https://www.nobroker.in/property/12345", d.listingUrl)
        assertNull(d.price)
        assertNull(d.bedrooms)
        assertNull(d.areaSqft)
        assertNull(d.contactPhone)
        assertEquals("House", d.label, "the sanitiser's label when the text gives none")
        assertEquals(emptyList(), d.warnings)
    }

    @Test
    fun linksAreCleanedOfTrackingOnlyAndHostsAreMatchedBySuffix() {
        assertEquals("https://a.in/p?id=7#photos", ListingText.cleanUrl("https://a.in/p?utm_source=x&id=7&fbclid=9#photos"))
        assertEquals("https://a.in/p", ListingText.cleanUrl("https://a.in/p?gclid=1"))
        assertEquals("https://a.in/p", ListingText.cleanUrl("https://a.in/p"))
        assertEquals("NoBroker", ListingText.portal("https://m.nobroker.in/x"))
        assertNull(ListingText.portal("https://nobroker.in.evil.example/x"))
        assertNull(ListingText.portal(null))
    }

    /** S4b-BL-174b: a price, a bedroom count, an area and a phone in the digits of an Indian script are read as 0-9; the text kept is as written. */
    @Test
    fun numbersInNativeDigitsAreRead() {
        val devanagari = ListingText.parse("\u0968.\u096B BHK flat for rent in Indiranagar, Bengaluru\nRs. \u096A\u096B,\u0966\u0966\u0966 monthly \u00b7 \u0967\u0968\u0966\u0966 sq ft\n\u0968\u0969\u096A\u096B\u0966 \u0967\u0968\u0969\u096A\u096B")
        assertEquals(45_000L, devanagari.price)
        assertEquals(2, devanagari.bedrooms, "2.5 BHK is 2, in any digits")
        assertEquals(1200, devanagari.areaSqft)
        assertEquals("RENT", devanagari.priceType)
        assertEquals("Indiranagar", devanagari.locality)
        assertEquals("\u0968.\u096B BHK flat for rent in Indiranagar, Bengaluru", devanagari.label, "the label is as written")
        val tamil = ListingText.parse("Flat in Adyar, Chennai\n\u20b9 \u0BE8.\u0BEB Cr\n+\u0BEF\u0BE7 \u0BEF\u0BEE\u0BEE\u0BE6\u0BE6 \u0BE7\u0BE8\u0BE9\u0BEA\u0BEB")
        assertEquals(25_000_000L, tamil.price)
        assertEquals("SALE", tamil.priceType)
        assertEquals("+91 98800 12345", tamil.contactPhone)
    }
}
