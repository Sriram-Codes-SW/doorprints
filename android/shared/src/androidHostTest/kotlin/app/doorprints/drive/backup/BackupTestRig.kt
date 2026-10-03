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

package app.doorprints.drive.backup

import app.doorprints.crypto.ByteSource
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.KeysWatermark
import app.doorprints.crypto.KeysWatermarkStore
import app.doorprints.crypto.P256PrivateKey
import app.doorprints.crypto.patternBytes
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.InMemoryFakeDrive

/** In-memory seams and a ready-made "device" for the Drive backup tests (S4b-BL-116). Test code only. */

class MemoryStateStore : DriveStateStore {
    var value = DriveDeviceState()
    override suspend fun load() = value
    override suspend fun save(state: DriveDeviceState) {
        value = state
    }
}

class MemoryControlStore(var value: ControlWatermark? = null) : ControlWatermarkStore {
    override fun load() = value

    @Synchronized
    override fun compareAndSet(expected: ControlWatermark?, next: ControlWatermark): Boolean {
        if (value != expected) return false
        value = next
        return true
    }
}

class MemoryKeysStore(var value: KeysWatermark? = null) : KeysWatermarkStore {
    override fun load() = value

    @Synchronized
    override fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark): Boolean {
        if (value != expected) return false
        value = next
        return true
    }
}

class MemoryTrust : FolderTrustStores {
    val keys = mutableMapOf<String, MemoryKeysStore>()
    val control = mutableMapOf<String, MemoryControlStore>()
    override fun keys(rootId: String): KeysWatermarkStore = keys.getOrPut(rootId) { MemoryKeysStore() }
    override fun control(rootId: String): ControlWatermarkStore = control.getOrPut(rootId) { MemoryControlStore() }
}

class TestIdentity(p: CryptoProvider, override val name: String) : DeviceIdentity {
    override val key: P256PrivateKey = p.p256Generate()
    override val platform = DevicePlatform.ANDROID
}

/** A staging sink that records what was written and whether it was discarded. */
class RecordingStaging : StagingSink {
    private val out = java.io.ByteArrayOutputStream()
    var discards = 0
    override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
    override fun discard() {
        discards++
        out.reset()
    }
    fun bytes(): ByteArray = out.toByteArray()
}

fun sourceOf(bytes: ByteArray): ByteSource {
    var at = 0
    return ByteSource { b, o, l ->
        if (at >= bytes.size) {
            -1
        } else {
            val n = minOf(l, bytes.size - at)
            bytes.copyInto(b, o, at, at + n)
            at += n
            n
        }
    }
}

/** What a device's backup ZIP is in these tests: bytes the Drive layer never looks into (the format string is all it needs). */
class Payload(val bytes: ByteArray, val houses: Int, val format: String = "doorprints-backup/1") {
    fun source() = BackupSource { BackupPayload(sourceOf(bytes), format, houses) }

    companion object {
        fun of(size: Int, houses: Int, seed: Int = 0) = Payload(patternBytes(size + seed).copyOfRange(seed, size + seed), houses)
    }
}

/** One device of one person: its own key, state, watermarks and client, on the shared [server]. */
class Rig(val server: FakeDriveServer, name: String = "Pixel 8", val p: CryptoProvider = JvmCryptoProvider) {
    val drive = InMemoryFakeDrive(server)
    val identity = TestIdentity(p, name)
    val state = MemoryStateStore()
    val trust = MemoryTrust()
    var offset = 330
    val service = DriveBackupService(drive, p, identity, state, trust, { server.clock.now() }, { offset })

    suspend fun created(): DriveConnection.Ready {
        val out = service.createFolder(withRecoveryKey = true)
        return out.connection as DriveConnection.Ready
    }

    suspend fun ready(): ReadyFolder = (service.connect() as DriveConnection.Ready).folder

    fun backupsFolder(): DriveFile = server.allFiles().first { it.appProperties[DriveLayout.ROLE] == "backups" }

    /** Drive files in `Backups/` that are not in the bin. */
    fun live(): List<DriveFile> = server.allFiles().filter { backupsFolder().id in it.parents && !it.trashed }

    fun binned(): List<DriveFile> = server.allFiles().filter { backupsFolder().id in it.parents && it.trashed }
}
