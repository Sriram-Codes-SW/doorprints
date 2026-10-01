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

import app.doorprints.R
import app.doorprints.shared.export.BackupProblem

/**
 * The translated reason for each failure code, as Android resources for the completion notifications (a stable code in
 * the worker's output, never the exception text). The screens read the same texts as Compose resources
 * (`messageResource` in `:ui`, CMP-6 P6b, S4b-BL-35); `StringParityTest` keeps the two copies equal.
 */
internal fun ExportProblem.messageRes(): Int = when (this) {
    ExportProblem.NO_SPACE -> R.string.export_problem_no_space
    ExportProblem.CANNOT_WRITE -> R.string.export_problem_cannot_write
    ExportProblem.UNKNOWN -> R.string.export_problem_unknown
}

internal fun BackupProblem.messageRes(): Int = when (this) {
    BackupProblem.NOT_A_BACKUP -> R.string.problem_not_a_backup
    BackupProblem.UNSUPPORTED_VERSION -> R.string.problem_unsupported
    BackupProblem.TOO_MANY_ENTRIES -> R.string.problem_too_many
    BackupProblem.TOO_LARGE -> R.string.problem_too_large
    BackupProblem.SUSPICIOUS_PATH -> R.string.problem_suspicious
    BackupProblem.CHECKSUM_MISMATCH -> R.string.problem_checksum
    BackupProblem.BROKEN_DATA -> R.string.problem_broken
    BackupProblem.READ_FAILED -> R.string.problem_read_failed
    BackupProblem.WRITE_FAILED -> R.string.problem_write_failed
}
