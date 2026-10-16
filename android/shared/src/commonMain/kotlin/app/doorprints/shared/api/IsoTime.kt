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

package app.doorprints.shared.api

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Epoch milliseconds <-> the API's ISO-8601 instants, replacing java.time.Instant (JVM-only).
 *
 * Uses kotlin.time.Instant from the Kotlin standard library (stable since Kotlin 2.3), so no kotlinx-datetime
 * dependency is needed. Output format matches java.time.Instant.toString() for millisecond values: UTC with a "Z",
 * no fraction for whole seconds, otherwise 3 digits ("2026-09-22T10:15:30.120Z"). Parsing accepts everything the
 * backend sends (fractions of any length, "Z" or an offset); sub-millisecond digits are truncated like
 * Instant.toEpochMilli() did.
 */
object IsoTime {
    /** The ISO-8601 UTC text of [epochMillis]. */
    fun format(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

    /** @throws IllegalArgumentException when [iso] is not an ISO-8601 instant. */
    fun parseMillis(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

    /** The current time in epoch milliseconds. */
    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
