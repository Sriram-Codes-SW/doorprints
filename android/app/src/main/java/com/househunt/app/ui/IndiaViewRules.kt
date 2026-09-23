package com.househunt.app.ui

/*
 * India's external boundary as the Government of India shows it (owner issue P0, 2026-09-24): all of Jammu and
 * Kashmir and Ladakh inside India (including the areas the tiles label "Azad Kashmir" and "Gilgit-Baltistan",
 * Shaksgam and Aksai Chin), Arunachal Pradesh inside India, and no Line of Control, Line of Actual Control or other
 * de facto or claim line. Every user of the app is in India, so this is the only view; there is no switch.
 *
 * The decisions are here, with no Android or MapLibre class, so a JVM test holds them (IndiaViewRulesTest). The
 * MapLibre calls that apply them to the loaded OpenFreeMap Liberty style are in IndiaView.kt. The web map is to apply
 * the same five rules (web team; android/shared/README.md section 9 handover):
 *  1. hide [DISPUTED_LAYER] (every disputed line: LoC, LAC, claim lines);
 *  2. [COUNTRY_LAYER] from zoom [DETAILED_FROM_ZOOM] only ([countryMinZoom]), only the lines that carry an adm0 side
 *     (so a zoom 0-4 tile's Natural Earth line is never drawn, even when MapLibre shows that tile in place of a
 *     missing zoom 5+ one) and never the Pakistan-China line ([COUNTRY_LINE_EXTRA_FILTER]); and [COUNTRY_LAYER],
 *     [STATE_LINE_LAYER] and every other `boundary` line layer that starts at zoom 5 take only the features of a
 *     zoom 5+ tile ([TILE_ZOOM_GUARD], [tileZoomGuardedLayers]), so no zoom 0-4 tile's line of any admin level is
 *     drawn in place of a loading or missing one;
 *  3. the bundled outline ([SOURCE_URI], built from Natural Earth by web/scripts/geo/build_in_boundaries.py): the
 *     'world' lines below zoom 5 ([WORLD_MAX_ZOOM]; the tiles' own lines there are Natural Earth's ISO view and
 *     cannot be filtered) and India's 'claim' outline at every zoom, directly above [COUNTRY_LAYER] and drawn like it;
 *  4. no state label for the areas above ([STATE_LABEL_EXTRA_FILTER]);
 *  5. a missing layer is skipped with a warning, never a crash, and the outline is still added; a layer whose own
 *     filter is in the deprecated syntax gets the same rules in that syntax ([extraFilterFor]), except the tile-zoom
 *     guard, which that syntax cannot express: such a layer is left as it is with a warning ([tileZoomGuardFor]).
 */
object IndiaViewRules {
    const val SOURCE_ID = "in-boundaries"

    /** The bundled file, byte-identical to web/public/geo/in-boundaries.geojson (IndiaBoundaryDataTest). */
    const val ASSET_PATH = "geo/in-boundaries.geojson"
    const val SOURCE_URI = "asset://$ASSET_PATH"

    const val WORLD_LAYER = "in-boundary-world"
    const val CLAIM_LAYER = "in-boundary-claim"

    /** Liberty's solid country lines (admin level 2, not maritime, not disputed, not a claim). */
    const val COUNTRY_LAYER = "boundary_2"

    /** Liberty's dashed state lines (admin levels 3 to 6, not maritime, not disputed, not a claim; minzoom 5). */
    const val STATE_LINE_LAYER = "boundary_3"

    /** Liberty's dashed disputed lines: the Line of Control, the Line of Actual Control and every claim line. */
    const val DISPUTED_LAYER = "boundary_disputed"

    const val BOUNDARY_SOURCE_LAYER = "boundary"
    const val PLACE_SOURCE_LAYER = "place"

    /**
     * From this zoom the tile lines carry adm0_l / adm0_r, so the Pakistan-China line can be filtered out; below it
     * the bundled 'world' lines stand in for them.
     */
    const val DETAILED_FROM_ZOOM = 5f

