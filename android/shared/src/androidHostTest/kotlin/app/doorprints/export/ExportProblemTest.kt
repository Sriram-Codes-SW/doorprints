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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Export and automatic-backup failures reach the screen as a stable code, never as exception text (which is
 * English, may contain a `content://` URI, and was shown to Hindi, Tamil and Telugu users as-is). In `:shared` since
 * S4b-BL-106, with the classifier.
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
