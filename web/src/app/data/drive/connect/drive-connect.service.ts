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

/**
 * Orchestrates Google Drive connection, backup, import, sync, photos, and deletion for the Connect page.
 * Depends on: DriveBackupService, DriveImportService, DriveSyncEngine, DrivePhotos, DriveDeletionService.
 * Called from the drive-connect component.
 *
 * Notes:
 * - Device must be pinned before opening anything from Drive (S4b-BL-126).
 * - Recovery key is written ONCE at first connect and shown ONCE to the user.
 * - Recovery key is confirmed by read-back before it is trusted.
 * - All i18n strings are in web/src/app/i18n/*.ts under `driveConnect.*`.
 */
export class DriveConnectService {
  constructor() {
    // To be injected with: DriveBackupService, DriveImportService, DriveSyncEngine, DrivePhotos, DriveDeletionService
    // For now, a placeholder for the component to compile.
  }
}