    /**
     * The 'world' lines' maxzoom: the largest float below [DETAILED_FROM_ZOOM], so they hand over to [COUNTRY_LAYER] at
     * exactly 5.0 as on the web. maplibre-native includes both ends of a layer's zoom range (src/mln/renderer/
     * render_layer.cpp:53 at android-v13.6.1, `minZoom <= zoom && maxZoom >= zoom`, with the map zoom cast to float,
     * render_orchestrator.cpp:175), so a maxzoom of 5 would draw both layers at 5.0; maplibre-gl excludes maxzoom
     * (style_layer.ts:323). No float lies between this and 5, so there is no zoom with neither layer either.
     */
    val WORLD_MAX_ZOOM: Float = Math.nextDown(DETAILED_FROM_ZOOM)

    /**
     * [COUNTRY_LAYER]'s minzoom: [DETAILED_FROM_ZOOM], or the layer's own when the base style sets a higher one (the
     * web keeps the higher of the two too, india-boundaries.ts). MapLibre's "no minzoom" is -infinity.
     */
    fun countryMinZoom(current: Float): Float = maxOf(current, DETAILED_FROM_ZOOM)

    /** The countries whose shared admin-2 line (the Khunjerab line, through Indian territory) is not drawn. */
    val HIDDEN_LINE_COUNTRIES = listOf("PAK", "CHN")

    /**
     * The line carries adm0_l or adm0_r. The Natural Earth lines of the zoom 0-4 tiles never do; every undisputed
     * admin-2 land line of the zoom 5+ tiles near India has at least one (India's side may be missing, the other one
     * set; the lines with neither are all disputed, hidden anyway): shown for zoom 5 (all 15 tiles over India's land
     * borders) and one zoom 6 tile by the decode of the 20260913 planet, zoom 6 to 14 still to be sampled
     * (android/shared/README.md section 9, item 38 (a)). MapLibre (native and gl)
     * draws a lower-zoom parent tile while a tile loads or when offline, and checks a layer's zoom range against the
     * map zoom, not the tile's, so minzoom alone would let a zoom 4 tile's ISO-view line through Kashmir, Aksai Chin
     * or Arunachal Pradesh show at zoom 5 and above. It stays next to [TILE_ZOOM_GUARD] because it also holds in the
     * deprecated syntax, which has no zoom. The same text in both syntaxes: `has`
     * with a property name (not `$id` / `$type`) inside `any` is read as an expression in an expression filter and
     * converted as the deprecated `has` in a deprecated one (maplibre-native src/mln/style/conversion/filter.cpp,
     * `isExpression` and `convertLegacyHasFilter`), and means "the feature has this property" in both.
     */
    private const val ADM0_PRESENT = "[\"any\", [\"has\", \"adm0_l\"], [\"has\", \"adm0_r\"]]"

    /** State labels not shown, compared with coalesce(name:en, name). */
    val HIDDEN_STATE_NAMES = listOf("Azad Kashmir", "Azad Jammu and Kashmir", "Gilgit-Baltistan")

    /** The same labels by their local (Urdu) name, compared with name. */
    val HIDDEN_STATE_LOCAL_NAMES = listOf("آزاد کشمیر", "گلگت بلتستان")

    /**
     * ANDed with [COUNTRY_LAYER]'s own filter: a line with at least one adm0 side ([ADM0_PRESENT]), and not a line with
     * Pakistan or China on both sides. `match` rather than `in`: a missing adm0_l / adm0_r (India's side is often
     * null) falls to `match`'s `false` branch, so the result never depends on how a renderer's `in` treats null.
     */
    val COUNTRY_LINE_EXTRA_FILTER: String =
        "[\"all\", $ADM0_PRESENT, [\"!\", [\"all\", ${matchAny(get("adm0_l"), HIDDEN_LINE_COUNTRIES)}, " +
            "${matchAny(get("adm0_r"), HIDDEN_LINE_COUNTRIES)}]]]"

