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
 * Free of Android types on purpose, so `ExportProblemTest` runs as a plain JVM test.
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

        /**
         * Classifies a failure by walking its cause chain.
         *
         * Out-of-space is recognised by the `ENOSPC` errno name, which Android's `ErrnoException` puts in its
         * message and `IoBridge` copies into the `IOException` it rethrows — so the check works at every level of
         * the chain without touching `android.system` classes. "Cannot write" is a `FileNotFoundException` (what
         * `ContentResolver.openOutputStream` throws for a document that is gone or read-only, and what
         * [Saf.openOutput] throws when a provider hands back no stream) or a `SecurityException` (a revoked grant).
         */
        fun of(error: Throwable): ExportProblem {
            val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
            if (chain.any { it.message?.contains("ENOSPC") == true }) return NO_SPACE
            if (chain.any { it is java.io.FileNotFoundException || it is SecurityException }) return CANNOT_WRITE
            return UNKNOWN
        }

        private const val MAX_CAUSES = 8
    }
}
