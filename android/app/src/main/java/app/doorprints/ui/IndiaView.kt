package app.doorprints.ui

import android.util.Log
import app.doorprints.ui.IndiaViewRules.CLAIM_LAYER
import app.doorprints.ui.IndiaViewRules.COUNTRY_LAYER
import app.doorprints.ui.IndiaViewRules.DISPUTED_LAYER
import app.doorprints.ui.IndiaViewRules.LayerInfo
import app.doorprints.ui.IndiaViewRules.Placement
import app.doorprints.ui.IndiaViewRules.SOURCE_ID
import app.doorprints.ui.IndiaViewRules.STATE_LINE_LAYER
import app.doorprints.ui.IndiaViewRules.STATE_OVERLAY_LAYER
import app.doorprints.ui.IndiaViewRules.WORLD_LAYER
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI

private const val TAG = "IndiaView"

/**
 * Applies [IndiaViewRules] to a freshly loaded base style: India's external boundary as the Government of India shows
 * it, and no Line of Control or Line of Actual Control. Call it from every `setStyle` callback, before the app's own
 * layers are added, so a retry or any later style load gets it too.
 *
 * Nothing here can crash the map: each step runs on its own, and a missing layer or a MapLibre refusal is logged
 * with [Log.w] and skipped. The outline is added even when the base layers it refers to are missing.
 */
