package app.doorprints.ui

import app.doorprints.ui.IndiaViewRules.CLAIM_LAYER
import app.doorprints.ui.IndiaViewRules.COUNTRY_LAYER
import app.doorprints.ui.IndiaViewRules.DISPUTED_LAYER
import app.doorprints.ui.IndiaViewRules.LayerInfo
import app.doorprints.ui.IndiaViewRules.Placement
import app.doorprints.ui.IndiaViewRules.SOURCE_ID
import app.doorprints.ui.IndiaViewRules.STATE_LINE_LAYER
import app.doorprints.ui.IndiaViewRules.STATE_OVERLAY_LAYER
import app.doorprints.ui.IndiaViewRules.WORLD_LAYER

/**
 * The style operations [applyIndiaView] needs, and nothing more (ADR-23 CMP-7): a loaded map style seen by layer id.
 * Android: `MapLibreStyleOps` over MapLibre Native's `Style` (androidMain); iOS: with the map (CMP-8); tests: a fake
 * (IndiaViewOpsTest). Filters travel as MapLibre style JSON text ([IndiaViewRules]' strings); a paint value copied from
 * one layer to another is the platform's own ([PaintValue.Copied]).
 *
 * Any member may throw (a JNI or conversion failure on Android): [applyIndiaView] catches, logs with [warn] and goes
 * on (rule 5), so an implementation does not catch for it.
 */
interface StyleOps {
    /** The kinds of layer the rules tell apart. */
    enum class Kind { LINE, SYMBOL, FILL, CIRCLE, OTHER }

    /** A style layer as listed: its id, kind and source layer (null for a layer without one). */
    data class Layer(val id: String, val kind: Kind, val sourceLayer: String?)

    /** Every layer of the style, bottom to top. */
    fun layers(): List<Layer>

    /** The kind of layer [id], or null when the style has no such layer. */
    fun kind(id: String): Kind?

    /** Layer [id]'s minzoom; "no minzoom" is -infinity. */
    fun minZoom(id: String): Float

    fun setMinZoom(id: String, zoom: Float)

    /**
     * Layer [id]'s own filter as nested lists or arrays of strings, numbers and booleans (Android:
     * `Expression.toArray()`), for [IndiaViewRules.extraFilterFor] and [IndiaViewRules.tileZoomGuardFor]; null when it
     * has none.
     */
    fun filter(id: String): Any?

    /** Layer [id]'s filter as text, for [IndiaViewRules.isStateLabelLayer]; null when it has none. */
    fun filterText(id: String): String?

    /** Sets layer [id]'s filter to its own filter ANDed with [extraJson] (`all`), or to [extraJson] alone without one. */
    fun andFilter(id: String, extraJson: String)

    /** Hides layer [id] (visibility none). */
    fun hide(id: String)

    fun hasSource(id: String): Boolean

    /** Adds a GeoJSON source [id] that reads [uri] (Android: `asset://…`). */
    fun addGeoJsonSource(id: String, uri: String)

    /**
     * Line layer [layerId]'s [paint] property as a value another layer can take ([PaintValue.Copied]), or null when it
     * holds nothing usable (the caller then uses Liberty's value).
     */
    fun linePaint(layerId: String, paint: LinePaint): PaintValue?

    /** Adds [layer] at [at]: above or below a layer, or on top of all ([Placement.Top]). */
    fun addLineLayer(layer: NewLineLayer, at: Placement)

    /**
     * The text of the app's bundled file [path] (Android: from the APK's assets), for the held areas' polygon
     * ([IndiaViewRules.HELD_AREAS_ASSET_PATH]). May throw when the file cannot be read.
     */
    fun readAsset(path: String): String

    /** Logs a warning (Android: `Log.w` with the tag `IndiaView`). */
    fun warn(message: String, error: Throwable? = null)
}

/** The line paint properties the outline copies from the base map's own lines. */
enum class LinePaint(val property: String) {
    COLOR("line-color"),
    WIDTH("line-width"),
    DASHES("line-dasharray"),
    OPACITY("line-opacity"),
}

/** One paint value for a new line layer: copied from a base layer, or Liberty's own value as a fallback. */
sealed interface PaintValue {
    /** A value read from another layer, in the platform's own form (Android: a MapLibre `PropertyValue`). */
    data class Copied(val value: Any) : PaintValue

    data class Color(val css: String) : PaintValue

    data class Number(val value: Float) : PaintValue

    data class Dashes(val values: List<Float>) : PaintValue
}

/**
 * A line layer [applyIndiaView] adds: drawn from [sourceId] through [filterJson], with [paint] and round joins, round
 * caps when [roundCap] (butt otherwise, MapLibre's default), and the zoom range given ([minZoom], [maxZoom]; null
 * leaves the default).
 */
