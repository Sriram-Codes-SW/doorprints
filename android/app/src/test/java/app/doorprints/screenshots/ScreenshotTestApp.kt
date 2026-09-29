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
