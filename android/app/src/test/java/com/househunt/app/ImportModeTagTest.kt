package com.househunt.app

import androidx.work.WorkInfo
import com.househunt.app.export.ImportWorker
import com.househunt.shared.export.ImportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * A stopped or failed import must say the right thing for its mode (UX review, 2026-09-22): "import the same file
 * again to finish" is safe only for a merge, because a copy gets new ids on every run and would add everything a
 * second time. A cancelled run has no output data, so the mode travels as a tag on the work request.
 */
class ImportModeTagTest {

    private fun run(state: WorkInfo.State, vararg tags: String) = WorkInfo(UUID.randomUUID(), state, tags.toSet())

    @Test
    fun theModeIsReadBackFromTheRunsTags() {
        for (mode in ImportMode.entries) {
            val info = run(WorkInfo.State.CANCELLED, "com.househunt.app.export.ImportWorker", ImportWorker.modeTag(mode))
            assertEquals(mode, ImportWorker.modeOf(info))
        }
    }

    @Test
    fun aRunWithoutTheTagHasNoKnownMode() {
        assertNull(ImportWorker.modeOf(run(WorkInfo.State.FAILED, "com.househunt.app.export.ImportWorker")))
        assertNull(ImportWorker.modeOf(run(WorkInfo.State.FAILED, "import-mode:SOMETHING_ELSE")))
    }

    @Test
    fun onlyACopySaysNothingWasAdded() {
        assertEquals(R.string.import_stopped_copy, ImportWorker.stoppedRes(ImportMode.COPY))
        assertEquals(R.string.import_write_failed_copy, ImportWorker.writeFailedRes(ImportMode.COPY))
        // Merge, and a run of unknown mode, keep "import the same file again to finish".
        assertEquals(R.string.import_stopped, ImportWorker.stoppedRes(ImportMode.MERGE))
        assertEquals(R.string.import_write_failed, ImportWorker.writeFailedRes(ImportMode.MERGE))
        assertEquals(R.string.import_stopped, ImportWorker.stoppedRes(null))
        assertEquals(R.string.import_write_failed, ImportWorker.writeFailedRes(null))
    }
}