fun applyIndiaView(style: Style) {
    // 1. No disputed lines at all.
    step("hide $DISPUTED_LAYER") {
        val disputed = style.getLayer(DISPUTED_LAYER)
        if (disputed == null) {
            Log.w(TAG, "layer $DISPUTED_LAYER not in the style; skipped")
        } else {
            disputed.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    }

    // 2. The tiles' country lines from zoom 5 only, only those with an adm0 side (never a zoom 0-4 placeholder tile's
    //    line), without the Pakistan-China line.
    val country = try {
        style.getLayer(COUNTRY_LAYER) as? LineLayer
    } catch (e: Exception) {
        Log.w(TAG, "reading $COUNTRY_LAYER failed", e)
        null
    }
    if (country == null) {
        Log.w(TAG, "line layer $COUNTRY_LAYER not in the style; skipped")
    } else {
        // The filter first and on its own: it alone keeps a placeholder tile's line off the map, so a failed minzoom
        // read or write must not stop it.
        step("filter $COUNTRY_LAYER") {
            country.setFilter(
                andFilter(
                    country.filter,
                    IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER,
                    IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER_LEGACY,
                ),
            )
        }
        step("set the minzoom of $COUNTRY_LAYER") {
            country.setMinZoom(IndiaViewRules.countryMinZoom(country.minZoom))
        }
    }

    // 2b. The country and state lines (and any other boundary line layer from zoom 5) take only the features of a
    //     zoom 5+ tile, so a zoom 0-4 tile drawn in place of a loading or missing one adds no line of any admin level
    //     (Natural Earth's admin-1 lines along the Line of Control and across Aksai Chin). Each layer on its own.
    step("guard the boundary lines against zoom 0-4 tiles") {
        val ids = IndiaViewRules.tileZoomGuardedLayers(style.layers.map(::boundaryLineInfo))
        if (ids.isEmpty()) Log.w(TAG, "no boundary line layer in the style; skipped")
        ids.forEach { id ->
            step("guard $id") {
                val line = style.getLayer(id) as LineLayer
                val existing = line.filter
                val guard = IndiaViewRules.tileZoomGuardFor(existing?.toArray())
                if (guard == null) {
                    Log.w(TAG, "the filter of $id is in the deprecated syntax, which has no zoom; not guarded")
                } else {
                    val extra = Expression.raw(guard)
                    line.setFilter(if (existing == null) extra else Expression.all(existing, extra))
                }
            }
        }
    }

    // 3. India's outline from the bundled file, drawn like the country lines.
    step("add the outline") {
        if (style.getSource(SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID, URI(IndiaViewRules.SOURCE_URI)))
        }
        val paint = linePaint(country)
        val world = LineLayer(WORLD_LAYER, SOURCE_ID)
            .withFilter(Expression.raw(IndiaViewRules.WORLD_FILTER))
            .withProperties(*paint)
        // Just below 5, so at exactly 5.0 only the tiles' lines draw, as on the web (IndiaViewRules.WORLD_MAX_ZOOM).
        world.setMaxZoom(IndiaViewRules.WORLD_MAX_ZOOM)
        val claim = LineLayer(CLAIM_LAYER, SOURCE_ID)
            .withFilter(Expression.raw(IndiaViewRules.CLAIM_FILTER))
            .withProperties(*paint)
        // Each layer on its own, so a refusal for one never leaves the other out (rule 5).
        step("add $WORLD_LAYER") {
            if (style.getLayer(WORLD_LAYER) == null) addAtItsPlace(style, world)
        }
        step("add $CLAIM_LAYER") {
            if (style.getLayer(CLAIM_LAYER) == null) {
                if (style.getLayer(WORLD_LAYER) == null) {
                    addAtItsPlace(style, claim)
                } else {
                    try {
                        style.addLayerAbove(claim, WORLD_LAYER)
                    } catch (e: Exception) {
                        Log.w(TAG, "placing $CLAIM_LAYER above $WORLD_LAYER failed; added on top", e)
                        if (style.getLayer(CLAIM_LAYER) == null) style.addLayer(claim)
                    }
                }
            }
        }
    }

    // 3b. India's state line that the tiles leave undrawn (Assam-Arunachal Pradesh), from zoom 5, drawn like the other
    //     state lines, directly above them.
    step("add $STATE_OVERLAY_LAYER") {
        if (style.getSource(SOURCE_ID) != null && style.getLayer(STATE_OVERLAY_LAYER) == null) {
            val stateLines = try {
                style.getLayer(STATE_LINE_LAYER) as? LineLayer
            } catch (e: Exception) {
                Log.w(TAG, "reading $STATE_LINE_LAYER failed", e)
                null
            }
            val state = LineLayer(STATE_OVERLAY_LAYER, SOURCE_ID)
                .withFilter(Expression.raw(IndiaViewRules.STATE_FILTER))
                .withProperties(*statePaint(stateLines))
            state.setMinZoom(IndiaViewRules.STATE_MIN_ZOOM)
            try {
                when (val at = IndiaViewRules.statePlacement(style.layers.map(::placementInfo))) {
                    is Placement.Above -> style.addLayerAbove(state, at.layerId)
                    is Placement.Below -> style.addLayerBelow(state, at.layerId)
                    Placement.Top -> style.addLayer(state)
                }
            } catch (e: Exception) {
                Log.w(TAG, "placing $STATE_OVERLAY_LAYER failed; added on top", e)
                if (style.getLayer(STATE_OVERLAY_LAYER) == null) style.addLayer(state)
            }
        }
    }

    // 4. No state label for the parts of Jammu and Kashmir and Ladakh under Pakistan's administration.
    step("filter state labels") {
        val ids = IndiaViewRules.stateLabelLayers(style.layers.map(::labelInfo))
        if (ids.isEmpty()) Log.w(TAG, "no state label layer in the style; skipped")
        ids.forEach { id ->
            step("filter $id") {
                val labels = style.getLayer(id) as SymbolLayer
                labels.setFilter(
                    andFilter(
                        labels.filter,
                        IndiaViewRules.STATE_LABEL_EXTRA_FILTER,
                        IndiaViewRules.STATE_LABEL_EXTRA_FILTER_LEGACY,
                    ),
                )
            }
        }
    }
}

/**
 * Adds [layer] where [IndiaViewRules.placement] puts the outline; if MapLibre refuses that place, on top, because the
 * outline is drawn even when it cannot go where it belongs (rule 5).
 */
