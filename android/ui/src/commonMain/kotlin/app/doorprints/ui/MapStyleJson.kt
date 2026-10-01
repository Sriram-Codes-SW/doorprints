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
import app.doorprints.data.TrackPointEntity
import app.doorprints.shared.model.LocationSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The map's style pieces that both platforms share (ADR-23 CMP-8c): the base style's address, the houses as GeoJSON,
 * and (for iOS, which builds its style as JSON: prepareMapStyle) the house markers and names as style layers. Android
 * adds the same markers through MapLibre's layer API (PlatformMap.android.kt); MapStyleJsonTest holds the two to the
 * same values (MapRules' MARKER_RADII and friends, Theme's MarkerColors).
 */

/**
 * Free vector map tiles from OpenFreeMap (OpenStreetMap data): no API key or billing needed. Every load of it goes
 * through [applyIndiaView] (India's boundary as the Government of India shows it; IndiaViewRules), so any new place
 * that loads a style must call it too (iOS: [prepareMapStyle]).
 */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** The path trace's GeoJSON source and its line layer (docs/11 5.27, S4b-FR-2), drawn under the houses. */
const val TRACK_SOURCE = "track"
const val TRACK_LAYER = "track-line"

/**
 * The trace's colour and width: a purple no base-map line uses (roads are white, yellow or grey, India's boundary
 * dark grey, the state lines light grey) and none of the three marker colours, readable on the light tiles both
 * themes show; the width grows with the zoom so the line stays a line, not a smear, at street zoom.
 */
const val TRACK_COLOR = "#8E24AA"

/** A gap longer than this between two points starts a new line (a new walk), so the map draws no leap between them. */
const val TRACK_GAP_MS = 30 * 60_000L

/**
 * The path trace as GeoJSON: one LineString per walk (the points split at [TRACK_GAP_MS] gaps; a lone point draws
 * nothing, MapLibre needs two). [points] oldest first, as `Repository.trackPoints` gives them.
 */
fun trackGeoJson(points: List<TrackPointEntity>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        splitTrack(points).forEach { walk ->
            add(
                buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") { put("from", walk.first().at); put("to", walk.last().at) }
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            walk.forEach { p -> add(buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }) }
                        }
                    }
                },
            )
        }
    }
}.toString()

/** The walks in [points]: runs of at least two points with no gap of [TRACK_GAP_MS] or more between neighbours. */
fun splitTrack(points: List<TrackPointEntity>): List<List<TrackPointEntity>> {
    val walks = mutableListOf<MutableList<TrackPointEntity>>()
    points.forEach { p ->
        val last = walks.lastOrNull()
        if (last == null || p.at - last.last().at >= TRACK_GAP_MS) walks += mutableListOf(p) else last += p
    }
    return walks.filter { it.size >= 2 }
}

/** The trace's empty source for a style built as JSON (iOS); the view sets its data afterwards. */
fun trackSourceJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", Json.parseToJsonElement(trackGeoJson(emptyList())))
}

/** The trace's line layer, the same on both platforms (Android builds it from these values in `PlatformMap`). */
fun trackLayerJson(): JsonObject = buildJsonObject {
    put("id", TRACK_LAYER)
    put("type", "line")
    put("source", TRACK_SOURCE)
    putJsonObject("layout") {
        put("line-cap", "round")
        put("line-join", "round")
    }
    putJsonObject("paint") {
        put("line-color", TRACK_COLOR)
        put("line-opacity", 0.85)
        put(
            "line-width",
            buildJsonArray {
                add("interpolate"); add(buildJsonArray { add("linear") }); add(buildJsonArray { add("zoom") })
                TRACK_WIDTHS.forEach { (zoom, width) -> add(JsonPrimitive(zoom)); add(JsonPrimitive(width)) }
            },
        )
    }
}

/** The trace's width by zoom, in px: thin at city zoom, a clear line at street zoom. */
val TRACK_WIDTHS = listOf(10 to 1.5, 14 to 3.0, 18 to 5.0)

/** The houses' GeoJSON source and the two layers drawn from it. */
const val HOUSES_SOURCE = "houses"
const val HOUSE_DOTS_LAYER = "houses-dots"
const val HOUSE_LABELS_LAYER = "houses-labels"

