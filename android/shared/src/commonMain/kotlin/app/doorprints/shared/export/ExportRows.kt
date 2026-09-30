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

import app.doorprints.shared.model.CostSummary
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.LengthUnit
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
     * house of it has a question asked (slice 3a).
     */
    fun tables(bundle: ExportBundle): List<ExportTable> =
        listOf(houses(bundle), scores(bundle), visits(bundle), photos(bundle)) +
            listOfNotNull(
                if (bundle.brokers.isEmpty()) null else brokers(bundle),
                if (bundle.hasRooms) rooms(bundle) else null,
                if (bundle.criteria.isEmpty()) null else criteria(bundle),
                if (bundle.hasAnswers) answers(bundle) else null,
            )

    fun houses(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = buildList {
            add(s["col.rank"]); add(s["col.label"]); add(s["col.status"]); add(s["col.score"])
            add(s["col.price"]); add(s["col.priceType"]); add(s["col.bedrooms"]); add(s["col.rating"])
            add(s["col.address"]); add(s["col.street"]); add(s["col.locality"])
            add(s["col.lat"]); add(s["col.lon"])
            if (bundle.options.includeContacts) {
                add(s["col.contactName"]); add(s["col.contactPhone"]); add(s["col.broker"])
            }
            add(s["col.listingUrl"]); add(s["col.notes"])
            addAll(COST_COLUMN_KEYS.map { s[it] })
            // Slice 1c: the number of rooms, right after the cost per sq ft.
            add(s["col.rooms"])
            add(s["col.visits"]); add(s["col.photos"])
            add(s["col.createdAt"]); add(s["col.updatedAt"]); add(s["col.id"])
        }
        val rows = bundle.houses.map { h ->
            buildList {
                add(Cell.Count(bundle.rankOf(h).toLong()))
                add(Cell.Text(h.label))
                add(Cell.Text(s.status(h.status)))
                add(bundle.overallOf(h)?.let { Cell.Num(it, 1) } ?: Cell.Blank)
                add(h.price?.let { Cell.Money(it) } ?: Cell.Blank)
                add(if (h.price == null) Cell.Blank else Cell.Text(s.priceType(h.priceType)))
                add(h.bedrooms?.let { Cell.Count(it.toLong()) } ?: Cell.Blank)
                add(h.rating?.let { Cell.Count(it.toLong()) } ?: Cell.Blank)
                add(text(h.address)); add(text(h.street)); add(text(h.locality))
                add(Cell.Num(h.lat, 6)); add(Cell.Num(h.lon, 6))
                if (bundle.options.includeContacts) {
                    add(text(h.contactName)); add(text(h.contactPhone)); add(text(bundle.brokerOf(h)?.label))
                }
                add(text(h.listingUrl)); add(text(h.notes))
                addAll(costCells(h, s))
                add(h.rooms?.takeIf { it.isNotEmpty() }?.let { Cell.Count(it.size.toLong()) } ?: Cell.Blank)
                add(Cell.Count(bundle.visitsOf(h).size.toLong()))
                add(Cell.Count(bundle.photosOf(h).size.toLong()))
                add(Cell.Stamp(h.createdAt)); add(Cell.Stamp(h.updatedAt))
                add(Cell.Text(h.id))
            }
        }
        return ExportTable("houses", s["table.houses"], columns, rows)
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
        return listOfNotNull(
            s["col.rank"], s["col.label"], s["col.score"], if (bundle.anyMissedMustHave) s["col.mustHaves"] else null,
            s["col.price"], s["col.status"],
        )
    }

    /** One row of the ranking table, in the reader's form, for [rankingColumns]; the house's label second. */
    fun rankingRow(h: ExportHouse, bundle: ExportBundle): List<String> {
        val s = bundle.strings
        val r = bundle.scoreOf(h)
        return listOfNotNull(
            bundle.rankOf(h).toString(),
            h.label,
            r.overall?.let { fixed(it, 1) } ?: s["none"],
            if (bundle.anyMissedMustHave) {
                if (r.missedMustHave) r.failedMustHave.joinToString(", ") { bundle.criterionLabel(it) } else s["none"]
            } else {
                null
            },
            h.price?.let { rupees(it) } ?: s["none"],
            s.status(h.status),
        )
    }

    /** The cover's rating share line value, "40%", when the copy has scoring records; null otherwise. */
    fun ratingShareText(bundle: ExportBundle): String? =
        if (bundle.hasScoringRecords) fixed(bundle.scoring.ratingShare * 100, 0) + "%" else null

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
        val s = bundle.strings
        return HouseAnswers.ordered(h.answers).map { a ->
            listOf(a.text, a.answer ?: NO_ANSWER, s.answerStatus(a.answerStatus.name))
        }
    }

    /** What a house page shows for a question not answered yet. */
    const val NO_ANSWER = "–"

    fun photos(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(s["col.house"], s["col.fileName"], s["col.createdAt"], s["col.houseId"], s["col.id"])
        val labels = bundle.houses.associate { it.id to it.label }
        val rows = bundle.photos.map { p ->
            listOf(
                Cell.Text(labels[p.houseId] ?: ""),
                Cell.Text(p.fileName),
                Cell.Stamp(p.createdAt),
                Cell.Text(p.houseId),
                Cell.Text(p.id),
            )
        }
        return ExportTable("photos", s["table.photos"], columns, rows)
    }

    /**
     * The house's own values (docs/11 5.30 item 1, slice 1a) as columns of the houses table, in this order: the
     * thirteen stored ones, then the three [CostSummary] computes. The same list on the web (`export-rows.ts`).
     */
    val COST_COLUMN_KEYS: List<String> = listOf(
        "col.areaSqft", "col.locationSource", "col.deposit", "col.depositMonths", "col.maintenance",
        "col.maintenanceIncluded", "col.brokerage", "col.brokerageMonths", "col.lockInMonths", "col.noticeMonths",
        "col.availableFrom", "col.myOffer", "col.agreedPrice", "col.monthlyCost", "col.moveIn", "col.perSqFt",
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

    /** `₹12,50,000` — Indian digit grouping (last three, then pairs), the same as the app screens show. */
    fun rupees(amount: Long): String {
        val negative = amount < 0
        val digits = (if (negative) -amount else amount).toString()
        return (if (negative) "-₹" else "₹") + indianGroups(digits)
    }

    internal fun indianGroups(digits: String): String {
        if (digits.length <= 3) return digits
        val head = digits.substring(0, digits.length - 3)
        val tail = digits.substring(digits.length - 3)
        // The head is grouped in pairs from the right: "1250" -> "12,50", so 1250000 reads 12,50,000.
        val groups = ArrayList<String>()
        var end = head.length
        while (end > 2) {
            groups.add(head.substring(end - 2, end))
            end -= 2
        }
        groups.add(head.substring(0, end))
        groups.reverse()
        return groups.joinToString(",") + "," + tail
    }

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
