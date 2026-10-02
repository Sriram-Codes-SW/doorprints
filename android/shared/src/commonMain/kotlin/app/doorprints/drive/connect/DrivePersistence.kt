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

package app.doorprints.drive.connect

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoException
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.KeysWatermark
import app.doorprints.crypto.KeysWatermarkStore
import app.doorprints.crypto.P256PrivateKey
import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.backup.ControlWatermark
import app.doorprints.drive.backup.ControlWatermarkStore
import app.doorprints.drive.backup.DriveDeviceState
import app.doorprints.drive.backup.DriveStateStore
import app.doorprints.drive.backup.FolderTrustStores
import app.doorprints.drive.delete.DeletedMarker
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionItem
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionStore
import app.doorprints.drive.delete.ItemKind
import app.doorprints.drive.delete.PendingDeletion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The small stores the Drive services need on a device (S4b-BL-117), over one [KeyValueStore] each platform provides
 * (Android: app-private SharedPreferences; the device key goes in a sealed one). Nothing here is a secret except the
 * device key's scalar, which only goes to a *sealed* store. Common code, so the encoding is tested on the JVM.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.lng(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private inline fun <T> parsedOr(text: String?, fallback: T, read: (JsonObject) -> T): T {
    if (text == null) return fallback
    return try {
        read(json.parseToJsonElement(text).jsonObject)
    } catch (_: Exception) {
        fallback
    }
}

class KvDriveStateStore(private val kv: KeyValueStore) : DriveStateStore {
    override suspend fun load(): DriveDeviceState = parsedOr(kv.get(KEY), DriveDeviceState()) { o ->
        DriveDeviceState(
            deviceId = o.str("deviceId"), rootId = o.str("rootId"), backupsId = o.str("backupsId"), keysId = o.str("keysId"),
            controlId = o.str("controlId"), creatingRootId = o.str("creatingRootId"), lastBackupId = o.str("lastBackupId"),
            lastSuccessAt = o.lng("lastSuccessAt"), lastAttemptAt = o.lng("lastAttemptAt"),
            lastFailure = o.str("lastFailure")?.let { n -> BackupSchedule.Failure.entries.firstOrNull { it.name == n } },
            lastVerifyAt = o.lng("lastVerifyAt"), newestSeenAt = o.lng("newestSeenAt"),
            confirmedDrops = (o["confirmedDrops"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),
        )
    }

    override suspend fun save(state: DriveDeviceState) {
        val o = buildJsonObject {
            fun s(k: String, v: String?) = if (v != null) put(k, v) else put(k, JsonNull)
            fun n(k: String, v: Long?) = if (v != null) put(k, v) else put(k, JsonNull)
            s("deviceId", state.deviceId); s("rootId", state.rootId); s("backupsId", state.backupsId); s("keysId", state.keysId)
            s("controlId", state.controlId); s("creatingRootId", state.creatingRootId); s("lastBackupId", state.lastBackupId)
            n("lastSuccessAt", state.lastSuccessAt); n("lastAttemptAt", state.lastAttemptAt)
            s("lastFailure", state.lastFailure?.name)
            n("lastVerifyAt", state.lastVerifyAt); n("newestSeenAt", state.newestSeenAt)
            put("confirmedDrops", buildJsonArray { state.confirmedDrops.forEach { add(JsonPrimitive(it)) } })
        }
        kv.put(KEY, o.toString())
    }

    /** After *Delete everything*: no folder of the old key set is remembered (docs/15 §3.4); the device id stays. */
    suspend fun forgetFolder() {
        val s = load()
        save(
            s.copy(
                rootId = null, backupsId = null, keysId = null, controlId = null, creatingRootId = null, lastBackupId = null,
                lastSuccessAt = null, lastFailure = null, lastVerifyAt = null, newestSeenAt = null, confirmedDrops = emptySet(),
            ),
        )
    }

    private companion object {
        const val KEY = "drive.state"
    }
}

/**
 * The pin and the control watermarks per folder. `compareAndSet` assumes one writer at a time, which the services
 * promise (one run at a time per device).
 */
class KvFolderTrustStores(private val kv: KeyValueStore) : FolderTrustStores {
    override fun keys(rootId: String): KeysWatermarkStore = object : KeysWatermarkStore {
        private val key = "drive.keys.$rootId"
        override fun load(): KeysWatermark? = parsedOr(kv.get(key), null) { o ->
            KeysWatermark(
                (o.lng("epoch") ?: return@parsedOr null).toInt(), o.lng("revision") ?: return@parsedOr null,
                Bytes.unhex(o.str("keyId") ?: return@parsedOr null), Bytes.unhex(o.str("bodyHash") ?: return@parsedOr null),
            )
        }

        override fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark): Boolean {
            if (load() != expected) return false
            kv.put(
                key,
                buildJsonObject {
                    put("epoch", next.epoch.toLong()); put("revision", next.revision)
                    put("keyId", Bytes.hex(next.keyId)); put("bodyHash", Bytes.hex(next.bodyHash))
                }.toString(),
            )
            return true
        }
    }

