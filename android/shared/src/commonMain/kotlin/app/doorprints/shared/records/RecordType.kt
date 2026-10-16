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

package app.doorprints.shared.records

import app.doorprints.data.RecordEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * One kind of record in the `records` table (docs/11 5.30 item 2, ADR-28): its [name], the column the rows are
 * filed under and the `type` on the wire, and the [serializer] of the payload class. A new kind of data is one
 * `@Serializable` class and one of these; no table, DAO or migration. Each kind is declared beside its payload class
 * (for example `BrokerType` in `Broker.kt`).
 */
class RecordType<T>(val name: String, val serializer: KSerializer<T>) {
    init {
        require(RecordRules.isValidType(name)) { "record type name '$name' is not [a-z][a-zA-Z0-9]{0,39}" }
    }

    /** [value] as the JSON object text a row stores and the wire carries. */
    fun encode(value: T): String = RecordRules.json.encodeToString(serializer, value)

    override fun toString(): String = "RecordType($name)"
}

/** The rules every record obeys on every stack (the server and the web check the same ones). */
object RecordRules {
    /** The payload's size in UTF-8 bytes, as the server caps it. */
    const val MAX_PAYLOAD_BYTES = 65_536

    /** Live rows per type, as the server caps them. */
    const val MAX_ROWS_PER_TYPE = 5_000

    /**
     * The payload's JSON: unknown keys pass (a row written by a newer app keeps what this one does not know until
     * it is next edited here), defaults written out, nulls left out, as the backup's `BackupFormat.json`.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val TYPE_PATTERN = Regex("[a-z][a-zA-Z0-9]{0,39}")
    private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    /** A type name goes into a URL path: a short lower-camel word. */
    fun isValidType(type: String): Boolean = TYPE_PATTERN.matches(type)

    /** An id goes into a URL path too: UUIDs and the like, nothing that is a path (`.` and `..` are segments). */
    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id) && id != "." && id != ".."

    /** Whether [payload] fits the server's cap; the size is counted in bytes, as the server counts it. */
    fun fitsPayload(payload: String): Boolean = payload.encodeToByteArray().size <= MAX_PAYLOAD_BYTES
}

/** A type's live rows are full: the caller shows [type] and [max] rather than the message. */
class RecordLimitException(val type: String, val max: Int) :
    IllegalStateException("$type already has $max records")

/**
 * The row's payload as a [type] value, or null when it does not parse: a row from a newer app, or a hand-edited
 * one, is skipped rather than crashing the screen that lists the type. Never throws.
 */
fun <T> RecordEntity.decode(type: RecordType<T>): T? =
    runCatching { RecordRules.json.decodeFromString(type.serializer, payload) }.getOrNull()
