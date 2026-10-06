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

import app.doorprints.shared.trace.MatchedStretch
import app.doorprints.shared.trace.PlaceBand
import app.doorprints.shared.trace.PlaceCheckResult
import app.doorprints.shared.trace.RepeatDetector
import app.doorprints.shared.trace.TraceWalk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the Map draws of the person's walks (docs/11 5.27.4): the GeoJSON of [TRACK_SOURCE], built once per change of the
 * stored walks (never per look: the look is a live property of the layer, so the same instance is handed to the map
 * when only the look changes). [isEmpty] is true when no line is drawn (the legend row hides then).
 */
class TraceDrawing private constructor(val geoJson: String, val isEmpty: Boolean) {
    companion object {
        val EMPTY = TraceDrawing(trackGeoJson(emptyList()), isEmpty = true)

        /**
         * The drawing of [walks] (the 30-day trace and the saved walks, whole): each walk's base line, and the stretches
         * [RepeatDetector] shows as overlay pieces. The detection is the expensive part: call it off the main thread.
         */
        fun of(walks: List<TraceWalk>): TraceDrawing {
            val lists = walks.map { it.points }.filter { it.size >= 2 }
            if (lists.isEmpty()) return EMPTY
            val repeats = RepeatDetector.detect(lists)
            val shown = lists.indices.flatMap { RepeatDetector.pieces(lists[it], repeats[it].shown) }
            return TraceDrawing(trackGeoJson(lists, shown), isEmpty = false)
        }
    }
}

/** The place check's source and layers (docs/11 5.27.13): the matched stretches and the ring at the place. */
const val CHECK_SOURCE = "track-check"
const val CHECK_HALO_LAYER = "track-check-halo"
const val CHECK_RING_LAYER = "track-check-ring"
const val CHECK_CROSS_LAYER = "track-check-cross"
const val CHECK_LABEL_LAYER = "track-check-label"

/** The halo and ring colour: a near-black, a form beside the lines' colours, not a marker or a trace colour. */
const val CHECK_COLOR = "#1F1F1F"

/** The halo is a casing this much wider than the base line, in px (docs/11 5.27.6: a 2 px halo). */
const val CHECK_HALO_EXTRA_PX = 4.0

/** The ring's radius and stroke, in px: a hollow ring with a cross, a form no marker has. */
const val CHECK_RING_RADIUS_PX = 14.0
const val CHECK_RING_STROKE_PX = 3.0

/**
 * What the Map draws for an answer of *Have I been here?*: the place, its label ("You are here", "This house", "This
 * spot") and the matched stretch of each walk that [PlaceBand.WALKED] it. Held in memory only, never stored.
 */
class CheckOverlay(val lat: Double, val lon: Double, val label: String, val stretches: List<List<Pair<Double, Double>>>) {
    /** Every point of the overlay, for fitting the camera to it. */
    val points: List<Pair<Double, Double>> get() = listOf(lat to lon) + stretches.flatten()

    val geoJson: String get() = checkGeoJson(this)

    companion object {
        fun of(lat: Double, lon: Double, label: String, result: PlaceCheckResult, walks: List<TraceWalk>): CheckOverlay =
            CheckOverlay(
                lat, lon, label,
                result.rows.filter { it.band == PlaceBand.WALKED }
                    .map { MatchedStretch.of(lat, lon, walks[it.walkIndex].points) }
                    .filter { it.size >= 2 },
            )
    }
}

/** [overlay] as GeoJSON: a Point of `kind` `place` with its `label`, and a LineString of `kind` `stretch` for each stretch. */
fun checkGeoJson(overlay: CheckOverlay?): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        if (overlay != null) {
            add(
                buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") { put("kind", "place"); put("label", overlay.label) }
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") { add(JsonPrimitive(overlay.lon)); add(JsonPrimitive(overlay.lat)) }
                    }
                },
            )
            overlay.stretches.forEach { line ->
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("properties") { put("kind", "stretch") }
                        putJsonObject("geometry") {
                            put("type", "LineString")
                            putJsonArray("coordinates") {
                                line.forEach { (lat, lon) -> add(buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) }) }
                            }
                        }
                    },
                )
            }
        }
    }
}.toString()

fun checkSourceJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", Json.parseToJsonElement(checkGeoJson(null)))
}

private fun kindIs(kind: String): JsonArray = buildJsonArray {
    add("=="); add(buildJsonArray { add("get"); add("kind") }); add(kind)
}

/**
 * The place check's layers, the same on both platforms (Android builds them from these values): the halo under, the
 * ring, the cross and the label over the base line. The halo is a casing wider than the base line, so with *How repeated
 * paths look* = Off it still shows; the ring is hollow with a cross, a form no marker has.
 */
fun checkLayersJson(): List<JsonObject> = listOf(
    buildJsonObject {
        put("id", CHECK_HALO_LAYER); put("type", "line"); put("source", CHECK_SOURCE); put("filter", kindIs("stretch"))
        putJsonObject("layout") { put("line-cap", "round"); put("line-join", "round") }
        putJsonObject("paint") {
            put("line-color", CHECK_COLOR)
            put("line-opacity", 0.55)
            put(
                "line-width",
                buildJsonArray {
                    add("interpolate"); add(buildJsonArray { add("linear") }); add(buildJsonArray { add("zoom") })
                    TRACK_WIDTHS.forEach { (zoom, width) -> add(JsonPrimitive(zoom)); add(JsonPrimitive(width + CHECK_HALO_EXTRA_PX)) }
                },
            )
        }
    },
    buildJsonObject {
        put("id", CHECK_RING_LAYER); put("type", "circle"); put("source", CHECK_SOURCE); put("filter", kindIs("place"))
        putJsonObject("paint") {
            put("circle-radius", CHECK_RING_RADIUS_PX)
            put("circle-color", "#FFFFFF")
            put("circle-opacity", 0.0)
            put("circle-stroke-color", CHECK_COLOR)
            put("circle-stroke-width", CHECK_RING_STROKE_PX)
        }
    },
    buildJsonObject {
        put("id", CHECK_CROSS_LAYER); put("type", "symbol"); put("source", CHECK_SOURCE); put("filter", kindIs("place"))
        putJsonObject("layout") {
            put("text-field", "+")
            put("text-font", buildJsonArray { add("Noto Sans Regular") })
            put("text-size", 22.0)
            put("text-allow-overlap", true)
        }
        putJsonObject("paint") { put("text-color", CHECK_COLOR) }
    },
    buildJsonObject {
        put("id", CHECK_LABEL_LAYER); put("type", "symbol"); put("source", CHECK_SOURCE); put("filter", kindIs("place"))
        putJsonObject("layout") {
            put("text-field", buildJsonArray { add("get"); add("label") })
            put("text-font", buildJsonArray { add("Noto Sans Regular") })
            put("text-size", 13.0)
            put("text-offset", buildJsonArray { add(JsonPrimitive(0.0)); add(JsonPrimitive(2.2)) })
            put("text-anchor", "top")
            put("text-allow-overlap", true)
        }
        putJsonObject("paint") {
            put("text-color", CHECK_COLOR); put("text-halo-color", "#FFFFFF"); put("text-halo-width", 1.5)
        }
    },
)
