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

package app.doorprints

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Runs WorkManager jobs that were queued before the package rename of 2026-09-24.
 *
 * WorkManager stores each job with its worker's class name. Until that date the classes lived in
 * [LEGACY_PACKAGE] (`com.househunt.app.data.SyncWorker`, `com.househunt.app.export.AutoBackupWorker`, …), so a job
 * queued by an older build of this app, above all the periodic sync kept with `ExistingPeriodicWorkPolicy.KEEP`,
 * would otherwise fail on every run because the class no longer exists. This factory maps such a name to the same
 * class in [CURRENT_PACKAGE]; every other name returns null, so WorkManager's default factory creates it as before.
 * [LEGACY_PACKAGE] is a stored identifier and is kept on purpose.
 */
object LegacyWorkerFactory : WorkerFactory() {
    const val LEGACY_PACKAGE = "com.househunt.app."
    const val CURRENT_PACKAGE = "app.doorprints."

    /** The current class name for a worker class name stored by an older build, or null if it is not one. */
    fun currentName(storedName: String): String? =
        if (storedName.startsWith(LEGACY_PACKAGE)) CURRENT_PACKAGE + storedName.removePrefix(LEGACY_PACKAGE) else null

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val name = currentName(workerClassName) ?: return null
        val type = runCatching { Class.forName(name).asSubclass(ListenableWorker::class.java) }.getOrNull()
            ?: return null
        return type.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
            .newInstance(appContext, workerParameters)
    }
}