private fun addAtItsPlace(style: Style, layer: Layer) {
    try {
        when (val at = IndiaViewRules.placement(style.layers.map(::placementInfo))) {
            is Placement.Above -> style.addLayerAbove(layer, at.layerId)
            is Placement.Below -> style.addLayerBelow(layer, at.layerId)
            Placement.Top -> style.addLayer(layer)
        }
    } catch (e: Exception) {
        Log.w(TAG, "placing ${layer.id} failed; added on top", e)
        if (style.getLayer(layer.id) == null) style.addLayer(layer)
    }
}

/**
 * The layer's own filter ANDed with the extra rule ([expressionJson], or [legacyJson] when the layer's filter is in
 * the deprecated syntax; [IndiaViewRules.extraFilterFor]), or the rule alone when the layer has no filter.
 */
private fun andFilter(existing: Expression?, expressionJson: String, legacyJson: String): Expression {
    val extra = Expression.raw(IndiaViewRules.extraFilterFor(existing?.toArray(), expressionJson, legacyJson))
    return if (existing == null) extra else Expression.all(existing, extra)
}

/**
 * [COUNTRY_LAYER]'s colour, width and opacity, copied at runtime so the outline looks like the base map's own lines,
 * with round joins and caps. Each is read on its own: one that cannot be read (a JNI or expression conversion
 * failure) is logged and replaced by Liberty's value, so the outline is still added (rule 5).
 */
private fun linePaint(country: LineLayer?): Array<PropertyValue<*>> {
    val color = copied("line-color", country) { layer ->
        val pv = layer.lineColor
        when {
            pv.isExpression -> pv.expression?.let { PropertyFactory.lineColor(it) }
            else -> (pv.value as? String)?.let { PropertyFactory.lineColor(it) }
        }
    } ?: PropertyFactory.lineColor(IndiaViewRules.FALLBACK_LINE_COLOR)
    val width = copied("line-width", country) { layer ->
        val pv = layer.lineWidth
        when {
            pv.isExpression -> pv.expression?.let { PropertyFactory.lineWidth(it) }
            else -> (pv.value as? Number)?.let { PropertyFactory.lineWidth(it.toFloat()) }
        }
    } ?: PropertyFactory.lineWidth(IndiaViewRules.FALLBACK_LINE_WIDTH)
    val opacity = copied("line-opacity", country) { layer ->
        val pv = layer.lineOpacity
        when {
            pv.isExpression -> pv.expression?.let { PropertyFactory.lineOpacity(it) }
            else -> (pv.value as? Number)?.let { PropertyFactory.lineOpacity(it.toFloat()) }
        }
    } ?: PropertyFactory.lineOpacity(IndiaViewRules.FALLBACK_LINE_OPACITY)
    return arrayOf<PropertyValue<*>>(
        color, width, opacity,
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
    )
}

/**
 * [STATE_LINE_LAYER]'s colour, width, dashes and opacity for India's state line, so it looks like the base map's other
 * state lines, with round joins and butt caps as there (round caps would fill the dashes' gaps). Each is read on its
 * own; one that cannot be read is logged and replaced by Liberty's value (rule 5).
 */
