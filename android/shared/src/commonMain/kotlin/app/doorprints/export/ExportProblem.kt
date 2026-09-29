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
 * Why an export or an automatic backup could not be written, as a stable code rather than an exception message.
 *
 * The worker's output (and the automatic backup's last result in Settings) used to carry `e.message`, which put
 * raw English JVM text — "write failed: ENOSPC (No space left on device)", sometimes with a `content://` URI in
 * it — in front of Hindi, Tamil and Telugu users. The code travels instead, the screen maps it to a translated
 * reason, and the exception itself goes to logcat. Same approach as the import side's `BackupProblem`.
 *
 * [code] is what is stored: it is persisted in Settings, so it must never change once shipped. An unrecognised
 * code — including an English message saved by a build older than this class — reads as [UNKNOWN].
 *
 * Common since ADR-23 CMP-6 P6b, so the Export and Settings screens in `:ui` can name the reason
 * (`messageResource`); the platform classifies a failure (`:app`'s `ExportProblem.of`, which reads JVM exception
 * types). `ExportProblemTest` pins both.
 */
enum class ExportProblem(val code: String) {
    /** The destination ran out of space. */
    NO_SPACE("no-space"),

    /** The document or folder the user chose cannot be opened for writing (gone, read-only, grant revoked). */
    CANNOT_WRITE("cannot-write"),

    /** Anything else. */
    UNKNOWN("write-failed"),
    ;

    companion object {
        fun fromCode(code: String?): ExportProblem = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