/**
 * The houses as a GeoJSON FeatureCollection: a point at each house with its id, label and status, and `indic` (the
 * label has Devanagari, Tamil or Telugu letters; for the label layer's filter, [MAP_LABELS_SHOW_INDIC], device check
 * 21 (d)).
 */
fun housesGeoJson(houses: List<HouseEntity>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        houses.forEach { h ->
            add(
                buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") {
                            add(JsonPrimitive(h.lon))
                            add(JsonPrimitive(h.lat))
                        }
                    }
                    putJsonObject("properties") {
                        put("id", h.id)
                        put("label", h.label)
                        put("status", h.status.name)
                        put("indic", hasIndicScript(h.label))
                        // An approximate spot (FR-068) is drawn as a ring: no fill, the stroke in the status colour.
                        put("approx", h.locationSource == LocationSource.APPROX)
                    }
                },
            )
        }
    }
}.toString()

/** A colour of [MarkerColors] (ARGB) as the style's `#rrggbb`. */
internal fun cssColor(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

/**
 * The houses' GeoJSON source, empty, for a style built as JSON (iOS); the view sets its data afterwards, as Android's
 * `GeoJsonSource.setGeoJson` does.
 */
fun housesSourceJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", Json.parseToJsonElement(housesGeoJson(emptyList())))
}

/**
 * The two house layers as style JSON, the same as Android's `addHouseLayers` (PlatformMap.android.kt): the markers,
 * whose status shows by colour, size, ring and opacity (round 5; docs/05 UX-002, A11Y-003, WCAG 1.4.1), growing with
 * the zoom ([MARKER_RADII]); and the names below them at [labelSizeSp], wrapped after [MARKER_LABEL_MAX_WIDTH_EM] ems,
 * leaving out Indic names unless [MAP_LABELS_SHOW_INDIC].
 */
fun houseLayersJson(labelSizeSp: Float): List<JsonObject> {
    val status = buildJsonArray {
        add("get")
        add("status")
    }
    // Slice 5: TAKEN is drawn like SHORTLISTED and NOT_CHOSEN like REJECTED in size, ring and opacity, each in its own
    // colour, so the two new statuses are told apart by colour and, from the others, by size.
    fun byStatus(
        shortlisted: JsonElement, rejected: JsonElement, new: JsonElement,
        taken: JsonElement = shortlisted, notChosen: JsonElement = rejected,
    ) = buildJsonArray {
        add("match")
        add(status)
        add("SHORTLISTED")
        add(shortlisted)
        add("REJECTED")
        add(rejected)
        add("TAKEN")
        add(taken)
        add("NOT_CHOSEN")
        add(notChosen)
        add(new)
    }
    val radius = buildJsonArray {
        add("interpolate")
        add(buildJsonArray { add("linear") })
        add(buildJsonArray { add("zoom") })
        MARKER_RADII.forEach { r ->
            add(JsonPrimitive(r.zoom))
            add(byStatus(JsonPrimitive(r.shortlisted), JsonPrimitive(r.rejected), JsonPrimitive(r.new)))
        }
    }
    val approx = buildJsonArray {
        add("to-boolean")
        add(buildJsonArray { add("get"); add("approx") })
    }
    fun ifApprox(then: JsonElement, otherwise: JsonElement) = buildJsonArray {
        add("case")
        add(approx)
        add(then)
        add(otherwise)
    }
    val statusColor = byStatus(
        JsonPrimitive(cssColor(MarkerColors.SHORTLISTED)),
        JsonPrimitive(cssColor(MarkerColors.REJECTED)),
        JsonPrimitive(cssColor(MarkerColors.NEW)),
        JsonPrimitive(cssColor(MarkerColors.TAKEN)),
        JsonPrimitive(cssColor(MarkerColors.NOT_CHOSEN)),
    )
    val dots = buildJsonObject {
        put("id", HOUSE_DOTS_LAYER)
        put("type", "circle")
        put("source", HOUSES_SOURCE)
        putJsonObject("paint") {
            put("circle-radius", radius)
            put("circle-color", statusColor)
            put(
                "circle-stroke-width",
                byStatus(
                    JsonPrimitive(MARKER_STROKE_SHORTLISTED_DP),
                    JsonPrimitive(MARKER_STROKE_DP),
                    JsonPrimitive(MARKER_STROKE_DP),
                    notChosen = JsonPrimitive(MARKER_STROKE_DP),
                ),
            )
            // The approximate marker (FR-068): the same radius, no fill, the ring in the status colour.
            put(
                "circle-opacity",
                ifApprox(JsonPrimitive(0f), byStatus(JsonPrimitive(1f), JsonPrimitive(MARKER_OPACITY_REJECTED), JsonPrimitive(1f))),
            )
            put("circle-stroke-color", ifApprox(statusColor, JsonPrimitive("#ffffff")))
        }
    }
    val labels = buildJsonObject {
        put("id", HOUSE_LABELS_LAYER)
        put("type", "symbol")
        put("source", HOUSES_SOURCE)
        put(
            "filter",
            buildJsonArray {
                add("any")
                add(JsonPrimitive(MAP_LABELS_SHOW_INDIC))
                add(
                    buildJsonArray {
                        add("!")
                        add(
                            buildJsonArray {
                                add("to-boolean")
                                add(buildJsonArray { add("get"); add("indic") })
                            },
                        )
                    },
                )
            },
        )
        putJsonObject("layout") {
            put("text-field", buildJsonArray { add("get"); add("label") })
            put("text-font", buildJsonArray { add("Noto Sans Regular") })
            put("text-size", labelSizeSp)
            put("text-max-width", MARKER_LABEL_MAX_WIDTH_EM)
            // Below the largest dot (15 dp and a 3 dp ring at zoom 18), so a name never sits on its marker.
            put("text-offset", buildJsonArray { add(JsonPrimitive(0f)); add(JsonPrimitive(1.6f)) })
            put("text-anchor", "top")
            put("text-optional", true)
        }
        putJsonObject("paint") {
            put("text-halo-color", "#ffffff")
            put("text-halo-width", 1.5f)
        }
    }
    return listOf(dots, labels)
}

