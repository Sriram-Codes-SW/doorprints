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
