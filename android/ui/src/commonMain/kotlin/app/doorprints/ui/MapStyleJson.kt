package app.doorprints.ui

import app.doorprints.data.HouseEntity
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
    fun byStatus(shortlisted: JsonElement, rejected: JsonElement, new: JsonElement) = buildJsonArray {
        add("match")
        add(status)
        add("SHORTLISTED")
        add(shortlisted)
        add("REJECTED")
        add(rejected)
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
    val dots = buildJsonObject {
        put("id", HOUSE_DOTS_LAYER)
        put("type", "circle")
        put("source", HOUSES_SOURCE)
        putJsonObject("paint") {
            put("circle-radius", radius)
            put(
                "circle-color",
                byStatus(
                    JsonPrimitive(cssColor(MarkerColors.SHORTLISTED)),
                    JsonPrimitive(cssColor(MarkerColors.REJECTED)),
                    JsonPrimitive(cssColor(MarkerColors.NEW)),
                ),
            )
            put(
                "circle-stroke-width",
                byStatus(
                    JsonPrimitive(MARKER_STROKE_SHORTLISTED_DP),
                    JsonPrimitive(MARKER_STROKE_DP),
                    JsonPrimitive(MARKER_STROKE_DP),
                ),
            )
            put(
                "circle-opacity",
                byStatus(JsonPrimitive(1f), JsonPrimitive(MARKER_OPACITY_REJECTED), JsonPrimitive(1f)),
            )
            put("circle-stroke-color", "#ffffff")
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
    ops.putSource(HOUSES_SOURCE, housesSourceJson())
    houseLayersJson(labelSizeSp).forEach(ops::addLayerOnTop)
    val style = ops.toJson()
    return PreparedMapStyle(style.toString(), style, IndiaViewCheck.problems(style))
}
