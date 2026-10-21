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

import app.doorprints.shared.country.Country
import app.doorprints.shared.model.CostSummary
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.LengthUnit
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.RoomSizes
import kotlin.math.floor

/**
 * One value in an exported table. The type is kept (rather than turning everything into a string straight away)
 * because XLSX wants typed cells — a price must be a number a spreadsheet can total, a date a real date — while
 * CSV wants the machine form and HTML/Markdown/PDF want the reader's form (₹12,50,000).
 */
sealed interface Cell {
    /** Nothing recorded. Renders as an empty CSV/XLSX cell and as "—" in a readable copy. */
    data object Blank : Cell

    data class Text(val value: String) : Cell

    /** A plain number with a fixed number of decimals (score 4.5, latitude 12.978321). */
    data class Num(val value: Double, val decimals: Int = 0) : Cell

    /** A whole count (bedrooms, visits, minutes). */
    data class Count(val value: Long) : Cell

    /** Rupees, always whole (the app never stores paise). */
    data class Money(val amount: Long) : Cell

    /** An instant; rendered in the export's fixed UTC offset. */
    data class Stamp(val epochMillis: Long) : Cell
}

/**
 * A table of the copy: one CSV file, one XLSX sheet, one Markdown/HTML table.
 *
 * [name] is language-neutral (the CSV file name and the sheet name); [title] is the translated heading.
 */
data class ExportTable(
    val name: String,
    val title: String,
    val columns: List<String>,
    val rows: List<List<Cell>>,
)

/**
 * **The format-independent model → rows logic** (Sprint 4a, S4-02). Every exporter on Android and on the web reads
 * its rows from here, so a CSV, an XLSX sheet and the HTML table always show the same values in the same order,
 * and the two apps agree cell for cell. `ExportRowsTest` and the golden files in `commonTest` pin the output.
 *
 * Nothing in here reads a clock, a locale or a file. Formatting is done by hand rather than with a platform
 * number formatter for the same reason: `java.text.NumberFormat` and `Intl.NumberFormat` disagree about spaces,
 * minus signs and the ₹ position, and a copy must not change when the phone's locale data is updated.
 */
object ExportRows {

    /**
     * The tables of a copy, in file order: the four, then `brokers` when the copy has brokers (slice 1b), `rooms`
     * when a house of it has a room (slice 1c), `criteria` when it has a criterion record (slice 2) and `answers` when a
     * house of it has a question asked (slice 3a), then `viewings` when it has a viewing (slice 3b-1).
     */
    fun tables(bundle: ExportBundle): List<ExportTable> =
        // The kind's `copyOrder` brings its blocks' tables forward (docs/03 section 18.29); a kind with none keeps the fixed order.
        TableOrder.inCopyOrder(fixedTables(bundle), bundle.voice?.kind)

    private fun fixedTables(bundle: ExportBundle): List<ExportTable> {
        // A copy in a kind's voice (S4b-BL-266) leaves out the tables of what its kind hides: a school has no Scores table,
        // whose rows would all be empty, and no Brokers, Rooms, Criteria, Answers or Viewings. A copy without a voice has them all.
        fun shows(ref: String) = bundle.shows(null, ref)
        return listOf(houses(bundle)) + listOfNotNull(if (shows("section.checklist")) scores(bundle) else null) +
            listOf(visits(bundle), photos(bundle)) +
            listOfNotNull(
                if (bundle.brokers.isEmpty() || !shows("field.brokerId")) null else brokers(bundle),
                if (bundle.hasRooms && shows("section.rooms")) rooms(bundle) else null,
                if (bundle.criteria.isEmpty() || !shows("section.checklist")) null else criteria(bundle),
                if (bundle.hasAnswers && shows("section.questions")) answers(bundle) else null,
                if (bundle.viewings.isEmpty() || !shows("section.viewings")) null else viewings(bundle),
            )
    }

    /** One column of the Houses table: the place of the kind it needs (null: always), its heading and its cell. */
    private class Col(val ref: String?, val head: String, val cell: (ExportHouse) -> Cell)

