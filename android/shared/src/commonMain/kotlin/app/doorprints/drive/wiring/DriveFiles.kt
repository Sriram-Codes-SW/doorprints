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

import app.doorprints.concurrent.PlatformLock
import app.doorprints.drive.connect.DrivePrefs
import app.doorprints.drive.device.DriveLockStore
import app.doorprints.drive.store.FileKeysWatermarkStore
import app.doorprints.drive.store.StateFile
import app.doorprints.drive.store.folderFileName
import app.doorprints.drive.store.stateFileAt
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.io.IOException

/**
 * The few per-device Drive preferences (automatic backup on or off, photos on mobile data) and whether Drive is in use
 * ([engaged], docs/15 §1.3), in one small file in the device's no-backup folder, so the platform's own backup never carries
 * them to another phone. Plain on/off values; nothing secret. A file that is missing or damaged reads as "nothing set"
 * (the controller's defaults), and a write that fails is dropped: a preference is never worth a crash.
 */
class FileDrivePrefs(private val store: StateFile) : DrivePrefs {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** What was parsed, with the file's stamp at the time: the next read of an unchanged file does not parse again. */
    private class Memo(val stamp: Pair<Long, Long>, val values: Map<String, String>)

    private val lock = PlatformLock()
    private var memo: Memo? = null

    /** How many times the file was parsed (the tests' proof that an unchanged file is read once). */
    @Volatile
    var parses = 0
        private set

    /** False until a value was ever written: a phone that never used Drive has no file (nothing to look at, nothing to schedule). */
    fun exists(): Boolean = store.exists()

    private fun read(): Map<String, String> {
        val stamp = store.stamp()
        if (stamp == null) {
            memo = null
            return emptyMap()
        }
        memo?.let { if (it.stamp == stamp) return it.values }
        parses++
        val values = try {
            store.readText()?.let { json.decodeFromString(serializer, it) } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        memo = Memo(stamp, values)
        return values
    }

    override fun get(key: String): String? = lock.withLock { read()[key] }

    /** Called after a value changed (not on a failed write): the wiring reschedules the background work from it. */
    @Volatile
    var onChange: ((key: String, value: String) -> Unit)? = null

    override fun put(key: String, value: String) {
        val written = lock.withLock {
            try {
                store.writeText(json.encodeToString(serializer, read() + (key to value)))
                memo = null
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
class FileDriveLockStore(private val store: StateFile) : DriveLockStore {
    private val lock = PlatformLock()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Dto(
        val paused: Boolean = false,
        val needsReenrolment: Boolean = false,
        val keyDropped: Boolean = false,
        val keyStoreFault: Boolean = false,
    )

    private fun read(): Dto = try {
        store.readText()?.let { json.decodeFromString(Dto.serializer(), it) } ?: Dto()
    } catch (_: Exception) {
        Dto()
    }

    private fun write(dto: Dto) {
        try {
            store.writeText(json.encodeToString(Dto.serializer(), dto))
        } catch (_: IOException) {
            // The pause is also found again by the live check of the lock.
        }
    }

    /** One read-modify-write: two fields changed from two threads never lose one of them. */
    private fun change(edit: (Dto) -> Dto) = lock.withLock { write(edit(read())) }

    override var paused: Boolean
        get() = lock.withLock { read().paused }
        set(value) = change { Dto(value, it.needsReenrolment, it.keyDropped, it.keyStoreFault) }

    override var needsReenrolment: Boolean
        get() = lock.withLock { read().needsReenrolment }
        set(value) = change { Dto(it.paused, value, it.keyDropped, it.keyStoreFault) }

    override var keyDropped: Boolean
        get() = lock.withLock { read().keyDropped }
        set(value) = change { Dto(it.paused, it.needsReenrolment, value, it.keyStoreFault) }

    override var keyStoreFault: Boolean
        get() = lock.withLock { read().keyStoreFault }
        set(value) = change { Dto(it.paused, it.needsReenrolment, it.keyDropped, value) }
}

/**
 * Whether the folder this device uses (the stored root id) has a pin of its `keys.json`: the answer for
 * `KeystoreDeviceIdentity.folderPinned`. While it is true a lost device key is a state ("connect again"), never a reason
 * to make a new key. A folder that was deleted and forgotten has no root id here, so it no longer pins the key.
 */
class FolderPinProbe(
    /** The stored root id of the folder in use, read without suspending (it must never block a thread on a coroutine: this runs on the main thread). */
    private val rootId: () -> String?,
    private val trustDir: String,
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

    private fun pinFileOf(root: String): StateFile? =
        stateFileAt("$trustDir/" + folderFileName("keys-watermark", root)).takeIf { it.exists() }
}
