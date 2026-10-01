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

package app.doorprints.shared.export

import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.Place
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.Preference
import app.doorprints.shared.records.RecordRules
import app.doorprints.shared.model.HouseRooms
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
    /**
     * Written into `manifest.json` and `data.json` when the copy holds only what `/1` knows. A copy with something of
     * `/2` (slice 1b: a broker; slice 1c: a room) is written as [ID_2]; [idFor] picks the lowest number that holds
     * everything (docs/schemas README 1.1). A reader accepts every format up to [MAX_VERSION] (S4b-BL-72).
     */
    const val ID = "doorprints-backup/1"

    /** The format that adds the `brokers` list (slice 1b). */
    const val ID_2 = "doorprints-backup/2"

    /**
     * The format a copy is written in: `/2` only when it holds a broker, a room (slice 1c), a criterion or a preference
     * (slice 2), a question or a house with answers (slice 3a), a viewing (slice 3b-1), an area, a place or an area
     * note (slice 4a), or [slice5]: a house TAKEN or NOT_CHOSEN, a house with a move-in or a photo with meta
     * ([ExportBundle.hasSlice5]); else `/1`, byte for byte as before.
     */
    fun idFor(
        brokers: Int, rooms: Int = 0, criteria: Int = 0, preferences: Int = 0, questions: Int = 0, answers: Int = 0,
        viewings: Int = 0, areas: Int = 0, places: Int = 0, areaNotes: Int = 0, slice5: Boolean = false,
    ): String =
        if (brokers > 0 || rooms > 0 || criteria > 0 || preferences > 0 || questions > 0 || answers > 0 || viewings > 0 ||
            areas > 0 || places > 0 || areaNotes > 0 || slice5
        ) ID_2 else ID

    /**
     * The newest format this app reads (S4b-BL-72): a new entity list in `data.json` means a new number, so an older
     * app refuses a newer file with "update the app" instead of dropping its lists in silence, while a `/2` file
     * without the lists this app knows reads fine (unknown keys are ignored). One constant per stack.
     */
    const val MAX_VERSION = 2

    private const val FAMILY = "doorprints-backup/"

    /** Every format id a reader takes: `/1` up to `/MAX_VERSION`. */
    val READ_IDS: List<String> = (1..MAX_VERSION).map { FAMILY + it }

    /** Whether a file that says it is [format] can be read here. */
    fun accepts(format: String?): Boolean = format in READ_IDS

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
data class BackupCounts(
    val houses: Int,
    val visits: Int,
    val photos: Int,
    /** Present only in a `/2` file (slice 1b); absent in a `/1` one, so its manifest is byte for byte what it was. */
    val brokers: Int? = null,
    /** Slice 2, like [brokers]: present only when the file has the list. */
    val criteria: Int? = null,
    val preferences: Int? = null,
    /** Slice 3a, after `preferences`: present only when the file has a `questions` list. */
    val questions: Int? = null,
    /** Slice 3b-1, after `questions`: present only when the file has a `viewings` list. */
    val viewings: Int? = null,
    /** Slice 4a, after `viewings`: present only when the file has the list. */
    val areas: Int? = null,
    val places: Int? = null,
    val areaNotes: Int? = null,
) {
    companion object {
        fun of(data: BackupData): BackupCounts = BackupCounts(
            data.houses.size, data.visits.size, data.photos.size,
            brokers = data.brokers?.size,
            criteria = data.criteria?.size,
            preferences = data.preferences?.size,
            questions = data.questions?.size,
            viewings = data.viewings?.size,
            areas = data.areas?.size,
            places = data.places?.size,
            areaNotes = data.areaNotes?.size,
        )
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
    /**
     * An update file (docs/11 5.28, S4b-FR-3): the rows changed after this instant (ISO-8601) only; absent on a copy
     * or a full share. Read for the Import screen's header, never to gate an import.
     */
    val sharedSince: String? = null,
    /** Who the update was made for (the name its maker typed); absent on a copy. */
    val sharedTo: String? = null,
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
    /**
     * `/2` only (slice 1b), after `photos`: null, not empty, when the copy has none, so a `/1` file has no such key
     * (`encodeDefaults` would write an empty list). Read an absent one as none ([brokerRows]).
     */
    val brokers: List<ExportBroker>? = null,
    /** `/2` only (slice 2), after `brokers`, null when the copy has none, like [brokers]. */
    val criteria: List<ExportCriterion>? = null,
    val preferences: List<ExportPreference>? = null,
    /** `/2` only (slice 3a), after `preferences`, null when the copy has none, like [brokers]. */
    val questions: List<ExportQuestion>? = null,
    /** `/2` only (slice 3b-1), after `questions`, null when the copy has none, like [brokers]. */
    val viewings: List<ExportViewing>? = null,
    /** `/2` only (slice 4a), after `viewings`, each null when the copy has none, like [brokers]. */
    val areas: List<ExportArea>? = null,
    val places: List<ExportPlace>? = null,
    val areaNotes: List<ExportAreaNote>? = null,
) {
    /** The brokers of the file, none when it has no list. */
    val brokerRows: List<ExportBroker> get() = brokers.orEmpty()

    /** The criteria and preferences of the file, none when it has no list. */
    val criterionRows: List<ExportCriterion> get() = criteria.orEmpty()
    val preferenceRows: List<ExportPreference> get() = preferences.orEmpty()

    /** The questions of the file, none when it has no list. */
    val questionRows: List<ExportQuestion> get() = questions.orEmpty()

    /** The viewings of the file, none when it has no list. */
    val viewingRows: List<ExportViewing> get() = viewings.orEmpty()

    /** The areas, places and area notes of the file, none when it has no list. */
    val areaRows: List<ExportArea> get() = areas.orEmpty()
    val placeRows: List<ExportPlace> get() = places.orEmpty()
    val areaNoteRows: List<ExportAreaNote> get() = areaNotes.orEmpty()

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
                format = BackupFormat.idFor(
                    bundle.brokers.size, bundle.houses.sumOf { it.rooms?.size ?: 0 }, bundle.criteria.size,
                    bundle.preferences.size, bundle.questions.size, bundle.houses.sumOf { it.answers?.size ?: 0 },
                    bundle.viewings.size, bundle.areas.size, bundle.places.size, bundle.areaNotes.size, bundle.hasSlice5,
                ),
                exportedAt = bundle.options.exportedAtMillis,
                houses = bundle.houses.map { it.withSortedChecklist() },
                visits = bundle.houses.flatMap { bundle.visitsOf(it) } + houseless,
                photos = bundle.houses.flatMap { bundle.photosOf(it) } +
                    bundle.photos.filter { it.houseId !in houseIds },
                brokers = bundle.brokers.takeIf { it.isNotEmpty() },
                criteria = bundle.criteria.takeIf { it.isNotEmpty() },
                preferences = bundle.preferences.takeIf { it.isNotEmpty() },
                questions = bundle.questions.takeIf { it.isNotEmpty() },
                viewings = bundle.viewings.takeIf { it.isNotEmpty() },
                areas = bundle.areas.takeIf { it.isNotEmpty() },
                places = bundle.places.takeIf { it.isNotEmpty() },
                areaNotes = bundle.areaNotes.takeIf { it.isNotEmpty() },
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
        !BackupFormat.accepts(manifest.format) -> BackupProblem.UNSUPPORTED_VERSION
        manifest.counts.houses < 0 || manifest.counts.visits < 0 || manifest.counts.photos < 0 ||
            (manifest.counts.brokers ?: 0) < 0 || (manifest.counts.criteria ?: 0) < 0 ||
            (manifest.counts.preferences ?: 0) < 0 || (manifest.counts.questions ?: 0) < 0 ||
            (manifest.counts.viewings ?: 0) < 0 || (manifest.counts.areas ?: 0) < 0 ||
            (manifest.counts.places ?: 0) < 0 || (manifest.counts.areaNotes ?: 0) < 0 -> BackupProblem.BROKEN_DATA
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
        !BackupFormat.accepts(data.format) -> BackupProblem.UNSUPPORTED_VERSION
        data.houses.any { !isValidId(it.id) } || data.visits.any { !isValidId(it.id) } -> BackupProblem.BROKEN_DATA
        data.visits.any { v -> v.houseId?.let { !isValidId(it) } ?: false } -> BackupProblem.BROKEN_DATA
        data.photos.any { !isValidId(it.id) || !isValidId(it.houseId) } -> BackupProblem.BROKEN_DATA
        data.houses.map { it.id }.toSet().size != data.houses.size -> BackupProblem.BROKEN_DATA
        data.visits.map { it.id }.toSet().size != data.visits.size -> BackupProblem.BROKEN_DATA
        data.photos.map { it.id }.toSet().size != data.photos.size -> BackupProblem.BROKEN_DATA
        // Slice 1b: a broker with a blank or oversized name or a rating outside 1..5 refuses the whole file, like a
        // bad house; a house's broker id must be a usable id, but may name a broker that is not in the file.
        data.brokerRows.any { !isValidId(it.id) || !it.toBroker().isValid } -> BackupProblem.BROKEN_DATA
        data.brokerRows.map { it.id }.toSet().size != data.brokerRows.size -> BackupProblem.BROKEN_DATA
        data.houses.any { h -> h.brokerId?.let { !isValidId(it) } ?: false } -> BackupProblem.BROKEN_DATA
        // Slice 1c: more than 30 rooms in a house, a room id used twice in it, or a room with a bad id or a value out
        // of range refuses the whole file, as the server's import does; an unknown type reads as OTHER.
        data.houses.any { h -> !roomsAreValid(h.rooms) } -> BackupProblem.BROKEN_DATA
        // Slice 2: a criterion with a bad key or a value out of range (a label on a built-in, over 60, a weight outside
        // 0..3, a minimum outside 1..5, a negative sort), a key used twice or more than 40 criteria refuse the whole file;
        // so do a preference with a bad key, a value over 500 characters or a key used twice.
        !criteriaAreValid(data.criterionRows) -> BackupProblem.BROKEN_DATA
        !preferencesAreValid(data.preferenceRows) -> BackupProblem.BROKEN_DATA
        // Slice 3a: a question with a bad id, a blank or over-long text or a negative sort, an id used twice or more than
        // 100 questions refuse the whole file; so does an answer with a bad or repeated id, a blank or over-long question,
        // an over-long answer, an unknown status or a negative sort, or a 61st answer on a house (the server's rules).
        !questionsAreValid(data.questionRows) -> BackupProblem.BROKEN_DATA
        data.houses.any { h -> !answersAreValid(h.answers) } -> BackupProblem.BROKEN_DATA
        // Slice 3b-1: a viewing with a bad id, a blank or over-long house id, no positive start, a duration outside
        // 5..480, an unknown kind or status, a reminder not in the list, an over-long text or visit id, an id used twice
        // or more than 5,000 viewings refuse the whole file (an absent or null duration, kind, status or reminder is the
        // default).
        !viewingsAreValid(data.viewingRows) -> BackupProblem.BROKEN_DATA
        // Slice 4a: an area, place or area note with a bad id, a blank or over-long name or text, a point out of range,
        // an area's radius outside 200..2000 (an absent one is 500), a note without exactly one target, an id used twice,
        // or more than 20 areas, 10 places or 200 notes refuse the whole file.
        !recordsAreValid(data.areaRows, Area.MAX_AREAS, { it.id }, { it.updatedAt }) { it.toArea()?.isValid == true } ->
            BackupProblem.BROKEN_DATA
        !recordsAreValid(data.placeRows, Place.MAX_PLACES, { it.id }, { it.updatedAt }) { it.toPlace()?.isValid == true } ->
            BackupProblem.BROKEN_DATA
        !recordsAreValid(data.areaNoteRows, AreaNote.MAX_NOTES, { it.id }, { it.updatedAt }) { it.toAreaNote().isValid } ->
            BackupProblem.BROKEN_DATA
        // Slice 5: a move-in with a date not > 0, notes over 2000, a 31st item, or an item with a bad or repeated id, a
        // blank or over-long text or a negative sort refuses the whole file; so does a photo whose meta has a room id
        // over 64 characters (or empty), more than 10 tags, a tag over 30 characters, a repeated tag (ignoring case), a
        // custom tag equal to a fixed key (any case), a caption over 200 or a negative `metaUpdatedAt`.
        data.houses.any { h -> h.moveIn?.isValid == false } -> BackupProblem.BROKEN_DATA
        data.photos.any { !it.rawMeta.isValid } -> BackupProblem.BROKEN_DATA
        else -> null
    }

    private fun <T> recordsAreValid(
        rows: List<T>, max: Int, id: (T) -> String, updatedAt: (T) -> Long, valid: (T) -> Boolean,
    ): Boolean = rows.size <= max && rows.all { updatedAt(it) >= 0 && valid(it) } && rows.map(id).toSet().size == rows.size

    private fun viewingsAreValid(rows: List<ExportViewing>): Boolean =
        rows.size <= Viewing.MAX_VIEWINGS && rows.all { it.updatedAt >= 0 && it.toViewing().isValid } && rows.map { it.id }.toSet().size == rows.size

    private fun questionsAreValid(rows: List<ExportQuestion>): Boolean =
        rows.size <= Question.MAX_QUESTIONS && rows.all { it.toQuestion().isValid } && rows.map { it.id }.toSet().size == rows.size

    private fun answersAreValid(answers: List<HouseAnswer>?): Boolean =
        answers == null ||
            (answers.size <= HouseAnswers.MAX && answers.all { it.isValid } && answers.map { it.id }.toSet().size == answers.size)

    private fun criteriaAreValid(rows: List<ExportCriterion>): Boolean =
        rows.size <= Criterion.MAX_CRITERIA && rows.all { it.toCriterion().isValid } &&
            rows.map { it.key }.toSet().size == rows.size

    private fun preferencesAreValid(rows: List<ExportPreference>): Boolean =
        rows.all { RecordRules.isValidId(it.key) && it.value.length <= Preference.MAX_VALUE } &&
            rows.map { it.key }.toSet().size == rows.size

    private fun roomsAreValid(rooms: List<HouseRoom>?): Boolean =
        rooms == null || (rooms.size <= HouseRooms.MAX && rooms.all { it.isValid } && rooms.map { it.id }.toSet().size == rooms.size)

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
