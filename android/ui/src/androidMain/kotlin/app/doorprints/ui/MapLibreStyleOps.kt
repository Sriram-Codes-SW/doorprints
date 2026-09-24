package app.doorprints.ui

import android.content.res.AssetManager
import android.util.Log
import app.doorprints.ui.IndiaViewRules.Placement
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
 * [StyleOps] over MapLibre Native's [Style] (ADR-23 CMP-7): the calls `:app`'s `IndiaView.kt` made before the India
 * view's steps moved to common code ([applyIndiaView]), one member each. Nothing is caught here: [applyIndiaView]
 * catches and logs ([warn], `Log.w` with the tag `IndiaView`, as before). [assets] holds the held areas' polygon
 * (IndiaViewRules.HELD_AREAS_ASSET_PATH).
 */
class MapLibreStyleOps(private val style: Style, private val assets: AssetManager) : StyleOps {
    override fun layers(): List<StyleOps.Layer> = style.layers.map { layer ->
        when (layer) {
            is SymbolLayer -> StyleOps.Layer(layer.id, StyleOps.Kind.SYMBOL, layer.sourceLayer)
            is LineLayer -> StyleOps.Layer(layer.id, StyleOps.Kind.LINE, layer.sourceLayer)
            is FillLayer -> StyleOps.Layer(layer.id, StyleOps.Kind.FILL, layer.sourceLayer)
            is CircleLayer -> StyleOps.Layer(layer.id, StyleOps.Kind.CIRCLE, layer.sourceLayer)
            else -> StyleOps.Layer(layer.id, StyleOps.Kind.OTHER, null)
        }
    }

    override fun kind(id: String): StyleOps.Kind? = when (style.getLayer(id)) {
        null -> null
        is SymbolLayer -> StyleOps.Kind.SYMBOL
        is LineLayer -> StyleOps.Kind.LINE
        is FillLayer -> StyleOps.Kind.FILL
        is CircleLayer -> StyleOps.Kind.CIRCLE
        else -> StyleOps.Kind.OTHER
    }

    private fun layer(id: String): Layer = style.getLayer(id) ?: error("layer $id not in the style")

    override fun minZoom(id: String): Float = layer(id).minZoom

    override fun setMinZoom(id: String, zoom: Float) = layer(id).setMinZoom(zoom)

    private fun filterOf(id: String): Expression? = when (val layer = layer(id)) {
        is LineLayer -> layer.filter
        is SymbolLayer -> layer.filter
        is FillLayer -> layer.filter
        is CircleLayer -> layer.filter
        else -> error("layer $id has no filter")
    }

    override fun filter(id: String): Any? = filterOf(id)?.toArray()

    override fun filterText(id: String): String? = filterOf(id)?.toString()

    override fun andFilter(id: String, extraJson: String) {
        val existing = filterOf(id)
        val extra = Expression.raw(extraJson)
        val filter = if (existing == null) extra else Expression.all(existing, extra)
        when (val layer = layer(id)) {
            is LineLayer -> layer.setFilter(filter)
            is SymbolLayer -> layer.setFilter(filter)
            is FillLayer -> layer.setFilter(filter)
            is CircleLayer -> layer.setFilter(filter)
            else -> error("layer $id has no filter")
        }
    }

    override fun hide(id: String) = layer(id).setProperties(PropertyFactory.visibility(Property.NONE))

    override fun hasSource(id: String): Boolean = style.getSource(id) != null

    override fun addGeoJsonSource(id: String, uri: String) = style.addSource(GeoJsonSource(id, URI(uri)))

    override fun linePaint(layerId: String, paint: LinePaint): PaintValue? {
        val layer = layer(layerId) as? LineLayer ?: return null
        val copied: PropertyValue<*>? = when (paint) {
            LinePaint.COLOR -> {
                val pv = layer.lineColor
                when {
                    pv.isExpression -> pv.expression?.let { PropertyFactory.lineColor(it) }
                    else -> (pv.value as? String)?.let { PropertyFactory.lineColor(it) }
                }
            }
            LinePaint.WIDTH -> {
                val pv = layer.lineWidth
                when {
                    pv.isExpression -> pv.expression?.let { PropertyFactory.lineWidth(it) }
                    else -> (pv.value as? Number)?.let { PropertyFactory.lineWidth(it.toFloat()) }
                }
            }
            LinePaint.DASHES -> {
                val pv = layer.lineDasharray
                when {
                    pv.isExpression -> pv.expression?.let { PropertyFactory.lineDasharray(it) }
                    else -> pv.value?.let { PropertyFactory.lineDasharray(it) }
                }
            }
            LinePaint.OPACITY -> {
                val pv = layer.lineOpacity
                when {
                    pv.isExpression -> pv.expression?.let { PropertyFactory.lineOpacity(it) }
                    else -> (pv.value as? Number)?.let { PropertyFactory.lineOpacity(it.toFloat()) }
                }
            }
        }
        return copied?.let { PaintValue.Copied(it) }
    }

    override fun addLineLayer(layer: NewLineLayer, at: Placement) {
        val line = LineLayer(layer.id, layer.sourceId)
            .withFilter(Expression.raw(layer.filterJson))
            .withProperties(*properties(layer))
        layer.minZoom?.let { line.setMinZoom(it) }
        layer.maxZoom?.let { line.setMaxZoom(it) }
        when (at) {
            is Placement.Above -> style.addLayerAbove(line, at.layerId)
            is Placement.Below -> style.addLayerBelow(line, at.layerId)
            Placement.Top -> style.addLayer(line)
        }
    }

    override fun readAsset(path: String): String = assets.open(path).use { it.readBytes().decodeToString() }

    override fun warn(message: String, error: Throwable?) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    /** The layer's paint in the order `IndiaView.kt` set it, with round joins and, when asked, round caps. */
    private fun properties(layer: NewLineLayer): Array<PropertyValue<*>> {
        val values = LinePaint.entries.mapNotNull { paint -> layer.paint[paint]?.let { toProperty(paint, it) } }
        val join = PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        val cap = if (layer.roundCap) listOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND)) else emptyList()
        return (values + join + cap).toTypedArray()
    }

    private fun toProperty(paint: LinePaint, value: PaintValue): PropertyValue<*> = when (value) {
        is PaintValue.Copied -> value.value as PropertyValue<*>
        is PaintValue.Color -> PropertyFactory.lineColor(value.css)
        is PaintValue.Number -> when (paint) {
            LinePaint.WIDTH -> PropertyFactory.lineWidth(value.value)
            LinePaint.OPACITY -> PropertyFactory.lineOpacity(value.value)
            else -> error("${paint.property} takes no number")
        }
        is PaintValue.Dashes -> PropertyFactory.lineDasharray(value.values.toTypedArray())
    }
}
