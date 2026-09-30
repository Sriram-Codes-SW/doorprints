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

package app.doorprints.export

import androidx.work.Data
import androidx.work.workDataOf
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ExportScope
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.PhotoScope

/**
 * What the export worker is asked to do, packed into WorkManager's [Data] (which only holds primitives, so the
 * options travel as plain fields rather than as JSON — one less thing that can fail to parse in a background job).
 */
data class ExportRequest(
    val format: ExportFormat,
    /** The `content://` document the user picked, or a `file://` path under `cache/exports/` for the share sheet. */
    val target: String,
    val options: ExportOptions,
) {
    fun toData(): Data = workDataOf(
        KEY_FORMAT to format.name,
        KEY_TARGET to target,
        KEY_SCOPE to options.scope.name,
        KEY_SELECTED to options.selectedIds.toTypedArray(),
        KEY_REJECTED to options.includeRejected,
        KEY_PHOTOS to options.photos.name,
        KEY_CONTACTS to options.includeContacts,
        KEY_LANGUAGE to options.language,
        KEY_OFFSET to options.utcOffsetMinutes,
        KEY_AT to options.exportedAtMillis,
        KEY_SINCE to (options.since ?: -1L),
        KEY_SHARED_TO to options.sharedTo,
    )

    companion object {
        const val KEY_FORMAT = "format"
        const val KEY_TARGET = "target"
        const val KEY_SCOPE = "scope"
        const val KEY_SELECTED = "selected"
        const val KEY_REJECTED = "rejected"
        const val KEY_PHOTOS = "photos"
        const val KEY_CONTACTS = "contacts"
        const val KEY_LANGUAGE = "language"
        const val KEY_OFFSET = "offset"
        const val KEY_AT = "at"
        /** Sharing updates (docs/11 5.28): the "since" instant (-1: none) and the name the update is for. */
        const val KEY_SINCE = "since"
        const val KEY_SHARED_TO = "sharedTo"

        /** Progress and result keys, read by the screen. */
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_WRITTEN = "written"

        /** The finished file's display name (`OpenableColumns.DISPLAY_NAME`), when the provider reports one. */
        const val KEY_NAME = "name"

        /** Wall-clock time a run finished, so a screen opened much later does not present an old result as new. */
        const val KEY_FINISHED_AT = "finishedAt"

        /**
         * Whether anyone was told how a run ended: true when its screen was showing (`ScreenWatch`) or the result
         * notification was really posted (`Notifications.result` returned true). A screen shows a finished run that
         * nobody was told about however old it is, until the user has seen it (UX review, round 11). Read with a
         * default of true, so a run finished by an older build is not brought back.
         */
        const val KEY_NOTIFIED = "notified"

        /** An export only: true for a full backup made with options that leave something out (`BackupCompleteness`). */
        const val KEY_PARTIAL = "partial"

        /**
         * An export only: the folder or storage the "Save to…" document went to ("Download", "Downloads", "Drive"),
         * when the provider exposes it (`Saf.locationName`); absent otherwise and for a Share copy.
         */
        const val KEY_LOCATION = "location"

        fun fromData(data: Data): ExportRequest? {
            val format = ExportFormat.entries.firstOrNull { it.name == data.getString(KEY_FORMAT) } ?: return null
            val target = data.getString(KEY_TARGET) ?: return null
            return ExportRequest(
                format,
                target,
                ExportOptions(
                    scope = ExportScope.entries.firstOrNull { it.name == data.getString(KEY_SCOPE) }
                        ?: ExportScope.ALL,
                    selectedIds = data.getStringArray(KEY_SELECTED)?.toSet() ?: emptySet(),
                    includeRejected = data.getBoolean(KEY_REJECTED, true),
                    photos = PhotoScope.entries.firstOrNull { it.name == data.getString(KEY_PHOTOS) }
                        ?: PhotoScope.ALL,
                    includeContacts = data.getBoolean(KEY_CONTACTS, true),
                    language = data.getString(KEY_LANGUAGE) ?: "en",
                    utcOffsetMinutes = data.getInt(KEY_OFFSET, 0),
                    exportedAtMillis = data.getLong(KEY_AT, 0L),
                    since = data.getLong(KEY_SINCE, -1L).takeIf { it >= 0 },
                    sharedTo = data.getString(KEY_SHARED_TO),
                ),
            )
        }
    }
}

/** The [ImportRequest] (common since ADR-23 CMP-6 P6b) packed into WorkManager's [Data], under its `KEY_*` names. */
fun ImportRequest.toData(): Data = workDataOf(
    ImportRequest.KEY_PATH to stagedPath,
    ImportRequest.KEY_MODE to mode.name,
    ImportRequest.KEY_RESTORE to restoreDeleted,
    ImportRequest.KEY_SKIP_UPDATES to skipUpdates,
)

/** The [ImportRequest] in a worker's input [data], or null when it is not one. */
fun ImportRequest.Companion.fromData(data: Data): ImportRequest? {
    val path = data.getString(ImportRequest.KEY_PATH) ?: return null
    val mode = ImportMode.entries.firstOrNull { it.name == data.getString(ImportRequest.KEY_MODE) } ?: return null
    return ImportRequest(
        path, mode,
        restoreDeleted = data.getBoolean(ImportRequest.KEY_RESTORE, false),
        skipUpdates = data.getBoolean(ImportRequest.KEY_SKIP_UPDATES, false),
    )
}
