package app.doorprints.ui

import app.doorprints.ui.IndiaViewRules.CLAIM_LAYER
import app.doorprints.ui.IndiaViewRules.COUNTRY_LAYER
import app.doorprints.ui.IndiaViewRules.DISPUTED_LAYER
import app.doorprints.ui.IndiaViewRules.Placement
import app.doorprints.ui.IndiaViewRules.SOURCE_ID
import app.doorprints.ui.IndiaViewRules.STATE_LINE_LAYER
import app.doorprints.ui.IndiaViewRules.STATE_OVERLAY_LAYER
import app.doorprints.ui.IndiaViewRules.WORLD_LAYER
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The India view's steps (ADR-22, ADR-23 CMP-7): [applyIndiaView] against a fake [StyleOps] shaped like OpenFreeMap
 * Liberty, so the order, the placements, the copied paint and rule 5 (a failure is logged and skipped, never a crash,
 * and the outline is still added) are held in common code. The filters' meaning is IndiaViewRulesTest's.
 */
class IndiaViewOpsTest {
    /** A style layer as the fake holds it; [filter] is style JSON text, or null. */
    private class FakeLayer(
        val id: String,
        val kind: StyleOps.Kind,
        val sourceLayer: String?,
        var filter: String? = null,
        var minZoom: Float = Float.NEGATIVE_INFINITY,
        var maxZoom: Float? = null,
        var hidden: Boolean = false,
        val paint: Map<LinePaint, PaintValue> = emptyMap(),
        val roundCap: Boolean? = null,
    )

    private class FakeStyle(layers: List<FakeLayer>) : StyleOps {
        val layers = layers.toMutableList()
        val sources = mutableSetOf<String>()
        val warnings = mutableListOf<String>()

        /** Calls that throw, by name ("kind:boundary_2", "add:in-boundary-world@Above", "paint:boundary_2:line-color"). */
        val failing = mutableSetOf<String>()

        private fun fail(what: String) {
            if (what in failing) throw IllegalStateException("refused: $what")
        }

        fun layer(id: String) = layers.first { it.id == id }
        fun ids() = layers.map { it.id }

        override fun layers() = layers.map { StyleOps.Layer(it.id, it.kind, it.sourceLayer) }
        override fun kind(id: String): StyleOps.Kind? {
            fail("kind:$id")
            return layers.firstOrNull { it.id == id }?.kind
        }
        override fun minZoom(id: String): Float {
            fail("minzoom:$id")
            return layer(id).minZoom
        }
        override fun setMinZoom(id: String, zoom: Float) {
            layer(id).minZoom = zoom
        }
        /** As `Expression.toArray()` gives it: the filter's JSON as nested lists, strings, numbers and booleans. */
        override fun filter(id: String): Any? = layer(id).filter?.let { toValue(Json.parseToJsonElement(it)) }
        override fun filterText(id: String): String? {
            fail("filterText:$id")
            return layer(id).filter
        }
        override fun andFilter(id: String, extraJson: String) {
            fail("andFilter:$id")
            val l = layer(id)
            l.filter = l.filter?.let { "[\"all\", $it, $extraJson]" } ?: extraJson
        }
        override fun hide(id: String) {
            layer(id).hidden = true
        }
        override fun hasSource(id: String) = id in sources
        override fun addGeoJsonSource(id: String, uri: String) {
            fail("source:$id")
            sources += id
        }
        override fun linePaint(layerId: String, paint: LinePaint): PaintValue? {
            fail("paint:$layerId:${paint.property}")
            return PaintValue.Copied("$layerId ${paint.property}")
        }
        override fun addLineLayer(layer: NewLineLayer, at: Placement) {
            val where = when (at) {
                is Placement.Above -> "Above"
                is Placement.Below -> "Below"
                Placement.Top -> "Top"
            }
            fail("add:${layer.id}@$where")
            val new = FakeLayer(
                layer.id, StyleOps.Kind.LINE, null, layer.filterJson, layer.minZoom ?: Float.NEGATIVE_INFINITY,
                layer.maxZoom, paint = layer.paint, roundCap = layer.roundCap,
            )
            when (at) {
                is Placement.Above -> layers.add(ids().indexOf(at.layerId).also { require(it >= 0) } + 1, new)
                is Placement.Below -> layers.add(ids().indexOf(at.layerId).also { require(it >= 0) }, new)
                Placement.Top -> layers.add(new)
            }
        }
        /** The app's bundled files by path; the held areas' polygon by default (a square around Gilgit). */
        val assets = mutableMapOf(IndiaViewRules.HELD_AREAS_ASSET_PATH to HELD_FILE)
        override fun readAsset(path: String): String {
            fail("asset:$path")
            return assets[path] ?: throw IllegalStateException("no asset $path")
        }
        override fun warn(message: String, error: Throwable?) {
            warnings += message
        }

