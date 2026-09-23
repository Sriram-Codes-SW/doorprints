package com.househunt.backup;

import java.util.UUID;

/**
 * The one JSON backup format, shared by the server, the Android app and the web app (docs/11 section 5.2,
 * story S4-00). The written contract and a canonical sample live in {@code docs/schemas/} (README.md and
 * backup-sample.json); the two client copies of these constants are
 * {@code android/shared/src/commonMain/kotlin/com/househunt/shared/export/Backup.kt} and
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

    /** Written into {@code manifest.json} and {@code data.json}; a reader refuses anything else. */
    public static final String ID = "doorprints-backup/1";

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

    /** {@code <id>.jpg}: photos are always stored re-encoded as JPEG, so the extension is fixed. */
    public static String photoFileName(UUID photoId) {
        return photoId + ".jpg";
    }

    /** {@code photos/<id>.jpg} — the entry name inside a backup ZIP. */
    public static String photoEntry(String fileName) {
        return PHOTO_DIR + fileName;
    }
}
