package app.doorprints.ui

import app.doorprints.ui.IndiaViewRules.Placement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * [StyleOps] over a map style held as MapLibre style JSON (ADR-23 CMP-8c), for the iOS map, which loads its style from
 * the JSON [prepareMapStyle] builds (`MLNMapView`'s `styleJSON`) rather than changing a loaded style layer by layer as
 * Android does (MapLibreStyleOps). [applyIndiaView] runs over it exactly as on Android, so both apps get the same steps
 * in the same order; the filters stay the rules' own JSON text, ANDed with `all`, and are never converted to another
 * form (MapLibre iOS's NSPredicate filters would be one). [toJson] gives the result.
 *
 * [readAsset] reads the app's bundled files (iOS: from the app bundle's `geo/` folder); a GeoJSON source added from an
 * `asset://` address has that file's content inline as its `data`, so the style needs no file access of its own.
 * [warn] logs a warning (iOS: the unified log). Not thread-safe; one style load at a time.
 */
class JsonStyleOps(
    style: JsonObject,
    private val readAsset: (String) -> String,
    private val warn: (String, Throwable?) -> Unit,
) : StyleOps {
    private val root: MutableMap<String, JsonElement> = style.toMutableMap()
    private val layers: MutableList<MutableMap<String, JsonElement>> =
        (style["layers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toMutableMap() }.toMutableList()
    private val sources: MutableMap<String, JsonElement> = (style["sources"] as? JsonObject)?.toMutableMap()
        ?: mutableMapOf()

    /** The style with every change made so far. */
    fun toJson(): JsonObject {
        root["sources"] = JsonObject(sources)
        root["layers"] = JsonArray(layers.map { JsonObject(it) })
        return JsonObject(root)
    }

    /** Adds [source] as [id], replacing one of that id (the houses' source; [prepareMapStyle]). */
    fun putSource(id: String, source: JsonObject) {
        sources[id] = source
    }

    /** Adds [layer] on top of all the others (the house layers; [prepareMapStyle]). */
    fun addLayerOnTop(layer: JsonObject) {
        layers += layer.toMutableMap()
    }

    override fun layers(): List<StyleOps.Layer> = layers.map { layer ->
        val id = layer.string("id") ?: ""
        val kind = kindOf(layer)
        StyleOps.Layer(id, kind, if (kind == StyleOps.Kind.OTHER) null else layer.string("source-layer"))
    }

    override fun kind(id: String): StyleOps.Kind? = find(id)?.let(::kindOf)

    override fun minZoom(id: String): Float =
        (layer(id)["minzoom"] as? JsonPrimitive)?.floatOrNull ?: Float.NEGATIVE_INFINITY

    override fun setMinZoom(id: String, zoom: Float) {
        layer(id)["minzoom"] = JsonPrimitive(zoom)
    }

    override fun filter(id: String): Any? = layer(id)["filter"]?.takeUnless { it is JsonNull }?.let(::plain)

    override fun filterText(id: String): String? = layer(id)["filter"]?.takeUnless { it is JsonNull }?.toString()

    override fun andFilter(id: String, extraJson: String) {
        val layer = layer(id)
        val extra = Json.parseToJsonElement(extraJson)
        val existing = layer["filter"]?.takeUnless { it is JsonNull }
        layer["filter"] = if (existing == null) extra else JsonArray(listOf(JsonPrimitive("all"), existing, extra))
    }

    override fun hide(id: String) {
        val layer = layer(id)
        val layout = (layer["layout"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        layout["visibility"] = JsonPrimitive("none")
        layer["layout"] = JsonObject(layout)
    }

    override fun hasSource(id: String): Boolean = sources.containsKey(id)

    override fun addGeoJsonSource(id: String, uri: String) {
        check(!sources.containsKey(id)) { "source $id already in the style" }
        val data: JsonElement = if (uri.startsWith(ASSET_SCHEME)) {
            Json.parseToJsonElement(readAsset(uri.removePrefix(ASSET_SCHEME)))
        } else {
            JsonPrimitive(uri)
        }
        sources[id] = JsonObject(mapOf("type" to JsonPrimitive("geojson"), "data" to data))
    }

    override fun linePaint(layerId: String, paint: LinePaint): PaintValue? {
        val layer = layer(layerId)
        if (kindOf(layer) != StyleOps.Kind.LINE) return null
        val value = (layer["paint"] as? JsonObject)?.get(paint.property)?.takeUnless { it is JsonNull }
        return value?.let { PaintValue.Copied(it) }
    }

    override fun addLineLayer(layer: NewLineLayer, at: Placement) {
        check(find(layer.id) == null) { "layer ${layer.id} already in the style" }
        val paint = LinePaint.entries.mapNotNull { p -> layer.paint[p]?.let { p.property to paintJson(p, it) } }.toMap()
        val layout = buildMap {
            put("line-join", JsonPrimitive("round"))
            if (layer.roundCap) put("line-cap", JsonPrimitive("round"))
        }
        val json = buildMap<String, JsonElement> {
            put("id", JsonPrimitive(layer.id))
            put("type", JsonPrimitive("line"))
            put("source", JsonPrimitive(layer.sourceId))
            put("filter", Json.parseToJsonElement(layer.filterJson))
            layer.minZoom?.let { put("minzoom", JsonPrimitive(it)) }
            layer.maxZoom?.let { put("maxzoom", JsonPrimitive(it)) }
            put("layout", JsonObject(layout))
            put("paint", JsonObject(paint))
        }.toMutableMap()
        val index = when (at) {
            is Placement.Above -> indexOf(at.layerId) + 1
            is Placement.Below -> indexOf(at.layerId)
            Placement.Top -> layers.size
        }
        layers.add(index, json)
    }

    override fun readAsset(path: String): String = readAsset.invoke(path)

    override fun warn(message: String, error: Throwable?) = warn.invoke(message, error)

    private fun find(id: String): MutableMap<String, JsonElement>? = layers.firstOrNull { it.string("id") == id }

    private fun layer(id: String): MutableMap<String, JsonElement> = find(id) ?: error("layer $id not in the style")

    private fun indexOf(id: String): Int = layers.indexOfFirst { it.string("id") == id }.also {
        check(it >= 0) { "layer $id not in the style" }
    }

    private fun paintJson(paint: LinePaint, value: PaintValue): JsonElement = when (value) {
        is PaintValue.Copied -> value.value as? JsonElement ?: error("${paint.property}: not a JSON value")
        is PaintValue.Color -> JsonPrimitive(value.css)
        is PaintValue.Number -> JsonPrimitive(value.value)
        is PaintValue.Dashes -> JsonArray(value.values.map { JsonPrimitive(it) })
    }

    private companion object {
        const val ASSET_SCHEME = "asset://"

        fun MutableMap<String, JsonElement>.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun kindOf(layer: Map<String, JsonElement>): StyleOps.Kind =
            when ((layer["type"] as? JsonPrimitive)?.contentOrNull) {
                "line" -> StyleOps.Kind.LINE
                "symbol" -> StyleOps.Kind.SYMBOL
                "fill" -> StyleOps.Kind.FILL
                "circle" -> StyleOps.Kind.CIRCLE
                else -> StyleOps.Kind.OTHER
            }

        /**
         * A filter as nested lists of strings, numbers, booleans and nulls, the form [IndiaViewRules.isExpressionSyntax]
         * reads (Android gives the same from `Expression.toArray()`); an object (a `within` polygon, a literal) stays a
         * map.
         */
        fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonArray -> e.map(::plain)
            is JsonObject -> e.mapValues { plain(it.value) }
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
        }
    }
}