        private fun toValue(e: JsonElement): Any? = when (e) {
            is JsonArray -> e.map(::toValue)
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
            else -> null
        }
    }

    /** The layers of Liberty that the rules touch, in Liberty's order, with its filters in the expression syntax. */
    private fun liberty() = FakeStyle(
        listOf(
            FakeLayer("background", StyleOps.Kind.OTHER, null),
            FakeLayer("water", StyleOps.Kind.FILL, "water", "[\"==\", 1, 1]"),
            FakeLayer(STATE_LINE_LAYER, StyleOps.Kind.LINE, "boundary", "[\"<=\", [\"get\", \"admin_level\"], 6]", 5f),
            FakeLayer(COUNTRY_LAYER, StyleOps.Kind.LINE, "boundary", "[\"==\", [\"get\", \"admin_level\"], 2]"),
            FakeLayer(DISPUTED_LAYER, StyleOps.Kind.LINE, "boundary", "[\"==\", [\"get\", \"disputed\"], 1]"),
            FakeLayer("road_label", StyleOps.Kind.SYMBOL, "transportation_name", "[\"==\", 1, 1]"),
            FakeLayer("label_other", StyleOps.Kind.SYMBOL, "place", "[\"!=\", [\"get\", \"class\"], \"state\"]"),
            FakeLayer("label_state", StyleOps.Kind.SYMBOL, "place", "[\"==\", [\"get\", \"class\"], \"state\"]"),
            FakeLayer("label_city", StyleOps.Kind.SYMBOL, "place", "[\"==\", [\"get\", \"class\"], \"city\"]"),
        ),
    )

