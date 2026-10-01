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
 * [ExportProblem.Companion.of] for Android's export, import and automatic-backup workers (S4b-BL-106; was `:app`'s
 * `ExportProblemOf.kt`): "cannot write" is a `FileNotFoundException` (what `ContentResolver.openOutputStream` throws
 * for a document that is gone or read-only, and what `Saf.openOutput` throws when a provider hands back no stream) or
 * a `SecurityException` (a revoked grant). JVM exception types, so this half stays out of common code.
 */
fun ExportProblem.Companion.of(error: Throwable): ExportProblem =
    of(error) { it is java.io.FileNotFoundException || it is SecurityException }
