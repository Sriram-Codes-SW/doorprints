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

import app.doorprints.crypto.ByteSink
import app.doorprints.crypto.ByteSource
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.KeysWatermarkStore
import app.doorprints.crypto.P256PrivateKey

/*
 * The platform seams of the Drive backups (S4b-BL-116): what the shared services need from the app and the device,
 * each a small interface so the services are common code and the tests run on fakes (web twin: `drive-backup-seams.ts`).
 */

/**
 * This device in the folder: its key pair (the platform's key store: Android Keystore, the Secure Enclave, a
 * non-extractable WebCrypto key; S4b-BL-126, -127, -131), and the name and platform it is listed under.
 */
interface DeviceIdentity {
    val key: P256PrivateKey
    val name: String
    val platform: DevicePlatform
}

/**
 * The device's own Drive bookkeeping (sealed on the device by the platform; never in a backup, docs/15 §1.2). Ids are
 * remembered because a listing may lag behind a write (docs/15 §7.1); times are epoch milliseconds.
 */
data class DriveDeviceState(
    /** A random id for `appProperties device` (never the key id, never a hardware id). */
    val deviceId: String? = null,
    val rootId: String? = null,
    val backupsId: String? = null,
    val keysId: String? = null,
    val controlId: String? = null,
    /** This device is creating [rootId]'s key list and has not finished: a retry may start it again. */
    val creatingRootId: String? = null,
    /** This device's newest uploaded backup. */
    val lastBackupId: String? = null,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastFailure: BackupSchedule.Failure? = null,
    val lastVerifyAt: Long? = null,
    /** The newest authenticated `createdAt` this device has seen in the folder: a listing older than it lost a file. */
    val newestSeenAt: Long? = null,
    /** Backups whose drop in houses the person confirmed here (the shrink guard, docs/15 §1.4 item 4). */
    val confirmedDrops: Set<String> = emptySet(),
)

/** Where [DriveDeviceState] lives. One writer at a time (the platform's single backup worker or tab lock). */
interface DriveStateStore {
    /** The saved state, or an empty one on first use. */
    suspend fun load(): DriveDeviceState
    /** Replaces the saved state with [state]. */
    suspend fun save(state: DriveDeviceState)
}

/** The two watermarks of one folder ([rootId]), each with an atomic compare-and-set, sealed on the device. */
interface FolderTrustStores {
    /** The `keys.json` watermark store of the folder [rootId]. */
    fun keys(rootId: String): KeysWatermarkStore
    /** The `doorprints.json` watermark store of the folder [rootId]. */
    fun control(rootId: String): ControlWatermarkStore
}

/**
 * The backup to upload: the app's existing *Full backup* ZIP (`CopyWriter`'s `ExportFormat.BACKUP` in common code,
 * Android's `Exporters`), read from where the platform wrote it. [format] is the ZIP's manifest format
 * (`doorprints-backup/<n>`), which becomes the `dpx/1` inner format; [houses] its live houses (the shrink guard).
 */
class BackupPayload(val source: ByteSource, val format: String, val houses: Int, val close: () -> Unit = {})

/** Makes a [BackupPayload] for one run. */
fun interface BackupSource {
    /** Writes this run's backup ZIP and returns where to read it; the caller closes the payload. */
    suspend fun open(): BackupPayload
}

/** A temporary file for one encrypted backup, written once, then read by ranges, then deleted. */
interface Scratch : ByteSink {
    val size: Long

    /** Reads up to [length] bytes at [position] into [buffer]; the count, or -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Wipes the contents and removes the file; the owner calls it when the run is over. */
    fun delete()
}

/** Makes [Scratch] files (Android and the iPhone: the app's cache directory; tests and small backups: memory). */
fun interface ScratchSpace {
    /** A fresh, empty scratch file. */
    fun create(): Scratch
}

/** A [ScratchSpace] in memory, for tests and for backups that are small anyway (docs/15 §5.2: 1 to 5 MB without photos). */
object MemoryScratchSpace : ScratchSpace {
    override fun create(): Scratch = MemoryScratch()
}

private class MemoryScratch : Scratch {
    private var buf = ByteArray(1024)
    private var length = 0

    override val size: Long get() = length.toLong()

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (this.length + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, this.length + length))
        buffer.copyInto(buf, this.length, offset, offset + length)
        this.length += length
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        val n = minOf(length.toLong(), this.length - position).toInt()
        buf.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
        return n
    }

    override fun delete() {
        buf.fill(0)
        buf = ByteArray(0)
        length = 0
    }
}

/** Reads a [Scratch] from the start, for the `dpx/1` decryption. */
internal fun Scratch.source(): ByteSource {
    var at = 0L
    return ByteSource { b, o, l ->
        val n = read(at, b, o, l)
        if (n > 0) at += n
        n
    }
}

/** The whole of a [Scratch] (for a multipart upload, at most 5 MB). */
internal fun Scratch.readAll(): ByteArray = readRange(0, size.toInt())

/** Reads [length] bytes at [position]; shorter only when the file ends first. */
internal fun Scratch.readRange(position: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    var done = 0
    while (done < length) {
        val n = read(position + done, out, done, length - done)
        if (n <= 0) break
        done += n
    }
    return if (done == length) out else out.copyOf(done)
}

/**
 * Where an import from Drive writes the decrypted backup ZIP: the same staged file a picked file goes to (Android's
 * `Imports` cache copy, the iPhone's staged path). [discard] is called on any refusal: what was written is then
 * unconfirmed and must not reach the import (docs/15 §9.9, decrypt's streaming note).
 */
interface StagingSink : ByteSink {
    /** Throws away what was written so far; nothing of a refused download may reach the import. */
    fun discard()
}