    fun houses(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val contacts = bundle.options.includeContacts
        // The sixteen cost cells of a row are worked out once, for the row's first cost column.
        var costFor: ExportHouse? = null
        var costCache: List<Cell> = emptyList()
        fun costOf(h: ExportHouse): List<Cell> {
            if (costFor !== h) { costCache = costCells(h, s); costFor = h }
            return costCache
        }
        val cols = buildList {
            add(Col(null, s["col.rank"]) { Cell.Count(bundle.rankOf(it).toLong()) })
            add(Col(null, s["col.label"]) { Cell.Text(it.label) })
            add(Col("field.status", s["col.status"]) { Cell.Text(s.status(it.status)) })
            add(Col("section.checklist", s["col.score"]) { h -> bundle.overallOf(h)?.let { Cell.Num(it, 1) } ?: Cell.Blank })
            add(Col("field.money", s["col.price"]) { h -> h.price?.let { Cell.Money(it) } ?: Cell.Blank })
            add(Col("field.money", s["col.priceType"]) { h -> if (h.price == null) Cell.Blank else Cell.Text(s.priceType(h.priceType)) })
            add(Col("field.bedrooms", s["col.bedrooms"]) { h -> h.bedrooms?.let { Cell.Count(it.toLong()) } ?: Cell.Blank })
            add(Col("field.rating", s["col.rating"]) { h -> h.rating?.let { Cell.Count(it.toLong()) } ?: Cell.Blank })
            add(Col("field.address", s["col.address"]) { text(it.address) })
            add(Col("field.street", s["col.street"]) { text(it.street) })
            add(Col("field.locality", s["col.locality"]) { text(it.locality) })
            add(Col("field.location", s["col.lat"]) { Cell.Num(it.lat, 6) })
            add(Col("field.location", s["col.lon"]) { Cell.Num(it.lon, 6) })
            if (contacts) {
                add(Col("field.contactName", s["col.contactName"]) { text(it.contactName) })
                add(Col("field.contactPhone", s["col.contactPhone"]) { text(it.contactPhone) })
                add(Col("field.brokerId", s["col.broker"]) { text(bundle.brokerOf(it)?.label) })
            }
            add(Col("field.listingUrl", s["col.listingUrl"]) { text(it.listingUrl) })
            add(Col("field.notes", s["col.notes"]) { text(it.notes) })
            COST_COLUMN_KEYS.forEachIndexed { i, key -> add(Col(COST_COLUMN_REFS[i], s[key]) { costOf(it)[i] }) }
            // Slice 1c: the number of rooms, right after the cost per sq ft; S4b-BL-87: the floor after it.
            add(Col("section.rooms", s["col.rooms"]) { h -> h.rooms?.takeIf { it.isNotEmpty() }?.let { Cell.Count(it.size.toLong()) } ?: Cell.Blank })
            add(Col("field.floor", s["col.floor"]) { h -> h.floor?.let { Cell.Count(it.toLong()) } ?: Cell.Blank })
            // Kinds PR 6 (S4b-BL-210): a kind's typed extras after the floor ([KindCopy]); none in a copy of houses only.
            for (c in bundle.extraColumns) add(Col(null, c.label) { h -> bundle.extrasOf(h).firstOrNull { it.key == c.key }?.cell ?: Cell.Blank })
            add(Col(null, s["col.visits"]) { Cell.Count(bundle.visitsOf(it).size.toLong()) })
            add(Col(null, s["col.photos"]) { Cell.Count(bundle.photosOf(it).size.toLong()) })
            add(Col(null, s["col.createdAt"]) { Cell.Stamp(it.createdAt) })
            add(Col(null, s["col.updatedAt"]) { Cell.Stamp(it.updatedAt) })
            add(Col(null, s["col.id"]) { Cell.Text(it.id) })
        }
        // A copy in a kind's voice leaves a column its kind hides out entirely, not blank (S4b-BL-266).
        val shown = cols.filter { it.ref == null || bundle.shows(null, it.ref) }
        return ExportTable("houses", s["table.houses"], shown.map { it.head }, bundle.houses.map { h -> shown.map { it.cell(h) } })
    }

    /**
     * One row per scored checklist item, in the shared display order (`Checklist.keys`), then any other key the house
     * carries, alphabetically. The label is a custom criterion's own (slice 2), a built-in's translated name, or the key.
     */
    fun scores(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(s["col.house"], s["col.item"], s["col.itemLabel"], s["col.score"], s["col.houseId"])
        val rows = bundle.houses.flatMap { h ->
            orderedChecklistKeys(h).map { key ->
                listOf(
                    Cell.Text(h.label),
                    Cell.Text(key),
                    Cell.Text(bundle.criterionLabel(key)),
                    Cell.Count(h.checklist.getValue(key).toLong()),
                    Cell.Text(h.id),
                )
            }
        }
        return ExportTable("scores", s["table.scores"], columns, rows)
    }

    fun visits(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(
            s["col.house"], s["col.arrivedAt"], s["col.leftAt"], s["col.minutes"], s["col.source"],
            s["col.street"], s["col.lat"], s["col.lon"], s["col.houseId"], s["col.id"],
        )
        val labels = bundle.houses.associate { it.id to it.label }
        val rows = bundle.visits.map { v ->
            listOf(
                Cell.Text(labels[v.houseId] ?: ""),
                Cell.Stamp(v.arrivedAt),
                v.leftAt?.let { Cell.Stamp(it) } ?: Cell.Blank,
                v.minutes?.let { Cell.Count(it) } ?: Cell.Blank,
                Cell.Text(s.source(v.source)),
                text(v.street),
                Cell.Num(v.lat, 6), Cell.Num(v.lon, 6),
                Cell.Text(v.houseId ?: ""),
                Cell.Text(v.id),
            )
        }
        return ExportTable("visits", s["table.visits"], columns, rows)
    }

