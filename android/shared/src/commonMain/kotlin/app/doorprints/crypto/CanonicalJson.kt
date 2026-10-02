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

package app.doorprints.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one JSON form the crypto formats write (the `dpx/1` header, `keys.json`), identical in the TypeScript twin
 * (`canonical-json.ts`): no whitespace, keys in the order the format lists them, integers in plain decimal, strings
 * with only `"`, `\` and the control characters escaped (`\b \f \n \r \t` short, the rest `\u00xx` in lowercase hex)
 * and everything else as UTF-8. A reader parses, maps to the model, writes it again and requires the very same bytes,
 * so whitespace, duplicate or unknown keys, another key order, `1.0` for `1` or another escape are all refused, and
 * the bytes a MAC or an AEAD covers mean one thing on both stacks.
 */
internal class CanonicalJson {
    private val sb = StringBuilder()

    fun raw(text: String) = apply { sb.append(text) }

    fun string(value: String) = apply {
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    fun number(value: Long) = apply { sb.append(value.toString()) }

    override fun toString() = sb.toString()

    fun bytes(): ByteArray = sb.toString().encodeToByteArray()

    companion object {
        private const val HEX = "0123456789abcdef"

        /** The largest integer both stacks read exactly (2⁵³ − 1). */
        const val MAX_SAFE = 9007199254740991L

        /** Parses [bytes] as one JSON value, or null if it is not JSON or not valid UTF-8. */
        fun parse(bytes: ByteArray): JsonElement? {
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return null
            }
            return try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Strict readers over a parsed value; each returns null when the value is not exactly of that kind. */
internal object JsonRead {
    fun obj(e: JsonElement?, vararg keys: String): JsonObject? {
        if (e !is JsonObject) return null
        if (e.keys != keys.toSet()) return null
        return e
    }

    fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * A JSON number whose value is an integer in [min, max], read as JavaScript reads it (so `1.0` is 1 here as on
     * the website; the canonical re-write then refuses that spelling on both stacks alike).
     */
    fun long(e: JsonElement?, min: Long, max: Long): Long? {
        val prim = e as? JsonPrimitive ?: return null
        if (prim.isString || prim is JsonNull) return null
        val text = prim.content
        if (!NUMBER.matches(text)) return null
        val d = text.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite() || d != kotlin.math.floor(d)) return null
        if (d < -CanonicalJson.MAX_SAFE.toDouble() || d > CanonicalJson.MAX_SAFE.toDouble()) return null
        val v = d.toLong()
        return v.takeIf { it in min..max }
    }

    fun array(e: JsonElement?): JsonArray? = e as? JsonArray

    fun isNull(e: JsonElement?): Boolean = e is JsonNull

    /** JSON's number grammar (RFC 8259 §6); `true`, `null` and the rest are not numbers. */
    private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")
}
