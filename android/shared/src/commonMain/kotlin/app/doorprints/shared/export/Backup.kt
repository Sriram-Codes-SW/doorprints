package app.doorprints.shared.export

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The exact, re-importable JSON backup (docs/11 section 5.2, S4-02/S4-04).
 *
 * A backup is a ZIP holding
 * ```
 * manifest.json          format, versions, options, counts and a SHA-256 for every other entry
 * data.json              the rows, exactly as the app stores them
 * photos/<id>.jpg        the photo files that data.json's photo rows name
 * Doorprints-<date>.html the readable copy, so a backup is also openable without the app
 * ```
 * `manifest.json` is written **last** (its hashes cover the other entries) but is small, so an importer reads the
 * whole ZIP's directory first anyway.
 */
object BackupFormat {
    /** Written into `manifest.json` and `data.json`; a reader refuses anything else. */
    const val ID = "doorprints-backup/1"

    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"
    const val PHOTO_DIR = "photos/"

    /** ZIP-bomb and nonsense limits, checked before anything is written (docs/11 section 5.2, threat model). */
    const val MAX_ENTRIES = 5_000
    const val MAX_UNCOMPRESSED_BYTES = 1_073_741_824L // 1 GiB
    const val MAX_COMPRESSION_RATIO = 100L
    /**
     * data.json is text; a backup of 5 000 houses with long notes is a couple of megabytes. The limit is what a
     * phone can actually decode — the bytes are turned into a String (2x) and then into objects — not what the
     * format could theoretically hold, so a file above it is refused with "too large" rather than an OOM.
     * MAX_ENTRIES and MAX_UNCOMPRESSED_BYTES bound the archive; this bounds the one entry that is parsed whole.
     */
    const val MAX_DATA_JSON_BYTES = 16L * 1024 * 1024

    /**
     * Stable JSON settings for both writing and reading. `encodeDefaults` keeps every field present so a reader
     * never has to guess; `explicitNulls = false` leaves empty optionals out; `ignoreUnknownKeys` lets a newer
     * app's extra fields pass through an older reader instead of failing the whole import. No pretty-printing:
     * the output must be byte-identical for the same data (the golden tests rely on it).
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** `photos/<id>.jpg` — the entry name a photo row points at. */
    fun photoEntry(fileName: String): String = PHOTO_DIR + fileName
}

/** The rows `data.json` holds — unlinked visits included — so a reader can check it got them all. */
@Serializable
data class BackupCounts(val houses: Int, val visits: Int, val photos: Int) {
    companion object {
        fun of(data: BackupData): BackupCounts = BackupCounts(data.houses.size, data.visits.size, data.photos.size)
    }
}

/** One entry of the ZIP, with the SHA-256 (lower-case hex) of its bytes. */
@Serializable
data class BackupFile(val path: String, val sizeBytes: Long, val sha256: String)

@Serializable
data class BackupManifest(
    /** [Required] on read, like [BackupData.format]: a manifest that does not say what it is was not written by us. */
    @Required val format: String = BackupFormat.ID,
    val app: String = "Doorprints",
    /** The app version that wrote the backup, for a support question; never used to gate an import. */
    val appVersion: String = "",
    /** ISO-8601 instant, see [app.doorprints.shared.api.IsoTime]. */
    val createdAt: String,
    val language: String = "en",
    val scope: String = ExportScope.ALL.name,
    val includeRejected: Boolean = true,
    val photoScope: String = PhotoScope.ALL.name,
    val includeContacts: Boolean = true,
    val counts: BackupCounts,
    val files: List<BackupFile> = emptyList(),
)

/**
 * `data.json`: the rows themselves, in the format's fixed order (docs/schemas/README.md section 5).
 *
 * [format] carries a default so Kotlin callers need not repeat it, but it is [Required] on the way **in**: a
 * `data.json` with no `format` is not a Doorprints backup, and the server refuses it too (section 4.4). Without the
 * annotation the default would quietly stand in for the missing field. The three row lists stay lenient when
 * absent (read as empty), which is also what the server does.
 */