    /**
     * ANDed with the filter of every layer [tileZoomGuardedLayers] names: only the features of a tile of zoom
     * [DETAILED_FROM_ZOOM] or more. A filter's `zoom` is the TILE's zoom, not the map's: maplibre-native evaluates it
     * with `EvaluationContext(id.overscaledZ, feature)` (src/mln/tile/geometry_tile_worker.cpp:502 at android-v13.6.1)
     * and parses a filter with `parseExpression`, which, unlike a paint property, does not require `zoom` to feed a
     * top-level step or interpolate (conversion/filter.cpp, parsing_context.cpp); maplibre-gl builds line buckets with
     * `EvaluationParameters(this.zoom)`, the worker tile's zoom (line_bucket.ts). So a zoom 4 tile shown in place of a
     * loading or missing zoom 5+ one draws nothing through these layers at any map zoom, whatever its properties:
     * the zoom 4 tiles carry only admin_level, disputed, maritime and disputed_name, and tile 4/11/6 has an undisputed
     * admin-4 line along the Line of Control north of the Kashmir valley and one across Aksai Chin that
     * [STATE_LINE_LAYER]'s own filter lets through (lead's 20260913 decode). Expression syntax only.
     */
    val TILE_ZOOM_GUARD: String = "[\">=\", [\"zoom\"], ${DETAILED_FROM_ZOOM.toInt()}]"

    /**
     * The line layers that get [TILE_ZOOM_GUARD]: [COUNTRY_LAYER] and [STATE_LINE_LAYER] by name, and every other line
     * layer on the `boundary` source layer whose minzoom is [DETAILED_FROM_ZOOM] or more, since for those the minzoom
     * alone keeps the zoom 0-4 tiles' lines off the map and MapLibre checks it against the map zoom. Not
     * [DISPUTED_LAYER] (hidden), not a symbol layer, and not a line layer meant for zoom 0-4 (lower minzoom), whose
     * low-zoom lines the guard would remove.
     */
    fun tileZoomGuardedLayers(layers: List<LayerInfo>): List<String> =
        layers.filter {
            it.isLine && it.sourceLayer == BOUNDARY_SOURCE_LAYER && it.id != DISPUTED_LAYER &&
                (it.id == COUNTRY_LAYER || it.id == STATE_LINE_LAYER || it.minZoom >= DETAILED_FROM_ZOOM)
        }.map { it.id }

    /**
     * [TILE_ZOOM_GUARD] when the layer's own [existing] filter (as `Expression.toArray()` gives it) is missing or an
     * expression; null when it is in the deprecated syntax, which has no `zoom` and cannot be mixed with an
     * expression: that layer is then left as it is with a warning (for [COUNTRY_LAYER] the adm0 guard still holds).
     */
    fun tileZoomGuardFor(existing: Any?): String? =
        if (existing == null || isExpressionSyntax(existing)) TILE_ZOOM_GUARD else null

