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
package app.doorprints.drive.photo

import app.doorprints.crypto.KeysException
import app.doorprints.drive.DriveException
import app.doorprints.drive.auth.SignInException
import app.doorprints.drive.sync.DriveSyncEngine
import app.doorprints.shared.sync.SyncRow
import kotlinx.coroutines.CancellationException

/** What the caller knows of the restored candidate's photos and of when it was deleted. */
class PhotoRestoreRequest(
    val houseId: String,
    /** Epoch milliseconds of the candidate's tombstone. */
    val houseDeletedAt: Long,
    /** The photos with a tombstone still stored on this device (their bytes are gone). */
    val known: List<GonePhoto> = emptyList(),
)

/** One photo that came back from Drive. */
class FetchedPhoto(val photo: GonePhoto, val bytes: ByteArray)

/** A photo that did not, and why. */
data class FailedPhoto(val photo: GonePhoto, val reason: PhotoRestoreReason)

/** What a run did: the photos fetched, and the reason for each that was not. Never an error. */
class PhotoRestoreReport(
    val connection: RestoreConnection,
    val fetched: List<FetchedPhoto> = emptyList(),
    val failed: List<FailedPhoto> = emptyList(),
) {
    companion object {
        /** The report of a device with no usable Drive folder: nothing fetched, each known photo *not connected* (or *offline*). */
        fun noConnection(known: List<GonePhoto>, connection: RestoreConnection = RestoreConnection.NOT_CONNECTED): PhotoRestoreReport {
            val reason = if (connection == RestoreConnection.OFFLINE) PhotoRestoreReason.OFFLINE else PhotoRestoreReason.NOT_CONNECTED
            return PhotoRestoreReport(connection, emptyList(), known.map { FailedPhoto(it, reason) })
        }
    }
}

/**
 * Getting the photos of a restored candidate back from the person's own Google Drive (S4b-BL-293; docs/15 §1.4). It finds
 * the candidate's photo tombstones (the ones this device still stores, and the ones its own sync file holds), checks each
 * is inside the 30 days and has a Drive file, and reads, checks and decrypts that file exactly like a sync download. It
 * uploads nothing, deletes nothing and writes nothing; the caller puts the bytes in the store. Every failure becomes a
 * reason for a photo, none is thrown, so the candidate's own restore never fails because of it. Web twin: `DrivePhotoRestorer`.
 */
class PhotoRestorer(
    private val engine: DriveSyncEngine,
    private val photos: DrivePhotos,
    private val clock: () -> Long,
) {
    suspend fun restore(req: PhotoRestoreRequest): PhotoRestoreReport {
        val rows: List<SyncRow> = try {
            engine.ownPhotoRows()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val c = connectionOf(e)
            if (c != null) return PhotoRestoreReport.noConnection(req.known, c)
            emptyList() // the file cannot be read (a rule refused it): the photos this device still stores are tried
        }
        val refs = try {
            photos.refs()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        val plan = PhotoRestoreRules.planRestore(req.houseId, req.houseDeletedAt, clock(), req.known, rows, refs)
        val fetched = ArrayList<FetchedPhoto>()
        val failed = ArrayList<FailedPhoto>()
        var fault: RestoreConnection? = null
        for (item in plan) {
            val f = fault
            if (f != null) {
                failed += FailedPhoto(item.photo, if (f == RestoreConnection.OFFLINE) PhotoRestoreReason.OFFLINE else PhotoRestoreReason.NOT_CONNECTED)
                continue
            }
            when (item.action) {
                RestoreAction.PAST_WINDOW -> failed += FailedPhoto(item.photo, PhotoRestoreReason.PAST_WINDOW)
                RestoreAction.MISSING -> failed += FailedPhoto(item.photo, PhotoRestoreReason.MISSING)
                RestoreAction.FETCH -> try {
                    when (val got = photos.fetchForRestore(item.photo.ref!!)) {
                        is DrivePhotos.RestoreFetch.Bytes -> fetched += FetchedPhoto(item.photo, got.bytes)
                        is DrivePhotos.RestoreFetch.Gone -> failed += FailedPhoto(item.photo, reasonOfSkip(got.reason, got.detail))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val c = connectionOf(e)
                    if (c != null) {
                        fault = c
                        failed += FailedPhoto(item.photo, if (c == RestoreConnection.OFFLINE) PhotoRestoreReason.OFFLINE else PhotoRestoreReason.NOT_CONNECTED)
                    } else {
                        failed += FailedPhoto(item.photo, PhotoRestoreReason.UNREADABLE)
                    }
                }
            }
        }
        return PhotoRestoreReport(fault ?: RestoreConnection.OK, fetched, failed)
    }

    companion object {
        /** How a failure while talking to Drive reads to the person: no sign-in or pin, no answer, or null for something to count against one photo. */
        fun connectionOf(e: Throwable): RestoreConnection? = when {
            e is SignInException -> if (e.kind == SignInException.Kind.OFFLINE) RestoreConnection.OFFLINE else RestoreConnection.NOT_CONNECTED
            e is KeysException && e.kind == KeysException.Kind.NOT_PINNED -> RestoreConnection.NOT_CONNECTED
            e is DriveException -> when (e.kind) {
                DriveException.Kind.UNAUTHORIZED, DriveException.Kind.CANCELLED -> RestoreConnection.NOT_CONNECTED
                DriveException.Kind.OFFLINE, DriveException.Kind.RATE_LIMITED, DriveException.Kind.SERVER -> RestoreConnection.OFFLINE
                else -> null
            }
            else -> null
        }

        /** A photo Drive could not give, as the reason the person reads. */
        fun reasonOfSkip(reason: PhotoSkipReason, detail: String?): PhotoRestoreReason = when (reason) {
            PhotoSkipReason.NO_REFERENCE, PhotoSkipReason.NO_FOLDER, PhotoSkipReason.NOT_OURS -> PhotoRestoreReason.MISSING
            PhotoSkipReason.UNREADABLE -> if (detail == "NOT_FOUND" || detail == "FORBIDDEN") PhotoRestoreReason.MISSING else PhotoRestoreReason.UNREADABLE
            else -> PhotoRestoreReason.UNREADABLE
        }
    }
}
