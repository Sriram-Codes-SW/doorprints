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

/** A worker reports a backup problem by name; anything unrecognised is treated as "not a backup". */
internal fun backupProblemOf(name: String?): BackupProblem =
    BackupProblem.entries.firstOrNull { it.name == name } ?: BackupProblem.NOT_A_BACKUP