    @Test
    fun onLibertyEveryStepApplies() {
        val style = liberty()
        applyIndiaView(style)

        assertTrue(style.layer(DISPUTED_LAYER).hidden)
        // Rule 2: the country lines' own filter ANDed with the adm0 / Pakistan-China rule, then the tile-zoom guard.
        assertEquals(
            "[\"all\", [\"all\", [\"==\", [\"get\", \"admin_level\"], 2], ${IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER}], " +
                "${IndiaViewRules.TILE_ZOOM_GUARD}]",
            style.layer(COUNTRY_LAYER).filter,
        )
        assertEquals(5f, style.layer(COUNTRY_LAYER).minZoom)
        // The state lines: Liberty's filter, the tile-zoom guard, then no line wholly inside the held areas.
        assertEquals(
            "[\"all\", [\"all\", [\"<=\", [\"get\", \"admin_level\"], 6], ${IndiaViewRules.TILE_ZOOM_GUARD}], " +
                "[\"!\", [\"within\", $HELD_GEOMETRY]]]",
            style.layer(STATE_LINE_LAYER).filter,
        )
        // The disputed lines are hidden, not guarded.
        assertEquals("[\"==\", [\"get\", \"disputed\"], 1]", style.layer(DISPUTED_LAYER).filter)

        // Rule 3: the outline source, 'world' directly above boundary_2 up to just below 5, 'claim' directly above it,
        // both drawn like boundary_2 with round joins and caps.
        assertTrue(SOURCE_ID in style.sources)
        val ids = style.ids()
        assertEquals(ids.indexOf(COUNTRY_LAYER) + 1, ids.indexOf(WORLD_LAYER))
        assertEquals(ids.indexOf(WORLD_LAYER) + 1, ids.indexOf(CLAIM_LAYER))
        val world = style.layer(WORLD_LAYER)
        assertEquals(IndiaViewRules.WORLD_FILTER, world.filter)
        assertEquals(IndiaViewRules.WORLD_MAX_ZOOM, world.maxZoom)
        assertEquals(Float.NEGATIVE_INFINITY, world.minZoom)
        assertEquals(true, world.roundCap)
        val countryPaint = mapOf(
            LinePaint.COLOR to PaintValue.Copied("boundary_2 line-color"),
            LinePaint.WIDTH to PaintValue.Copied("boundary_2 line-width"),
            LinePaint.OPACITY to PaintValue.Copied("boundary_2 line-opacity"),
        )
        assertEquals(countryPaint, world.paint)
        val claim = style.layer(CLAIM_LAYER)
        assertEquals(IndiaViewRules.CLAIM_FILTER, claim.filter)
        assertNull(claim.maxZoom)
        assertEquals(countryPaint, claim.paint)

        // Rule 3b: India's state line directly above boundary_3, from zoom 5, drawn like it, with butt caps.
        assertEquals(style.ids().indexOf(STATE_LINE_LAYER) + 1, style.ids().indexOf(STATE_OVERLAY_LAYER))
        val state = style.layer(STATE_OVERLAY_LAYER)
        assertEquals(IndiaViewRules.STATE_FILTER, state.filter)
        assertEquals(IndiaViewRules.STATE_MIN_ZOOM, state.minZoom)
        assertEquals(false, state.roundCap)
        assertEquals(
            listOf(LinePaint.COLOR, LinePaint.WIDTH, LinePaint.DASHES, LinePaint.OPACITY)
                .associateWith { PaintValue.Copied("boundary_3 ${it.property}") },
            state.paint,
        )

        // Rule 4: both place label layers that name "state" get the label rule; the city labels and roads do not.
        listOf("label_other", "label_state").forEach { id ->
            assertTrue(style.layer(id).filter!!.endsWith("${IndiaViewRules.STATE_LABEL_EXTRA_FILTER}]"), id)
        }
        assertEquals("[\"==\", [\"get\", \"class\"], \"city\"]", style.layer("label_city").filter)
        assertEquals("[\"==\", 1, 1]", style.layer("road_label").filter)
        assertEquals(emptyList(), style.warnings)
    }

    @Test
    fun aSecondRunOnTheSameStyleAddsNoLayerTwice() {
        val style = liberty()
        applyIndiaView(style)
        val layers = style.ids()
        applyIndiaView(style)
        assertEquals(layers, style.ids())
    }

    @Test
    fun aDeprecatedFilterGetsTheDeprecatedRulesAndNoTileZoomGuard() {
        val style = liberty()
        style.layer(COUNTRY_LAYER).filter = "[\"!in\", \"admin_level\", 3]"
        style.layer("label_state").filter = "[\"in\", \"class\", \"state\"]"
        applyIndiaView(style)
        // The adm0 rule in the deprecated syntax, and no guard (that syntax has no zoom): logged instead.
        assertEquals(
            "[\"all\", [\"!in\", \"admin_level\", 3], ${IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY}]",
            style.layer(COUNTRY_LAYER).filter,
        )
        assertTrue(style.warnings.any { it.contains("the filter of $COUNTRY_LAYER is in the deprecated syntax") })
        assertTrue(style.layer("label_state").filter!!.endsWith("${IndiaViewRules.STATE_LABEL_EXTRA_FILTER_LEGACY}]"))
    }

    @Test
    fun withoutTheBaseLayersTheOutlineIsStillAdded() {
        // A style with none of Liberty's boundary or place layers: every step warns, nothing throws, and the outline
        // goes below the first symbol layer with Liberty's own paint.
        val style = FakeStyle(
            listOf(
                FakeLayer("background", StyleOps.Kind.OTHER, null),
                FakeLayer("roads", StyleOps.Kind.LINE, "transportation"),
                FakeLayer("road_label", StyleOps.Kind.SYMBOL, "transportation_name"),
            ),
        )
        applyIndiaView(style)
        assertEquals(
            // 'world' below the first symbol layer, 'claim' above it, the state line below 'world' (statePlacement).
            listOf("background", "roads", STATE_OVERLAY_LAYER, WORLD_LAYER, CLAIM_LAYER, "road_label"),
            style.ids(),
        )
        assertEquals(
            mapOf(
                LinePaint.COLOR to PaintValue.Color(IndiaViewRules.FALLBACK_LINE_COLOR),
                LinePaint.WIDTH to PaintValue.Number(IndiaViewRules.FALLBACK_LINE_WIDTH),
                LinePaint.OPACITY to PaintValue.Number(IndiaViewRules.FALLBACK_LINE_OPACITY),
            ),
            style.layer(WORLD_LAYER).paint,
        )
        assertEquals(
            PaintValue.Dashes(IndiaViewRules.STATE_FALLBACK_LINE_DASHARRAY.toList()),
            style.layer(STATE_OVERLAY_LAYER).paint[LinePaint.DASHES],
        )
        assertEquals(
            listOf(
                "layer $DISPUTED_LAYER not in the style; skipped",
                "line layer $COUNTRY_LAYER not in the style; skipped",
                "no boundary line layer in the style; skipped",
                "no state label layer in the style; skipped",
            ),
            style.warnings,
        )
    }