data class NewLineLayer(
    val id: String,
    val sourceId: String,
    val filterJson: String,
    val paint: Map<LinePaint, PaintValue>,
    val roundCap: Boolean,
    val minZoom: Float? = null,
    val maxZoom: Float? = null,
)

/**
 * Applies [IndiaViewRules] to a freshly loaded base style: India's external boundary as the Government of India shows
 * it, and no Line of Control or Line of Actual Control (ADR-22). Call it from every style load, before the app's own
 * layers are added, so a retry or any later style load gets it too. Common since CMP-7 (was `:app`'s `IndiaView.kt`,
 * over MapLibre's `Style`), with the same steps in the same order.
 *
 * Nothing here can crash the map: each step runs on its own, and a missing layer or a refusal is logged with
 * [StyleOps.warn] and skipped. The outline is added even when the base layers it refers to are missing (rule 5).
 */
fun applyIndiaView(ops: StyleOps) {
    // 1. No disputed lines at all.
    ops.step("hide $DISPUTED_LAYER") {
        if (ops.kind(DISPUTED_LAYER) == null) {
            ops.warn("layer $DISPUTED_LAYER not in the style; skipped")
        } else {
            ops.hide(DISPUTED_LAYER)
        }
    }

    // 2. The tiles' country lines from zoom 5 only, only those with an adm0 side (never a zoom 0-4 placeholder tile's
    //    line), without the Pakistan-China line.
    val countryIsLine = try {
        ops.kind(COUNTRY_LAYER) == StyleOps.Kind.LINE
    } catch (e: Exception) {
        ops.warn("reading $COUNTRY_LAYER failed", e)
        false
    }
    if (!countryIsLine) {
        ops.warn("line layer $COUNTRY_LAYER not in the style; skipped")
    } else {
        // The filter first and on its own: it alone keeps a placeholder tile's line off the map, so a failed minzoom
        // read or write must not stop it.
        ops.step("filter $COUNTRY_LAYER") {
            ops.andFilter(
                COUNTRY_LAYER,
                IndiaViewRules.extraFilterFor(
                    ops.filter(COUNTRY_LAYER),
                    IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER,
                    IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY,
                ),
            )
        }
        ops.step("set the minzoom of $COUNTRY_LAYER") {
            ops.setMinZoom(COUNTRY_LAYER, IndiaViewRules.countryMinZoom(ops.minZoom(COUNTRY_LAYER)))
        }
    }

    // 2b. The country and state lines (and any other boundary line layer from zoom 5) take only the features of a
    //     zoom 5+ tile, so a zoom 0-4 tile drawn in place of a loading or missing one adds no line of any admin level
    //     (Natural Earth's admin-1 lines along the Line of Control and across Aksai Chin). Both renderers already leave
    //     a minzoom 5 layer out of such a tile (IndiaViewRules.tileZoomGuardedLayers), so this is defence in depth.
    //     Each layer on its own.
    ops.step("guard the boundary lines against zoom 0-4 tiles") {
        val ids = IndiaViewRules.tileZoomGuardedLayers(ops.layers().map { boundaryLineInfo(ops, it) })
        if (ids.isEmpty()) ops.warn("no boundary line layer in the style; skipped")
        ids.forEach { id ->
            ops.step("guard $id") {
                val guard = IndiaViewRules.tileZoomGuardFor(ops.filter(id))
                if (guard == null) {
                    ops.warn("the filter of $id is in the deprecated syntax, which has no zoom; not guarded")
                } else {
                    ops.andFilter(id, guard)
                }
            }
        }
    }

    // 2c. No Pakistani or Chinese admin line inside India's outline (S4b-BL-12): the state lines leave out every tile
    //     feature wholly inside the held areas' polygon. After the guard, so the layer's filter reads
    //     all(all(Liberty's, guard), rule), as on the web. Nothing to do without the state lines.
    ops.step("filter $STATE_LINE_LAYER by the held areas") {
        if (ops.kind(STATE_LINE_LAYER) == StyleOps.Kind.LINE) {
            val geometry = IndiaViewRules.heldAreasGeometry(ops.readAsset(IndiaViewRules.HELD_AREAS_ASSET_PATH))
            val rule = geometry?.let { IndiaViewRules.heldAreasFilterFor(ops.filter(STATE_LINE_LAYER), it) }
            when {
                geometry == null -> ops.warn(
                    "the held areas' polygon is malformed; $STATE_LINE_LAYER keeps the admin lines inside them",
                )
                rule == null -> ops.warn(
                    "the filter of $STATE_LINE_LAYER is in the deprecated syntax, which has no within; " +
                        "its admin lines inside the held areas are kept",
                )
                else -> ops.andFilter(STATE_LINE_LAYER, rule)
            }
        }
    }

    // 3. India's outline from the bundled file, drawn like the country lines.
    ops.step("add the outline") {
        if (!ops.hasSource(SOURCE_ID)) ops.addGeoJsonSource(SOURCE_ID, IndiaViewRules.SOURCE_URI)
        val paint = countryPaint(ops, if (countryIsLine) COUNTRY_LAYER else null)
        // Just below 5, so at exactly 5.0 only the tiles' lines draw, as on the web (IndiaViewRules.WORLD_MAX_ZOOM).
        val world = NewLineLayer(
            WORLD_LAYER, SOURCE_ID, IndiaViewRules.WORLD_FILTER, paint, roundCap = true,
            maxZoom = IndiaViewRules.WORLD_MAX_ZOOM,
        )
        val claim = NewLineLayer(CLAIM_LAYER, SOURCE_ID, IndiaViewRules.CLAIM_FILTER, paint, roundCap = true)
        // Each layer on its own, so a refusal for one never leaves the other out (rule 5).
        ops.step("add $WORLD_LAYER") {
            if (ops.kind(WORLD_LAYER) == null) addAtItsPlace(ops, world)
        }
        ops.step("add $CLAIM_LAYER") {
            if (ops.kind(CLAIM_LAYER) == null) {
                if (ops.kind(WORLD_LAYER) == null) {
                    addAtItsPlace(ops, claim)
                } else {
                    try {
                        ops.addLineLayer(claim, Placement.Above(WORLD_LAYER))
                    } catch (e: Exception) {
                        ops.warn("placing $CLAIM_LAYER above $WORLD_LAYER failed; added on top", e)
                        if (ops.kind(CLAIM_LAYER) == null) ops.addLineLayer(claim, Placement.Top)
                    }
                }
            }
        }
    }

    // 3b. India's state line that the tiles leave undrawn (Assam-Arunachal Pradesh), from zoom 5, drawn like the other
    //     state lines, directly above them.
    ops.step("add $STATE_OVERLAY_LAYER") {
        if (ops.hasSource(SOURCE_ID) && ops.kind(STATE_OVERLAY_LAYER) == null) {
            val stateLinesIsLine = try {
                ops.kind(STATE_LINE_LAYER) == StyleOps.Kind.LINE
            } catch (e: Exception) {
                ops.warn("reading $STATE_LINE_LAYER failed", e)
                false
            }
            val state = NewLineLayer(
                STATE_OVERLAY_LAYER, SOURCE_ID, IndiaViewRules.STATE_FILTER,
                statePaint(ops, if (stateLinesIsLine) STATE_LINE_LAYER else null), roundCap = false,
                minZoom = IndiaViewRules.STATE_MIN_ZOOM,
            )
            try {
                ops.addLineLayer(state, IndiaViewRules.statePlacement(ops.layers().map(::placementInfo)))
            } catch (e: Exception) {
                ops.warn("placing $STATE_OVERLAY_LAYER failed; added on top", e)
                if (ops.kind(STATE_OVERLAY_LAYER) == null) ops.addLineLayer(state, Placement.Top)
            }
        }
    }

    // 4. No state label for the parts of Jammu and Kashmir and Ladakh under Pakistan's administration.
    ops.step("filter state labels") {
        val ids = IndiaViewRules.stateLabelLayers(ops.layers().map { labelInfo(ops, it) })
        if (ids.isEmpty()) ops.warn("no state label layer in the style; skipped")
        ids.forEach { id ->
            ops.step("filter $id") {
                ops.andFilter(
                    id,
                    IndiaViewRules.extraFilterFor(
                        ops.filter(id),
                        IndiaViewRules.STATE_LABEL_EXTRA_FILTER,
                        IndiaViewRules.STATE_LABEL_EXTRA_FILTER_LEGACY,
                    ),
                )
            }
        }
    }
}

