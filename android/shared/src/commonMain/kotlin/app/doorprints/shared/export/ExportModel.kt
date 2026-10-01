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

import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.Ranking
import app.doorprints.shared.model.RankedHouse
import app.doorprints.shared.model.ScoreResult
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.LengthUnit
import app.doorprints.shared.model.HouseScore
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.AreaNotes
import app.doorprints.shared.model.Distances
import app.doorprints.shared.model.HousePoint
import app.doorprints.shared.model.Place
import app.doorprints.shared.model.PlaceDistance
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Platform-neutral input of every exporter and the exact shape of a backup's `data.json` (docs/11 section 5.2).
 *
 * Android fills these from Room, the web app from IndexedDB; both then run the same [ExportRows] logic, so the two
 * apps produce the same rows for the same data. Nothing here touches a file, a database or a clock: the export
 * time is passed in ([ExportOptions.exportedAtMillis]), so the same input always gives the same output.
 *
 * Property names are part of the backup format `doorprints-backup/1` and must not be renamed; they match the API
 * DTOs (`HouseDto`, `VisitDto`) so an import can hand rows straight to the sync layer. Timestamps are epoch
 * milliseconds here (the apps' internal form) rather than the API's ISO strings, because a backup is a copy of the
 * local store, not an API payload; [ExportTime] turns them into text.
 *
 * **Reading a file (docs/schemas/README.md section 4.4).** The fields the format marks "always present" are refused
 * when a file leaves them out or writes `null`, as the server refuses them: the ones without a Kotlin default fail
 * on their own, and `status` / `source` carry a default only for Kotlin callers, so they are [Required] on the way
 * in rather than silently read as `NEW` / `MANUAL`. The one exception is `checklist`: an **absent or `null`**
 * checklist is read as `{}`, "no scores" — decided: option (b), see docs/schemas/README.md section 4.4 and ticket
 * S4-00/g. Absent falls back to the default below; `null` is handled by [LenientChecklistSerializer] on that one
 * property. `coerceInputValues` is deliberately **not** used for it, because it would also read `"status": null`
 * as `NEW`, which section 4.4 forbids. `BackupTest.checklistAbsentOrNullReadsAsNoScores` pins this on both JSON
 * decoders.
 */
@Serializable
data class ExportHouse(
    val id: String,
    val label: String,
    val address: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val lat: Double,
    val lon: Double,
    /** [app.doorprints.shared.model.HouseStatus] name: NEW, SHORTLISTED, REJECTED, TAKEN or NOT_CHOSEN. Required in a file. */
    @Required val status: String = "NEW",
    val price: Long? = null,
    val priceType: String? = null,
    val bedrooms: Int? = null,
    val rating: Int? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val listingUrl: String? = null,
    val notes: String? = null,
    /** Carpet area in sq ft (docs/11 5.30 item 1, slice 1a); absent when unknown. */
    val areaSqft: Int? = null,
    /** `GPS`, `MAP` or `APPROX`; absent for a house saved before slice 1a. */
    val locationSource: String? = null,
    /**
     * The cost, absent when every field is absent (the mappers write [HouseCost.orNull]; never `{}` in a file). An
     * empty object in a file reads as no cost, and a field out of range as unknown (`ExportHouse.toEntity`).
     */
    val cost: HouseCost? = null,
    /**
     * The rooms (slice 1c, format `/2`), in the order shown; absent for none, never `[]` (an empty list read is none).
     * Kept in a copy made without contact details: a room is not a contact.
     */
    val rooms: List<HouseRoom>? = null,
    /**
     * The questions asked (slice 3a, format `/2`), after `rooms`; absent for none, never `[]`. Kept in a copy made
     * without contact details: a question is not a contact, and a copy is the person's own data, kept whole.
     */
    val answers: List<HouseAnswer>? = null,
    /**
     * Moving in (slice 5, format `/2`), after `answers`: absent when it has no date, notes or items. Kept in a copy
     * made without contact details, like the answers.
     */
    val moveIn: MoveIn? = null,
    /**
     * The floor (S4b-BL-87, format `/2`), -5..200 with 0 the ground floor, after `moveIn`; absent when unknown, and a
     * value out of range reads as unknown (`ExportHouse.toEntity`).
     */
    val floor: Int? = null,
    /** The broker's record id (slice 1b, format `/2`); absent for a house without one and in a copy made without contacts. */
    val brokerId: String? = null,
    /** Absent or `null` in a file reads as `{}` (docs/schemas/README.md section 4.4); always written. */
    @Serializable(with = LenientChecklistSerializer::class)
    val checklist: Map<String, Int> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** The score under [scoring], exactly the one the app screens show ([HouseScore.evaluate]); see [ExportBundle.scoreOf]. */
    fun scoreResult(scoring: Scoring): ScoreResult = HouseScore.evaluate(checklist, rating, scoring)
}

/**
 * A broker in a `/2` backup (slice 1b): the record's id and its payload keys in the format's order (docs/schemas
 * README 3.4), and `updatedAt` in epoch milliseconds, so a backup merges brokers by id like houses.
 */
@Serializable
data class ExportBroker(
    val id: String,
    val name: String,
    val phone: String? = null,
    val agency: String? = null,
    val feeTerms: String? = null,
    val notes: String? = null,
    val rating: Int? = null,
    val updatedAt: Long,
) {
    fun toBroker() = Broker(name, phone, agency, feeTerms, notes, rating)

    /** The name with " (agency)" when there is one, as the `broker` column and a house page write it. */
    val label: String get() = toBroker().label

    companion object {
        fun of(id: String, broker: Broker, updatedAt: Long) = ExportBroker(
            id, broker.name, broker.phone, broker.agency, broker.feeTerms, broker.notes, broker.rating, updatedAt,
        )
    }
}

/**
 * A criterion in a `/2` backup (slice 2): the record's key and its payload keys in the format's order (docs/schemas
 * README 3.6), `archived` only when true, and `updatedAt` in epoch milliseconds, so a backup merges criteria by key.
 */
@Serializable
data class ExportCriterion(
    val key: String,
    val label: String? = null,
    val weight: Int,
    val mustHave: Boolean,
    val minScore: Int,
    val sort: Int,
    /** Written only when true (`null` otherwise, which the format leaves out). */
    val archived: Boolean? = null,
    val updatedAt: Long,
) {
    fun toCriterion() = Criterion(key, label, weight, mustHave, minScore, sort, archived == true)

    companion object {
        fun of(c: Criterion, updatedAt: Long) = ExportCriterion(
            c.key, c.label, c.weight, c.mustHave, c.minScore, c.sort, c.archived.takeIf { it }, updatedAt,
        )
    }
}

/**
 * A question of the bank in a `/2` backup (slice 3a): the record's id and its payload keys in the format's order
 * (`text`, `category`, `appliesTo`, `defaultOn`, `sort`, `archived` only when true) and `updatedAt` in epoch
 * milliseconds, so a backup merges questions by id with the last write winning.
 */
@Serializable
data class ExportQuestion(
    val id: String,
    val text: String,
    val category: String,
    val appliesTo: String,
    val defaultOn: Boolean,
    val sort: Int,
    /** Written only when true (`null` otherwise, which the format leaves out). */
    val archived: Boolean? = null,
    val updatedAt: Long,
) {
    fun toQuestion() = Question(id, text, category, appliesTo, defaultOn, sort, archived == true)

    companion object {
        fun of(q: Question, updatedAt: Long) = ExportQuestion(
            q.id, q.text, q.category, q.appliesTo, q.defaultOn, q.sort, q.archived.takeIf { it }, updatedAt,
        )
    }
}

/**
 * A viewing in a `/2` backup (slice 3b-1): the record's id and its payload keys in the format's order (`houseId`,
 * `startsAt`, `durationMin`, `kind`, `status`, `remindMin`, then `huntReminder` only when true and `withWhom`, `notes`,
 * `visitId` only when set) and `updatedAt` in epoch milliseconds, so a backup merges viewings by id with the last write
 * winning. Kinds and statuses stay text here, so a file with an unknown one can be refused ([Viewing.isValid]).
 */
@Serializable
data class ExportViewing(
    val id: String,
    val houseId: String = "",
    val startsAt: Long = 0L,
    // These four read an absent or `null` value as the default (the server's reader agrees); a present bad one is refused.
    val durationMin: Int? = Viewing.DEFAULT_DURATION,
    val kind: String? = "FIRST",
    val status: String? = "PLANNED",
    val remindMin: Int? = Viewing.DEFAULT_REMIND,
    /** Written only when true (`null` otherwise, which the format leaves out). */
    val huntReminder: Boolean? = null,
    val withWhom: String? = null,
    val notes: String? = null,
    val visitId: String? = null,
    val updatedAt: Long,
) {
    fun toViewing() = Viewing(
        id, houseId, startsAt, durationMin ?: Viewing.DEFAULT_DURATION, kind ?: "FIRST", status ?: "PLANNED",
        remindMin ?: Viewing.DEFAULT_REMIND, huntReminder == true, withWhom, notes, visitId,
    )

    companion object {
        fun of(v: Viewing, updatedAt: Long) = ExportViewing(
            v.id, v.houseId, v.startsAt, v.durationMin, v.kind, v.status, v.remindMin, v.huntReminder.takeIf { it },
            v.withWhom?.ifEmpty { null }, v.notes?.ifEmpty { null }, v.visitId, updatedAt,
        )
    }
}

/**
 * A hunting area in a `/2` backup (slice 4a): `id, name, lat, lon, radiusM`, `enabled` only when false, `updatedAt`.
 * An absent radius reads as 500 (the server agrees); a present one out of range is refused ([Area.isValid]).
 */
@Serializable
data class ExportArea(
    val id: String,
    val name: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusM: Int? = Area.DEFAULT_RADIUS,
    /** Written only when false (`null` otherwise, which the format leaves out). */
    val enabled: Boolean? = null,
    val updatedAt: Long,
) {
    /** The area, or null without a point (such a row is refused). */
    fun toArea(): Area? {
        if (lat == null || lon == null) return null
        return Area(id, name, lat, lon, radiusM ?: Area.DEFAULT_RADIUS, enabled != false)
    }

    companion object {
        fun of(a: Area, updatedAt: Long) =
            ExportArea(a.id, a.name, a.lat, a.lon, a.radiusM, false.takeIf { !a.enabled }, updatedAt)
    }
}

/** A place in a `/2` backup (slice 4a): `id, name, lat, lon, updatedAt`. */
@Serializable
data class ExportPlace(
    val id: String,
    val name: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val updatedAt: Long,
) {
    fun toPlace(): Place? = if (lat == null || lon == null) null else Place(id, name, lat, lon)

    companion object {
        fun of(p: Place, updatedAt: Long) = ExportPlace(p.id, p.name, p.lat, p.lon, updatedAt)
    }
}

/** An area note in a `/2` backup (slice 4a): `id`, `areaId` or `street`, `text`, `updatedAt`. */
@Serializable
data class ExportAreaNote(
    val id: String,
    val areaId: String? = null,
    val street: String? = null,
    val text: String = "",
    val updatedAt: Long,
) {
    fun toAreaNote() = AreaNote(id, areaId, street, text, updatedAt)

    companion object {
        fun of(n: AreaNote, updatedAt: Long) = ExportAreaNote(n.id, n.areaId, n.street, n.text, updatedAt)
    }
}

/**
 * A deletion an update file carries (S4b-BL-82, docs/schemas §3.13): the `kind` of row (only [HOUSE] so far), its
 * `id` and the `updatedAt` of the delete. Written only into an update file (`/3`), applied only by an update import,
 * when the row here is live and older; a backup restore and a copy ignore it. An unknown kind is ignored.
 */
@Serializable
data class ExportDeletion(val kind: String, val id: String, val updatedAt: Long) {
    companion object {
        const val HOUSE = "house"

        /** At most this many deletions in one file; more refuses it. */
        const val MAX = 20_000
    }
}

/** A preference in a `/2` backup (slice 2): its key, its value (≤ 500) and `updatedAt`; merged by key. */
@Serializable
data class ExportPreference(val key: String, val value: String, val updatedAt: Long)

/**
 * `checklist` on the way in: a JSON `null` reads as an empty map, exactly like an absent key does through the
 * property's default (docs/schemas/README.md section 4.4, S4-00/g). On the way out it is the plain map serializer,
 * so a backup's bytes do not change.
 *
 * It lives on this one property, not in the shared `Json` configuration: `coerceInputValues = true` would also turn
 * a `null` `status` or `source` into its Kotlin default, and those must stay refused. It works the same through the
 * streaming decoder (`decodeFromString`, the ZIP path) and the tree decoder (`decodeFromJsonElement`, the bare
 * `data.json` path), because both answer `decodeNotNullMark` for the value being read.
 */
object LenientChecklistSerializer : KSerializer<Map<String, Int>> {
    private val delegate: KSerializer<Map<String, Int>> = MapSerializer(String.serializer(), Int.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, Int>) =
        encoder.encodeSerializableValue(delegate, value)

    override fun deserialize(decoder: Decoder): Map<String, Int> =
        decoder.decodeSerializableValue(delegate.nullable) ?: emptyMap()
}

@Serializable
data class ExportVisit(
    val id: String,
    val houseId: String? = null,
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val arrivedAt: Long,
    val leftAt: Long? = null,
    /** [app.doorprints.shared.model.VisitSource] name: AUTO or MANUAL. Required in a file, like [ExportHouse.status]. */
    @Required val source: String = "MANUAL",
    val updatedAt: Long,
) {
    /** Whole minutes spent at the house, or null while the visit has no end. */
    val minutes: Long? get() = leftAt?.let { (it - arrivedAt).coerceAtLeast(0L) / 60_000L }
}

/**
 * A photo's metadata. [fileName] is the name inside a backup ZIP's `photos/` folder and the name used by the
 * Markdown and CSV copies; the bytes never travel through this model (they would not fit in memory).
 */
@Serializable
data class ExportPhoto(
    val id: String,
    val houseId: String,
    val fileName: String,
    val createdAt: Long,
    /**
     * The photo's metadata (slice 5, format `/2`), each written only when set: a room id of the house (may dangle),
     * the tags (never `[]`), the caption and when they were last edited (epoch ms, > 0), on which an import's last
     * write wins.
     */
    val roomId: String? = null,
    val tags: List<String>? = null,
    val caption: String? = null,
    val metaUpdatedAt: Long? = null,
) {
    /** The meta as the rules read it (coerced: an import writes only what a reader keeps). */
    val meta: PhotoMeta get() = PhotoMeta.coerced(roomId, tags, caption, metaUpdatedAt)

    /** The meta exactly as the file has it, for the check that refuses a bad file ([PhotoMeta.isValid]). */
    val rawMeta: PhotoMeta get() = PhotoMeta(roomId, tags.orEmpty(), caption, metaUpdatedAt ?: 0L)

    /** True when the file row carries any meta key: such a photo makes the file `/2`. */
    val hasMeta: Boolean get() = roomId != null || !tags.isNullOrEmpty() || caption != null || (metaUpdatedAt ?: 0L) > 0
}

/** Which houses go into the copy. */
enum class ExportScope { ALL, SHORTLISTED, SELECTED }

/** Which houses' photos go into the copy. */
enum class PhotoScope { ALL, SHORTLISTED, NONE }

/**
 * The user's choices on the export screen (docs/11 section 5.2, "Options").
 *
 * [utcOffsetMinutes] and [exportedAtMillis] are supplied by the platform so the pure code has no clock and no time
 * zone database: the same options and data always give byte-identical output, which is what the golden tests pin.
 */
data class ExportOptions(
    val scope: ExportScope = ExportScope.ALL,
    /** Only used with [ExportScope.SELECTED]. */
    val selectedIds: Set<String> = emptySet(),
    /** Off leaves REJECTED houses out; it has no effect on [ExportScope.SHORTLISTED], which has none anyway. */
    val includeRejected: Boolean = true,
    val photos: PhotoScope = PhotoScope.ALL,
    /** Contact name and phone: the default is to include them, with a warning on the cover page. */
    val includeContacts: Boolean = true,
    /** Output language of headings and labels: en, hi, ta or te. */
    val language: String = "en",
    val utcOffsetMinutes: Int = 0,
    val exportedAtMillis: Long = 0L,
    /**
     * Sharing updates (docs/11 5.28, S4b-FR-3): only the rows changed after this instant (epoch ms; `updatedAt` for
     * houses and visits, `createdAt` or, since slice 5, `metaUpdatedAt` for photos, plus every photo of a changed
     * house). Null: everything, a copy.
     */
    val since: Long? = null,
    /** Who the update is for (a name the person typed), written into the manifest; null for a copy. */
    val sharedTo: String? = null,
    /**
     * The unit the rooms' sizes are written in (slice 1c): the length setting of the device that makes the copy
     * (`units.length`), not an export option and not in the manifest. Ignored by a backup's `data.json` (centimetres).
     */
    val lengthUnit: LengthUnit = LengthUnit.FT,
) {
    /** True for an update file (5.28), whose name is `Doorprints-updates-<date>` and whose manifest says who it is for. */
    val isUpdate: Boolean get() = since != null || sharedTo != null
}

/**
 * The data a copy is built from, already filtered, ordered and (when contacts are left out) redacted.
 *
 * Order is fixed: houses by `createdAt` then `id`, visits by `arrivedAt` then `id`, photos by `createdAt` then
 * `id`, each list sorted as a whole — the order of the CSV and XLSX tables. A backup's `data.json` regroups visits
 * and photos by house ([BackupData.of]). Deleted rows (tombstones) are never exported.
 *
 * [unlinkedVisits] are the visits that belong to **no** house (`houseId == null`): Hunt mode records a dwell at a
 * place that is not a house yet that way, and the user may turn it into a house later. They are not about any
 * house in the copy, so the readable copies and the tables (CSV, XLSX, HTML, Markdown, PDF) leave them out, as the
 * web copy does; only the JSON backup carries them ([BackupData.of], last, as docs/schemas/README.md section 5
 * orders rows whose house is not in the file). Without them a backup restored after a lost phone silently lost
 * that history. Filled for [ExportScope.ALL] only: a shortlist or a hand-picked set is a copy of *those* houses.
 */
data class ExportBundle(
    val options: ExportOptions,
    val houses: List<ExportHouse>,
    val visits: List<ExportVisit>,
    val photos: List<ExportPhoto>,
    val unlinkedVisits: List<ExportVisit> = emptyList(),
    /**
     * The brokers in the copy (slice 1b), ordered by `updatedAt` then `id`; empty without contact details. A copy that
     * has some is a `/2` backup, gets a `broker` column, a house page row, a Brokers section and `brokers.csv`.
     */
    val brokers: List<ExportBroker> = emptyList(),
    /**
     * The criterion and preference records in the copy (slice 2), each ordered by `updatedAt` then key: written to a
     * `/2` backup's lists and the `criteria` table. Kept in a copy without contact details (they are not contacts).
     */
    val criteria: List<ExportCriterion> = emptyList(),
    val preferences: List<ExportPreference> = emptyList(),
    /** The effective scoring every score, coverage and ranking of the copy uses: all the records merged with the defaults. */
    val scoring: Scoring = Scoring.DEFAULT,
    /**
     * The question records in the copy (slice 3a), ordered by `updatedAt` then id: a `/2` backup's `questions` list.
     * Kept in a copy without contact details. No readable table: the bank is settings and travels in the backup.
     */
    val questions: List<ExportQuestion> = emptyList(),
    /**
     * The viewing records in the copy (slice 3b-1), ordered by `updatedAt` then id: a `/2` backup's `viewings` list, a
     * Viewings table on each house page, `viewings.csv` and a Viewings sheet. Without contact details `withWhom` is blanked.
     */
    val viewings: List<ExportViewing> = emptyList(),
    /**
     * The areas, places and area notes in the copy (slice 4a), each ordered by `updatedAt` then id: a `/2` backup's
     * lists, and each house page's *Area notes* and *Distances*. Kept in a copy without contact details (a place is
     * the person's own, not a contact).
     */
    val areas: List<ExportArea> = emptyList(),
    val places: List<ExportPlace> = emptyList(),
    val areaNotes: List<ExportAreaNote> = emptyList(),
    /**
     * An update file's deletions (S4b-BL-82): the houses deleted after [ExportOptions.since], ordered by `updatedAt`
     * then id. Empty for a copy or a full share; only `data.json` carries them (a readable copy has nothing to show).
     */
    val deletions: List<ExportDeletion> = emptyList(),
) {
    val strings: ExportStrings = ExportStrings.of(options.language)

    private val liveAreas: List<Area> = areas.mapNotNull { it.toArea() }
    private val areaNames: Map<String, String> = liveAreas.associate { it.id to it.name }
    private val notes: List<AreaNote> = areaNotes.map { it.toAreaNote() }
    private val placeList: List<Place> = places.mapNotNull { it.toPlace() }

    /** The notes that reach [house] ([AreaNotes.reaching]), newest first. */
    fun areaNotesOf(house: ExportHouse): List<AreaNote> =
        if (notes.isEmpty()) emptyList() else AreaNotes.reaching(house.point(), liveAreas, notes)

    /** Where a note comes from: its area's name, or its street. */
    fun sourceOf(note: AreaNote): String = note.areaId?.let { areaNames[it] } ?: note.street.orEmpty()

    /** The distances from [house] to the copy's places, nearest first; none without a point. */
    fun distancesOf(house: ExportHouse): List<PlaceDistance> =
        if (placeList.isEmpty()) emptyList() else Distances.nearestFirst(Distances.toPlaces(house.point(), placeList))

    private fun ExportHouse.point() = HousePoint(lat, lon, street, locationSource)

    private val viewingsByHouse: Map<String, List<ExportViewing>> = viewings.groupBy { it.houseId }

    /**
     * A house's viewings as its page lists them, as the web's `viewingsForCopy`: the upcoming PLANNED ones (from the
     * copy's own [ExportOptions.exportedAtMillis], so a copy never reads a clock) soonest first, then the rest newest
     * first (the reverse of `startsAt`, then id).
     */
    fun viewingsOf(house: ExportHouse): List<ExportViewing> {
        val asc = viewingsByHouse[house.id].orEmpty().sortedWith(compareBy({ it.startsAt }, { it.id }))
        val now = options.exportedAtMillis
        val upcoming = asc.filter { (it.status ?: "PLANNED") == "PLANNED" && it.startsAt >= now }
        return upcoming + (asc - upcoming.toSet()).reversed()
    }

    private val scores: Map<String, ScoreResult> = houses.associate { it.id to it.scoreResult(scoring) }

    /** A house's score under the copy's [scoring]. */
    fun scoreOf(house: ExportHouse): ScoreResult = scores[house.id] ?: house.scoreResult(scoring)

    /** The overall score, null when nothing is scored. */
    fun overallOf(house: ExportHouse): Double? = scoreOf(house).overall

    /**
     * A criterion's name in the copy's language: a custom one's own label, a built-in's translated name, and the key
     * itself for a key the scoring does not know (a newer app's).
     */
    fun criterionLabel(key: String): String = scoring[key]?.label ?: strings.check(key)

    /** True when a house of the copy misses a must-have: the ranking table then has a Must-haves column. */
    val anyMissedMustHave: Boolean get() = scores.values.any { it.missedMustHave }

    /** True when the copy holds a criterion or preference record: the scoring is not the default one. */
    val hasScoringRecords: Boolean get() = criteria.isNotEmpty() || preferences.isNotEmpty()

    private val brokersById: Map<String, ExportBroker> = brokers.associateBy { it.id }
    private val housesByBroker: Map<String, List<ExportHouse>> =
        houses.filter { it.brokerId != null }.groupBy { it.brokerId!! }

    /** The broker a house names, if it is in this copy (a dangling id reads as none). */
    fun brokerOf(house: ExportHouse): ExportBroker? = house.brokerId?.let { brokersById[it] }

    /** True when a house of the copy has a room (slice 1c): the copy then has `rooms.csv`, a Rooms sheet and is `/2`. */
    val hasRooms: Boolean get() = houses.any { !it.rooms.isNullOrEmpty() }

    /** True when a house of the copy has an answer (slice 3a): the copy then has `answers.csv`, an Answers sheet and is `/2`. */
    val hasAnswers: Boolean get() = houses.any { !it.answers.isNullOrEmpty() }

    /**
     * True when the copy holds something of slice 5 (docs/11 5.7, 5.24): a house TAKEN or NOT_CHOSEN, a house with a
     * move-in, or a photo with meta. The file is then `/2`.
     */
    val hasSlice5: Boolean
        get() = houses.any { it.status == HouseStatus.TAKEN.name || it.status == HouseStatus.NOT_CHOSEN.name || it.moveIn != null } ||
            photos.any { it.hasMeta }

    private val roomNames: Map<String, Map<String, HouseRoom>> =
        houses.associate { h -> h.id to h.rooms.orEmpty().associateBy { it.id } }

    /** The room of [house] a photo names, or null when it names none or one that is gone (shown as untagged). */
    fun roomOf(photo: ExportPhoto): HouseRoom? = photo.roomId?.let { roomNames[photo.houseId]?.get(it) }

    /** The houses of the copy that name [broker], in the copy's order. */
    fun housesOf(broker: ExportBroker): List<ExportHouse> = housesByBroker[broker.id].orEmpty()

    private val visitsByHouse: Map<String, List<ExportVisit>> = visits.groupBy { it.houseId ?: "" }
    private val photosByHouse: Map<String, List<ExportPhoto>> = photos.groupBy { it.houseId }

    fun visitsOf(house: ExportHouse): List<ExportVisit> = visitsByHouse[house.id].orEmpty()
    fun photosOf(house: ExportHouse): List<ExportPhoto> = photosByHouse[house.id].orEmpty()

    /** Houses best first by [Ranking] (docs/11 5.4), the order of the ranking table and the rank column on both apps. */
    val ranked: List<ExportHouse> = Ranking.sort(houses) { RankedHouse(it.id, scoreOf(it), it.price, it.updatedAt) }

    private val rankById: Map<String, Int> = ranked.withIndex().associate { (i, h) -> h.id to i + 1 }

    /** 1-based position in [ranked]; 0 for a house that is not in this copy. */
    fun rankOf(house: ExportHouse): Int = rankById[house.id] ?: 0

    /** `Doorprints-2026-09-22`, the stem every file name is built on (local date of the export). */
    val fileStem: String = ExportFormat.stem(options)

    companion object {
        /**
         * Filters, orders and redacts. Callers pass everything they have; this decides what ends up in the copy.
         * [houses], [visits] and [photos] must already have tombstones (`deleted = 1`) removed.
         */
        fun build(
            options: ExportOptions,
            houses: List<ExportHouse>,
            visits: List<ExportVisit>,
            photos: List<ExportPhoto>,
            brokers: List<ExportBroker> = emptyList(),
            criteria: List<ExportCriterion> = emptyList(),
            preferences: List<ExportPreference> = emptyList(),
            questions: List<ExportQuestion> = emptyList(),
            viewings: List<ExportViewing> = emptyList(),
            areas: List<ExportArea> = emptyList(),
            places: List<ExportPlace> = emptyList(),
            areaNotes: List<ExportAreaNote> = emptyList(),
            /** The houses deleted on this device, id to the `updatedAt` of the delete (tombstones; S4b-BL-82). */
            deletedHouses: Map<String, Long> = emptyMap(),
        ): ExportBundle {
            val since = options.since
            val inScope = houses.filter { house ->
                when (options.scope) {
                    ExportScope.ALL -> options.includeRejected || house.status != "REJECTED"
                    ExportScope.SHORTLISTED -> house.status == "SHORTLISTED"
                    ExportScope.SELECTED -> house.id in options.selectedIds
                }
            }
            // An update (5.28): the houses changed since, with every visit and photo of theirs, plus a changed visit
            // of an unchanged house in the scope (the house rides along, so the visit has somewhere to land).
            val changedHouses = if (since == null) inScope else inScope.filter { it.updatedAt > since }
            val inScopeIds = inScope.mapTo(HashSet()) { it.id }
            val changedVisitHouses = if (since == null) emptySet() else
                visits.filter { it.updatedAt > since && it.houseId != null && it.houseId in inScopeIds }
                    .mapTo(HashSet()) { it.houseId!! }
            val changedIds = changedHouses.mapTo(HashSet()) { it.id }
            val kept = (changedHouses + inScope.filter { it.id in changedVisitHouses && it.id !in changedIds })
                .map {
                    if (options.includeContacts) it else it.copy(contactName = null, contactPhone = null, brokerId = null)
                }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
            val keptIds = kept.mapTo(HashSet()) { it.id }
            val keptVisits = visits
                .filter { it.houseId != null && it.houseId in keptIds }
                .filter { since == null || it.updatedAt > since || it.houseId in changedIds }
                .sortedWith(compareBy({ it.arrivedAt }, { it.id }))
            val photoHouses = when (options.photos) {
                PhotoScope.NONE -> emptySet()
                PhotoScope.ALL -> keptIds
                PhotoScope.SHORTLISTED -> kept.filter { it.status == "SHORTLISTED" }.mapTo(HashSet()) { it.id }
            }
            val keptPhotos = photos
                .filter { it.houseId in photoHouses }
                // A photo whose meta was edited since (slice 5) is a change too.
                .filter { since == null || it.createdAt > since || (it.metaUpdatedAt ?: 0L) > since || it.houseId in changedIds }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
            val unlinked = if (options.scope == ExportScope.ALL) {
                visits.filter { it.houseId == null && (since == null || it.updatedAt > since) }
                    .sortedWith(compareBy({ it.arrivedAt }, { it.id }))
            } else {
                emptyList()
            }
            // Brokers (slice 1b): all of them for the whole set of houses (for an update, those changed since and the ones the
            // kept houses name), else the ones the kept houses name; none when contacts are left out, and then no `/2` list is written.
            val brokerIds = kept.mapNotNullTo(HashSet()) { it.brokerId }
            val keptBrokers = when {
                !options.includeContacts -> emptyList()
                options.scope == ExportScope.ALL -> brokers.filter { since == null || it.updatedAt > since || it.id in brokerIds }
                else -> brokers.filter { it.id in brokerIds }
            }.sortedWith(compareBy({ it.updatedAt }, { it.id }))
            // Criteria and preferences (slice 2): settings, not contacts, so a copy without contact details keeps them; an
            // update carries the ones changed since. The scoring is always built from all of them.
            val scoring = Scoring.of(criteria.map { it.toCriterion() }, preferences.associate { it.key to it.value })
            val keptCriteria = criteria.filter { since == null || it.updatedAt > since }
                .sortedWith(compareBy({ it.updatedAt }, { it.key }))
            val keptPreferences = preferences.filter { since == null || it.updatedAt > since }
                .sortedWith(compareBy({ it.updatedAt }, { it.key }))
            // The question bank (slice 3a): settings like the criteria, kept without contact details; an update carries
            // the ones changed since.
            val keptQuestions = questions.filter { since == null || it.updatedAt > since }
                .sortedWith(compareBy({ it.updatedAt }, { it.id }))
            // Viewings (slice 3b-1): all of them for the whole set of houses (one of a house that is gone too), else
            // those of the kept houses; an update carries the ones changed since. `withWhom` is contact data.
            val keptViewings = viewings
                .filter { options.scope == ExportScope.ALL || it.houseId in keptIds }
                .filter { since == null || it.updatedAt > since }
                .map { if (options.includeContacts) it else it.copy(withWhom = null) }
                .sortedWith(compareBy({ it.updatedAt }, { it.id }))
            // Areas, places and area notes (slice 4a): the person's own settings, kept in every copy whatever its scope
            // and contact choice; an update carries the ones changed since.
            val keptAreas = areas.filter { since == null || it.updatedAt > since }.sortedWith(compareBy({ it.updatedAt }, { it.id }))
            val keptPlaces = places.filter { since == null || it.updatedAt > since }.sortedWith(compareBy({ it.updatedAt }, { it.id }))
            val keptNotes = areaNotes.filter { since == null || it.updatedAt > since }.sortedWith(compareBy({ it.updatedAt }, { it.id }))
            // An update's deletions (S4b-BL-82): the houses deleted since, whatever the scope (a house that is gone has
            // no status worth filtering on); a copy or a full share has none. A row that is live again is not one.
            val liveIds = houses.mapTo(HashSet()) { it.id }
            val deletions = if (since == null) emptyList() else deletedHouses
                .filter { (id, at) -> at > since && id !in liveIds }
                .map { (id, at) -> ExportDeletion(ExportDeletion.HOUSE, id, at) }
                .sortedWith(compareBy({ it.updatedAt }, { it.id }))
            return ExportBundle(
                options, kept, keptVisits, keptPhotos, unlinked, keptBrokers, keptCriteria, keptPreferences, scoring,
                keptQuestions, keptViewings, keptAreas, keptPlaces, keptNotes, deletions,
            )
        }
    }
}

/**
 * Dates and times for export text, without kotlinx-datetime and without a time-zone database.
 *
 * The platform passes the offset it wants the copy to read in (Android: the phone's current UTC offset in
 * minutes, +330 for IST); the instant is shifted by it and then printed from `kotlin.time.Instant`'s ISO-8601
 * form. A fixed offset is deliberate: a copy is a snapshot, and a DST rule that changes later must not change what
 * a golden test or an already-saved file says.
 */
object ExportTime {
    private fun isoLocal(epochMillis: Long, utcOffsetMinutes: Int): String {
        val seconds = (epochMillis + utcOffsetMinutes * 60_000L).floorDiv(1000L)
        return Instant.fromEpochSeconds(seconds).toString()
    }

    /** `2026-09-22`. */
    fun date(epochMillis: Long, utcOffsetMinutes: Int): String =
        isoLocal(epochMillis, utcOffsetMinutes).substring(0, 10)

    /** `2026-09-22 10:15`. */
    fun dateTime(epochMillis: Long, utcOffsetMinutes: Int): String =
        isoLocal(epochMillis, utcOffsetMinutes).substring(0, 16).replace('T', ' ')

    /** `+05:30`, printed on the cover so a reader knows which clock the times are on. */
    fun offsetLabel(utcOffsetMinutes: Int): String {
        val sign = if (utcOffsetMinutes < 0) "-" else "+"
        val total = if (utcOffsetMinutes < 0) -utcOffsetMinutes else utcOffsetMinutes
        return sign + two(total / 60) + ":" + two(total % 60)
    }

    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()
}
