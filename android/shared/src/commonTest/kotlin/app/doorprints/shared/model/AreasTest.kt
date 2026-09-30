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

import app.doorprints.shared.ai.RouteOptimizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The records and derived rules of slice 4a (docs/11 "Design of slice 4a"): the payload keys and their order, what a
 * reader keeps, what a file must hold, the notes that reach a house (vectors N1..N5, the web's `area.spec.ts` and the
 * server's `HouseDocumentsTest` have the same) and the distances (D1..D3), one decimal half up.
 */
class AreasTest {
    private val adyar = Area("a_1f2e3d4c", "Adyar", 13.0067, 80.2574, 500)
    private val areaNote = AreaNote("n_11223344", areaId = adyar.id, text = "Water tanker every morning.", updatedAt = 10)
    private val streetNote = AreaNote("n_55667788", street = "MG Road", text = "Noisy after 9 pm.", updatedAt = 20)

    /** A point [meters] north of the area's centre (one degree of latitude is about 111.2 km on this sphere). */
    private fun north(meters: Double) = adyar.lat + meters / (RouteOptimizer.haversineMeters(0.0, 0.0, 1.0, 0.0))

    @Test
    fun theVectorN1AHouse50MetresFromTheCentreGetsTheAreaNote() {
        val house = HousePoint(north(50.0), adyar.lon, locationSource = LocationSource.GPS)
        assertEquals(listOf(areaNote), AreaNotes.reaching(house, listOf(adyar), listOf(areaNote)))
        assertEquals(listOf(adyar), AreaNotes.areasReaching(house, listOf(adyar)))
    }

    @Test
    fun theVectorN2AHouse600MetresFromA500MetreAreaDoesNot() {
        val house = HousePoint(north(600.0), adyar.lon, locationSource = LocationSource.MAP)
        assertEquals(emptyList(), AreaNotes.reaching(house, listOf(adyar), listOf(areaNote)))
    }

    @Test
    fun theVectorN3AnApproxHouseGetsNoAreaNoteButItsStreetNote() {
        val house = HousePoint(adyar.lat, adyar.lon, street = "MG Road", locationSource = LocationSource.APPROX)
        assertEquals(listOf(streetNote), AreaNotes.reaching(house, listOf(adyar), listOf(areaNote, streetNote)))
        // A house at (0, 0) has no point at all: no area note there either, but its street note.
        assertEquals(listOf(streetNote), AreaNotes.reaching(HousePoint(0.0, 0.0, "MG Road"), listOf(adyar.copy(lat = 0.0, lon = 0.0)), listOf(areaNote, streetNote)))
    }

    @Test
    fun theVectorN4TheStreetMatchesTrimmedIgnoringCase() {
        val house = HousePoint(12.0, 77.0, street = "mg road ")
        assertEquals(listOf(streetNote), AreaNotes.reaching(house, emptyList(), listOf(streetNote)))
        assertFalse(AreaNotes.sameStreet("  ", "  "), "a blank street matches nothing")
        assertFalse(AreaNotes.sameStreet(null, "MG Road"))
    }

    @Test
    fun theVectorN5ANoteWhoseAreaIsGoneReachesNoHouse() {
        val house = HousePoint(adyar.lat, adyar.lon, locationSource = LocationSource.GPS)
        assertEquals(emptyList(), AreaNotes.reaching(house, emptyList(), listOf(areaNote)))
    }

    @Test
    fun notesComeNewestFirstTiesById() {
        val house = HousePoint(adyar.lat, adyar.lon, street = "MG Road", locationSource = LocationSource.GPS)
        val tie = streetNote.copy(id = "n_00000001")
        assertEquals(
            listOf("n_00000001", "n_55667788", "n_11223344"),
            AreaNotes.reaching(house, listOf(adyar), listOf(areaNote, streetNote, tie)).map { it.id },
        )
    }

    @Test
    fun theVectorsD1D2D3KilometresWithOneDecimalHalfUp() {
        val office = Place("p_0a1b2c3d", "Office", 13.0827, 80.2707)
        val d1 = Distances.toPlaces(HousePoint(13.0067, 80.2574), listOf(office)).single()
        assertEquals(8572.7, d1.meters, 0.1)
        assertEquals("8.6", d1.km)
        assertEquals("0.0", Distances.toPlaces(HousePoint(13.0827, 80.2707), listOf(office)).single().km)
        val d3 = Distances.toPlaces(HousePoint(12.9716, 77.5946), listOf(Place("p_1", "Amma", 13.0, 77.6))).single()
        assertEquals(3211.7, d3.meters, 0.1)
        assertEquals("3.2", d3.km)
        assertEquals(listOf("0.1", "0.2", "12.3"), listOf(Distances.km(50.0), Distances.km(150.0), Distances.km(12_345.0)))
        // Plan's walking estimate: metres x 1.3 at 80 m a minute, rounded up (8572.7 m is 140 min).
        assertEquals(RouteOptimizer.estimateWalkMinutes(d1.meters), d1.minutes)
        assertEquals(140, d1.minutes)
    }

    @Test
    fun aHouseWithoutAPointGetsNoDistancesAndTheNearestComesFirst() {
        val far = Place("p_2", "Far", 14.0, 80.0)
        val near = Place("p_1", "Near", 13.01, 80.26)
        assertEquals(emptyList(), Distances.toPlaces(HousePoint(0.0, 0.0), listOf(far, near)))
        val list = Distances.nearestFirst(Distances.toPlaces(HousePoint(13.0, 80.25), listOf(far, near)))
        assertEquals(listOf("Near", "Far"), list.map { it.place.name })
    }

    @Test
    fun theAreaPayloadKeysAreInOrderAndEnabledOnlyWhenFalse() {
        assertEquals("{\"name\":\"Adyar\",\"lat\":13.0067,\"lon\":80.2574,\"radiusM\":500}", AreaType.encode(adyar))
        assertEquals(
            "{\"name\":\"Adyar\",\"lat\":13.0067,\"lon\":80.2574,\"radiusM\":1200,\"enabled\":false}",
            AreaType.encode(adyar.copy(radiusM = 1200, enabled = false)),
        )
        assertEquals("{\"name\":\"Office\",\"lat\":13.0827,\"lon\":80.2707}", PlaceType.encode(Place("p_0a1b2c3d", "Office", 13.0827, 80.2707)))
        assertEquals("{\"areaId\":\"a_1f2e3d4c\",\"text\":\"Water tanker every morning.\"}", AreaNoteType.encode(areaNote))
        assertEquals("{\"street\":\"MG Road\",\"text\":\"Noisy after 9 pm.\"}", AreaNoteType.encode(streetNote))
    }

    @Test
    fun aReaderKeepsARadiusOutOfRangeAs500AndSkipsAnUntrustedRow() {
        assertEquals(500, adyar.copy(radiusM = 5000).coerced()?.radiusM)
        assertEquals(200, adyar.copy(radiusM = 200).coerced()?.radiusM)
        assertNull(adyar.copy(name = " ").coerced())
        assertNull(adyar.copy(name = "x".repeat(101)).coerced())
        assertNull(adyar.copy(lat = 91.0).coerced())
        assertNull(adyar.copy(lon = Double.NaN).coerced())
        assertNull(adyar.copy(id = "..").coerced())
        assertNull(Place("p_1", "x".repeat(61), 1.0, 1.0).coerced())
        assertTrue(Place("p_1", "x".repeat(60), 1.0, 1.0).isValid)
    }

    @Test
    fun aFileMustHoldAValidRadiusAndExactlyOneTarget() {
        assertTrue(adyar.isValid)
        assertFalse(adyar.copy(radiusM = 199).isValid)
        assertFalse(adyar.copy(radiusM = 2001).isValid)
        assertTrue(areaNote.isValid && streetNote.isValid)
        assertFalse(AreaNote("n_1", text = "x").isValid, "no target")
        assertFalse(AreaNote("n_1", areaId = "a_1", street = "MG Road", text = "x").isValid, "both targets")
        assertFalse(AreaNote("n_1", street = " ", text = "x").isValid, "a blank street")
        assertFalse(AreaNote("n_1", street = "MG Road", text = " ").isValid, "a blank text")
        assertFalse(AreaNote("n_1", street = "x".repeat(101), text = "x").isValid)
        assertFalse(AreaNote("n_1", areaId = "a".repeat(65), text = "x").isValid)
        assertFalse(AreaNote("n_1", street = "MG Road", text = "x".repeat(1001)).isValid)
        assertTrue(AreaNote("n_1", street = "MG Road", text = "x".repeat(1000)).isValid)
        // On read, a blank target counts as absent and a row without exactly one target is skipped.
        assertEquals("MG Road", AreaNote("n_1", areaId = "", street = "MG Road", text = "x").coerced()?.street)
        assertNull(AreaNote("n_1", areaId = "a_1", street = "MG Road", text = "x").coerced())
        assertNull(AreaNote("n_1", text = "x").coerced())
    }

    @Test
    fun freshIdsHaveThePrefixAndEightHexAndNeverClash() {
        val random = Random(4)
        val first = Area.newId({ false }, random)
        assertTrue(Regex("a_[0-9a-f]{8}").matches(first), first)
        assertTrue(Regex("p_[0-9a-f]{8}").matches(Place.newId({ false })))
        assertTrue(Regex("n_[0-9a-f]{8}").matches(AreaNote.newId({ false })))
        val again = Area.newId({ it == first }, Random(4))
        assertTrue(again != first)
    }
}