/**
 * Adds [layer] where [IndiaViewRules.placement] puts the outline; if that place is refused, on top, because the
 * outline is drawn even when it cannot go where it belongs (rule 5).
 */
private fun addAtItsPlace(ops: StyleOps, layer: NewLineLayer) {
    try {
        ops.addLineLayer(layer, IndiaViewRules.placement(ops.layers().map(::placementInfo)))
    } catch (e: Exception) {
        ops.warn("placing ${layer.id} failed; added on top", e)
        if (ops.kind(layer.id) == null) ops.addLineLayer(layer, Placement.Top)
    }
}

/**
 * [COUNTRY_LAYER]'s colour, width and opacity ([from], or Liberty's values when it is null), so the outline looks like
 * the base map's own lines. Each is read on its own: one that cannot be read is logged and replaced by Liberty's value,
 * so the outline is still added (rule 5).
 */
private fun countryPaint(ops: StyleOps, from: String?): Map<LinePaint, PaintValue> = mapOf(
    LinePaint.COLOR to (copied(ops, from, LinePaint.COLOR) ?: PaintValue.Color(IndiaViewRules.FALLBACK_LINE_COLOR)),
    LinePaint.WIDTH to (copied(ops, from, LinePaint.WIDTH) ?: PaintValue.Number(IndiaViewRules.FALLBACK_LINE_WIDTH)),
    LinePaint.OPACITY to
        (copied(ops, from, LinePaint.OPACITY) ?: PaintValue.Number(IndiaViewRules.FALLBACK_LINE_OPACITY)),
)