    /** The brokers (slice 1b): one row each, with the count of the copy's houses that name it. */
    fun brokers(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(
            s["col.name"], s["col.phone"], s["col.agency"], s["col.feeTerms"], s["col.notes"], s["col.rating"],
            s["col.houses"], s["col.id"],
        )
        val rows = bundle.brokers.map { b ->
            listOf(
                Cell.Text(b.name), text(b.phone), text(b.agency), text(b.feeTerms), text(b.notes), count(b.rating),
                Cell.Count(bundle.housesOf(b).size.toLong()), Cell.Text(b.id),
            )
        }
        return ExportTable("brokers", s["table.brokers"], columns, rows)
    }

    /**
     * The criteria (slice 2), in a copy that has a criterion record: the whole effective list in its order (defaults
     * included, so the table says how every score was weighed), with the weight's translated name.
     */
    fun criteria(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(
            s["col.item"], s["col.name"], s["col.weight"], s["col.mustHave"], s["col.minScore"], s["col.archived"],
            s["col.sort"],
        )
        val rows = bundle.scoring.criteria.map { c ->
            listOf(
                Cell.Text(c.key), Cell.Text(bundle.criterionLabel(c.key)), Cell.Text(s["weight.${c.weight}"]),
                Cell.Text(s[if (c.mustHave) "yes" else "no"]), Cell.Count(c.minScore.toLong()),
                Cell.Text(s[if (c.archived) "yes" else "no"]), Cell.Count(c.sort.toLong()),
            )
        }
        return ExportTable("criteria", s["table.criteria"], columns, rows)
    }

    /**
     * The house page's scoring lines (slice 2), after the score: "Scored 3 of 10 that matter" when a criterion that
     * counts is scored, and the must-haves the house missed by name.
     */
    fun scoringLines(h: ExportHouse, bundle: ExportBundle): List<Pair<String, String>> {
        val s = bundle.strings
        val r = bundle.scoreOf(h)
        if (!bundle.shows(h, "section.checklist")) return emptyList()
        return buildList {
            if (r.scored > 0) add(s["col.coverage"] to coverageText(r.scored, r.active, s))
            if (r.failedMustHave.isNotEmpty()) {
                add(s["mustHave.missed"] to r.failedMustHave.joinToString(", ") { bundle.criterionLabel(it) })
            }
        }
    }

    /** "Scored 7 of 10 that matter" in the copy's language. */
    fun coverageText(scored: Int, active: Int, s: ExportStrings): String =
        s["coverage"].replace("{n}", scored.toString()).replace("{m}", active.toString())

    /** A score as the PDF's ranking lines show it: one decimal, "—" when none, and the mark when a must-have is missed. */
    fun rankedScore(h: ExportHouse, bundle: ExportBundle): String {
        val s = bundle.strings
        val r = bundle.scoreOf(h)
        val score = r.overall?.let { fixed(it, 1) } ?: s["none"]
        return if (r.missedMustHave) score + " · " + s["mustHave.missed"] else score
    }

    /**
     * The ranking table's columns (HTML, Markdown): rank, house, score, then **Must-haves** only when a house of the
     * copy misses one (slice 2), price, status.
     */
    fun rankingColumns(bundle: ExportBundle): List<String> {
        val s = bundle.strings
        val score = bundle.shows(null, "section.checklist")
        return listOfNotNull(
            s["col.rank"], s["col.label"], if (score) s["col.score"] else null,
            if (score && bundle.anyMissedMustHave) s["col.mustHaves"] else null,
            if (bundle.shows(null, "field.money")) s["col.price"] else null, s["col.status"],
        )
    }

    /** One row of the ranking table, in the reader's form, for [rankingColumns]; the house's label second. */
    fun rankingRow(h: ExportHouse, bundle: ExportBundle): List<String> {
        val s = bundle.strings
        val r = bundle.scoreOf(h)
        val score = bundle.shows(null, "section.checklist")
        return listOfNotNull(
            bundle.rankOf(h).toString(),
            h.label,
            if (score) r.overall?.let { fixed(it, 1) } ?: s["none"] else null,
            if (score && bundle.anyMissedMustHave) {
                if (r.missedMustHave) r.failedMustHave.joinToString(", ") { bundle.criterionLabel(it) } else s["none"]
            } else {
                null
            },
            if (bundle.shows(null, "field.money")) h.price?.let { rupees(it) } ?: s["none"] else null,
            s.status(h.status),
        )
    }

    /** The cover's rating share line value, "40%", when the copy has scoring records; null otherwise. */
    fun ratingShareText(bundle: ExportBundle): String? =
        if (bundle.hasScoringRecords && bundle.shows(null, "section.checklist")) fixed(bundle.scoring.ratingShare * 100, 0) + "%" else null

