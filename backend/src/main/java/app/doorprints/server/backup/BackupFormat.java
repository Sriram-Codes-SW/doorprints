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

package app.doorprints.server.backup;

import java.util.List;
import java.util.UUID;

/**
 * The one JSON backup format, shared by the server, the Android app and the web app (docs/11 section 5.2,
 * story S4-00). The written contract and a canonical sample live in {@code docs/schemas/} (README.md and
 * backup-sample.json); the two client copies of these constants are
 * {@code android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt} and
 * {@code web/src/app/export/backup-export.ts}. Nothing here may be renamed on one side only.
 *
 * <p>A device writes the format as a ZIP ({@code manifest.json}, {@code data.json}, {@code photos/<id>.jpg} and a
 * readable HTML copy). The server speaks the {@code data.json} half of it: {@code GET /api/export} returns exactly
 * that object and {@code POST /api/import} accepts exactly that object, so a backup made on a phone or in a browser
 * can be restored to a server and back again without a converter. Photo bytes are not JSON: on the server they are
 * uploaded and fetched through {@code /api/houses/{id}/photos} and {@code /api/photos/{id}}, and
 * {@link BackupPhoto#fileName()} still names the entry a ZIP copy of the same data would use.
 */
public final class BackupFormat {

    /**
     * Written into {@code manifest.json} and {@code data.json}. Readers accept {@code doorprints-backup/1} up to
     * {@link #MAX_VERSION} (docs/11 section 5.30 item 3, ADR-28): a new entity list means a new format number, and
     * a file newer than {@code MAX_VERSION} is refused with "update the app" rather than read with its lists
     * dropped in silence. A writer uses the lowest number that holds the copy: {@code /2} once it has a broker (slice 1b), a room (slice 1c), a criterion, a preference, a question or an answer (slice 3a), a viewing (slice 3b-1), else {@code /1}.
     */
    public static final String ID = "doorprints-backup/1";
    /** Written instead of {@link #ID} only when the copy holds at least one broker or one room: the lowest number that holds it. */
    public static final String ID_WITH_BROKERS = "doorprints-backup/2";
    private static final String PREFIX = "doorprints-backup/";
    /**
     * An update file with a {@code deleted} list (S4b-BL-82): written only by a device sharing updates. The server reads
     * it as a restore, and a restore never deletes, so the list is ignored here (Jackson drops the unknown key).
     */
    public static final String ID_WITH_DELETIONS = "doorprints-backup/3";
    /** The newest format this reader understands ({@code /3}: {@code /2}'s lists and an update file's deletions). */
    public static final int MAX_VERSION = 3;
    /** The format ids {@code POST /api/import} accepts, oldest first. */
    public static final List<String> READ_IDS = List.of(ID, ID_WITH_BROKERS, ID_WITH_DELETIONS);

    /** ZIP entry names used by the device writers (kept here so all three copies of the format agree). */
    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String DATA_ENTRY = "data.json";
    public static final String PHOTO_DIR = "photos/";

    /** ZIP-bomb and nonsense limits of the device readers, mirrored from Backup.kt / backup-export.ts. */
    public static final int MAX_ENTRIES = 5_000;
    public static final long MAX_UNCOMPRESSED_BYTES = 1_073_741_824L; // 1 GiB
    public static final long MAX_COMPRESSION_RATIO = 100L;

    /**
     * The largest {@code data.json} any reader accepts: <b>16 MiB</b>, the one number for all three implementations
     * (docs/schemas/README.md section 7). {@code :shared} ({@code Backup.kt}) and the web mirror
     * ({@code backup-export.ts}) use the same value, and on the server it is the default of
     * {@code app.limits.max-import-bytes}, so a {@code data.json} one reader accepts is one every reader accepts.
     * This used to be 64 MiB here and in the README while {@code :shared} enforced 16 MiB and the server's body cap
     * was 8 MiB (docs/10 section 11.3 row 7). {@code BackupLimitsParityTest} keeps the copies together.
     */
    public static final long MAX_DATA_JSON_BYTES = 16L * 1024 * 1024;

    private BackupFormat() {
    }

    /** True for a format id this reader understands ({@link #READ_IDS}). */
    public static boolean accepts(String format) {
        return format != null && READ_IDS.contains(format);
    }

    /** True for a {@code doorprints-backup/<n>} with {@code n} above {@link #MAX_VERSION}: a file from a newer app. */
    public static boolean isNewer(String format) {
        if (format == null || !format.startsWith(PREFIX)) return false;
        try {
            return Integer.parseInt(format.substring(PREFIX.length())) > MAX_VERSION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code <id>.jpg}: photos are always stored re-encoded as JPEG, so the extension is fixed. */
    public static String photoFileName(UUID photoId) {
        return photoId + ".jpg";
    }

    /** {@code photos/<id>.jpg} — the entry name inside a backup ZIP. */
    public static String photoEntry(String fileName) {
        return PHOTO_DIR + fileName;
    }
}
