package app.doorprints.screenshots

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.DoorprintsApp

/**
 * The real app with its data container, but without the platform services that need native code or a device
 * (MapLibre, notification channels, scheduled work). Settings and Export observe WorkManager, so a test WorkManager
 * is installed instead.
 */
class ScreenshotTestApp : DoorprintsApp() {
    override fun startServices() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }
}
