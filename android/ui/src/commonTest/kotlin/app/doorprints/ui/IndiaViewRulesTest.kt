package app.doorprints.ui

import app.doorprints.ui.IndiaViewRules.LayerInfo
import app.doorprints.ui.IndiaViewRules.Placement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * India's boundary on the map (owner issue P0, 2026-09-24): the decisions the Map applies to the Liberty style through
 * applyIndiaView (IndiaViewOps.kt). The filters are evaluated here by a small evaluator for the operators they use,
 * against feature properties as the OpenFreeMap tiles carry them, so the test checks what they select, not only their text.
 */
class IndiaViewRulesTest {

    // --- Rule 2: the tiles' country lines ------------------------------------------------------------------------

    @Test
    fun thePakistanChinaLineIsNotDrawnAndEveryOtherCountryLineIs() {
        val keep = parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER)
        assertFalse(eval(keep, mapOf("adm0_l" to "PAK", "adm0_r" to "CHN")))
        assertFalse(eval(keep, mapOf("adm0_l" to "CHN", "adm0_r" to "PAK")))
        assertTrue(eval(keep, mapOf("adm0_l" to "PAK", "adm0_r" to "AFG")))
        assertTrue(eval(keep, mapOf("adm0_l" to "CHN", "adm0_r" to "NPL")))
        assertTrue(eval(keep, mapOf("adm0_l" to "IND", "adm0_r" to "PAK")))
        // India's side is often missing in the tiles: a missing name is "not Pakistan or China", never an error.
        assertTrue(eval(keep, mapOf("adm0_r" to "PAK")))
        assertTrue(eval(keep, mapOf("admin_level" to "2", "adm0_r" to "NPL")))
    }

    @Test
    fun indiasLineWithChinaIsNotDrawnFromTheTilesOurOutlineDrawsIt() {
        // The tiles cut India's line with China into undisputed (drawn) and disputed (hidden) pieces, which showed as
        // stray lines beside India's outline (owner, 2026-09-24); India's side is usually missing in the tiles.
        val keep = parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER)
        val deprecated = parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY)
        val both = listOf<(Map<String, String>) -> Boolean>({ eval(keep, it) }, { legacy(deprecated, it) })
        both.forEachIndexed { syntax, drawn ->
            assertFalse(drawn(mapOf("adm0_l" to "CHN")), "syntax $syntax")
            assertFalse(drawn(mapOf("adm0_r" to "CHN")), "syntax $syntax")
            assertFalse(drawn(mapOf("adm0_l" to "IND", "adm0_r" to "CHN")), "syntax $syntax")
            assertFalse(drawn(mapOf("adm0_l" to "CHN", "adm0_r" to "IND")), "syntax $syntax")
            // China's lines with India's other neighbours stay: Nepal, Bhutan, Myanmar.
            assertTrue(drawn(mapOf("adm0_l" to "CHN", "adm0_r" to "NPL")), "syntax $syntax")
            assertTrue(drawn(mapOf("adm0_l" to "BTN", "adm0_r" to "CHN")), "syntax $syntax")
            assertTrue(drawn(mapOf("adm0_l" to "MMR", "adm0_r" to "CHN")), "syntax $syntax")
            // India's lines with its other neighbours stay.
            assertTrue(drawn(mapOf("adm0_r" to "NPL")), "syntax $syntax")
            assertTrue(drawn(mapOf("adm0_l" to "BTN")), "syntax $syntax")
            assertTrue(drawn(mapOf("adm0_l" to "PAK", "adm0_r" to "AFG")), "syntax $syntax")
        }
    }

    @Test
    fun aLineFromAZoomZeroToFourTileIsNeverDrawnEvenWhenShownAtZoomFiveOrMore() {
        // MapLibre draws a zoom 4 tile in place of a zoom 5+ one still loading or not cached (offline). By the source
        // both renderers leave boundary_2 (minzoom 5) out of such a tile (maplibre-native geometry_tile.cpp:317,
        // maplibre-gl worker_tile.ts:109); as defence in depth, whatever a renderer does with minzoom, the filter
        // itself drops the Natural Earth ISO-view lines of those tiles (through Jammu and Kashmir, Ladakh and
        // Arunachal Pradesh): they carry no adm0 side.
        val expression = parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER)
        val deprecated = parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY)
        val both = listOf<(Map<String, String>) -> Boolean>({ eval(expression, it) }, { legacy(deprecated, it) })
        both.forEachIndexed { syntax, drawn ->
            assertFalse(drawn(emptyMap()), "syntax $syntax")
            assertFalse(drawn(mapOf("admin_level" to "2")), "syntax $syntax")
            assertFalse(drawn(mapOf("admin_level" to "2", "disputed" to "0", "maritime" to "0")), "syntax $syntax")
            // A zoom 5+ line with only its non-Indian side set is still drawn; PAK/CHN still is not.
            assertTrue(drawn(mapOf("admin_level" to "2", "adm0_r" to "BTN")), "syntax $syntax")
            assertTrue(drawn(mapOf("admin_level" to "2", "adm0_l" to "MMR")), "syntax $syntax")
            assertFalse(drawn(mapOf("admin_level" to "2", "adm0_l" to "PAK", "adm0_r" to "CHN")), "syntax $syntax")
        }
    }

    @Test
    fun aStateLineFromAZoomFourTileIsNeverDrawnButTheSameLineFromAZoomFiveTileIs() {
        // Tile 4/11/6 of the 20260913 planet (lead's decode): Natural Earth admin-1 lines, one along the Line of
        // Control north of the Kashmir valley and one across Aksai Chin, carrying exactly these properties. Liberty's
        // boundary_3 filter lets them through; only its minzoom 5 keeps them out of a zoom 4 tile drawn while a zoom
        // 5 tile loads, or offline (both renderers skip the layer there by the source: geometry_tile.cpp:317,
        // worker_tile.ts:109). As defence in depth, a filter's zoom is the tile's zoom (overscaledZ), so the guard
        // drops every feature of a zoom 0-4 tile whatever a renderer does with minzoom.
        val placeholder: Map<String, Any> = mapOf("admin_level" to 4, "disputed" to 0, "maritime" to 0)
        assertTrue(eval(parse(LIBERTY_BOUNDARY_3), placeholder, zoom = 4f), "Liberty alone draws it")
        val guarded = parse("[\"all\", $LIBERTY_BOUNDARY_3, ${IndiaViewRules.TILE_ZOOM_GUARD}]")
        assertFalse(eval(guarded, placeholder, zoom = 4f))
        assertFalse(eval(guarded, placeholder, zoom = 0f))
        // The same state line in a zoom 5+ tile (and an overscaled one past the source's maxzoom 14) is drawn.
        listOf(5f, 8f, 14f, 16f).forEach { assertTrue(eval(guarded, placeholder, zoom = it), "tile zoom $it") }
        // Liberty's own rules still apply from zoom 5: no disputed or claimed line, no country line.
        assertFalse(eval(guarded, placeholder + ("disputed" to 1), zoom = 5f))
        assertFalse(eval(guarded, placeholder + ("claimed_by" to "CN"), zoom = 5f))
        assertFalse(eval(guarded, placeholder + ("admin_level" to 2), zoom = 5f))
    }

    @Test
    fun theCountryLinesAreGuardedByTileZoomAsWellAsByTheAdm0Side() {
        // As rules 2 and 2b build boundary_2's filter: Liberty's, then the country rule, then the tile-zoom guard.
        val filter = parse(
            "[\"all\", [\"all\", $LIBERTY_BOUNDARY_2, ${IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER}], " +
                "${IndiaViewRules.TILE_ZOOM_GUARD}]",
        )
        // A zoom 4 tile's line, with or without an adm0 side (the guard no longer depends on the tile data alone).
        assertFalse(eval(filter, mapOf("admin_level" to 2, "disputed" to 0, "maritime" to 0), zoom = 4f))
        assertFalse(eval(filter, mapOf("admin_level" to 2, "disputed" to 0, "adm0_r" to "BTN"), zoom = 4f))
        assertTrue(eval(filter, mapOf("admin_level" to 2, "disputed" to 0, "maritime" to 0, "adm0_r" to "BTN"), 5f))
        assertFalse(eval(filter, mapOf("admin_level" to 2, "adm0_l" to "PAK", "adm0_r" to "CHN"), zoom = 8f))
    }

    @Test
    fun everyBoundaryLineLayerThatStartsAtZoomFiveGetsTheTileZoomGuardInExpressionSyntaxOnly() {
        assertEquals("boundary_3", IndiaViewRules.STATE_LINE_LAYER)
        val layers = listOf(
            line("water", "water", 5f),
            line("boundary_3", "boundary", 5f),
            line("boundary_2", "boundary"),
            line("boundary_disputed", "boundary"),
            line("boundary_world_low", "boundary", 0f),
            line("boundary_detail", "boundary", 6f),
            LayerInfo("boundary_label", true, "boundary", null, minZoom = 5f),
        )
        assertEquals(
            listOf("boundary_3", "boundary_2", "boundary_detail"),
            IndiaViewRules.tileZoomGuardedLayers(layers),
        )
        // By name even if the style lowers their minzoom: no line through Indian territory outweighs a state line.
        assertEquals(
            listOf("boundary_3", "boundary_2"),
            IndiaViewRules.tileZoomGuardedLayers(
                listOf(line("boundary_3", "boundary", 3f), line("boundary_2", "boundary")),
            ),
        )
        // Liberty's filters are expressions, so they get the guard; a deprecated one gets nothing (warning).
        val boundary3 = toList(parse(LIBERTY_BOUNDARY_3))
        assertEquals(IndiaViewRules.TILE_ZOOM_GUARD, IndiaViewRules.tileZoomGuardFor(boundary3))
        assertEquals(IndiaViewRules.TILE_ZOOM_GUARD, IndiaViewRules.tileZoomGuardFor(null))
        assertEquals(null, IndiaViewRules.tileZoomGuardFor(listOf("all", listOf(">=", "admin_level", 3f))))
        assertTrue(IndiaViewRules.isExpressionSyntax(toList(parse(IndiaViewRules.TILE_ZOOM_GUARD))))
        assertTrue(
            IndiaViewRules.isExpressionSyntax(
                listOf("all", boundary3, toList(parse(IndiaViewRules.TILE_ZOOM_GUARD))),
            ),
        )
    }

    @Test
    fun theTilesOwnLinesStartAtZoomFiveWhereTheyCanBeFiltered() {
        assertEquals(5f, IndiaViewRules.DETAILED_FROM_ZOOM)
        assertEquals("boundary_2", IndiaViewRules.COUNTRY_LAYER)
        assertEquals("boundary_disputed", IndiaViewRules.DISPUTED_LAYER)
        // Never lower than 5, and a higher minzoom of the base style is kept (as on the web).
        assertEquals(5f, IndiaViewRules.countryMinZoom(Float.NEGATIVE_INFINITY))
        assertEquals(5f, IndiaViewRules.countryMinZoom(0f))
        assertEquals(5f, IndiaViewRules.countryMinZoom(5f))
        assertEquals(6f, IndiaViewRules.countryMinZoom(6f))
    }

    @Test
    fun theWorldLinesHandOverAtExactlyZoomFiveWithNoGapAndNoOverlap() {
        // maplibre-native includes both ends of a zoom range (render_layer.cpp:53), so the world lines must stop
        // below 5 for boundary_2 alone to draw at 5.0, as on the web; and no float may lie between the two, or some
        // zoom would draw neither.
        val max = IndiaViewRules.WORLD_MAX_ZOOM
        assertTrue(max < IndiaViewRules.DETAILED_FROM_ZOOM, "$max")
        assertTrue(max > IndiaViewRules.DETAILED_FROM_ZOOM - 0.001f, "$max")
        // The next float up from max (Math.nextUp on the JVM; computed from the bits in common code).
        assertEquals(IndiaViewRules.DETAILED_FROM_ZOOM, Float.fromBits(max.toBits() + 1))
        // At every float zoom exactly one of the two draws (native: minZoom <= zoom && maxZoom >= zoom).
        listOf(4f, 4.9f, max, 5f, 5.0001f, 8f).forEach { zoom ->
            val world = zoom <= max
            val country = IndiaViewRules.countryMinZoom(Float.NEGATIVE_INFINITY) <= zoom
            assertTrue(world != country, "zoom $zoom")
        }
    }

    // --- Rule 3: the bundled outline -------------------------------------------------------------------------------

    @Test
    fun theOutlineLayersPickTheirKind() {
        val world = parse(IndiaViewRules.WORLD_FILTER)
        val claim = parse(IndiaViewRules.CLAIM_FILTER)
        assertTrue(eval(world, mapOf("kind" to "world")))
        assertFalse(eval(world, mapOf("kind" to "claim")))
        assertTrue(eval(claim, mapOf("kind" to "claim")))
        assertFalse(eval(claim, mapOf("kind" to "world")))
        assertEquals("asset://geo/in-boundaries.geojson", IndiaViewRules.SOURCE_URI)
        assertEquals("in-boundaries", IndiaViewRules.SOURCE_ID)
        assertEquals("in-boundary-world", IndiaViewRules.WORLD_LAYER)
        assertEquals("in-boundary-claim", IndiaViewRules.CLAIM_LAYER)
        val state = parse(IndiaViewRules.STATE_FILTER)
        assertTrue(eval(state, mapOf("kind" to "state")))
        assertFalse(eval(state, mapOf("kind" to "claim")))
        assertFalse(eval(world, mapOf("kind" to "state")))
        assertFalse(eval(claim, mapOf("kind" to "state")))
        assertEquals("in-boundary-state", IndiaViewRules.STATE_OVERLAY_LAYER)
    }

    @Test
    fun indiasStateLineGoesDirectlyAboveTheStateLinesFromZoomFive() {
        // Assam-Arunachal Pradesh: the tiles mark it disputed and claimed by China, so boundary_3 never draws it; ours
        // is drawn from where boundary_3 starts, like it (the web's in-boundary-state, india-boundaries.ts).
        assertEquals(5f, IndiaViewRules.STATE_MIN_ZOOM)
        val liberty = listOf(
            line("boundary_3", "boundary", 5f),
            line("boundary_2", "boundary"),
            line("in-boundary-world", "in-boundaries"),
            line("in-boundary-claim", "in-boundaries"),
            line("boundary_disputed", "boundary"),
        )
        assertEquals(Placement.Above("boundary_3"), IndiaViewRules.statePlacement(liberty))
        val noStateLines = liberty.filter { it.id != "boundary_3" }
        assertEquals(Placement.Below("in-boundary-world"), IndiaViewRules.statePlacement(noStateLines))
        val neither = noStateLines.filter { !it.id.startsWith("in-boundary") }
        assertEquals(IndiaViewRules.placement(neither), IndiaViewRules.statePlacement(neither))
        assertEquals(Placement.Top, IndiaViewRules.statePlacement(emptyList()))
        assertEquals("hsl(0,0%,70%)", IndiaViewRules.STATE_FALLBACK_LINE_COLOR)
        assertEquals(listOf(1f, 1f), IndiaViewRules.STATE_FALLBACK_LINE_DASHARRAY.toList())
    }

    @Test
    fun theOutlineGoesDirectlyAboveTheCountryLines() {
        val liberty = listOf(
            line("boundary_3", "boundary"),
            line("boundary_2", "boundary"),
            line("boundary_disputed", "boundary"),
            symbol("label_state", "place", "[\"==\", [\"get\", \"class\"], \"state\"]"),
        )
        assertEquals(Placement.Above("boundary_2"), IndiaViewRules.placement(liberty))
    }

    @Test
    fun withoutTheCountryLinesTheOutlineGoesAboveTheFirstBoundaryLayerThenBelowTheLabels() {
        val noCountry = listOf(
            line("water", "water"),
            line("boundary_3", "boundary"),
            line("boundary_disputed", "boundary"),
            symbol("label_city", "place", null),
        )
        assertEquals(Placement.Above("boundary_3"), IndiaViewRules.placement(noCountry))
        val noBoundary = listOf(line("water", "water"), symbol("road_label", "transportation_name", null))
        assertEquals(Placement.Below("road_label"), IndiaViewRules.placement(noBoundary))
        assertEquals(Placement.Top, IndiaViewRules.placement(listOf(line("water", "water"))))
        assertEquals(Placement.Top, IndiaViewRules.placement(emptyList()))
    }

    // --- Rule 4: state labels --------------------------------------------------------------------------------------

    @Test
    fun theStateLabelsOfPakistanAdministeredKashmirAreNotShown() {
        val keep = parse(IndiaViewRules.STATE_LABEL_EXTRA_FILTER)
        // As the OpenFreeMap tiles carry them (decoded by the lead from the 20260913 planet).
        assertFalse(eval(keep, mapOf("name" to "آزاد کشمیر")))
        assertFalse(eval(keep, mapOf("name" to "گلگت بلتستان")))
        assertFalse(eval(keep, mapOf("name:en" to "Azad Kashmir", "name" to "آزاد کشمیر")))
        assertFalse(eval(keep, mapOf("name:en" to "Gilgit-Baltistan")))
        assertFalse(eval(keep, mapOf("name" to "Azad Jammu and Kashmir")))
        // India's states and union territories stay.
        assertTrue(eval(keep, mapOf("name:en" to "Jammu and Kashmir", "name" to "जम्मू और कश्मीर")))
        assertTrue(eval(keep, mapOf("name:en" to "Ladakh", "name" to "लद्दाख")))
        assertTrue(eval(keep, mapOf("name:en" to "Arunachal Pradesh", "name" to "Arunachal Pradesh")))
        assertTrue(eval(keep, mapOf("name" to "Punjab")))
        assertTrue(eval(keep, emptyMap()))
    }

    @Test
    fun everyPlaceLayerThatCanShowAStateGetsTheFilterAndNoOtherLayerDoes() {
        val layers = listOf(
            line("boundary_2", "boundary"),
            symbol(
                "label_other", "place",
                "[\"match\", [\"get\", \"class\"], [\"city\", \"continent\", \"country\", \"state\", \"town\", " +
                    "\"village\"], false, true]",
            ),
            symbol("label_village", "place", "[\"==\", [\"get\", \"class\"], \"village\"]"),
            symbol("label_state", "place", "[\"==\", [\"get\", \"class\"], \"state\"]"),
            symbol(
                "label_city", "place",
                "[\"all\", [\"==\", [\"get\", \"class\"], \"city\"], [\"!=\", [\"get\", \"capital\"], 2.0]]",
            ),
            symbol("label_any_place", "place", null),
            symbol("road_label", "transportation_name", "[\"==\", [\"get\", \"class\"], \"state\"]"),
        )
        // label_other names "state" only to exclude it; ANDing the name filter there changes nothing it draws.
        assertEquals(listOf("label_other", "label_state", "label_any_place"), IndiaViewRules.stateLabelLayers(layers))
    }

    @Test
    fun theNameListsAreTheOnesTheOwnerAgreed() {
        assertEquals(listOf("PAK", "CHN"), IndiaViewRules.HIDDEN_LINE_COUNTRIES)
        assertEquals(
            listOf("Azad Kashmir", "Azad Jammu and Kashmir", "Gilgit-Baltistan"),
            IndiaViewRules.HIDDEN_STATE_NAMES,
        )
        assertEquals(listOf("آزاد کشمیر", "گلگت بلتستان"), IndiaViewRules.HIDDEN_STATE_LOCAL_NAMES)
    }

    // --- Rule 5: a layer whose filter is in the deprecated syntax -------------------------------------------------

    @Test
    fun libertysFiltersAreReadAsExpressionsSoTheExpressionRulesAreUsed() {
        // As Expression.toArray() gives them: nested arrays, numbers as floats.
        val boundary2 = arrayOf(
            "all",
            arrayOf("==", arrayOf("get", "admin_level"), 2f),
            arrayOf("!=", arrayOf("get", "maritime"), 1f),
            arrayOf("!=", arrayOf("get", "disputed"), 1f),
            arrayOf("!", arrayOf("has", "claimed_by")),
        )
        val labelState = arrayOf("==", arrayOf("get", "class"), "state")
        val labelOther = arrayOf(
            "match", arrayOf("get", "class"), arrayOf("city", "continent", "country", "state", "town", "village"),
            false, true,
        )
        listOf(boundary2, labelState, labelOther).forEach { assertTrue(IndiaViewRules.isExpressionSyntax(it)) }
        assertEquals(
            IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER,
            IndiaViewRules.extraFilterFor(
                boundary2, IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER, IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY,
            ),
        )
        assertEquals(
            IndiaViewRules.STATE_LABEL_EXTRA_FILTER,
            IndiaViewRules.extraFilterFor(
                null, IndiaViewRules.STATE_LABEL_EXTRA_FILTER, IndiaViewRules.STATE_LABEL_EXTRA_FILTER_LEGACY,
            ),
        )
        // Our own rules are expressions, so ANDing them with an expression never mixes the syntaxes.
        assertTrue(IndiaViewRules.isExpressionSyntax(toList(parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER))))
        assertTrue(IndiaViewRules.isExpressionSyntax(toList(parse(IndiaViewRules.STATE_LABEL_EXTRA_FILTER))))
    }

    @Test
    fun eachRuleIsInOneSyntaxOnlyAndStaysSoWhenAndedWithTheLayersOwnFilter() {
        // A filter MapLibre Android cannot convert is a native crash (JNI toFilter), so each rule is written in one
        // syntax and the tree it makes with the layer's filter is read the same way as a whole.
        val expressionOps = setOf("all", "any", "has", "!", "match", "get", "coalesce", "==", ">=", "zoom")
        val legacyOps = setOf("all", "any", "has", "!has", "==", "none", "in", "!in")
        listOf(
            IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER, IndiaViewRules.STATE_LABEL_EXTRA_FILTER,
            IndiaViewRules.WORLD_FILTER, IndiaViewRules.CLAIM_FILTER, IndiaViewRules.STATE_FILTER,
            IndiaViewRules.TILE_ZOOM_GUARD,
        ).forEach {
            val ops = expressionOperators(parse(it))
            assertTrue(expressionOps.containsAll(ops), "$it uses ${ops - expressionOps}")
            assertTrue(IndiaViewRules.isExpressionSyntax(toList(parse(it))), it)
        }
        val legacyRules = listOf(
            IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY, IndiaViewRules.STATE_LABEL_EXTRA_FILTER_LEGACY,
        )
        legacyRules.forEach {
            val ops = legacyOperators(parse(it))
            assertTrue(legacyOps.containsAll(ops), "$it uses ${ops - legacyOps}")
            assertFalse(IndiaViewRules.isExpressionSyntax(toList(parse(it))), it)
        }
        // As andFilter builds them: Expression.all(existing, extra).
        val liberty = listOf(
            "all", listOf("==", listOf("get", "admin_level"), 2f), listOf("!", listOf("has", "claimed_by")),
        )
        val legacyCountry = listOf("all", listOf("==", "admin_level", 2f), listOf("!=", "maritime", 1f))
        assertTrue(
            IndiaViewRules.isExpressionSyntax(
                listOf("all", liberty, toList(parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER))),
            ),
        )
        assertFalse(
            IndiaViewRules.isExpressionSyntax(
                listOf("all", legacyCountry, toList(parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY))),
            ),
        )
    }

    @Test
    fun aDeprecatedFilterGetsTheRulesInItsOwnSyntax() {
        val legacyCountry = listOf("all", listOf("==", "admin_level", 2f), listOf("!=", "maritime", 1f))
        val legacyState = listOf("==", "class", "state")
        listOf(legacyCountry, legacyState, listOf("!in", "class", "city"), listOf("has", "\$type"), listOf("none"))
            .forEach { assertFalse(IndiaViewRules.isExpressionSyntax(it), "$it") }
        assertEquals(
            IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY,
            IndiaViewRules.extraFilterFor(
                legacyCountry,
                IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER,
                IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY,
            ),
        )
        // The deprecated rules are deprecated all the way down, and select what the expression rules select.
        val country = parse(IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY)
        val state = parse(IndiaViewRules.STATE_LABEL_EXTRA_FILTER_LEGACY)
        assertFalse(IndiaViewRules.isExpressionSyntax(toList(country)))
        assertFalse(IndiaViewRules.isExpressionSyntax(toList(state)))
        assertFalse(legacy(country, mapOf("adm0_l" to "PAK", "adm0_r" to "CHN")))
        assertTrue(legacy(country, mapOf("adm0_l" to "PAK", "adm0_r" to "AFG")))
        assertTrue(legacy(country, mapOf("adm0_r" to "PAK")))
        assertFalse(legacy(country, emptyMap()))
        assertFalse(legacy(state, mapOf("name" to "آزاد کشمیر")))
        assertFalse(legacy(state, mapOf("name:en" to "Gilgit-Baltistan")))
        assertTrue(legacy(state, mapOf("name:en" to "Ladakh", "name" to "लद्दाख")))
        assertTrue(legacy(state, emptyMap()))
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private companion object {
        /** Liberty's own filters (OpenFreeMap liberty style, as cached by the lead on 2026-09-13). */
        const val LIBERTY_BOUNDARY_3 =
            "[\"all\", [\">=\", [\"get\", \"admin_level\"], 3], [\"<=\", [\"get\", \"admin_level\"], 6], " +
                "[\"!=\", [\"get\", \"maritime\"], 1], [\"!=\", [\"get\", \"disputed\"], 1], " +
                "[\"!\", [\"has\", \"claimed_by\"]]]"
        const val LIBERTY_BOUNDARY_2 =
            "[\"all\", [\"==\", [\"get\", \"admin_level\"], 2], [\"!=\", [\"get\", \"maritime\"], 1], " +
                "[\"!=\", [\"get\", \"disputed\"], 1], [\"!\", [\"has\", \"claimed_by\"]]]"
    }

    private fun toList(e: JsonElement): Any? = when (e) {
        is JsonArray -> e.map { toList(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.content.toFloat()
        else -> throw AssertionError("no objects in a filter: $e")
    }

    /** The deprecated-syntax operators the legacy rules use. */
    private fun legacy(filter: JsonElement, props: Map<String, String>): Boolean {
        val a = filter.jsonArray
        val op = a[0].jsonPrimitive.content
        fun values() = a.drop(2).map { it.jsonPrimitive.content }
        return when (op) {
            "all" -> a.drop(1).all { legacy(it, props) }
            "any" -> a.drop(1).any { legacy(it, props) }
            "none" -> a.drop(1).none { legacy(it, props) }
            "has" -> a[1].jsonPrimitive.content in props
            "!has" -> a[1].jsonPrimitive.content !in props
            "==" -> props[a[1].jsonPrimitive.content] == a[2].jsonPrimitive.content
            // A feature without the key is in no list.
            "in" -> props[a[1].jsonPrimitive.content]?.let { it in values() } ?: false
            "!in" -> props[a[1].jsonPrimitive.content]?.let { it !in values() } ?: true
            else -> throw AssertionError("operator $op has no case in this test")
        }
    }

    private fun line(id: String, sourceLayer: String, minZoom: Float = Float.NEGATIVE_INFINITY) =
        LayerInfo(id, false, sourceLayer, null, isLine = true, minZoom = minZoom)

    private fun symbol(id: String, sourceLayer: String, filter: String?) = LayerInfo(id, true, sourceLayer, filter)

    private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)

    /**
     * [props] as the tiles carry them (strings, or numbers for admin_level, disputed and maritime); [zoom] is the
     * TILE's zoom, which is what MapLibre gives a filter's `zoom` (IndiaViewRules.TILE_ZOOM_GUARD).
     */
    private fun eval(filter: JsonElement, props: Map<String, Any>, zoom: Float = 14f): Boolean =
        (value(filter, props, zoom) as JsonPrimitive).booleanOrNull
            ?: throw AssertionError("not a boolean filter: $filter")

    /** The style-spec operators the filters use; anything else fails the test, so a new operator gets a case here. */
    private fun value(e: JsonElement, props: Map<String, Any>, zoom: Float): JsonElement {
        if (e !is JsonArray) return e
        val op = e[0].jsonPrimitive.content
        fun v(i: Int) = value(e[i], props, zoom)
        return when (op) {
            "get" -> when (val p = props[e[1].jsonPrimitive.content]) {
                null -> JsonNull
                is Number -> JsonPrimitive(p)
                is String -> JsonPrimitive(p)
                else -> throw AssertionError("property type ${p::class} has no case in this test")
            }
            "zoom" -> JsonPrimitive(zoom)
            "coalesce" -> e.drop(1).map { value(it, props, zoom) }.firstOrNull { it !is JsonNull } ?: JsonNull
            "!" -> JsonPrimitive(!truth(v(1)))
            "all" -> JsonPrimitive(e.drop(1).all { truth(value(it, props, zoom)) })
            "any" -> JsonPrimitive(e.drop(1).any { truth(value(it, props, zoom)) })
            "has" -> {
                // The one-argument form: the feature's own properties.
                assertEquals(2, e.size, "has takes one property name here: $e")
                JsonPrimitive(e[1].jsonPrimitive.content in props)
            }
            "==" -> JsonPrimitive(same(v(1), v(2)))
            "!=" -> JsonPrimitive(!same(v(1), v(2)))
            ">=" -> JsonPrimitive(number(v(1)) >= number(v(2)))
            "<=" -> JsonPrimitive(number(v(1)) <= number(v(2)))
            "match" -> {
                assertEquals(5, e.size, "match takes one label list here: $e")
                val input = v(1)
                val labels = e[2].jsonArray
                val hit = input is JsonPrimitive && input.isString && labels.any { it == input }
                if (hit) e[3] else e[4]
            }
            else -> throw AssertionError("operator $op has no case in this test")
        }
    }

    /** Every operator in an expression; a `match` label list (its third element) is data, not an operator. */
    private fun expressionOperators(e: JsonElement): Set<String> {
        if (e !is JsonArray) return emptySet()
        val op = e[0].jsonPrimitive.content
        val args = e.drop(1).filterIndexed { i, _ -> !(op == "match" && i == 1) }
        return setOf(op) + args.flatMap { expressionOperators(it) }
    }

    /** Every operator in a deprecated filter; only all, any and none hold filters, the others hold a key and values. */
    private fun legacyOperators(e: JsonElement): Set<String> {
        val op = e.jsonArray[0].jsonPrimitive.content
        val children = if (op in setOf("all", "any", "none")) e.jsonArray.drop(1) else emptyList()
        return setOf(op) + children.flatMap { legacyOperators(it) }
    }

    /** Style-spec equality: numbers by value (2 == 2.0), anything else by type and content; null equals only null. */
    private fun same(a: JsonElement, b: JsonElement): Boolean {
        val x = (a as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val y = (b as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return if (x != null && y != null) x == y else a == b
    }

    /** An ordering comparison needs two numbers here; MapLibre makes anything else an evaluation error (false). */
    private fun number(e: JsonElement): Double =
        (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?: throw AssertionError("not a number in an ordering comparison: $e")

    private fun truth(e: JsonElement): Boolean =
        (e as? JsonPrimitive)?.booleanOrNull ?: throw AssertionError("expected a boolean, got $e")
}
