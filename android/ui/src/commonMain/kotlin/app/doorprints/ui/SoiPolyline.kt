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

package app.doorprints.ui

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Survey of India's lines (`geo/in-boundaries-soi.json`, OVSF/1M/7, S4b-BL-99; docs/03 ADR-22): India's
 * international land boundary along Jammu and Kashmir, Ladakh, Himachal Pradesh, Uttarakhand, Sikkim and Arunachal
 * Pradesh (kind `claim`) and the Assam-Arunachal Pradesh state line (kind `state`), each run an encoded polyline at
 * 1e-7 degree written by web/scripts/geo/build_in_boundaries_soi.py. Decoded here for the map, with every vertex as
 * the Survey of India published it: none added, moved or removed (MapLibre generalises for the screen only, as it
 * cuts the lines into tiles). Common code with no JVM-only call, so Android and iOS decode the same way; the web's
 * `decodePolyline7` and `soiBoundaryGeoJson` (india-boundaries.ts) are the twin, and the parity test
 * (IndiaBoundaryDataTest) pins both to the same counts and checksums.
 */
object SoiPolyline {
    /** One run: its kind (`claim` or `state`), its state's name, and its vertices as 1e-7 degree integers. */
    class Run(val kind: String, val state: String, val e7: LongArray) {
        /** The number of vertices. */
        val size: Int get() = e7.size / 2

        /** Vertex [i]'s longitude in 1e-7 degree. */
        fun lonE7(i: Int): Long = e7[2 * i]

        /** Vertex [i]'s latitude in 1e-7 degree. */
        fun latE7(i: Int): Long = e7[2 * i + 1]
    }

    /**
     * Google's encoded polyline algorithm at 1e-7 degree: latitude then longitude, each a zigzag-encoded delta in 5-bit
     * chunks offset by 63. Gives longitude, latitude, longitude, ... as 1e-7 degree integers. Longs throughout: a
     * longitude delta can reach 3.6e9. Throws [IllegalArgumentException] on a character outside the alphabet or a
     * truncated value.
     */
    fun decode(encoded: String): LongArray {
        val out = ArrayList<Long>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(i < encoded.length) { "polyline7: truncated value" }
                val b = encoded[i++].code - 63
                require(b in 0..63) { "polyline7: character out of range at ${i - 1}" }
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
                require(shift <= 45) { "polyline7: value too long" }
            }
            return if (result and 1L == 1L) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            lat += next()
            lon += next()
            out += lon
            out += lat
        }
        return out.toLongArray()
    }

    /**
     * The runs of the file's [text], or null when it is not that file: not JSON, a run of another kind, a run whose
     * decoded vertex count differs from the `vertices` it states, or one of fewer than two. So a broken or partial
     * file never reaches the map.
     */
    fun runs(text: String): List<Run>? {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null
        if ((root["type"] as? JsonPrimitive)?.contentOrNull != "FeatureCollection") return null
        val features = root["features"] as? JsonArray ?: return null
        if (features.isEmpty()) return null
        return features.map { f ->
            val p = (f as? JsonObject)?.get("properties") as? JsonObject ?: return null
            val kind = (p["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val encoded = (p["polyline7"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if ((kind != "claim" && kind != "state") || encoded == null) return null
            val e7 = try {
                decode(encoded)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val run = Run(kind, (p["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "", e7)
            if (run.size < 2 || run.size != (p["vertices"] as? JsonPrimitive)?.intOrNull) return null
            run
        }
    }

    /**
     * The runs as the map's GeoJSON text: a FeatureCollection with one LineString per run and its `kind` and `state`.
     * Each coordinate is written from its integer as the 7-decimal number the build wrote ([decimal]), so MapLibre
     * reads exactly the Survey of India's value on every platform (no double-to-text conversion that could differ).
     */
    fun geoJson(runs: List<Run>): String = buildString(runs.sumOf { it.size } * 24 + 256) {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        runs.forEachIndexed { r, run ->
            if (r > 0) append(',')
            append("{\"type\":\"Feature\",\"properties\":{\"kind\":")
            append(jsonString(run.kind)).append(",\"state\":").append(jsonString(run.state))
            append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for (i in 0 until run.size) {
                if (i > 0) append(',')
                append('[').append(decimal(run.lonE7(i))).append(',').append(decimal(run.latE7(i))).append(']')
            }
            append("]}}")
        }
        append("]}")
    }

    /** The file's [text] as the map's GeoJSON ([runs], [geoJson]), or null when it is not that file. */
    fun geoJson(text: String): String? = runs(text)?.let(::geoJson)

    /**
     * Adler-style sums (mod 2^31 - 1) over the vertices of [runs] as 1e-7 degree integers, longitude then latitude, in
     * order: (s1, s2). The parity check of the decoders: web `soiChecksum` and web/scripts/geo/test_build_in_boundaries.py
     * compute the same.
     */
    fun checksum(runs: List<Run>): Pair<Long, Long> {
        val m = 2147483647L
        var s1 = 0L
        var s2 = 0L
        runs.forEach { run ->
            run.e7.forEach { v ->
                s1 = (s1 + ((v % m) + m) % m) % m
                s2 = (s2 + s1) % m
            }
        }
        return s1 to s2
    }

    /** A 1e-7 degree integer as a decimal with at most 7 decimals and no trailing zero (753322322 -> "75.3322322"). */
    fun decimal(e7: Long): String {
        val sign = if (e7 < 0) "-" else ""
        val abs = if (e7 < 0) -e7 else e7
        val whole = abs / 10_000_000L
        val fraction = (abs % 10_000_000L).toString().padStart(7, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
    }

    private fun jsonString(s: String): String = JsonPrimitive(s).toString()
}