    /**
     * The rooms (slice 1c): one row per room, the houses in the copy's order and each house's rooms in the order shown.
     * Sizes in the unit of the device that made the copy ([ExportOptions.lengthUnit]): decimal feet with one decimal
     * and whole sq ft, or metres with two decimals and m² with one. A blank name is the type's translated name.
     */
    fun rooms(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val metres = bundle.options.lengthUnit == LengthUnit.M
        val columns = listOf(
            s["col.house"], s["col.roomName"], s["col.roomType"],
            s[if (metres) "col.lengthM" else "col.lengthFt"], s[if (metres) "col.widthM" else "col.widthFt"],
            s[if (metres) "col.areaSqM" else "col.areaSqFt"],
            s["col.condition"], s["col.notes"], s["col.houseId"], s["col.id"],
        )
        fun length(cm: Int?): Cell = when {
            cm == null -> Cell.Blank
            metres -> Cell.Num(cm / 100.0, 2)
            else -> Cell.Num(cm / (12 * RoomSizes.CM_PER_INCH), 1)
        }
        val rows = bundle.houses.flatMap { h ->
            h.rooms.orEmpty().map { r ->
                val area = HouseRooms.areaSqCm(r)
                listOf(
                    Cell.Text(h.label), Cell.Text(roomName(r, s)), Cell.Text(s.roomType(r.roomType.name)),
                    length(r.lengthCm), length(r.widthCm),
                    when {
                        area == null -> Cell.Blank
                        metres -> Cell.Num(RoomSizes.sqM(area), 1)
                        else -> Cell.Count(RoomSizes.sqFt(area))
                    },
                    count(r.condition), text(r.notes), Cell.Text(h.id), Cell.Text(r.id),
                )
            }
        }
        return ExportTable("rooms", s["table.rooms"], columns, rows)
    }

    /** A room as the copies name it: its own name, or its type's translated name when it has none. */
    fun roomName(r: HouseRoom, s: ExportStrings): String = r.name?.takeIf { it.isNotBlank() } ?: s.roomType(r.roomType.name)

    /** The headings of a house page's **Rooms** table (HTML, PDF, Markdown): name, size, area, condition, notes. */
    fun roomColumns(bundle: ExportBundle): List<String> {
        val s = bundle.strings
        val area = if (bundle.options.lengthUnit == LengthUnit.M) "col.areaSqM" else "col.areaSqFt"
        return listOf(s["col.roomName"], s["col.size"], s[area], s["col.condition"], s["col.notes"])
    }

    /**
     * The rows of a house page's **Rooms** table, in the reader's form ("13 ft 0 in × 12 ft 0 in", "156", "4/5"), and
     * the total of the areas as a last row when at least two rooms have both sizes; empty for a house without rooms.
     */
    fun roomRows(h: ExportHouse, bundle: ExportBundle): List<List<String>> {
        if (!bundle.shows(h, "section.rooms")) return emptyList()
        val s = bundle.strings
        val unit = bundle.options.lengthUnit
        val none = s["none"]
        val rooms = h.rooms.orEmpty()
        val rows = rooms.map { r ->
            listOf(
                roomName(r, s),
                RoomSizes.sizeText(r, unit) ?: none,
                HouseRooms.areaSqCm(r)?.let { RoomSizes.areaNumber(it, unit) } ?: none,
                r.condition?.let { "$it/5" } ?: none,
                r.notes ?: "",
            )
        }
        val (total, sized) = HouseRooms.totalAreaSqCm(rooms)
        return if (sized >= 2) rows + listOf(listOf(s["rooms.total"], "", RoomSizes.areaNumber(total, unit), "", "")) else rows
    }

    /**
     * The answers (slice 3a): one row per question asked, the houses in the copy's order and each house's answers in
     * the order shown ([HouseAnswers.ordered]: open first). Only in a copy where a house has answers.
     */
    fun answers(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(
            s["col.house"], s["col.question"], s["col.answer"], s["col.status"], s["col.houseId"], s["col.id"],
            s["col.questionId"],
        )
        val rows = bundle.houses.flatMap { h ->
            HouseAnswers.ordered(h.answers).map { a ->
                listOf(
                    Cell.Text(h.label), Cell.Text(a.text), text(a.answer), Cell.Text(s.answerStatus(a.answerStatus.name)),
                    Cell.Text(h.id), Cell.Text(a.id), text(a.questionId),
                )
            }
        }
        return ExportTable("answers", s["table.answers"], columns, rows)
    }

    /** The headings of a house page's **Questions** table (HTML, PDF, Markdown): question, answer, status. */
    fun answerColumns(bundle: ExportBundle): List<String> {
        val s = bundle.strings
        return listOf(s["col.question"], s["col.answer"], s["col.status"])
    }

    /**
     * The rows of a house page's **Questions** table, open ones first; an empty answer is [NO_ANSWER] (the three stacks
     * write the same en dash). Empty for a house without answers.
     */
    fun answerRows(h: ExportHouse, bundle: ExportBundle): List<List<String>> {
        if (!bundle.shows(h, "section.questions")) return emptyList()
        val s = bundle.strings
        return HouseAnswers.ordered(h.answers).map { a ->
            listOf(a.text, a.answer ?: NO_ANSWER, s.answerStatus(a.answerStatus.name))
        }
    }

