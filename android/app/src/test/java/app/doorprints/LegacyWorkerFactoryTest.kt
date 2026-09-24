package app.doorprints

import androidx.work.ListenableWorker
import app.doorprints.data.SyncWorker
import app.doorprints.export.AutoBackupWorker
import app.doorprints.export.ExportWorker
import app.doorprints.export.ImportWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jobs WorkManager stored under the pre-rename class names still find their worker ([LegacyWorkerFactory]). */
class LegacyWorkerFactoryTest {
    private val workers =
        listOf(SyncWorker::class.java, AutoBackupWorker::class.java, ExportWorker::class.java, ImportWorker::class.java)

    @Test
    fun everyWorkerStoredUnderTheOldPackageMapsToItsClass() {
        for (worker in workers) {
            val stored = "com.househunt.app." + worker.name.removePrefix("app.doorprints.")
            val name = LegacyWorkerFactory.currentName(stored)
            assertEquals(worker.name, name)
            assertTrue(ListenableWorker::class.java.isAssignableFrom(Class.forName(name!!)))
        }
    }

    @Test
    fun otherNamesAreLeftToTheDefaultFactory() {
        assertNull(LegacyWorkerFactory.currentName(SyncWorker::class.java.name))
        assertNull(LegacyWorkerFactory.currentName("androidx.work.impl.workers.ConstraintTrackingWorker"))
    }
}
