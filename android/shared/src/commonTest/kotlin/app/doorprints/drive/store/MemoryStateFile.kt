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

/** A [StateFile] in memory, for the common tests of the stores (the real ones are `AtomicJsonFile` and `IosStateFile`). */
class MemoryStateFile(val path: String = "memory/state.json") : StateFile {
    var text: String? = null
    var failWrites = false
    var writes = 0
    private var version = 0L

    override val lock: PlatformLock get() = PathLocks.of(path)

    override fun readText(): String? = text?.takeIf { it.isNotBlank() }

    override fun exists(): Boolean = text != null

    override fun stamp(): Pair<Long, Long>? = text?.let { version to it.length.toLong() }

    override fun delete() {
        if (failWrites) throw IOException("cannot remove")
        text = null
        version++
    }

    override fun writeText(text: String) {
        if (failWrites) throw IOException("disk full")
        this.text = text
        writes++
        version++
    }
}