    /** What a house page shows for a question not answered yet. */
    const val NO_ANSWER = "–"

    /**
     * The viewings (slice 3b-1), in a copy that has one: house (its label, blank for a house that is gone), when,
     * duration, kind, status, reminder minutes, with whom (blank without contact details), notes, then the house,
     * viewing and visit ids; by `startsAt`, then id, as the web's `viewingsTable`.
     */
    fun viewings(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(
            s["col.house"], s["col.when"], s["col.durationMin"], s["col.kind"], s["col.status"], s["col.remindMin"],
            s["col.withWhom"], s["col.notes"], s["col.houseId"], s["col.id"], s["col.visitId"],
        )
        val labels = bundle.houses.associate { it.id to it.label }
        val rows = bundle.viewings.sortedWith(compareBy({ it.startsAt }, { it.id })).map { e ->
            val v = e.toViewing()
            listOf(
                text(labels[v.houseId]), Cell.Stamp(v.startsAt), Cell.Count(v.durationMin.toLong()),
                Cell.Text(s.viewingKind(v.kind)), Cell.Text(s.viewingStatus(v.status)), Cell.Count(v.remindMin.toLong()),
                if (bundle.options.includeContacts) text(v.withWhom) else Cell.Blank, text(v.notes),
                Cell.Text(v.houseId), Cell.Text(v.id), text(v.visitId),
            )
        }
        return ExportTable("viewings", s["table.viewings"], columns, rows)
    }

    /** The headings of a house page's **Viewings** table: when, kind, status, notes and (with contact details) with whom. */
    fun viewingColumns(bundle: ExportBundle): List<String> {
        val s = bundle.strings
        return listOfNotNull(
            s["col.when"], s["col.kind"], s["col.status"], s["col.notes"],
            if (bundle.options.includeContacts) s["col.withWhom"] else null,
        )
    }

    /**
     * The rows of a house page's **Viewings** table in [ExportBundle.viewingsOf]'s order (upcoming PLANNED first, then
     * the rest newest first), the time in the copy's offset like the visits; an empty text is [NO_ANSWER]. Empty for a
     * house without viewings.
     */
    fun viewingRows(h: ExportHouse, bundle: ExportBundle): List<List<String>> {
        if (!bundle.shows(h, "section.viewings")) return emptyList()
        val s = bundle.strings
        return bundle.viewingsOf(h).map { e ->
            val v = e.toViewing()
            listOfNotNull(
                ExportTime.dateTime(v.startsAt, bundle.options.utcOffsetMinutes), s.viewingKind(v.kind),
                s.viewingStatus(v.status), v.notes ?: NO_ANSWER,
                if (bundle.options.includeContacts) v.withWhom ?: NO_ANSWER else null,
            )
        }
    }

    /** The headings of a house page's **Area notes** table (slice 4a): the note, and where it comes from. */
    fun areaNoteColumns(bundle: ExportBundle): List<String> = listOf(bundle.strings["col.notes"], bundle.strings["col.noteSource"])

    /**
     * The rows of a house page's **Area notes** table (slice 4a): each note that reaches the house, newest first
     * ([ExportBundle.areaNotesOf]), with its area's name or its street. Empty when none reaches it.
     */
    fun areaNoteRows(h: ExportHouse, bundle: ExportBundle): List<List<String>> =
        if (!bundle.shows(h, "section.areaNotes")) emptyList() else bundle.areaNotesOf(h).map { listOf(it.text, bundle.sourceOf(it)) }

    /** The headings of a house page's **Distances** table (slice 4a): the place and the straight-line km. */
    fun distanceColumns(bundle: ExportBundle): List<String> = listOf(bundle.strings["col.place"], bundle.strings["col.km"])

    /** The rows of a house page's **Distances** table, nearest first, the km with one decimal; empty without a point. */
    fun distanceRows(h: ExportHouse, bundle: ExportBundle): List<List<String>> =
        if (!bundle.shows(h, "section.distances")) emptyList() else bundle.distancesOf(h).map { listOf(it.place.name, it.km) }

    /**
     * One row per photo; since slice 5 the last three columns are its room (the room's name, empty when it names none or
     * one that is gone), its tags (the stored keys and texts joined with `; `) and its caption.
     */
    fun photos(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(
            s["col.house"], s["col.fileName"], s["col.createdAt"], s["col.houseId"], s["col.id"],
            s["col.room"], s["col.tags"], s["col.caption"],
        )
        val labels = bundle.houses.associate { it.id to it.label }
        val rows = bundle.photos.map { p ->
            listOf(
                Cell.Text(labels[p.houseId] ?: ""),
                Cell.Text(p.fileName),
                Cell.Stamp(p.createdAt),
                Cell.Text(p.houseId),
                Cell.Text(p.id),
                text(photoRoomName(p, bundle)),
                text(photoTagsText(p)),
                text(p.meta.caption),
            )
        }
        return ExportTable("photos", s["table.photos"], columns, rows)
    }