@Serializable
data class BackupData(
    @Required val format: String = BackupFormat.ID,
    val exportedAt: Long,
    val houses: List<ExportHouse> = emptyList(),
    val visits: List<ExportVisit> = emptyList(),
    val photos: List<ExportPhoto> = emptyList(),
) {
    companion object {
        /**
         * The rows of [bundle] in the order every writer of `doorprints-backup/1` uses (docs/schemas/README.md
         * section 5, ticket S4-00/a):
         *
         * - houses as [ExportBundle.build] ordered them (`createdAt`, then `id`);
         * - visits and photos **grouped by their house**, in that house order, each group in the bundle's own
         *   order (`arrivedAt` / `createdAt`, then `id`) — `groupBy` keeps it;
         * - a row whose house is not in the copy last, in the same order: above all the visits that belong to no
         *   house ([ExportBundle.unlinkedVisits], Hunt mode's dwells at a place that is not a house yet), and any
         *   such row a hand-built bundle puts in its own lists, because dropping it here would lose data silently;
         * - `checklist` keys sorted (`String.compareTo`, UTF-16 code units: Java's `TreeMap` and JavaScript's
         *   default sort agree on it).
         *
         * Only `data.json` groups. The bundle's own lists stay sorted globally, because the CSV and XLSX tables are
         * built from them and the web tables match that order.
         */
        fun of(bundle: ExportBundle): BackupData {
            val houseIds = bundle.houses.mapTo(HashSet()) { it.id }
            val houseless = (bundle.visits.filter { it.houseId == null || it.houseId !in houseIds } +
                bundle.unlinkedVisits)
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.arrivedAt }, { it.id }))
            return BackupData(
                exportedAt = bundle.options.exportedAtMillis,
                houses = bundle.houses.map { it.withSortedChecklist() },
                visits = bundle.houses.flatMap { bundle.visitsOf(it) } + houseless,
                photos = bundle.houses.flatMap { bundle.photosOf(it) } +
                    bundle.photos.filter { it.houseId !in houseIds },
            )
        }

        private fun ExportHouse.withSortedChecklist(): ExportHouse {
            val keys = checklist.keys.sorted()
            if (keys == checklist.keys.toList()) return this
            return copy(checklist = keys.associateWith { checklist.getValue(it) })
        }
    }
}

/** Why a file cannot be imported. The UI turns each one into a translated sentence. */
enum class BackupProblem {
    NOT_A_BACKUP,
    UNSUPPORTED_VERSION,
    TOO_MANY_ENTRIES,
    TOO_LARGE,
    SUSPICIOUS_PATH,
    CHECKSUM_MISMATCH,
    BROKEN_DATA,

    /**
     * The file could not be read at all: the provider handed back nothing, the read failed, or there was no room
     * to stage a copy. Not the same as [TOO_LARGE] — telling someone whose disk is full that their backup is too
     * big sends them to the wrong fix.
     */
    READ_FAILED,

    /**
     * The backup was fine; writing what it held was not — no room for a photo file, or the database refused a
     * row. The file is not the problem, so the sentence the user reads must not blame it. Without this the
     * importer had nothing honest to report and fell back to an exception message, which the screen then mapped
     * to [NOT_A_BACKUP]: the one answer guaranteed to send the user looking for a different file.
     */
    WRITE_FAILED,
}

/** Structural checks that do not need the ZIP itself; the platform reader adds the size and path checks. */
object BackupValidation {

    fun checkManifest(manifest: BackupManifest): BackupProblem? = when {
        manifest.format != BackupFormat.ID -> BackupProblem.UNSUPPORTED_VERSION
        manifest.counts.houses < 0 || manifest.counts.visits < 0 || manifest.counts.photos < 0 ->
            BackupProblem.BROKEN_DATA
        else -> null
    }

    /**
     * A row id that is safe to use as a file name.
     *
     * The apps' own ids are UUIDs, so this costs nothing honest — and it is the second half of the zip-slip
     * guard. [isSuspiciousPath] checks the names the *archive* carries, but an importer also builds paths from
     * ids inside `data.json` (Android: `filesDir/photos/<photo id>.jpg`), and those are just as much attacker
     * input in a hand-edited backup. Without this, a photo id of `../../shared_prefs/x` writes outside the photo
     * directory, and an id containing `/` aborts the whole import with a FileNotFoundException.
     */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    fun checkData(data: BackupData): BackupProblem? = when {
        data.format != BackupFormat.ID -> BackupProblem.UNSUPPORTED_VERSION
        data.houses.any { !isValidId(it.id) } || data.visits.any { !isValidId(it.id) } -> BackupProblem.BROKEN_DATA
        data.visits.any { v -> v.houseId?.let { !isValidId(it) } ?: false } -> BackupProblem.BROKEN_DATA
        data.photos.any { !isValidId(it.id) || !isValidId(it.houseId) } -> BackupProblem.BROKEN_DATA
        data.houses.map { it.id }.toSet().size != data.houses.size -> BackupProblem.BROKEN_DATA
        data.visits.map { it.id }.toSet().size != data.visits.size -> BackupProblem.BROKEN_DATA
        data.photos.map { it.id }.toSet().size != data.photos.size -> BackupProblem.BROKEN_DATA
        else -> null
    }

    /**
     * A ZIP entry name that must never be written: an absolute path, a Windows drive, a `..` segment or a
     * backslash (which some tools turn back into a separator). Zip-slip guard, checked for **every** entry, even
     * ones the importer does not use.
     */
    fun isSuspiciousPath(name: String): Boolean {
        if (name.isEmpty()) return true
        if (name.startsWith("/") || name.startsWith("\\")) return true
        if (name.contains("\\")) return true
        if (name.length >= 2 && name[1] == ':') return true
        return name.split("/").any { it == ".." || it == "." }
    }

    /** A photo entry the importer may read: exactly `photos/<name>`, one level deep, nothing clever. */
    fun isPhotoEntry(name: String): Boolean =
        name.startsWith(BackupFormat.PHOTO_DIR) &&
            name.length > BackupFormat.PHOTO_DIR.length &&
            !name.substring(BackupFormat.PHOTO_DIR.length).contains('/') &&
            !isSuspiciousPath(name)
}
