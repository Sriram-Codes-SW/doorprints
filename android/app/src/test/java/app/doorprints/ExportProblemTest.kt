package app.doorprints

import app.doorprints.export.ExportProblem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Export and automatic-backup failures reach the screen as a stable code, never as exception text (which is
 * English, may contain a `content://` URI, and was shown to Hindi, Tamil and Telugu users as-is).
 */
class ExportProblemTest {

    @Test
    fun outOfSpaceIsRecognisedAnywhereInTheCauseChain() {
        // What IoBridge throws on a full disk: an IOException carrying the errno name, caused by ErrnoException.
        val direct = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(ExportProblem.NO_SPACE, ExportProblem.of(direct))
        val wrapped = IllegalStateException("zip", IOException("x", IOException("write failed: ENOSPC")))
        assertEquals(ExportProblem.NO_SPACE, ExportProblem.of(wrapped))
    }

    @Test
    fun anUnwritableDestinationIsCannotWrite() {
        assertEquals(ExportProblem.CANNOT_WRITE, ExportProblem.of(FileNotFoundException("gone")))
        assertEquals(ExportProblem.CANNOT_WRITE, ExportProblem.of(SecurityException("grant revoked")))
        assertEquals(ExportProblem.CANNOT_WRITE, ExportProblem.of(IOException(FileNotFoundException())))
    }

    @Test
    fun anythingElseIsUnknown() {
        assertEquals(ExportProblem.UNKNOWN, ExportProblem.of(IOException("broken pipe")))
        assertEquals(ExportProblem.UNKNOWN, ExportProblem.of(IllegalStateException()))
    }

    @Test
    fun codesRoundTripAndAnOldEnglishMessageReadsAsUnknown() {
        for (problem in ExportProblem.entries) assertEquals(problem, ExportProblem.fromCode(problem.code))
        // Builds before this change stored e.message in Settings; it must not be shown, or crash anything.
        assertEquals(ExportProblem.UNKNOWN, ExportProblem.fromCode("Cannot write to the file you picked"))
        assertEquals(ExportProblem.UNKNOWN, ExportProblem.fromCode(null))
        // The stored codes are persisted: changing one would misread every saved result.
        assertEquals(listOf("no-space", "cannot-write", "write-failed"), ExportProblem.entries.map { it.code })
    }
}