    /** The name of the room a photo names (slice 5): its own name or its type's; null for none or one that is gone. */
    fun photoRoomName(p: ExportPhoto, bundle: ExportBundle): String? = bundle.roomOf(p)?.let { roomName(it, bundle.strings) }

    /** A photo's tags as a copy writes them: the stored keys and texts, joined with `; `; null for none. */
    fun photoTagsText(p: ExportPhoto): String? = p.meta.tags.takeIf { it.isNotEmpty() }?.joinToString("; ")

    /**
     * The line under a photo on a house page (slice 5): its room, its tags and its caption, those it has, joined with
     * ` · `; null when it has none.
     */
    fun photoLine(p: ExportPhoto, bundle: ExportBundle): String? =
        listOfNotNull(photoRoomName(p, bundle), photoTagsText(p), p.meta.caption).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** Before a ticked move-in item and an open one, on a house page (slice 5). */
    const val TICK = "✓"
    const val OPEN = "○"

    /**
     * A house page's **Moving in** section (slice 5): the date and the notes as label and value, those it has. Empty
     * for a house without a move-in; see [moveInItems] for the list.
     */
    fun moveInLines(h: ExportHouse, bundle: ExportBundle): List<Pair<String, String>> {
        val m = h.moveIn?.takeIf { bundle.shows(h, "section.moveIn") } ?: return emptyList()
        val s = bundle.strings
        return listOfNotNull(
            m.date?.let { s["col.moveInDate"] to ExportTime.date(it, bundle.options.utcOffsetMinutes) },
            m.notes?.let { s["col.notes"] to it },
        )
    }

    /** The move-in items in the order shown, each `✓ <text>` when done or `○ <text>` when not. */
    fun moveInItems(h: ExportHouse): List<String> =
        MoveIn.ordered(h.moveIn?.items).map { (if (it.isDone) TICK else OPEN) + " " + it.text }

    /**
     * The house's own values (docs/11 5.30 item 1, slice 1a) as columns of the houses table, in this order: the
     * thirteen stored ones, then the three [CostSummary] computes. The same list on the web (`export-rows.ts`).
     */
    val COST_COLUMN_KEYS: List<String> = listOf(
        "col.areaSqft", "col.locationSource", "col.deposit", "col.depositMonths", "col.maintenance",
        "col.maintenanceIncluded", "col.brokerage", "col.brokerageMonths", "col.lockInMonths", "col.noticeMonths",
        "col.availableFrom", "col.myOffer", "col.agreedPrice", "col.monthlyCost", "col.moveIn", "col.perSqFt",
    )

    /** The place of the kind each of [COST_COLUMN_KEYS] needs, in the same order: a copy in a kind's voice leaves the column out where the kind hides it. */
    private val COST_COLUMN_REFS: List<String> = listOf(
        "field.areaSqft", "field.locationSource", "field.deposit", "field.depositMonths", "field.maintenance",
        "field.maintenanceIncluded", "field.brokerage", "field.brokerageMonths", "field.lockInMonths", "field.noticeMonths",
        "field.availableFrom", "field.myOffer", "field.agreedPrice", "field.money", "field.money", "field.money",
    )

    /** The cells of [COST_COLUMN_KEYS] for [h]: blank where unknown or not computable. */
    fun costCells(h: ExportHouse, s: ExportStrings): List<Cell> {
        val c = h.cost
        val summary = CostSummary.of(h.price, h.priceType, h.areaSqft, c)
        return listOf(
            count(h.areaSqft),
            // The source as its enum word (GPS, MAP, APPROX), like a checklist key: a machine value, as the web writes it.
            text(h.locationSource),
            money(c?.deposit), count(c?.depositMonths), money(c?.maintenance),
            c?.maintenanceIncluded?.let { Cell.Text(s[if (it) "yes" else "no"]) } ?: Cell.Blank,
            money(c?.brokerage), count(c?.brokerageMonths), count(c?.lockInMonths), count(c?.noticeMonths),
            text(c?.availableFrom), money(c?.myOffer), money(c?.agreedPrice),
            money(summary.monthlyCost), money(summary.moveIn),
            summary.perSqFt?.let { Cell.Num(it, 1) } ?: Cell.Blank,
        )
    }