/**
 * A style ready for a map that loads its style as JSON (iOS): its [json] text, the same as [style], and the problems
 * [IndiaViewCheck] found in it (empty when India's boundary rules all hold).
 */
class PreparedMapStyle(val json: String, val style: JsonObject, val problems: List<String>)

/**
 * The iOS map's style (ADR-23 CMP-8c): the base style [baseStyleJson] (the text of [MAP_STYLE_URL]) with India's
 * boundary as the Government of India shows it ([applyIndiaView] over [JsonStyleOps], the same steps as Android's), then
 * the houses' empty source and their two layers on top ([houseLayersJson] at [labelSizeSp]), then [IndiaViewCheck].
 * [readAsset] reads the bundled geo files; [warn] logs the steps' warnings; [soiAttribution] is the Survey of India's
 * credit in the app's language, which MapLibre iOS lists in its attribution sheet (S4b-BL-114). Throws only when
 * [baseStyleJson] is not a JSON object; a problem with the rules is reported in [PreparedMapStyle.problems], never
 * thrown (rule 5).
 */
fun prepareMapStyle(
    baseStyleJson: String,
    labelSizeSp: Float,
    readAsset: (String) -> String,
    warn: (String, Throwable?) -> Unit,
    soiAttribution: String = IndiaViewRules.SOI_ATTRIBUTION,
): PreparedMapStyle {
    val base = Json.parseToJsonElement(baseStyleJson) as? JsonObject
        ?: throw IllegalArgumentException("the base style is not a JSON object")
    val ops = JsonStyleOps(base, readAsset, warn)
    applyIndiaView(ops, soiAttribution)
    // The trace under the houses, so a dot is never hidden by the line.
    ops.putSource(TRACK_SOURCE, trackSourceJson())
    ops.addLayerOnTop(trackLayerJson())
    ops.putSource(HOUSES_SOURCE, housesSourceJson())
    houseLayersJson(labelSizeSp).forEach(ops::addLayerOnTop)
    val style = ops.toJson()
    return PreparedMapStyle(style.toString(), style, IndiaViewCheck.problems(style))
}
