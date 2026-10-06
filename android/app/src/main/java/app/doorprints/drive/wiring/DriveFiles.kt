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

import app.doorprints.drive.connect.DrivePrefs
import app.doorprints.drive.store.AtomicJsonFile
import app.doorprints.drive.store.FileKeysWatermarkStore
import app.doorprints.drive.store.folderFileName
import app.doorprints.drive.device.DriveLockStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The few per-device Drive preferences (automatic backup on or off, photos on mobile data) and whether Drive is in use
 * ([engaged], docs/15 §1.3), in one small file in the device's no-backup folder, so Android's own backup never carries
 * them to another phone. Plain on/off values; nothing secret. A file that is missing or damaged reads as "nothing set"
 * (the controller's defaults), and a write that fails is dropped: a preference is never worth a crash.
 */
class FileDrivePrefs(file: File) : DrivePrefs {
    private val store = AtomicJsonFile(file)
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    private fun read(): Map<String, String> = try {
        store.readText()?.let { json.decodeFromString(serializer, it) } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    @Synchronized
    override fun get(key: String): String? = read()[key]

    /** Called after a value changed (not on a failed write): the wiring reschedules the background work from it. */
    @Volatile
    var onChange: ((key: String, value: String) -> Unit)? = null

    override fun put(key: String, value: String) {
        val written = synchronized(this) {
            try {
                store.writeText(json.encodeToString(serializer, read() + (key to value)))
                true
            } catch (_: IOException) {
                // Dropped on purpose.
                false
            }
        }
        if (written) onChange?.invoke(key, value)
    }

    /** True while Drive is in use on this phone ([DriveEngagement]); false until the folder was open once. */
    var engaged: Boolean
        get() = get(KEY_ENGAGED) == "1"
        set(value) = put(KEY_ENGAGED, if (value) "1" else "0")

    companion object {
        const val KEY_ENGAGED = "doorprints.drive.engaged"
    }
}

/**
 * The device lock's memory of a pause ([DriveLockStore]) in a small file next to the other Drive files, so the Settings
 * notice survives a restart. A damaged file reads as "not paused" (the live lock check still pauses the runs).
 */
class FileDriveLockStore(file: File) : DriveLockStore {
    private val store = AtomicJsonFile(file)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Dto(
        val paused: Boolean = false,
        val needsReenrolment: Boolean = false,
        val keyDropped: Boolean = false,
        val keyStoreFault: Boolean = false,
    )

    @Synchronized
    private fun read(): Dto = try {
        store.readText()?.let { json.decodeFromString(Dto.serializer(), it) } ?: Dto()
    } catch (_: Exception) {
        Dto()
    }

    @Synchronized
    private fun write(dto: Dto) {
        try {
            store.writeText(json.encodeToString(Dto.serializer(), dto))
        } catch (_: IOException) {
            // The pause is also found again by the live check of the lock.
        }
    }

    override var paused: Boolean
        get() = read().paused
        set(value) = write(read().let { Dto(value, it.needsReenrolment, it.keyDropped, it.keyStoreFault) })

    override var needsReenrolment: Boolean
        get() = read().needsReenrolment
        set(value) = write(read().let { Dto(it.paused, value, it.keyDropped, it.keyStoreFault) })

    override var keyDropped: Boolean
        get() = read().keyDropped
        set(value) = write(read().let { Dto(it.paused, it.needsReenrolment, value, it.keyStoreFault) })

    override var keyStoreFault: Boolean
        get() = read().keyStoreFault
        set(value) = write(read().let { Dto(it.paused, it.needsReenrolment, it.keyDropped, value) })
}

/**
 * Whether the folder this device uses (the stored root id) has a pin of its `keys.json`: the answer for
 * `KeystoreDeviceIdentity.folderPinned`. While it is true a lost device key is a state ("connect again"), never a reason
 * to make a new key. A folder that was deleted and forgotten has no root id here, so it no longer pins the key.
 */
class FolderPinProbe(
    /** The stored root id of the folder in use, read without suspending (it must never block a thread on a coroutine: this runs on the main thread). */
    private val rootId: () -> String?,
    private val trustDir: File,
) {
    fun isPinned(): Boolean = try {
        val root = rootId() ?: return false
        pinFileOf(root)?.let { FileKeysWatermarkStore(it).load() != null } ?: false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        // Cannot tell: a pin is assumed (the safe side: the key is never replaced on a guess).
        true
    }

    private fun pinFileOf(root: String): File? {
        return File(trustDir, folderFileName("keys-watermark", root)).takeIf { it.isFile }
    }
}