    @Test
    fun eachFailureIsLoggedAndTheOtherStepsStillRun() {
        val style = liberty()
        style.failing += listOf(
            "andFilter:$COUNTRY_LAYER", // rule 2's filter and guard on boundary_2
            "minzoom:$STATE_LINE_LAYER", // read for the guard's list: taken as none, still guarded by name
            "paint:$COUNTRY_LAYER:line-width", // Liberty's width instead, the rest copied
            "add:$WORLD_LAYER@Above", // refused at its place: added on top instead
            "filterText:label_other", // left as it is
        )
        applyIndiaView(style)

        assertEquals("[\"==\", [\"get\", \"admin_level\"], 2]", style.layer(COUNTRY_LAYER).filter)
        assertEquals(5f, style.layer(COUNTRY_LAYER).minZoom)
        assertTrue(style.layer(STATE_LINE_LAYER).filter!!.contains("${IndiaViewRules.TILE_ZOOM_GUARD}]"))
        assertTrue(style.layer(STATE_LINE_LAYER).filter!!.endsWith("${IndiaViewRules.heldAreasFilter(HELD_GEOMETRY)}]"))
        assertTrue(style.layer(DISPUTED_LAYER).hidden)
        // 'world' on top; 'claim' still directly above it.
        assertEquals(listOf(WORLD_LAYER, CLAIM_LAYER), style.ids().takeLast(2))
        assertEquals(PaintValue.Number(IndiaViewRules.FALLBACK_LINE_WIDTH), style.layer(CLAIM_LAYER).paint[LinePaint.WIDTH])
        assertEquals(PaintValue.Copied("boundary_2 line-color"), style.layer(CLAIM_LAYER).paint[LinePaint.COLOR])
        assertEquals("[\"!=\", [\"get\", \"class\"], \"state\"]", style.layer("label_other").filter)
        assertTrue(style.layer("label_state").filter!!.endsWith("${IndiaViewRules.STATE_LABEL_EXTRA_FILTER}]"))
        assertEquals(
            listOf(
                "filter $COUNTRY_LAYER failed; skipped",
                "reading the minzoom of $STATE_LINE_LAYER failed; taken as none",
                "guard $COUNTRY_LAYER failed; skipped",
                "reading line-width of $COUNTRY_LAYER failed; Liberty's value used",
                "placing $WORLD_LAYER failed; added on top",
                "reading the filter of label_other failed; left as it is",
            ),
            style.warnings,
        )
    }

    @Test
    fun aFailedReadOfTheCountryLayerStillAddsTheOutline() {
        val style = liberty()
        style.failing += "kind:$COUNTRY_LAYER"
        applyIndiaView(style)
        // Rule 2 skipped for boundary_2 (not read), its guard still applied from the listing; the outline added with
        // Liberty's paint, at its place above boundary_2.
        assertEquals(
            listOf("reading $COUNTRY_LAYER failed", "line layer $COUNTRY_LAYER not in the style; skipped"),
            style.warnings,
        )
        assertEquals(style.ids().indexOf(COUNTRY_LAYER) + 1, style.ids().indexOf(WORLD_LAYER))
        assertEquals(PaintValue.Color(IndiaViewRules.FALLBACK_LINE_COLOR), style.layer(WORLD_LAYER).paint[LinePaint.COLOR])
        assertFalse(style.layer(COUNTRY_LAYER).minZoom == 5f)
    }

