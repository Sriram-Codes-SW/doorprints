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

package app.doorprints.shared.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** S4b-BL-83: the name a shared listing's locality is looked up by, and the answer kept, inside India only. */
class PlaceLookupTest {
    @Test
    fun theLocalityIsLookedUpInIndia() {
        assertEquals("Indiranagar, Bengaluru, India", PlaceLookup.query("  Indiranagar,\n Bengaluru "))
        assertEquals("12, MG Road, India", PlaceLookup.query(null, "12, MG Road"))
        assertEquals("Adyar, Chennai, India", PlaceLookup.query("Adyar, Chennai, India"))
        assertEquals(PlaceLookup.MAX_QUERY + ", India".length, PlaceLookup.query("x".repeat(300))!!.length)
        assertNull(PlaceLookup.query(" ", null))
        assertNull(PlaceLookup.query(null, null))
    }

    /** S4b-BL-174: localities of eight regions and four scripts are looked up as typed, with ", India" after them. */
    @Test
    fun localitiesOfEveryRegionAreLookedUpAsTyped() {
        val cases = listOf(
            "  Sector 21,\n Chandigarh 160022 " to "Sector 21, Chandigarh 160022, India",
            "Bandra West, Mumbai 400050" to "Bandra West, Mumbai 400050, India",
            "Candolim,   Goa 403515" to "Candolim, Goa 403515, India",
            "Sanjauli, Shimla 171006" to "Sanjauli, Shimla 171006, India",
            "Beltola Tiniali, Guwahati" to "Beltola Tiniali, Guwahati, India",
            "\u092e\u093e\u0932\u0935\u0940\u092f \u0928\u0917\u0930,  \u091c\u092f\u092a\u0941\u0930" to "\u092e\u093e\u0932\u0935\u0940\u092f \u0928\u0917\u0930, \u091c\u092f\u092a\u0941\u0930, India",
            "\u0b85\u0b9f\u0bc8\u0baf\u0bbe\u0bb1\u0bc1, \u0b9a\u0bc6\u0ba9\u0bcd\u0ba9\u0bc8 600020" to "\u0b85\u0b9f\u0bc8\u0baf\u0bbe\u0bb1\u0bc1, \u0b9a\u0bc6\u0ba9\u0bcd\u0ba9\u0bc8 600020, India",
            "Kakkanad, Kochi, India" to "Kakkanad, Kochi, India",
        )
        for ((typed, query) in cases) assertEquals(query, PlaceLookup.query(typed), typed)
    }

    /** The name is cut on whole characters: half an emoji of a forwarded address would reach the geocoder as a lone surrogate. */
    @Test
    fun aLongAddressIsNeverCutInsideAnEmoji() {
        val house = "\uD83C\uDFE0" // U+1F3E0, two UTF-16 units
        assertEquals("a".repeat(199) + house + ", India", PlaceLookup.query("a".repeat(199) + house + " Bandra West"))
        val many = PlaceLookup.query(house.repeat(300))!!.removeSuffix(", India")
        assertEquals(PlaceLookup.MAX_QUERY * 2, many.length) // 200 emoji, 400 units, none split
        assertEquals(house.repeat(PlaceLookup.MAX_QUERY), many)
    }

    /** S4b-BL-174: the box takes every extreme of India, which is the date-line-free span 68.1..97.4 E and 6.5..37.1 N. */
    @Test
    fun theBoxTakesTheExtremesOfIndiaAndRefusesFarAwayPlaces() {
        val inIndia = listOf(
            "Kanyakumari" to (8.0883 to 77.5385), "Indira Point" to (6.7456 to 93.8403), "Guhar Moti, Kutch" to (23.72 to 68.12),
            "Kibithu, Arunachal Pradesh" to (28.2 to 97.0), "Kavaratti" to (10.5626 to 72.6369), "Port Blair" to (11.6234 to 92.7265),
            "Srinagar" to (34.0837 to 74.7973), "Leh" to (34.1526 to 77.5771), "Siachen base camp" to (35.42 to 77.1),
            "Gangtok" to (27.3389 to 88.6065), "Guwahati" to (26.1445 to 91.7362), "Mumbai" to (19.076 to 72.8777),
        )
        for ((name, p) in inIndia) assertEquals(PlaceLookup.Found(p.first, p.second), PlaceLookup.pick(listOf(p)), name)
        val faraway = listOf(
            "Karachi" to (24.8607 to 67.0011), "Dubai" to (25.2048 to 55.2708), "London" to (51.5 to -0.12),
            "Singapore" to (1.3521 to 103.8198), "Bangkok" to (13.7563 to 100.5018), "Tashkent" to (41.2995 to 69.2401),
        )
        for ((name, p) in faraway) assertNull(PlaceLookup.pick(listOf(p)), name)
    }

    /**
     * KNOWN GAP S4b-BL-174c (docs/10): the box is a rectangle, not India's outline, so a place of a neighbour inside it is
     * accepted. The lookup says ", India" and the website sends `countrycodes=in`, so a neighbour's town is rarely what comes
     * back; the day it does, the pin is dropped abroad. The wanted answer is null for each; when the filter uses the outline,
     * this test turns into the assertion of that.
     */
    @Test
    fun neighboursInsideTheBoxAreAcceptedUntilTheFilterUsesTheOutline() {
        val neighbours = listOf(
            "Dhaka" to (23.8103 to 90.4125), "Kathmandu" to (27.7172 to 85.324), "Colombo" to (6.9271 to 79.8612),
            "Lahore" to (31.5497 to 74.3436), "Lhasa" to (29.65 to 91.1),
        )
        for ((name, p) in neighbours) assertEquals(PlaceLookup.Found(p.first, p.second), PlaceLookup.pick(listOf(p)), name)
    }

    @Test
    fun theFirstPointInIndiaIsKept() {
        assertEquals(PlaceLookup.Found(12.9784, 77.6408), PlaceLookup.pick(listOf(51.5 to -0.12, 12.9784 to 77.6408)))
        assertNull(PlaceLookup.pick(listOf(0.0 to 0.0)))
        assertNull(PlaceLookup.pick(listOf(Double.NaN to 77.0)))
        assertNull(PlaceLookup.pick(emptyList()))
    }
}
