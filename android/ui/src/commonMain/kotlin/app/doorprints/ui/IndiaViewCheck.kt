package app.doorprints.ui

import app.doorprints.ui.IndiaViewRules.CLAIM_LAYER
import app.doorprints.ui.IndiaViewRules.COUNTRY_LAYER
import app.doorprints.ui.IndiaViewRules.DISPUTED_LAYER
import app.doorprints.ui.IndiaViewRules.SOURCE_ID
import app.doorprints.ui.IndiaViewRules.STATE_LINE_LAYER
import app.doorprints.ui.IndiaViewRules.STATE_OVERLAY_LAYER
import app.doorprints.ui.IndiaViewRules.WORLD_LAYER
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Checks that India's boundary rules (ADR-22, [IndiaViewRules]) hold in a style built as JSON ([prepareMapStyle],
 * iOS), after [applyIndiaView] has run: the "in-app boundary check" the owner made the CI gate for the iOS map
 * (2026-09-29; the launch smoke's `indiaView` line). It reads the result, not the steps, so a step that was skipped
 * with a warning (rule 5: never a crash) still fails here. Every problem is one short line that names a layer, never a
 * house; an empty list means the style is right.
 *
 * What must hold, for the OpenFreeMap Liberty style as it is today (a renamed layer fails, so the change is seen):
 *  1. the disputed lines ([DISPUTED_LAYER]) are in the style and hidden;
 *  2. [COUNTRY_LAYER] starts at zoom 5 or later, its filter holds the adm0 rule and the tile-zoom guard;
 *  3. [STATE_LINE_LAYER] holds the tile-zoom guard and the held areas' `within` rule; every other `boundary` line
 *     layer from zoom 5 holds the guard;
 *  4. the outline: the source [SOURCE_ID] with the 'world', 'claim' and 'state' lines inline, [WORLD_LAYER] below zoom 5
 *     directly above [COUNTRY_LAYER], [CLAIM_LAYER] directly above it, [STATE_OVERLAY_LAYER] from zoom 5 directly
 *     above [STATE_LINE_LAYER], each with its own filter;
 *  5. every state label layer holds the state-label rule, and there is at least one;
 *  6. the house layers are the top two, so no base layer covers a marker.
 */
object IndiaViewCheck {
    fun problems(style: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        val layers = (style["layers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val ids = layers.map { it.str("id") }
        fun layer(id: String): JsonObject? = layers.firstOrNull { it.str("id") == id }
        fun has(id: String, rule: String) = layer(id)?.get("filter")?.contains(Json.parseToJsonElement(rule)) == true

        // 1. The disputed lines.
        val disputed = layer(DISPUTED_LAYER)
        when {
            disputed == null -> problems += "$DISPUTED_LAYER is not in the style"
            (disputed["layout"] as? JsonObject)?.str("visibility") != "none" -> problems += "$DISPUTED_LAYER is shown"
        }

        // 2. The country lines.
        val country = layer(COUNTRY_LAYER)
        if (country == null || country.str("type") != "line") {
            problems += "$COUNTRY_LAYER is not a line layer in the style"
        } else {
            val minZoom = (country["minzoom"] as? JsonPrimitive)?.floatOrNull ?: Float.NEGATIVE_INFINITY
            if (minZoom < IndiaViewRules.DETAILED_FROM_ZOOM) problems += "$COUNTRY_LAYER starts below zoom 5"
            if (!has(COUNTRY_LAYER, IndiaViewRules.COUNTRY_LINE_EXTRA_FILTER)) {
                problems += "$COUNTRY_LAYER lacks the country-line rule"
            }
            if (!has(COUNTRY_LAYER, IndiaViewRules.TILE_ZOOM_GUARD)) problems += "$COUNTRY_LAYER lacks the tile-zoom guard"
        }

        // 3. The state lines, and every other boundary line layer from zoom 5.
        val stateLines = layer(STATE_LINE_LAYER)
        if (stateLines == null || stateLines.str("type") != "line") {
            problems += "$STATE_LINE_LAYER is not a line layer in the style"
        } else {
            if (!has(STATE_LINE_LAYER, IndiaViewRules.TILE_ZOOM_GUARD)) {
                problems += "$STATE_LINE_LAYER lacks the tile-zoom guard"
            }
            val within = stateLines["filter"]?.let(::hasHeldAreasRule) == true
            if (!within) problems += "$STATE_LINE_LAYER lacks the held areas' rule"
        }
        layers.filter {
            it.str("type") == "line" && it.str("source-layer") == IndiaViewRules.BOUNDARY_SOURCE_LAYER &&
                it.str("id") !in setOf(DISPUTED_LAYER, COUNTRY_LAYER, STATE_LINE_LAYER) &&
                ((it["minzoom"] as? JsonPrimitive)?.floatOrNull ?: Float.NEGATIVE_INFINITY) >=
                IndiaViewRules.DETAILED_FROM_ZOOM
        }.forEach { l ->
            val id = l.str("id") ?: return@forEach
            if (!has(id, IndiaViewRules.TILE_ZOOM_GUARD)) problems += "$id lacks the tile-zoom guard"
        }

        // 4. The outline.
        val source = (style["sources"] as? JsonObject)?.get(SOURCE_ID) as? JsonObject
        val kinds = ((source?.get("data") as? JsonObject)?.get("features") as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("properties") as? JsonObject)?.str("kind") }.toSet()
        if (source?.str("type") != "geojson") {
            problems += "the source $SOURCE_ID is not in the style"
        } else {
            listOf("world", "claim", "state").filter { it !in kinds }
                .forEach { problems += "the source $SOURCE_ID has no '$it' line" }
        }
        fun outline(id: String, filter: String, directlyAbove: String?) {
            val l = layer(id)
            when {
                l == null -> problems += "$id is not in the style"
                l.str("type") != "line" || l.str("source") != SOURCE_ID -> problems += "$id is not a line of $SOURCE_ID"
                l["filter"] != Json.parseToJsonElement(filter) -> problems += "$id has the wrong filter"
                directlyAbove != null && ids.indexOf(id) != ids.indexOf(directlyAbove) + 1 ->
                    problems += "$id is not directly above $directlyAbove"
            }
        }
        outline(WORLD_LAYER, IndiaViewRules.WORLD_FILTER, COUNTRY_LAYER)
        outline(CLAIM_LAYER, IndiaViewRules.CLAIM_FILTER, WORLD_LAYER)
        outline(STATE_OVERLAY_LAYER, IndiaViewRules.STATE_FILTER, STATE_LINE_LAYER)
        val worldMax = (layer(WORLD_LAYER)?.get("maxzoom") as? JsonPrimitive)?.floatOrNull
        if (layer(WORLD_LAYER) != null && (worldMax == null || worldMax >= IndiaViewRules.DETAILED_FROM_ZOOM)) {
            problems += "$WORLD_LAYER is drawn at zoom 5"
        }
        val stateMin = (layer(STATE_OVERLAY_LAYER)?.get("minzoom") as? JsonPrimitive)?.floatOrNull
        if (layer(STATE_OVERLAY_LAYER) != null && stateMin != IndiaViewRules.STATE_MIN_ZOOM) {
            problems += "$STATE_OVERLAY_LAYER does not start at zoom 5"
        }

        // 5. The state labels.
        val labels = layers.filter {
            IndiaViewRules.isStateLabelLayer(
                IndiaViewRules.LayerInfo(
                    it.str("id") ?: "", it.str("type") == "symbol", it.str("source-layer"),
                    it["filter"]?.toString(),
                ),
            )
        }
        if (labels.isEmpty()) problems += "no state label layer in the style"
        labels.forEach { l ->
            val id = l.str("id") ?: return@forEach
            if (!has(id, IndiaViewRules.STATE_LABEL_EXTRA_FILTER)) problems += "$id lacks the state-label rule"
        }

        // 6. The houses on top.
        if (ids.takeLast(2) != listOf(HOUSE_DOTS_LAYER, HOUSE_LABELS_LAYER)) {
            problems += "the house layers are not the top two"
        }
        return problems
    }

    /**
     * Compares the layers MapLibre reports for the loaded style ([loadedIds], bottom to top, and the ids it reports
     * hidden, [hiddenIds]) with the style JSON handed to it: MapLibre leaves out a layer it cannot read (a filter it
     * rejects) with only a log line, so a rule that was right in the JSON could still be missing on the map.
     */
    fun loadedProblems(style: JsonObject, loadedIds: List<String>, hiddenIds: Set<String>): List<String> {
        val expected = (style["layers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("id") }
        val problems = mutableListOf<String>()
        val missing = expected - loadedIds.toSet()
        if (missing.isNotEmpty()) problems += "the map left out ${missing.take(5).joinToString()}"
        if (missing.isEmpty() && loadedIds.filter { it in expected } != expected) {
            problems += "the map has the layers in another order"
        }
        if (DISPUTED_LAYER in loadedIds && DISPUTED_LAYER !in hiddenIds) problems += "the map shows $DISPUTED_LAYER"
        return problems
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Whether [rule] is this element or any element inside it. */
    private fun JsonElement.contains(rule: JsonElement): Boolean = when {
        this == rule -> true
        this is JsonArray -> any { it.contains(rule) }
        else -> false
    }

    /** A `["!", ["within", <Polygon>]]` anywhere in [filter]. */
    private fun hasHeldAreasRule(filter: JsonElement): Boolean = when (filter) {
        is JsonArray -> {
            val within = (filter.getOrNull(1) as? JsonArray)
            val isRule = (filter.getOrNull(0) as? JsonPrimitive)?.contentOrNull == "!" && within != null &&
                (within.getOrNull(0) as? JsonPrimitive)?.contentOrNull == "within" &&
                ((within.getOrNull(1) as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "Polygon"
            isRule || filter.any(::hasHeldAreasRule)
        }
        else -> false
    }
}
