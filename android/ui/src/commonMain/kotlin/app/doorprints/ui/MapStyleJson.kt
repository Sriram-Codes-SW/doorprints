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
import app.doorprints.shared.trace.RepeatLook
import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.model.LocationSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.round

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

/** The path trace's GeoJSON source and its line layers (docs/11 5.27, 5.27.4), drawn under the houses. */
const val TRACK_SOURCE = "track"

/** The base line: every walk whole, solid. */
const val TRACK_LAYER = "track-line"

/** The repeat overlay, above the base line and under the house layers (docs/11 5.27.4). */
const val TRACK_REPEAT_LAYER = "track-repeat-line"

/** The property `kind` of a feature of [TRACK_SOURCE]: the base line or a repeated stretch. */
const val TRACK_KIND_BASE = "base"
const val TRACK_KIND_REPEAT = "repeat"

/**
 * The trace's colour and width: a purple no base-map line uses (roads are white, yellow or grey, India's boundary
 * dark grey, the state lines light grey) and none of the three marker colours, readable on the light tiles both
 * themes show; the width grows with the zoom so the line stays a line, not a smear, at street zoom.
 */
const val TRACK_COLOR = "#8E24AA"

/**
 * The second colour of a repeated path (docs/11 5.27.4): a deep orange, 3.79:1 against white, 1.86:1 against the
 * purple (so the dash, not the colour pair, carries the cue). If the colour-vision check (TC-M-57) fails, it changes
 * here and in the web's `trace-style.ts`, nowhere else.
 */
const val TRACK_REPEAT_COLOR = "#E65100"

/** The overlay's width is the base's times this, by the person's look (docs/11 5.27.4); the dash stays in both. */
const val TRACK_REPEAT_FACTOR_CLEAR = 1.8
const val TRACK_REPEAT_FACTOR_SUBTLE = 1.0

/** The overlay's dash: three line widths of dash, two of gap, with butt caps (round ones would close the gaps). */
val TRACK_REPEAT_DASH = listOf(3.0, 2.0)
const val TRACK_REPEAT_OPACITY = 0.95

/** The overlay fades in from zoom 10.5 to 11: at zoom 10 a dash of 1.5 px reads as dots. */
const val TRACK_REPEAT_FADE_FROM = 10.5
const val TRACK_REPEAT_FADE_TO = 11.0

/** A gap longer than this between two points starts a new line (a new walk), so the map draws no leap between them. */
const val TRACK_GAP_MS = 30 * 60_000L

/**
 * The path trace as GeoJSON (docs/11 5.27.4), one source: a LineString of `kind` `base` for every walk of [walks]
 * (each whole, with its first and last time as `from` and `to`; a lone point draws nothing, MapLibre needs two), and a
 * LineString of `kind` `repeat` for every piece of [shown] (the stretches walked in two or more different walks, as
 * `[lat, lon]` pairs from `RepeatDetector.pieces`).
 */
fun trackGeoJson(
    walks: List<List<TracePoint>>,
    shown: List<List<Pair<Double, Double>>> = emptyList(),
): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        walks.filter { it.size >= 2 }.forEach { walk ->
            add(
                buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {
                        put("kind", TRACK_KIND_BASE); put("from", walk.first().atMs); put("to", walk.last().atMs)
                    }
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            walk.forEach { p -> add(buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }) }
                        }
                    }
                },
            )
        }
        shown.filter { it.size >= 2 }.forEach { piece ->
            add(
                buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") { put("kind", TRACK_KIND_REPEAT) }
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            piece.forEach { (lat, lon) -> add(buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) }) }
                        }
                    }
                },
            )
        }
    }
}.toString()

/** The trace's empty source for a style built as JSON (iOS); the view sets its data afterwards. */
fun trackSourceJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", Json.parseToJsonElement(trackGeoJson(emptyList())))
}

/** A filter for features whose `kind` property is [kind]. */
private fun kindFilter(kind: String): JsonArray = buildJsonArray {
    add("=="); add(buildJsonArray { add("get"); add("kind") }); add(kind)
}

/** A zoom interpolation over [stops], in the style's expression syntax. */
private fun zoomInterpolation(stops: List<Pair<Double, Double>>): JsonArray = buildJsonArray {
    add("interpolate"); add(buildJsonArray { add("linear") }); add(buildJsonArray { add("zoom") })
    stops.forEach { (zoom, value) -> add(JsonPrimitive(zoom)); add(JsonPrimitive(value)) }
}