    @Test
    fun theOutlineSourceIsNotAddedTwiceAndAMissingSourceSkipsTheStateLine() {
        val style = liberty()
        style.failing += "source:$SOURCE_ID"
        applyIndiaView(style)
        // The outline step failed as a whole; the state line needs the source, so it is not added either.
        assertFalse(WORLD_LAYER in style.ids())
        assertFalse(STATE_OVERLAY_LAYER in style.ids())
        assertTrue(style.warnings.contains("add the outline failed; skipped"))
        // The label rule still ran after them.
        assertTrue(style.layer("label_state").filter!!.endsWith("${IndiaViewRules.STATE_LABEL_EXTRA_FILTER}]"))
    }

    @Test
    fun aMalformedOrUnreadableHeldAreasFileLeavesTheStateLinesGuardedOnly() {
        val guardedOnly = "[\"all\", [\"<=\", [\"get\", \"admin_level\"], 6], ${IndiaViewRules.TILE_ZOOM_GUARD}]"
        val malformed = liberty()
        malformed.assets[IndiaViewRules.HELD_AREAS_ASSET_PATH] = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        applyIndiaView(malformed)
        assertEquals(guardedOnly, malformed.layer(STATE_LINE_LAYER).filter)
        assertEquals(
            listOf("the held areas' polygon is malformed; $STATE_LINE_LAYER keeps the admin lines inside them"),
            malformed.warnings,
        )
        // The rest still applied: the outline is there.
        assertTrue(CLAIM_LAYER in malformed.ids())

        val unreadable = liberty()
        unreadable.failing += "asset:${IndiaViewRules.HELD_AREAS_ASSET_PATH}"
        applyIndiaView(unreadable)
        assertEquals(guardedOnly, unreadable.layer(STATE_LINE_LAYER).filter)
        assertEquals(listOf("filter $STATE_LINE_LAYER by the held areas failed; skipped"), unreadable.warnings)
        assertTrue(CLAIM_LAYER in unreadable.ids())
    }

    @Test
    fun aDeprecatedStateLineFilterIsLeftAsItIsAndWithoutStateLinesNothingIsSaid() {
        val deprecated = liberty()
        deprecated.layer(STATE_LINE_LAYER).filter = "[\"<=\", \"admin_level\", 6]"
        applyIndiaView(deprecated)
        assertEquals("[\"<=\", \"admin_level\", 6]", deprecated.layer(STATE_LINE_LAYER).filter)
        assertEquals(
            listOf(
                "the filter of $STATE_LINE_LAYER is in the deprecated syntax, which has no zoom; not guarded",
                "the filter of $STATE_LINE_LAYER is in the deprecated syntax, which has no within; " +
                    "its admin lines inside the held areas are kept",
            ),
            deprecated.warnings,
        )

        // Without boundary_3 no admin line is drawn at all: nothing to filter, no warning, and no file read.
        val none = liberty()
        none.layers.removeAll { it.id == STATE_LINE_LAYER }
        none.failing += "asset:${IndiaViewRules.HELD_AREAS_ASSET_PATH}"
        applyIndiaView(none)
        assertEquals(emptyList(), none.warnings)
    }

    @Test
    fun aStateLineLayerWithoutAFilterGetsTheGuardAndTheRule() {
        val style = liberty()
        style.layer(STATE_LINE_LAYER).filter = null
        applyIndiaView(style)
        assertEquals(
            "[\"all\", ${IndiaViewRules.TILE_ZOOM_GUARD}, ${IndiaViewRules.heldAreasFilter(HELD_GEOMETRY)}]",
            style.layer(STATE_LINE_LAYER).filter,
        )
    }

    private companion object {
        /** A held-areas file as the build writes it (a square around Gilgit); the real one: IndiaBoundaryDataTest. */
        const val HELD_FILE =
            "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"kind\":\"held\"}," +
                "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[74,35],[75,35],[75,36],[74,36],[74,35]]]}}]}"
        const val HELD_GEOMETRY = "{\"type\":\"Polygon\",\"coordinates\":[[[74,35],[75,35],[75,36],[74,36],[74,35]]]}"
    }
}