private fun statePaint(stateLines: LineLayer?): Array<PropertyValue<*>> {
    val color = copied("line-color", stateLines, STATE_LINE_LAYER) { layer ->
        val pv = layer.lineColor
        when {
            pv.isExpression -> pv.expression?.let { PropertyFactory.lineColor(it) }
            else -> (pv.value as? String)?.let { PropertyFactory.lineColor(it) }
        }
    } ?: PropertyFactory.lineColor(IndiaViewRules.STATE_FALLBACK_LINE_COLOR)
    val width = copied("line-width", stateLines, STATE_LINE_LAYER) { layer ->
        val pv = layer.lineWidth
        when {
            pv.isExpression -> pv.expression?.let { PropertyFactory.lineWidth(it) }
            else -> (pv.value as? Number)?.let { PropertyFactory.lineWidth(it.toFloat()) }
        }
    } ?: PropertyFactory.lineWidth(IndiaViewRules.STATE_FALLBACK_LINE_WIDTH)
    val dashes = copied("line-dasharray", stateLines, STATE_LINE_LAYER) { layer ->
        val pv = layer.lineDasharray
        when {
            pv.isExpression -> pv.expression?.let { PropertyFactory.lineDasharray(it) }
            else -> pv.value?.let { PropertyFactory.lineDasharray(it) }
        }
    } ?: PropertyFactory.lineDasharray(IndiaViewRules.STATE_FALLBACK_LINE_DASHARRAY)
    val opacity = copied("line-opacity", stateLines, STATE_LINE_LAYER) { layer ->
        val pv = layer.lineOpacity
        when {
            pv.isExpression -> pv.expression?.let { PropertyFactory.lineOpacity(it) }
            else -> (pv.value as? Number)?.let { PropertyFactory.lineOpacity(it.toFloat()) }
        }
    } ?: PropertyFactory.lineOpacity(IndiaViewRules.FALLBACK_LINE_OPACITY)
    return arrayOf<PropertyValue<*>>(color, width, dashes, opacity, PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
}

/** One paint property of [layer] for an overlay layer, or null (the caller's fallback) when it cannot be read. */
private inline fun copied(
    what: String,
    layer: LineLayer?,
    layerId: String = COUNTRY_LAYER,
    read: (LineLayer) -> PropertyValue<*>?,
): PropertyValue<*>? {
    if (layer == null) return null
    return try {
        read(layer)
    } catch (e: Exception) {
        Log.w(TAG, "reading $what of $layerId failed; Liberty's value used", e)
        null
    }
}

/** Where the outline goes needs no filters; reading them costs a JNI call and a conversion per layer. */
private fun placementInfo(layer: Layer): LayerInfo = when (layer) {
    is SymbolLayer -> LayerInfo(layer.id, true, layer.sourceLayer, null)
    is LineLayer -> LayerInfo(layer.id, false, layer.sourceLayer, null)
    is FillLayer -> LayerInfo(layer.id, false, layer.sourceLayer, null)
    is CircleLayer -> LayerInfo(layer.id, false, layer.sourceLayer, null)
    else -> LayerInfo(layer.id, false, null, null)
}

/**
 * A line layer with its source layer and minzoom, for [IndiaViewRules.tileZoomGuardedLayers]; other layers are never
 * guarded. A minzoom that cannot be read is logged and taken as none, so only the layers guarded by name are.
 */
private fun boundaryLineInfo(layer: Layer): LayerInfo {
    if (layer !is LineLayer) return LayerInfo(layer.id, layer is SymbolLayer, null, null)
    val minZoom = try {
        layer.minZoom
    } catch (e: Exception) {
        Log.w(TAG, "reading the minzoom of ${layer.id} failed; taken as none", e)
        Float.NEGATIVE_INFINITY
    }
    return LayerInfo(layer.id, false, layer.sourceLayer, null, isLine = true, minZoom = minZoom)
}

/**
 * A symbol layer with its filter as text, for [IndiaViewRules.isStateLabelLayer]; other layers never show a place
 * label. A filter that cannot be read is logged and the layer left as it is ("" names no class).
 */
private fun labelInfo(layer: Layer): LayerInfo {
    if (layer !is SymbolLayer) return LayerInfo(layer.id, false, null, null)
    val filter = try {
        layer.filter?.toString()
    } catch (e: Exception) {
        Log.w(TAG, "reading the filter of ${layer.id} failed; left as it is", e)
        ""
    }
    return LayerInfo(layer.id, true, layer.sourceLayer, filter)
}

/** Runs one step; a failure is logged and the next step still runs (rule 5: never a crash). */
private inline fun step(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$what failed; skipped", e)
    }
}
