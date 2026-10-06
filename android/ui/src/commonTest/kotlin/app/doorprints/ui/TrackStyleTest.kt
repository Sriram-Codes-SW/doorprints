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

import app.doorprints.shared.trace.RepeatLook
import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.trace.TraceWalk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The path trace's style and GeoJSON (docs/11 5.27.4; TC-U-149): kinds, the overlay layer, the widths of each look. */
class TrackStyleTest {
    private val minute = 60_000L

    private fun p(at: Long, lat: Double = 12.97, lon: Double = 77.64, walk: Long = 1) = TracePoint(lat, lon, at, walk)

    private fun features(json: String) = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.map { it.jsonObject }

    private fun kind(f: JsonObject) = f["properties"]!!.jsonObject["kind"]!!.jsonPrimitive.content

    private fun stops(expression: JsonArray): List<Pair<Double, Double>> =
        expression.drop(3).chunked(2).map { it[0].jsonPrimitive.content.toDouble() to it[1].jsonPrimitive.content.toDouble() }

    private fun paint(look: RepeatLook) = trackRepeatLayerJson(look)["paint"]!!.jsonObject

    @Test
    fun theBaseLineIsOneLineStringPerWalkWithLonLatCoordinatesAndItsTimes() {
        val json = trackGeoJson(listOf(listOf(p(0, 12.9, 77.6), p(minute, 12.91, 77.61))))
        val feature = features(json).single()
        assertEquals("base", kind(feature))
        assertEquals("LineString", feature["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("[[77.6,12.9],[77.61,12.91]]", feature["geometry"]!!.jsonObject["coordinates"].toString())
        assertEquals("0", feature["properties"]!!.jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals("60000", feature["properties"]!!.jsonObject["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLonePointDrawsNothingAndNoWalksIsAnEmptyCollection() {
        assertEquals(emptyList(), features(trackGeoJson(listOf(listOf(p(0))))))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", trackGeoJson(emptyList()))
    }

    @Test
    fun theShownPiecesAreRepeatFeaturesWithLonLatAfterTheBaseLines() {
        val json = trackGeoJson(listOf(listOf(p(0, 12.9, 77.6), p(minute, 12.91, 77.61))), listOf(listOf(12.9 to 77.6, 12.905 to 77.605)))
        val all = features(json)
        assertEquals(listOf("base", "repeat"), all.map(::kind))
        assertEquals("[[77.6,12.9],[77.605,12.905]]", all[1]["geometry"]!!.jsonObject["coordinates"].toString())
    }

    @Test
    fun aPieceOfOnePointDrawsNothing() {
        assertEquals(emptyList(), features(trackGeoJson(emptyList(), listOf(listOf(12.9 to 77.6)))))
    }

    @Test
    fun theBaseLayerDrawsOnlyTheBaseKindAndTheOverlayOnlyTheRepeatKind() {
        assertEquals("""["==",["get","kind"],"base"]""", trackLayerJson()["filter"].toString())
        assertEquals("""["==",["get","kind"],"repeat"]""", trackRepeatLayerJson(RepeatLook.CLEAR)["filter"].toString())
        assertEquals("track-line", trackLayerJson()["id"]!!.jsonPrimitive.content)
        assertEquals("track-repeat-line", trackRepeatLayerJson(RepeatLook.CLEAR)["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun clearIsThickerSubtleIsTheBaseWidthAndBothAreDashedInTheSecondColour() {
        assertEquals(listOf(10.0 to 2.7, 14.0 to 5.4, 18.0 to 9.0), repeatWidthStops(RepeatLook.CLEAR))
        assertEquals(listOf(10.0 to 1.5, 14.0 to 3.0, 18.0 to 5.0), repeatWidthStops(RepeatLook.SUBTLE))
        for (look in listOf(RepeatLook.CLEAR, RepeatLook.SUBTLE)) {
            val layer = trackRepeatLayerJson(look)
            assertEquals("#E65100", layer["paint"]!!.jsonObject["line-color"]!!.jsonPrimitive.content)
            assertEquals("[3.0,2.0]", layer["paint"]!!.jsonObject["line-dasharray"].toString())
            assertEquals("butt", layer["layout"]!!.jsonObject["line-cap"]!!.jsonPrimitive.content)
            assertEquals("visible", layer["layout"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)
            assertEquals(repeatWidthStops(look), stops(paint(look)["line-width"] as JsonArray))
        }
    }

    @Test
    fun offHidesTheOverlay() {
        assertEquals("none", trackRepeatLayerJson(RepeatLook.OFF)["layout"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)
    }

    @Test
    fun theOverlayFadesInFromZoomTenAndAHalfToEleven() {
        assertEquals(listOf(10.5 to 0.0, 11.0 to 0.95), stops(paint(RepeatLook.CLEAR)["line-opacity"] as JsonArray))
    }

    @Test
    fun theBaseLineKeepsTodaysColourAndWidths() {
        val paint = trackLayerJson()["paint"]!!.jsonObject
        assertEquals("#8E24AA", paint["line-color"]!!.jsonPrimitive.content)
        assertEquals(listOf(10.0 to 1.5, 14.0 to 3.0, 18.0 to 5.0), stops(paint["line-width"] as JsonArray))
    }

    @Test
    fun theOverlaySitsAboveTheBaseLineAndUnderTheHouses() {
        val ids = Json.parseToJsonElement(
            prepareMapStyle("""{"version":8,"sources":{},"layers":[]}""", 12f, { "{}" }, { _, _ -> }).json,
        ).jsonObject["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(ids.indexOf("track-line") < ids.indexOf("track-repeat-line"))
        assertTrue(ids.indexOf("track-repeat-line") < ids.indexOf("track-check-halo"))
        assertTrue(ids.indexOf("track-check-label") < ids.indexOf("houses-dots"))
    }

    @Test
    fun theOrangeIsNotAMarkerColourAndHasThreeToOneAgainstWhite() {
        fun luminance(hex: String): Double {
            fun channel(i: Int): Double {
                val c = hex.substring(1 + 2 * i, 3 + 2 * i).toInt(16) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2)
        }
        assertTrue((1.0 + 0.05) / (luminance(TRACK_REPEAT_COLOR) + 0.05) >= 3.0)
        for (marker in listOf(MarkerColors.NEW, MarkerColors.SHORTLISTED, MarkerColors.REJECTED, MarkerColors.TAKEN, MarkerColors.NOT_CHOSEN)) {
            assertFalse("#" + (marker and 0xFFFFFF).toString(16).padStart(6, '0').uppercase() == TRACK_REPEAT_COLOR)
        }
    }

    @Test
    fun aDrawingOfNoWalksIsTheEmptyOneAndAWalkMakesALine() {
        assertSame(TraceDrawing.EMPTY, TraceDrawing.of(emptyList()))
        assertTrue(TraceDrawing.EMPTY.isEmpty)
        val drawing = TraceDrawing.of(listOf(TraceWalk(listOf(p(0, 12.9, 77.6), p(minute, 12.91, 77.61)))))
        assertFalse(drawing.isEmpty)
        assertEquals(listOf("base"), features(drawing.geoJson).map(::kind))
    }

    @Test
    fun twoWalksOfOneStreetDrawOneOrangeOverlayPieceOnTheNewerWalk() {
        // 200 m of street walked twice, 3 m apart: a repeat (80 m is the minimum).
        fun walk(id: Long, east: Double) = TraceWalk((0..10).map { TracePoint(12.97 + it * 0.00018, 77.64 + east, id + it * 20_000L, id) })
        val drawing = TraceDrawing.of(listOf(walk(1_000_000, 0.0), walk(900_000_000, 0.00003)))
        val kinds = features(drawing.geoJson).map(::kind)
        assertEquals(2, kinds.count { it == "base" })
        assertEquals(1, kinds.count { it == "repeat" }, "each repeated stretch is drawn once: by one walk")
    }
}