    /**
     * The **Cost** block of a house page in the readable copies (HTML, PDF, Markdown): a line per set field, in the
     * format's order (months as "{n} months"), then "Monthly cost", "Money to move in" and "Cost per sq ft" when
     * computable; empty when the house has no cost and nothing computes beyond its price. Rupees in the reader's form.
     */
    fun costLines(h: ExportHouse, s: ExportStrings): List<Pair<String, String>> {
        val c = h.cost
        val summary = CostSummary.of(h.price, h.priceType, h.areaSqft, c)
        return buildList {
            c?.deposit?.let { add(s["col.deposit"] to rupees(it)) }
            c?.depositMonths?.let { add(s["col.depositMonths"] to s.months(it)) }
            c?.maintenance?.let { add(s["col.maintenance"] to rupees(it)) }
            c?.maintenanceIncluded?.let { add(s["col.maintenanceIncluded"] to s[if (it) "yes" else "no"]) }
            c?.brokerage?.let { add(s["col.brokerage"] to rupees(it)) }
            c?.brokerageMonths?.let { add(s["col.brokerageMonths"] to s.months(it)) }
            c?.lockInMonths?.let { add(s["col.lockInMonths"] to s.months(it)) }
            c?.noticeMonths?.let { add(s["col.noticeMonths"] to s.months(it)) }
            c?.availableFrom?.let { add(s["col.availableFrom"] to it) }
            c?.myOffer?.let { add(s["col.myOffer"] to rupees(it)) }
            c?.agreedPrice?.let { add(s["col.agreedPrice"] to rupees(it)) }
            // The monthly cost is only worth a line when it says more than the price itself.
            summary.monthlyCost?.takeIf { it != h.price }?.let { add(s["col.monthlyCost"] to rupees(it)) }
            summary.moveIn?.takeIf { it != h.price }?.let { add(s["col.moveIn"] to rupees(it)) }
            // Whole rupees here, as the web's page writes it; the table keeps the decimal.
            summary.perSqFt?.let { add(s["col.perSqFt"] to rupees(floor(it + 0.5).toLong())) }
        }
    }

    /**
     * The lines of a broker in the **Brokers** section of the readable copies (HTML, PDF, Markdown): a line per set
     * field, then the houses of the copy that name it (their labels, in the copy's order).
     */
    fun brokerLines(b: ExportBroker, bundle: ExportBundle): List<Pair<String, String>> {
        val s = bundle.strings
        val houses = bundle.housesOf(b).joinToString(", ") { it.label }
        return buildList {
            if (!b.phone.isNullOrEmpty()) add(s["col.phone"] to b.phone)
            if (!b.agency.isNullOrEmpty()) add(s["col.agency"] to b.agency)
            if (!b.feeTerms.isNullOrEmpty()) add(s["col.feeTerms"] to b.feeTerms)
            b.rating?.let { add(s["col.rating"] to it.toString()) }
            if (!b.notes.isNullOrEmpty()) add(s["col.notes"] to b.notes)
            if (houses.isNotEmpty()) add(s["col.houses"] to houses)
        }
    }

    /**
     * The **Details** block of a house page in the readable copies (HTML, Markdown): score and its lines, price, bedrooms,
     * area, floor, rating, the address and the point, the listing link, then the kind's typed extras of the *details* section
     * and when it was added and last changed. A place the candidate's kind hides is not written (S4b-BL-266: a school has
     * no score, price, bedrooms, area or floor), and its words are its kind's ([ExportBundle.stringsOf]); a house's are
     * word for word what they were. The status is the page's own line (HTML's rank line, Markdown's first row).
     */
    fun detailLines(h: ExportHouse, bundle: ExportBundle): List<Pair<String, String>> {
        val s = bundle.stringsOf(h)
        val o = bundle.options
        fun shows(ref: String) = bundle.shows(h, ref)
        return buildList {
            if (shows("section.checklist")) {
                add(s["col.score"] to (bundle.overallOf(h)?.let { fixed(it, 1) } ?: s["none"]))
                addAll(scoringLines(h, bundle))
            }
            if (shows("field.money")) {
                add(s["col.price"] to (h.price?.let { rupees(it) } ?: s["none"]))
                if (h.price != null) add(s["col.priceType"] to s.priceType(h.priceType))
            }
            if (shows("field.bedrooms")) add(s["col.bedrooms"] to (h.bedrooms?.toString() ?: s["none"]))
            if (h.areaSqft != null && shows("field.areaSqft")) add(s["col.areaSqft"] to h.areaSqft.toString())
            if (h.floor != null && shows("field.floor")) add(s["col.floor"] to s.floor(h.floor))
            if (shows("field.rating")) add(s["col.rating"] to (h.rating?.toString() ?: s["none"]))
            if (shows("field.address")) add(s["col.address"] to (h.address ?: s["none"]))
            if (shows("field.street")) add(s["col.street"] to (h.street ?: s["none"]))
            if (shows("field.locality")) add(s["col.locality"] to (h.locality ?: s["none"]))
            if (shows("field.location")) {
                add(s["col.lat"] to fixed(h.lat, 6))
                add(s["col.lon"] to fixed(h.lon, 6))
            }
            // Only the approximate case says anything: a reader is warned the marker may be off (FR-068).
            if (h.locationSource == "APPROX" && shows("field.locationSource")) add(s["house.approx"] to s["yes"])
            // The link is text, never an href: the copy must not be a way to reach the network.
            if (!h.listingUrl.isNullOrEmpty() && shows("field.listingUrl")) add(s["col.listingUrl"] to h.listingUrl)
            // Kinds PR 6 and the phone half of kinds PR 16: the kind's typed extras of the Details section, labelled by the kind.
            for (e in bundle.extrasOf(h)) if (e.section != COST_SECTION) add(e.label to e.text)
            add(s["col.createdAt"] to ExportTime.dateTime(h.createdAt, o.utcOffsetMinutes))
            add(s["col.updatedAt"] to ExportTime.dateTime(h.updatedAt, o.utcOffsetMinutes))
        }
    }

