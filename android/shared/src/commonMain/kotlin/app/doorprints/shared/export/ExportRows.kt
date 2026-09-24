package app.doorprints.shared.export

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

    /** The four tables of a copy, in file order. */
    fun tables(bundle: ExportBundle): List<ExportTable> =
        listOf(houses(bundle), scores(bundle), visits(bundle), photos(bundle))

    fun houses(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = buildList {
            add(s["col.rank"]); add(s["col.label"]); add(s["col.status"]); add(s["col.score"])
            add(s["col.price"]); add(s["col.priceType"]); add(s["col.bedrooms"]); add(s["col.rating"])
            add(s["col.address"]); add(s["col.street"]); add(s["col.locality"])
            add(s["col.lat"]); add(s["col.lon"])
            if (bundle.options.includeContacts) { add(s["col.contactName"]); add(s["col.contactPhone"]) }
            add(s["col.listingUrl"]); add(s["col.notes"])
            add(s["col.visits"]); add(s["col.photos"])
            add(s["col.createdAt"]); add(s["col.updatedAt"]); add(s["col.id"])
        }
        val rows = bundle.houses.map { h ->
            buildList {
                add(Cell.Count(bundle.rankOf(h).toLong()))
                add(Cell.Text(h.label))
                add(Cell.Text(s.status(h.status)))
                add(h.score?.let { Cell.Num(it, 1) } ?: Cell.Blank)
                add(h.price?.let { Cell.Money(it) } ?: Cell.Blank)
                add(if (h.price == null) Cell.Blank else Cell.Text(s.priceType(h.priceType)))
                add(h.bedrooms?.let { Cell.Count(it.toLong()) } ?: Cell.Blank)
                add(h.rating?.let { Cell.Count(it.toLong()) } ?: Cell.Blank)
                add(text(h.address)); add(text(h.street)); add(text(h.locality))
                add(Cell.Num(h.lat, 6)); add(Cell.Num(h.lon, 6))
                if (bundle.options.includeContacts) { add(text(h.contactName)); add(text(h.contactPhone)) }
                add(text(h.listingUrl)); add(text(h.notes))
                add(Cell.Count(bundle.visitsOf(h).size.toLong()))
                add(Cell.Count(bundle.photosOf(h).size.toLong()))
                add(Cell.Stamp(h.createdAt)); add(Cell.Stamp(h.updatedAt))
                add(Cell.Text(h.id))
            }
        }
        return ExportTable("houses", s["table.houses"], columns, rows)
    }

    /**
     * One row per scored checklist item, in the shared display order (`Checklist.keys`), then any custom key the
     * house carries, alphabetically — Sprint 4b's custom criteria (docs/11 section 5.4) land in the same table
     * without a format change, because the key travels next to its label.
     */
    fun scores(bundle: ExportBundle): ExportTable {
        val s = bundle.strings
        val columns = listOf(s["col.house"], s["col.item"], s["col.itemLabel"], s["col.score"], s["col.houseId"])
        val rows = bundle.houses.flatMap { h ->
            orderedChecklistKeys(h).map { key ->
                listOf(
                    Cell.Text(h.label),
                    Cell.Text(key),
                    Cell.Text(s.check(key)),
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

    /** Built-in checklist keys in display order first, then anything else the house has, alphabetically. */
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
