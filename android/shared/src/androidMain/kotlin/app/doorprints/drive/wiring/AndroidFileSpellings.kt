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

package app.doorprints.drive.wiring

import app.doorprints.drive.store.AtomicJsonFile
import java.io.File

/*
 * The Android spellings of the Drive wiring's file constructors (the classes are common code since the iPhone's Drive,
 * over `StateFile` and path strings): the same names over `java.io.File`, so the app and its tests are unchanged.
 */

fun FileDrivePrefs(file: File) = FileDrivePrefs(AtomicJsonFile(file))

fun FileDriveLockStore(file: File) = FileDriveLockStore(AtomicJsonFile(file))

fun FolderPinProbe(rootId: () -> String?, trustDir: File) = FolderPinProbe(rootId, trustDir.path)