/**
 * [STATE_LINE_LAYER]'s colour, width, dashes and opacity for India's state line ([from], or Liberty's values when it is
 * null), so it looks like the base map's other state lines; butt caps, as there (round caps would fill the dashes'
 * gaps). Each is read on its own; one that cannot be read is logged and replaced by Liberty's value (rule 5).
 */
private fun statePaint(ops: StyleOps, from: String?): Map<LinePaint, PaintValue> = mapOf(
    LinePaint.COLOR to
        (copied(ops, from, LinePaint.COLOR) ?: PaintValue.Color(IndiaViewRules.STATE_FALLBACK_LINE_COLOR)),
    LinePaint.WIDTH to
        (copied(ops, from, LinePaint.WIDTH) ?: PaintValue.Number(IndiaViewRules.STATE_FALLBACK_LINE_WIDTH)),
    LinePaint.DASHES to (
        copied(ops, from, LinePaint.DASHES)
            ?: PaintValue.Dashes(IndiaViewRules.STATE_FALLBACK_LINE_DASHARRAY.toList())
        ),
    LinePaint.OPACITY to
        (copied(ops, from, LinePaint.OPACITY) ?: PaintValue.Number(IndiaViewRules.FALLBACK_LINE_OPACITY)),
)

/** One paint property of layer [from] for an overlay layer, or null (the caller's fallback) when it cannot be read. */
private fun copied(ops: StyleOps, from: String?, paint: LinePaint): PaintValue? {
    if (from == null) return null
    return try {
        ops.linePaint(from, paint)
    } catch (e: Exception) {
        ops.warn("reading ${paint.property} of $from failed; Liberty's value used", e)
        null
    }
}

/** Where the outline goes needs no filters; reading them costs a call and a conversion per layer. */
private fun placementInfo(layer: StyleOps.Layer): LayerInfo = when (layer.kind) {
    StyleOps.Kind.SYMBOL -> LayerInfo(layer.id, true, layer.sourceLayer, null)
    StyleOps.Kind.LINE, StyleOps.Kind.FILL, StyleOps.Kind.CIRCLE -> LayerInfo(layer.id, false, layer.sourceLayer, null)
    StyleOps.Kind.OTHER -> LayerInfo(layer.id, false, null, null)
}

/**
 * A line layer with its source layer and minzoom, for [IndiaViewRules.tileZoomGuardedLayers]; other layers are never
 * guarded. A minzoom that cannot be read is logged and taken as none, so only the layers guarded by name are.
 */
private fun boundaryLineInfo(ops: StyleOps, layer: StyleOps.Layer): LayerInfo {
    if (layer.kind != StyleOps.Kind.LINE) return LayerInfo(layer.id, layer.kind == StyleOps.Kind.SYMBOL, null, null)
    val minZoom = try {
        ops.minZoom(layer.id)
    } catch (e: Exception) {
        ops.warn("reading the minzoom of ${layer.id} failed; taken as none", e)
        Float.NEGATIVE_INFINITY
    }
    return LayerInfo(layer.id, false, layer.sourceLayer, null, isLine = true, minZoom = minZoom)
}

/**
 * A symbol layer with its filter as text, for [IndiaViewRules.isStateLabelLayer]; other layers never show a place
 * label. A filter that cannot be read is logged and the layer left as it is ("" names no class).
 */
private fun labelInfo(ops: StyleOps, layer: StyleOps.Layer): LayerInfo {
    if (layer.kind != StyleOps.Kind.SYMBOL) return LayerInfo(layer.id, false, null, null)
    val filter = try {
        ops.filterText(layer.id)
    } catch (e: Exception) {
        ops.warn("reading the filter of ${layer.id} failed; left as it is", e)
        ""
    }
    return LayerInfo(layer.id, true, layer.sourceLayer, filter)
}

/** Runs one step; a failure is logged and the next step still runs (rule 5: never a crash). */
private inline fun StyleOps.step(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        warn("$what failed; skipped", e)
    }
}
