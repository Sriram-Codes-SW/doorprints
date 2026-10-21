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

package app.doorprints.shared.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * A strict look at a JSON text before it is parsed (docs/03 ADR-39, docs/02 T-T23), the twin of the website's
 * `core/user-kinds/json-scan.ts`: one document and nothing after it, no key twice in one object (a parser would keep one of
 * the two, and another reader might keep the other), at most [maxDepth] arrays and objects inside one another, at most
 * [maxValues] values and no string longer than [maxString] characters (UTF-16 units, as the website counts). It walks the
 * text once, keeps only the keys of the objects it is inside, and stops at the first fault, so a hostile file (deep nesting,
 * a huge list, a long string) costs at most one pass over text that is already capped in size. The answers are the
 * website's and the server's (`docs/schemas/kinds/user-kind-vectors.json`).
 */
internal class JsonScan(private val text: String, private val maxDepth: Int, private val maxValues: Int, private val maxString: Int) {
    /** A fault: its code (`notJson`, `duplicateKey`, `tooDeep`, `tooMany`, `tooLong`) and where (`$`, `.key`, `[n]`). */
    data class Fault(val code: String, val path: String)

    private class Stop(val fault: Fault) : Exception()

    private var i = 0
    private var values = 0

    /** The first fault of the text, or null when it is one well-formed document within the limits. */
    fun firstFault(): Fault? = try {
        value("$", 0)
        space()
        if (i == text.length) null else Fault(NOT_JSON, "$")
    } catch (e: Stop) {
        e.fault
    } catch (_: Exception) {
        Fault(NOT_JSON, "$")
    }

    // A syntax fault is the document's (`$`): where a broken text breaks is no help to the person, and the readers agree on `$`.
    private fun fail(code: String, path: String): Nothing = throw Stop(Fault(code, if (code == NOT_JSON) "$" else path))

    private fun space() {
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    private fun string(path: String): String {
        val start = i
        i++
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                i++
                // The escapes are checked, and the text decoded, by the platform's own parser of this one token.
                val s = (Json.parseToJsonElement(text.substring(start, i)) as JsonPrimitive).content
                if (s.length > maxString) fail(TOO_LONG, path)
                return s
            }
            if (c == '\\') i += 2 else if (c.code < 0x20) fail(NOT_JSON, path) else i++
        }
        fail(NOT_JSON, path)
    }

    private fun value(path: String, depth: Int) {
        values++
        if (values > maxValues) fail(TOO_MANY, path)
        space()
        val c = text.getOrNull(i)
        if (c == '{' || c == '[') {
            if (depth >= maxDepth) fail(TOO_DEEP, path)
            i++
            space()
            val close = if (c == '{') '}' else ']'
            if (text.getOrNull(i) == close) {
                i++
                return
            }
            val seen = if (c == '{') HashSet<String>() else null
            var n = 0
            while (true) {
                var item = "$path[$n]"
                if (seen != null) {
                    space()
                    if (text.getOrNull(i) != '"') fail(NOT_JSON, path)
                    val key = string(path)
                    item = "$path.$key"
                    if (!seen.add(key)) fail(DUPLICATE_KEY, item)
                    space()
                    if (text.getOrNull(i) != ':') fail(NOT_JSON, item)
                    i++
                }
                value(item, depth + 1)
                space()
                when (text.getOrNull(i)) {
                    ',' -> {
                        i++
                        n++
                    }
                    close -> {
                        i++
                        return
                    }
                    else -> fail(NOT_JSON, item)
                }
            }
        }
        if (c == '"') {
            string(path)
            return
        }
        for (word in WORDS) {
            if (text.startsWith(word, i)) {
                i += word.length
                return
            }
        }
        if (!number()) fail(NOT_JSON, path)
    }

    /** A JSON number at [i] (`-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`), consumed; false when there is none. */
    private fun number(): Boolean {
        var j = i
        if (text.getOrNull(j) == '-') j++
        when (text.getOrNull(j)) {
            '0' -> j++
            in '1'..'9' -> while (text.getOrNull(j)?.isAsciiDigit() == true) j++
            else -> return false
        }
        if (text.getOrNull(j) == '.' && text.getOrNull(j + 1)?.isAsciiDigit() == true) {
            j += 2
            while (text.getOrNull(j)?.isAsciiDigit() == true) j++
        }
        val e = text.getOrNull(j)
        if (e == 'e' || e == 'E') {
            var k = j + 1
            if (text.getOrNull(k) == '+' || text.getOrNull(k) == '-') k++
            if (text.getOrNull(k)?.isAsciiDigit() == true) {
                while (text.getOrNull(k)?.isAsciiDigit() == true) k++
                j = k
            }
        }
        i = j
        return true
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    companion object {
        const val NOT_JSON = "notJson"
        const val DUPLICATE_KEY = "duplicateKey"
        const val TOO_DEEP = "tooDeep"
        const val TOO_MANY = "tooMany"
        const val TOO_LONG = "tooLong"
        private val WORDS = listOf("true", "false", "null")
    }
}
