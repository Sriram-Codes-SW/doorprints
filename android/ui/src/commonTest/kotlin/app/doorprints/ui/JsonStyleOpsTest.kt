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
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.LocationSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS map's style (ADR-23 CMP-8c): [applyIndiaView] over [JsonStyleOps] on an excerpt of the real Liberty style
 * ([LIBERTY_EXCERPT]), [prepareMapStyle] and the in-app boundary check ([IndiaViewCheck]) that the iOS launch smoke
 * makes a CI gate.
 */
class JsonStyleOpsTest {
    /** Stand-ins for the bundled files: one line of each kind, and a small closed polygon for the held areas. */
    private val boundaries = """{"type":"FeatureCollection","features":[""" +
        """{"type":"Feature","properties":{"kind":"world"},"geometry":{"type":"LineString","coordinates":[[70,30],[71,31]]}},""" +
        """{"type":"Feature","properties":{"kind":"claim"},"geometry":{"type":"LineString","coordinates":[[75,35],[76,36]]}},""" +
        """{"type":"Feature","properties":{"kind":"state"},"geometry":{"type":"LineString","coordinates":[[92,27],[93,28]]}}]}"""
    private val heldAreas = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
        """"geometry":{"type":"Polygon","coordinates":[[[73,34],[78,34],[78,37],[73,37],[73,34]]]}}]}"""
    private val warnings = mutableListOf<String>()

    private fun readAsset(path: String): String = when (path) {
        IndiaViewRules.ASSET_PATH -> boundaries
        IndiaViewRules.HELD_AREAS_ASSET_PATH -> heldAreas
        else -> error("no asset $path")
    }

    private fun prepare(base: String = LIBERTY_EXCERPT): PreparedMapStyle =
        prepareMapStyle(base, labelSizeSp = 12f, readAsset = ::readAsset, warn = { m, _ -> warnings += m })

    private fun layers(style: JsonObject): List<JsonObject> = style["layers"]!!.jsonArray.map { it.jsonObject }

    private fun ids(style: JsonObject): List<String> = layers(style).map { it["id"]!!.jsonPrimitive.content }

    private fun layer(style: JsonObject, id: String): JsonObject = layers(style).first { it["id"]!!.jsonPrimitive.content == id }

    private fun libertyLayer(id: String): JsonObject =
        layer(Json.parseToJsonElement(LIBERTY_EXCERPT).jsonObject, id)

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun all(vararg parts: JsonElement) = JsonArray(listOf(JsonPrimitive("all")) + parts)

    @Test
    fun libertyPreparedForIosPassesTheBoundaryCheckWithNoWarning() {
        val prepared = prepare()
        assertEquals(emptyList(), prepared.problems)
        assertEquals(emptyList(), warnings)
        assertEquals(prepared.style, json(prepared.json))
    }

    @Test
    fun theOutlineAndStateLineGoWhereAndroidPutsThemAndTheHousesOnTop() {
        assertEquals(
            listOf(
                "background", "water",
                "boundary_3", IndiaViewRules.STATE_OVERLAY_LAYER,
                "boundary_2", IndiaViewRules.WORLD_LAYER, IndiaViewRules.CLAIM_LAYER,
                "boundary_disputed", "label_other", "label_state", "label_city",
                TRACK_LAYER, HOUSE_DOTS_LAYER, HOUSE_LABELS_LAYER,
            ),
            ids(prepare().style),
        )
    }

    /** The path trace's layer (docs/11 5.27) is under the houses, from an empty source the view fills later. */
    @Test
    fun theTraceIsALineUnderTheHousesFromAnEmptySource() {
        val style = prepare().style
        val source = style["sources"]!!.jsonObject[TRACK_SOURCE]!!.jsonObject
        assertEquals("geojson", source["type"]!!.jsonPrimitive.content)
        assertTrue(source["data"]!!.jsonObject["features"]!!.jsonArray.isEmpty())
        val line = layer(style, TRACK_LAYER)
        assertEquals("line", line["type"]!!.jsonPrimitive.content)
        assertEquals(TRACK_SOURCE, line["source"]!!.jsonPrimitive.content)
        assertEquals(TRACK_COLOR, line["paint"]!!.jsonObject["line-color"]!!.jsonPrimitive.content)
    }