    override fun control(rootId: String): ControlWatermarkStore = object : ControlWatermarkStore {
        private val key = "drive.control.$rootId"
        override fun load(): ControlWatermark? = parsedOr(kv.get(key), null) { o ->
            ControlWatermark(o.lng("revision") ?: return@parsedOr null, Bytes.unhex(o.str("bodyHash") ?: return@parsedOr null), o.lng("backupsDeletedAt"))
        }

        override fun compareAndSet(expected: ControlWatermark?, next: ControlWatermark): Boolean {
            if (load() != expected) return false
            kv.put(
                key,
                buildJsonObject {
                    put("revision", next.revision); put("bodyHash", Bytes.hex(next.bodyHash))
                    if (next.backupsDeletedAt != null) put("backupsDeletedAt", next.backupsDeletedAt!!) else put("backupsDeletedAt", JsonNull)
                }.toString(),
            )
            return true
        }
    }
}

class KvDeletionStore(private val kv: KeyValueStore, private val state: KvDriveStateStore) : DeletionStore {
    override suspend fun pending(): PendingDeletion? = parsedOr(kv.get(PENDING), null) { o ->
        val action = o["action"]!!.jsonObject
        PendingDeletion(
            operationId = o.str("operationId")!!,
            level = DeletionLevel.valueOf(o.str("level")!!),
            action = when (action.str("type")) {
                "one" -> DeletionAction.OneBackup(action.str("id")!!)
                "older" -> DeletionAction.OlderBackups
                "all" -> DeletionAction.AllBackups
                "everything" -> DeletionAction.Everything
                else -> return@parsedOr null
            },
            rootId = o.str("rootId")!!,
            items = o["items"]!!.jsonArray.map {
                val i = it.jsonObject
                DeletionItem(i.str("id")!!, ItemKind.entries.first { k -> k.code == i.str("kind") }, i.lng("bytes")!!, i.lng("phase")!!.toInt())
            },
            total = o.lng("total")!!.toInt(),
            createdAtMs = o.lng("createdAtMs")!!,
        )
    }

    override suspend fun savePending(pending: PendingDeletion) {
        kv.put(
            PENDING,
            buildJsonObject {
                put("operationId", pending.operationId); put("level", pending.level.name)
                put(
                    "action",
                    buildJsonObject {
                        when (val a = pending.action) {
                            is DeletionAction.OneBackup -> { put("type", "one"); put("id", a.fileId) }
                            DeletionAction.OlderBackups -> put("type", "older")
                            DeletionAction.AllBackups -> put("type", "all")
                            DeletionAction.Everything -> put("type", "everything")
                        }
                    },
                )
                put("rootId", pending.rootId); put("total", pending.total.toLong()); put("createdAtMs", pending.createdAtMs)
                put(
                    "items",
                    buildJsonArray {
                        pending.items.forEach {
                            add(buildJsonObject { put("id", it.id); put("kind", it.kind.code); put("bytes", it.bytes); put("phase", it.phase.toLong()) })
                        }
                    },
                )
            }.toString(),
        )
    }

    override suspend fun clearPending() = kv.remove(PENDING)

    override suspend fun marker(): DeletedMarker? = parsedOr(kv.get(MARKER), null) { o ->
        DeletedMarker(DeletionLevel.valueOf(o.str("level")!!), o.lng("atMs")!!, o.str("action")!!)
    }

    override suspend fun recordFinished(marker: DeletedMarker, forgetFolder: Boolean) {
        kv.put(MARKER, buildJsonObject { put("level", marker.level.name); put("atMs", marker.atMs); put("action", marker.action) }.toString())
        if (forgetFolder) state.forgetFolder()
    }

    private companion object {
        const val PENDING = "drive.deletion.pending"
        const val MARKER = "drive.deletion.marker"
    }
}

/** The device's long-lived P-256 key: a random scalar, kept only in a *sealed* [KeyValueStore] (Keystore-bound on Android). */
class DeviceKeyStore(private val sealed: KeyValueStore, private val p: CryptoProvider) {
    fun loadOrCreate(): P256PrivateKey {
        sealed.get(KEY)?.let { stored ->
            val scalar = Bytes.unb64(stored, 32)
            if (scalar != null) {
                try {
                    return p.p256FromScalar(scalar)
                } catch (_: CryptoException) {
                    // A damaged entry is not trusted; a new key means this device joins again (docs/15 §9.5).
                }
            }
        }
        while (true) {
            val scalar = p.randomBytes(32)
            val key = try {
                p.p256FromScalar(scalar)
            } catch (_: CryptoException) {
                continue
            }
            sealed.put(KEY, Bytes.b64(scalar))
            return key
        }
    }

    /** Forgets the key (the lock was removed, or *Disconnect* on the iPhone's rule): the device joins again later. */
    fun forget() = sealed.remove(KEY)

    private companion object {
        const val KEY = "drive.device-key"
    }
}