/** The trace's base line layer, the same on both platforms (Android builds it from these values in `PlatformMap`). */
fun trackLayerJson(): JsonObject = buildJsonObject {
    put("id", TRACK_LAYER)
    put("type", "line")
    put("source", TRACK_SOURCE)
    put("filter", kindFilter(TRACK_KIND_BASE))
    putJsonObject("layout") {
        put("line-cap", "round")
        put("line-join", "round")
    }
    putJsonObject("paint") {
        put("line-color", TRACK_COLOR)
        put("line-opacity", 0.85)
        put("line-width", zoomInterpolation(TRACK_WIDTHS.map { (zoom, width) -> zoom.toDouble() to width }))
    }
}

/** The trace's width by zoom, in px: thin at city zoom, a clear line at street zoom. */
val TRACK_WIDTHS = listOf(10 to 1.5, 14 to 3.0, 18 to 5.0)

/** The overlay's width factor for [look] (docs/11 5.27.4); *Off* draws no overlay, so its factor is the base's. */
fun repeatFactor(look: RepeatLook): Double = when (look) {
    RepeatLook.CLEAR -> TRACK_REPEAT_FACTOR_CLEAR
    RepeatLook.SUBTLE, RepeatLook.OFF -> TRACK_REPEAT_FACTOR_SUBTLE
}

/** The overlay's width by zoom for [look]: the base's [TRACK_WIDTHS] times the factor, to two decimals (2.7, 5.4, 9.0). */
fun repeatWidthStops(look: RepeatLook): List<Pair<Double, Double>> =
    TRACK_WIDTHS.map { (zoom, width) -> zoom.toDouble() to round(width * repeatFactor(look) * 100.0) / 100.0 }

/** [repeatWidthStops] as a style expression, the same on both platforms (Android sets it live on the layer). */
fun repeatWidthExpressionJson(look: RepeatLook): JsonArray = zoomInterpolation(repeatWidthStops(look))

/** The overlay's layer (docs/11 5.27.4): dashed, butt-capped, in the second colour, hidden for [RepeatLook.OFF]. */
fun trackRepeatLayerJson(look: RepeatLook): JsonObject = buildJsonObject {
    put("id", TRACK_REPEAT_LAYER)
    put("type", "line")
    put("source", TRACK_SOURCE)
    put("filter", kindFilter(TRACK_KIND_REPEAT))
    putJsonObject("layout") {
        put("line-cap", "butt")
        put("line-join", "round")
        put("visibility", if (look == RepeatLook.OFF) "none" else "visible")
    }
    putJsonObject("paint") {
        put("line-color", TRACK_REPEAT_COLOR)
        put(
            "line-opacity",
            zoomInterpolation(listOf(TRACK_REPEAT_FADE_FROM to 0.0, TRACK_REPEAT_FADE_TO to TRACK_REPEAT_OPACITY)),
        )
        put("line-width", repeatWidthExpressionJson(look))
        put("line-dasharray", buildJsonArray { TRACK_REPEAT_DASH.forEach { add(JsonPrimitive(it)) } })
    }
}

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
    /** An expression that picks [then] for a house whose location is approximate and [otherwise] for the rest. */
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
 * [readAsset] reads the bundled geo files; [warn] logs the steps' warnings. Throws only when [baseStyleJson] is not a
 * JSON object; a problem with the rules is reported in [PreparedMapStyle.problems], never thrown (rule 5).
 */
fun prepareMapStyle(
    baseStyleJson: String,
    labelSizeSp: Float,
    readAsset: (String) -> String,
    warn: (String, Throwable?) -> Unit,
): PreparedMapStyle {
    val base = Json.parseToJsonElement(baseStyleJson) as? JsonObject
        ?: throw IllegalArgumentException("the base style is not a JSON object")
    val ops = JsonStyleOps(base, readAsset, warn)
    applyIndiaView(ops)
    // The trace under the houses, so a dot is never hidden by the line.
    ops.putSource(TRACK_SOURCE, trackSourceJson())
    ops.addLayerOnTop(trackLayerJson())
    ops.addLayerOnTop(trackRepeatLayerJson(RepeatLook.CLEAR))
    ops.putSource(CHECK_SOURCE, checkSourceJson())
    checkLayersJson().forEach(ops::addLayerOnTop)
    ops.putSource(HOUSES_SOURCE, housesSourceJson())
    houseLayersJson(labelSizeSp).forEach(ops::addLayerOnTop)
    val style = ops.toJson()
    return PreparedMapStyle(style.toString(), style, IndiaViewCheck.problems(style))
}