    /** ANDed with the filter of every place label layer that can show a state ([isStateLabelLayer]). */
    val STATE_LABEL_EXTRA_FILTER: String =
        "[\"all\", [\"!\", ${matchAny("[\"coalesce\", ${get("name:en")}, ${get("name")}]", HIDDEN_STATE_NAMES)}], " +
            "[\"!\", ${matchAny(get("name"), HIDDEN_STATE_LOCAL_NAMES)}]]"

    /**
     * The same two rules in the deprecated filter syntax, for a layer whose own filter is written in it: MapLibre
     * reads a filter as an expression only when every part of an `all` is one, and a filter it cannot read is a native
     * error the app cannot catch. Liberty uses expressions throughout, so these are only a safety net if the style
     * changes; they are the web's (india-boundaries.ts). `!in` on both names hides a little more than the `coalesce`
     * form (a feature whose English name differs but whose local name is listed); they are the same labels.
     */
    val COUNTRY_LINE_EXTRA_FILTER_LEGACY: String =
        "[\"all\", $ADM0_PRESENT, [\"none\", [\"all\", ${legacyIn("adm0_l", HIDDEN_LINE_COUNTRIES)}, " +
            "${legacyIn("adm0_r", HIDDEN_LINE_COUNTRIES)}]]]"
    val STATE_LABEL_EXTRA_FILTER_LEGACY: String =
        "[\"all\", ${legacyNotIn("name:en", HIDDEN_STATE_NAMES)}, " +
            "${legacyNotIn("name", HIDDEN_STATE_NAMES + HIDDEN_STATE_LOCAL_NAMES)}]"

    val WORLD_FILTER: String = "[\"==\", ${get("kind")}, ${quote("world")}]"
    val CLAIM_FILTER: String = "[\"==\", ${get("kind")}, ${quote("claim")}]"

    /** Liberty's `boundary_2` paint, used only when that layer is missing and there is nothing to copy. */
    const val FALLBACK_LINE_COLOR = "hsl(248,1%,41%)"
    const val FALLBACK_LINE_WIDTH = 1.2f
    const val FALLBACK_LINE_OPACITY = 1f

    /**
     * What the rules need to know about a style layer. [isLine] and [minZoom] are read only for [tileZoomGuardedLayers]
     * (MapLibre's "no minzoom" is -infinity).
     */
    data class LayerInfo(
        val id: String,
        val isSymbol: Boolean,
        val sourceLayer: String?,
        val filterText: String?,
        val isLine: Boolean = false,
        val minZoom: Float = Float.NEGATIVE_INFINITY,
    )

    /** Where the two outline layers go: [WORLD_LAYER] at this place, [CLAIM_LAYER] directly above it. */
    sealed interface Placement {
        data class Above(val layerId: String) : Placement
        data class Below(val layerId: String) : Placement
        data object Top : Placement
    }

    /**
     * Directly above [COUNTRY_LAYER]; without it, above the first layer drawn from the `boundary` source layer; without
     * that, below the first symbol layer (so labels stay on top); on a style with none of them, on top.
     */
    fun placement(layers: List<LayerInfo>): Placement {
        if (layers.any { it.id == COUNTRY_LAYER }) return Placement.Above(COUNTRY_LAYER)
        layers.firstOrNull { it.sourceLayer == BOUNDARY_SOURCE_LAYER }?.let { return Placement.Above(it.id) }
        layers.firstOrNull { it.isSymbol }?.let { return Placement.Below(it.id) }
        return Placement.Top
    }

    /**
     * A symbol layer on the `place` source layer that can show a state label: its filter names the class "state"
     * (Liberty: label_state; label_other names it too, only to exclude it, so the extra filter is a no-op there) or
     * it has no filter at all.
     */
    fun isStateLabelLayer(layer: LayerInfo): Boolean =
        layer.isSymbol && layer.sourceLayer == PLACE_SOURCE_LAYER &&
            (layer.filterText == null || layer.filterText.contains(quote("state")))

    fun stateLabelLayers(layers: List<LayerInfo>): List<String> = layers.filter(::isStateLabelLayer).map { it.id }

    /**
     * The extra filter to AND with a layer's own [existing] filter (as `Expression.toArray()` gives it: nested arrays
     * or lists of strings, numbers and booleans): [expression] when there is none or it is an expression, [legacy]
     * when it is in the deprecated syntax, so the two syntaxes are never mixed.
     */
    fun extraFilterFor(existing: Any?, expression: String, legacy: String): String =
        if (existing == null || isExpressionSyntax(existing)) expression else legacy

    /**
     * Whether MapLibre reads [filter] as an expression rather than a deprecated filter: a port of `isExpression` in
     * maplibre-native src/mln/style/conversion/filter.cpp (tag android-v13.6.1), itself a port of maplibre-gl's.
     */
    fun isExpressionSyntax(filter: Any?): Boolean {
        val a = asList(filter) ?: return false
        if (a.isEmpty()) return false
        val op = a[0] as? String ?: return false
        return when (op) {
            "has" -> a.size >= 2 && (a[1] as? String).let { it != null && it != "\$id" && it != "\$type" }
            "!in", "!has", "none" -> false
            "in" -> a.size >= 3 && (a[1] !is String || asList(a[2]) != null)
            "==", "!=", ">", ">=", "<", "<=" -> a.size != 3 || asList(a[1]) != null || asList(a[2]) != null
            "any", "all" -> a.drop(1).all { isExpressionSyntax(it) || it is Boolean }
            else -> true
        }
    }

    private fun asList(value: Any?): List<Any?>? = when (value) {
        is List<*> -> value
        is Array<*> -> value.asList()
        else -> null
    }

    private fun legacyIn(key: String, values: List<String>) =
        "[\"in\", ${quote(key)}, ${values.joinToString(", ") { quote(it) }}]"

    private fun legacyNotIn(key: String, values: List<String>) =
        "[\"!in\", ${quote(key)}, ${values.joinToString(", ") { quote(it) }}]"

    private fun get(property: String) = "[\"get\", ${quote(property)}]"

    private fun matchAny(input: String, values: List<String>) =
        "[\"match\", $input, ${values.joinToString(", ", "[", "]") { quote(it) }}, true, false]"

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