    @Test
    fun rulesAreAndedToLibertysOwnFiltersAsJsonInTheOrderAndroidAppliesThem() {
        val style = prepare().style
        val guard = json(IndiaViewRules.TILE_ZOOM_GUARD)
        assertEquals(
            all(all(libertyLayer("boundary_2")["filter"]!!, json(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER)), guard),
            layer(style, "boundary_2")["filter"],
        )
        val held = json(IndiaViewRules.heldAreasFilter(IndiaViewRules.heldAreasGeometry(heldAreas)!!))
        assertEquals(all(all(libertyLayer("boundary_3")["filter"]!!, guard), held), layer(style, "boundary_3")["filter"])
        assertEquals(
            all(libertyLayer("label_state")["filter"]!!, json(IndiaViewRules.STATE_LABEL_EXTRA_FILTER)),
            layer(style, "label_state")["filter"],
        )
        // Not a state label layer: unchanged.
        assertEquals(libertyLayer("label_city"), layer(style, "label_city"))
    }

    @Test
    fun disputedLinesAreHiddenAndTheCountryLinesStartAtZoomFive() {
        val style = prepare().style
        assertEquals("none", layer(style, "boundary_disputed")["layout"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)
        assertEquals(5f, layer(style, "boundary_2")["minzoom"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun theOutlineIsInlineAndDrawnLikeLibertysOwnLines() {
        val style = prepare().style
        val source = style["sources"]!!.jsonObject[IndiaViewRules.SOURCE_ID]!!.jsonObject
        assertEquals("geojson", source["type"]!!.jsonPrimitive.content)
        assertEquals(json(boundaries), source["data"])
        val country = libertyLayer("boundary_2")["paint"]!!.jsonObject
        val world = layer(style, IndiaViewRules.WORLD_LAYER)
        assertEquals(country["line-color"], world["paint"]!!.jsonObject["line-color"])
        assertEquals(country["line-width"], world["paint"]!!.jsonObject["line-width"])
        assertEquals(json(IndiaViewRules.WORLD_FILTER), world["filter"])
        assertEquals(IndiaViewRules.WORLD_MAX_ZOOM, world["maxzoom"]!!.jsonPrimitive.content.toFloat())
        assertEquals("round", world["layout"]!!.jsonObject["line-cap"]!!.jsonPrimitive.content)
        val state = layer(style, IndiaViewRules.STATE_OVERLAY_LAYER)
        assertEquals(libertyLayer("boundary_3")["paint"]!!.jsonObject["line-dasharray"], state["paint"]!!.jsonObject["line-dasharray"])
        assertEquals(null, state["layout"]!!.jsonObject["line-cap"])
        assertEquals(IndiaViewRules.STATE_MIN_ZOOM, state["minzoom"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun theCheckFailsWhenTheDisputedLinesAreShown() {
        val style = prepare().style
        val shown = style.withLayer("boundary_disputed") { it - "layout" }
        assertEquals(listOf("boundary_disputed is shown"), IndiaViewCheck.problems(shown))
    }

    @Test
    fun theCheckFailsWhenLibertyRenamesALayerTheRulesNeed() {
        val renamed = LIBERTY_EXCERPT.replace("\"boundary_2\"", "\"boundary_country\"")
        val problems = prepare(renamed).problems
        assertTrue("boundary_2 is not a line layer in the style" in problems, problems.toString())
        // The outline is still added (rule 5), placed on the first boundary layer instead.
        assertTrue(warnings.isNotEmpty())
    }

    @Test
    fun theCheckFailsWhenARuleIsMissingFromAFilter() {
        val style = prepare().style
        val unguarded = style.withLayer("boundary_3") { it + ("filter" to libertyLayer("boundary_3")["filter"]!!) }
        assertEquals(
            listOf("boundary_3 lacks the tile-zoom guard", "boundary_3 lacks the held areas' rule"),
            IndiaViewCheck.problems(unguarded),
        )
        val noLabelRule = style.withLayer("label_other") { it + ("filter" to libertyLayer("label_other")["filter"]!!) }
        assertEquals(listOf("label_other lacks the state-label rule"), IndiaViewCheck.problems(noLabelRule))
    }

    @Test
    fun theLoadedMapMustHaveEveryLayerInOrderWithTheDisputedLinesHidden() {
        val style = prepare().style
        val all = ids(style)
        assertEquals(emptyList(), IndiaViewCheck.loadedProblems(style, all, setOf("boundary_disputed")))
        assertEquals(
            listOf("the map left out ${IndiaViewRules.CLAIM_LAYER}"),
            IndiaViewCheck.loadedProblems(style, all - IndiaViewRules.CLAIM_LAYER, setOf("boundary_disputed")),
        )
        assertEquals(
            listOf("the map has the layers in another order"),
            IndiaViewCheck.loadedProblems(style, all.reversed(), setOf("boundary_disputed")),
        )
        assertEquals(listOf("the map shows boundary_disputed"), IndiaViewCheck.loadedProblems(style, all, emptySet()))
    }

    @Test
    fun theHouseLayersMatchAndroidsMarkers() {
        val (dots, labels) = houseLayersJson(14f)
        val radius = dots["paint"]!!.jsonObject["circle-radius"]!!.jsonArray
        // interpolate, linear, zoom, then a stop per MARKER_RADII entry.
        assertEquals(3 + 2 * MARKER_RADII.size, radius.size)
        MARKER_RADII.forEachIndexed { i, r ->
            assertEquals(r.zoom, radius[3 + 2 * i].jsonPrimitive.content.toFloat())
            val byStatus = radius[4 + 2 * i].jsonArray
            assertEquals(r.shortlisted, byStatus[3].jsonPrimitive.content.toFloat())
            assertEquals(r.rejected, byStatus[5].jsonPrimitive.content.toFloat())
            assertEquals(r.new, byStatus[6].jsonPrimitive.content.toFloat())
        }
        val colors = dots["paint"]!!.jsonObject["circle-color"]!!.jsonArray
        assertEquals(listOf("#1a7a43", "#b3261e", "#3c5a99"), listOf(3, 5, 6).map { colors[it].jsonPrimitive.content })
        // FR-068: an approximate house has no fill and its ring in the status colour; the others a white ring.
        val opacity = dots["paint"]!!.jsonObject["circle-opacity"]!!.jsonArray
        assertEquals("case", opacity[0].jsonPrimitive.content)
        assertEquals("0.0", opacity[2].jsonPrimitive.content)
        assertEquals("match", opacity[3].jsonArray[0].jsonPrimitive.content)
        val stroke = dots["paint"]!!.jsonObject["circle-stroke-color"]!!.jsonArray
        assertEquals("case", stroke[0].jsonPrimitive.content)
        assertEquals(colors, stroke[2].jsonArray)
        assertEquals("#ffffff", stroke[3].jsonPrimitive.content)
        assertEquals(14f, labels["layout"]!!.jsonObject["text-size"]!!.jsonPrimitive.content.toFloat())
        assertEquals(HOUSES_SOURCE, labels["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun housesGeoJsonCarriesIdLabelStatusAndIndic() {
        val house = HouseEntity(
            id = "h1", label = "தமிழ் வீடு", lat = 12.97, lon = 77.59, status = HouseStatus.SHORTLISTED,
            createdAt = 0, updatedAt = 0,
        )
        val feature = json(housesGeoJson(listOf(house))).jsonObject["features"]!!.jsonArray[0].jsonObject
        assertEquals(listOf(77.59, 12.97), feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() })
        val p = feature["properties"]!!.jsonObject
        assertEquals("h1", p["id"]!!.jsonPrimitive.content)
        assertEquals("SHORTLISTED", p["status"]!!.jsonPrimitive.content)
        assertEquals("true", p["indic"]!!.jsonPrimitive.content)
        assertEquals("false", p["approx"]!!.jsonPrimitive.content)
        val approx = json(housesGeoJson(listOf(house.copy(locationSource = LocationSource.APPROX)))).jsonObject["features"]!!.jsonArray[0].jsonObject
        assertEquals("true", approx["properties"]!!.jsonObject["approx"]!!.jsonPrimitive.content)
    }

    private fun JsonObject.withLayer(id: String, change: (Map<String, JsonElement>) -> Map<String, JsonElement>): JsonObject {
        val layers = this["layers"]!!.jsonArray.map { l ->
            val o = l.jsonObject
            if (o["id"]!!.jsonPrimitive.content == id) JsonObject(change(o)) else o
        }
        return JsonObject(this + ("layers" to JsonArray(layers)))
    }
}
