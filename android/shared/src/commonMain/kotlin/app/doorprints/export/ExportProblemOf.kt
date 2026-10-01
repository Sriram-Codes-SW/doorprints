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
 * Classifies a failure by walking its cause chain (common since S4b-BL-106; was `:app`'s `ExportProblemOf.kt`).
 *
 * Out-of-space is recognised by the `ENOSPC` errno name anywhere in the chain's messages: Android's `ErrnoException`
 * puts it in its message and `IoBridge` copies it into the `IOException` it rethrows, so the check works at every
 * level without touching `android.system` classes. Whether a cause means "cannot write" depends on the platform's
 * exception types, so the caller names them in [cannotWrite]; Android's one-argument [of] (androidMain) passes
 * `FileNotFoundException` and `SecurityException`.
 */
fun ExportProblem.Companion.of(error: Throwable, cannotWrite: (Throwable) -> Boolean): ExportProblem {
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
    if (chain.any { it.message?.contains("ENOSPC") == true }) return ExportProblem.NO_SPACE
    if (chain.any(cannotWrite)) return ExportProblem.CANNOT_WRITE
    return ExportProblem.UNKNOWN
}

private const val MAX_CAUSES = 8
