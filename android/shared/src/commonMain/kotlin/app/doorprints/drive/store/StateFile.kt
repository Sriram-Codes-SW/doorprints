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

package app.doorprints.drive.store

import app.doorprints.concurrent.PlatformLock
import kotlinx.io.IOException

/**
 * One small file of per-device state (docs/15 §9.10): written whole to a temporary file and moved over the old one, so a
 * crash leaves the old text or the new, never half. Android: `AtomicJsonFile` over `java.io.File` (fsync, atomic move);
 * the iPhone: `IosStateFile` (`NSData` written atomically, in the app's no-backup folder). Everything above it (the stores,
 * the preferences, the lock memory) is common code.
 */
interface StateFile {
    /** The text, or null when the file is missing, blank or cannot be read (never throws). */
    fun readText(): String?

    /** Replaces the file's text atomically, making its folder first. */
    @Throws(IOException::class)
    fun writeText(text: String)

    /** True when the file is there. */
    fun exists(): Boolean

    /** A cheap stamp of the content on disk (modified time, length), or null when absent; an unchanged stamp means unchanged text. */
    fun stamp(): Pair<Long, Long>?

    /** Removes the file; no error when it is already gone. */
    @Throws(IOException::class)
    fun delete()

    /** The lock every read-modify-write of this file takes ([PathLocks.of]: one per path in the process). */
    val lock: PlatformLock
}

/** One lock per path, so two store objects over the same file exclude each other. */
object PathLocks {
    private val gate = PlatformLock()
    private val locks = HashMap<String, PlatformLock>()

    /** The lock for [path]; the same object for every call with the same path. */
    fun of(path: String): PlatformLock = gate.withLock { locks.getOrPut(path.trimEnd('/')) { PlatformLock() } }
}

/** The platform's [StateFile] at [path] (Android: `AtomicJsonFile`; the iPhone: `IosStateFile`). */
expect fun stateFileAt(path: String): StateFile
