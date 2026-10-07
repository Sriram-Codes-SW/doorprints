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

package app.doorprints.shared.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Gemini-dialect schema to strict JSON Schema (docs/03 §13.2, ADR-35; vectors section `schemaDialect`). For every node
 * `nullable` goes and, where it was true, `type` becomes `[T, "null"]`; `description` and `items` stay; every object
 * gets `additionalProperties: false` and `required` listing **all** its properties (strict mode wants that), both after
 * the input's own keys, which keep their order.
 */
object SchemaDialect {
    fun strict(node: JsonObject): JsonObject = buildJsonObject {
        val nullable = (node["nullable"] as? JsonPrimitive)?.booleanOrNull == true
        for ((key, value) in node) {
            when (key) {
                "nullable", "required" -> Unit
                "type" -> put(key, if (nullable) nullableType(value) else value)
                "properties" -> put(key, JsonObject((value as? JsonObject).orEmpty().mapValues { (_, v) -> child(v) }))
                "items" -> put(key, child(value))
                else -> put(key, value)
            }
        }
        if ((node["type"] as? JsonPrimitive)?.contentOrNull == "object") {
            val names = (node["properties"] as? JsonObject)?.keys.orEmpty()
            putJsonArray("required") { names.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
        }
    }

    private fun child(value: JsonElement): JsonElement = if (value is JsonObject) strict(value) else value

    private fun nullableType(type: JsonElement): JsonElement =
        if (type is JsonPrimitive) JsonArray(listOf(type, JsonPrimitive("null"))) else type

    /** The name the provider is given for the schema (`json_schema.name`): the call it belongs to. */
    fun nameOf(schema: JsonObject): String {
        val properties = (schema["properties"] as? JsonObject)?.keys.orEmpty()
        return when {
            "stops" in properties -> "plan"
            "answer" in properties -> "answer"
            "label" in properties -> "listing"
            else -> "response"
        }
    }
}
