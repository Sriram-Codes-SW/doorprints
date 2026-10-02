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

package app.doorprints.drive.sync

import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.KeysFile
import app.doorprints.crypto.KeysGuard
import app.doorprints.crypto.MemoryWatermarkStore
import app.doorprints.crypto.OpenedKeys
import app.doorprints.crypto.P256PrivateKey
import app.doorprints.crypto.kidOf
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.FOLDER_MIME
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.InMemoryFakeDrive
import app.doorprints.drive.NewFile
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.sync.DriveMerge
import app.doorprints.shared.sync.SyncKind
import app.doorprints.shared.sync.SyncRow
import app.doorprints.shared.sync.SyncTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A device's local rows for the tests: a map of [SyncRow]s with dirty marks, and a sync loop in miniature. */
class MemLocal(private val deviceId: String) : LocalRows {
    val rows = LinkedHashMap<Pair<SyncKind, String>, SyncRow>()
    val dirty = LinkedHashSet<Pair<SyncKind, String>>()

    override suspend fun all(): List<SyncRow> = rows.values.toList()

    override suspend fun photo(photoId: String): PhotoChangeDto? =
        rows[SyncKind.PHOTOS to photoId]?.let { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(PhotoChangeDto.serializer(), it.json) }

    fun put(row: SyncRow, markDirty: Boolean) {
        rows[row.kind to row.key] = row
        if (markDirty) dirty += row.kind to row.key else dirty -= row.kind to row.key
    }

    fun label(id: String): String? = rows[SyncKind.HOUSES to id]?.let { r ->
        if (r.stamp.deleted) "<deleted>" else (r.json["label"] as JsonPrimitive).content
    }

    fun labels(): Map<String, String?> = rows.filterKeys { it.first == SyncKind.HOUSES }.entries.associate { it.key.second to label(it.key.second) }.toSortedMap()

    fun stamp(id: String) = rows[SyncKind.HOUSES to id]?.stamp
}

fun houseRow(id: String, label: String, updatedAt: Long, by: String, deleted: Boolean = false): SyncRow = SyncRow.of(
    SyncKind.HOUSES,
    buildJsonObject {
        put("id", id)
        put("label", label)
        put("lat", 12.9)
        put("lon", 77.5)
        put("updatedAt", IsoTime.format(updatedAt))
        put("deleted", deleted)
        put("by", by)
    },
)

/** One Drive folder with a key list, shared by the test devices. */
class SyncWorld(val server: FakeDriveServer = FakeDriveServer()) {
    val p = JvmCryptoProvider
    val keysFile = KeysFile(p)
    val rootId: String = server.putByHand(NewFile("Doorprints", FOLDER_MIME, emptyList(), DriveLayout.ROOT.appProperties)).id
    var keysBytes: ByteArray = ByteArray(0)
    val devices = LinkedHashMap<String, TestDevice>()
    private var first: TestDevice? = null

    fun now(): Long = server.clock.nowMs

    fun add(name: String): TestDevice {
        val key = p.p256Generate()
        val newDevice = KeysFile.NewDevice(key.publicKey, name, DevicePlatform.ANDROID)
        val guard = KeysGuard(p, MemoryWatermarkStore())
        val opened: OpenedKeys
        val f = first
        if (f == null) {
            val written = keysFile.createFirstDevice(newDevice, null, now())
            guard.pinCreated(written)
            keysBytes = written.bytes
            opened = written.opened
        } else {
            val written = keysFile.addDevice(f.opened, f.kid, newDevice, now())
            f.guard.acceptWritten(written)
            f.opened = written.opened
            keysBytes = written.bytes
            opened = keysFile.openFirstPin(written.bytes, key, guard, written.opened.currentFolderKey())
        }
        return TestDevice(this, name, key, guard, opened).also {
            devices[name] = it
            if (first == null) first = it
        }
    }

    /** [by] revokes [victim] (a new epoch); survivors see it when they reopen `keys.json`. */
    fun revoke(by: TestDevice, victim: TestDevice) {
        val written = keysFile.newEpoch(by.opened, now(), revokeKid = victim.kid)
        keysBytes = written.bytes
        by.guard.acceptWritten(written)
        by.opened = written.opened
    }

    fun syncFolderId(): String = server.allFiles().first { it.appProperties[DriveLayout.ROLE] == "sync" }.id

    fun syncFiles(): List<DriveFile> = server.allFiles().filter { it.appProperties[DriveLayout.KIND] == KIND_SYNC }
}

class TestDevice(val world: SyncWorld, val name: String, val key: P256PrivateKey, val guard: KeysGuard, var opened: OpenedKeys) {
    val p = world.p
    val kid: ByteArray = kidOf(p, key.publicKey)
    val id: String = SyncDeviceIds.of(kid)
    val local = MemLocal(id)
    val drive = InMemoryFakeDrive(world.server)
    var skewMs = 0L
    var state = DriveSyncState()
    val store = object : SyncStateStore {
        override suspend fun load() = state
        override suspend fun save(state: DriveSyncState) {
            this@TestDevice.state = state
        }
    }

    fun now() = world.now() + skewMs

    private fun session(stale: Boolean) = FolderSession(
        world.rootId, kid, opened, guard,
        if (stale) null else ({ world.keysFile.open(world.keysBytes, key, guard).also { opened = it } }),
    )

    fun engine(stale: Boolean = false) = DriveSyncEngine(drive, p, session(stale), store, local, ::now)

    fun edit(houseId: String, label: String) {
        val prev = local.stamp(houseId)?.updatedAt
        local.put(houseRow(houseId, label, DriveMerge.nextStamp(now(), prev), id), true)
    }

    fun delete(houseId: String) {
        val prev = local.rows.getValue(SyncKind.HOUSES to houseId)
        local.put(houseRow(houseId, (prev.json["label"] as JsonPrimitive).content, DriveMerge.nextStamp(now(), prev.stamp.updatedAt), id, deleted = true), true)
    }

    /**
     * The loop in miniature: one pass; on success the rows taken are applied by the Drive rule and the pushed rows marked
     * clean (and not before). A thrown error leaves everything dirty.
     */
    suspend fun sync(confirmShrink: Boolean = false, stale: Boolean = false, force: Boolean = true, engine: DriveSyncEngine = engine(stale)): SyncPassResult {
        val result = engine.run(confirmShrink, force)
        result.report?.take?.forEach { r ->
            val here = local.rows[r.kind to r.key]?.stamp
            if (DriveMerge.takesIncoming(here, r.stamp)) local.put(r, false)
        }
        if (result.report != null) local.dirty.clear()
        return result
    }
}

fun SyncPassResult.skippedReasons(): List<SkipReason> = report?.skipped?.map { it.reason }.orEmpty()
