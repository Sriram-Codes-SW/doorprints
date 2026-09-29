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

package app.doorprints.export

/**
 * Classifies a failure by walking its cause chain ([ExportProblem] is common since ADR-23 CMP-6 P6b; this reads JVM
 * exception types, so it stays in `:app`).
 *
 * Out-of-space is recognised by the `ENOSPC` errno name, which Android's `ErrnoException` puts in its message and
 * `IoBridge` copies into the `IOException` it rethrows — so the check works at every level of the chain without
 * touching `android.system` classes. "Cannot write" is a `FileNotFoundException` (what
 * `ContentResolver.openOutputStream` throws for a document that is gone or read-only, and what [Saf.openOutput] throws
 * when a provider hands back no stream) or a `SecurityException` (a revoked grant).
 */
fun ExportProblem.Companion.of(error: Throwable): ExportProblem {
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
    if (chain.any { it.message?.contains("ENOSPC") == true }) return ExportProblem.NO_SPACE
    if (chain.any { it is java.io.FileNotFoundException || it is SecurityException }) return ExportProblem.CANNOT_WRITE
    return ExportProblem.UNKNOWN
}

private const val MAX_CAUSES = 8