    /**
     * The **Cost** block of a house page (a school's *Fees*): the house's cost lines where its kind has money, then the kind's
     * extras of its *cost* section, so each extra is written under the heading of its own section (S4b-BL-266). A house has
     * no such extra, so its block is [costLines] as it was.
     */
    fun costBlock(h: ExportHouse, bundle: ExportBundle): List<Pair<String, String>> =
        (if (bundle.shows(h, "field.money")) costLines(h, bundle.stringsOf(h)) else emptyList()) +
            bundle.extrasOf(h).filter { it.section == COST_SECTION }.map { it.label to it.text }

    /** The section of a kind whose typed extras a readable copy writes in its Cost block. */
    private const val COST_SECTION = "cost"

    /** The checklist keys of a house page, none for a candidate whose kind has no checklist. */
    fun checklistKeys(h: ExportHouse, bundle: ExportBundle): List<String> =
        if (bundle.shows(h, "section.checklist")) orderedChecklistKeys(h) else emptyList()

    private fun money(value: Long?): Cell = value?.let { Cell.Money(it) } ?: Cell.Blank
    private fun count(value: Int?): Cell = value?.let { Cell.Count(it.toLong()) } ?: Cell.Blank

    /**
     * Built-in checklist keys in display order first, then anything else the house has (custom criteria, a newer
     * app's keys), alphabetically: the same order on both apps whatever the person's criteria (slice 2 keeps it).
     */
    fun orderedChecklistKeys(house: ExportHouse): List<String> {
        val builtIn = app.doorprints.shared.model.Checklist.keys.filter { house.checklist.containsKey(it) }
        val extra = house.checklist.keys.filter { it !in app.doorprints.shared.model.Checklist.keys }.sorted()
        return builtIn + extra
    }

    private fun text(value: String?): Cell = if (value.isNullOrEmpty()) Cell.Blank else Cell.Text(value)

    // ---- rendering ----

    /**
     * Machine form, used by CSV: no currency sign, no grouping, no "—". A spreadsheet can add these up.
     * Timestamps become `2026-09-22 10:15` in the export's fixed offset.
     */
    fun plain(cell: Cell, options: ExportOptions): String = when (cell) {
        Cell.Blank -> ""
        is Cell.Text -> cell.value
        is Cell.Num -> fixed(cell.value, cell.decimals)
        is Cell.Count -> cell.value.toString()
        is Cell.Money -> cell.amount.toString()
        is Cell.Stamp -> ExportTime.dateTime(cell.epochMillis, options.utcOffsetMinutes)
    }

    /** Reader's form, used by HTML, Markdown and the PDF: ₹12,50,000 and an em dash for "nothing recorded". */
    fun display(cell: Cell, bundle: ExportBundle): String = when (cell) {
        Cell.Blank -> bundle.strings["none"]
        is Cell.Money -> rupees(cell.amount)
        else -> plain(cell, bundle.options)
    }

    /** `₹12,50,000`: the country pack's sign and digit grouping (India's: last three, then pairs), the same as the app screens show. */
    fun rupees(amount: Long): String {
        val negative = amount < 0
        val digits = (if (negative) -amount else amount).toString()
        return (if (negative) "-" else "") + Country.pack.money.symbol + indianGroups(digits)
    }

    /** The pack's grouping of [digits] (0-9 only): India's `1250000` gives `12,50,000`, the head grouped in pairs from the right. */
    internal fun indianGroups(digits: String): String = Country.pack.money.group(digits)

    /**
     * Fixed-decimal formatting without a platform formatter: always a dot, always [decimals] digits, ties away
     * from zero. `floor(x + 0.5)` is used rather than `kotlin.math.round`, which is `rint` on the JVM and rounds
     * ties to the **even** digit — so a score of 3.85 would print as "3.8" on Android and "3.9" in the browser,
     * and the two apps' copies would not match.
     */
    fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return ""
        val negative = value < 0
        val abs = if (negative) -value else value
        var scale = 1L
        repeat(decimals) { scale *= 10L }
        val scaled = floor(abs * scale + 0.5).toLong()
        val whole = scaled / scale
        val frac = scaled % scale
        val out = StringBuilder()
        if (negative && scaled != 0L) out.append('-')
        out.append(whole)
        if (decimals > 0) {
            out.append('.')
            val f = frac.toString()
            repeat(decimals - f.length) { out.append('0') }
            out.append(f)
        }
        return out.toString()
    }
}
