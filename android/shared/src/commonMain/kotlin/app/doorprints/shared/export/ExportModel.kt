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
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseScore
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
    /** [app.doorprints.shared.model.HouseStatus] name: NEW, SHORTLISTED or REJECTED. Required in a file. */
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
    /** The broker's record id (slice 1b, format `/2`); absent for a house without one and in a copy made without contacts. */
    val brokerId: String? = null,
    /** Absent or `null` in a file reads as `{}` (docs/schemas/README.md section 4.4); always written. */
    @Serializable(with = LenientChecklistSerializer::class)
    val checklist: Map<String, Int> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 0–5 overall score, exactly the one the app screens show ([HouseScore.of]); null when nothing is scored. */
    val score: Double? get() = HouseScore.of(checklist, rating)
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
)

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
     * houses and visits, `createdAt` for photos, plus every photo of a changed house). Null: everything, a copy.
     */
    val since: Long? = null,
    /** Who the update is for (a name the person typed), written into the manifest; null for a copy. */
    val sharedTo: String? = null,
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
) {
    val strings: ExportStrings = ExportStrings.of(options.language)

    private val brokersById: Map<String, ExportBroker> = brokers.associateBy { it.id }
    private val housesByBroker: Map<String, List<ExportHouse>> =
        houses.filter { it.brokerId != null }.groupBy { it.brokerId!! }

    /** The broker a house names, if it is in this copy (a dangling id reads as none). */
    fun brokerOf(house: ExportHouse): ExportBroker? = house.brokerId?.let { brokersById[it] }

    /** The houses of the copy that name [broker], in the copy's order. */
    fun housesOf(broker: ExportBroker): List<ExportHouse> = housesByBroker[broker.id].orEmpty()

    private val visitsByHouse: Map<String, List<ExportVisit>> = visits.groupBy { it.houseId ?: "" }
    private val photosByHouse: Map<String, List<ExportPhoto>> = photos.groupBy { it.houseId }

    fun visitsOf(house: ExportHouse): List<ExportVisit> = visitsByHouse[house.id].orEmpty()
    fun photosOf(house: ExportHouse): List<ExportPhoto> = photosByHouse[house.id].orEmpty()

    /** Houses best-scored first, the order of the HTML/PDF ranking table and of Compare on both apps. */
    val ranked: List<ExportHouse> = houses.sortedWith(
        compareByDescending<ExportHouse> { HouseScore.rankKey(it.score) }.thenBy { it.createdAt }.thenBy { it.id }
    )

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
                .filter { since == null || it.createdAt > since || it.houseId in changedIds }
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
            return ExportBundle(options, kept, keptVisits, keptPhotos, unlinked, keptBrokers)
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
