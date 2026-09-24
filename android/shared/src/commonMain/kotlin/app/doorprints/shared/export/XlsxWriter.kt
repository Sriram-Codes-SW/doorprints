package app.doorprints.shared.export

/** One file inside the `.xlsx` package. The ZIP container itself is written by the platform. */
data class XlsxPart(val path: String, val xml: String)

/**
 * A minimal SpreadsheetML (`.xlsx`) writer: one sheet per [ExportTable], a frozen bold header row, ₹ amounts and
 * timestamps as **typed** cells a spreadsheet can sort and total.
 *
 * **Why write the format by hand instead of taking a library** (docs/11 section 5.2): an `.xlsx` is a ZIP of a
 * handful of XML parts, and the subset this app needs — inline strings, numbers, two number formats, one frozen
 * pane — is about 200 lines. Apache POI is tens of megabytes of jars, pulls in `java.awt`/XMLBeans and does not
 * run on Android without heavy shrinking; `fastexcel` is JVM-only and would not work for the KMP/iOS target;
 * every JS option is either unmaintained or (SheetJS) no longer published to npm, so the web team would not be
 * able to mirror it. A hand-written writer costs no APK size, no licence review and no supply-chain risk, and the
 * web team can port these ~200 lines directly. The cost is that unusual things (charts, formulas, merged cells)
 * are not supported — the copy does not need them.
 *
 * Strings are written as **inline strings** (`t="inlineStr"`) rather than through a shared-strings table: it makes
 * the writer streamable (no dictionary to hold in memory while rows are written) at the price of a slightly larger
 * file, which compresses away in the ZIP.
 */
object XlsxWriter {

    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    /** Style indexes into `cellXfs` in [styles]. */
    private const val STYLE_GENERAL = 0
    private const val STYLE_HEADER = 1
    private const val STYLE_MONEY = 2
    private const val STYLE_DATETIME = 3

    /** Every part of the package, in the order they should be added to the ZIP. */
    fun parts(bundle: ExportBundle): List<XlsxPart> {
        val tables = ExportRows.tables(bundle)
        val names = sheetNames(tables)
        return buildList {
            add(XlsxPart("[Content_Types].xml", contentTypes(tables.size)))
            add(XlsxPart("_rels/.rels", packageRels()))
            add(XlsxPart("xl/workbook.xml", workbook(names)))
            add(XlsxPart("xl/_rels/workbook.xml.rels", workbookRels(tables.size)))
            add(XlsxPart("xl/styles.xml", styles()))
            tables.forEachIndexed { i, table ->
                add(XlsxPart("xl/worksheets/sheet${i + 1}.xml", sheet(table, bundle.options)))
            }
        }
    }

    // ---- parts ----

    private fun contentTypes(sheets: Int): String = buildString {
        append(HEAD)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in 1..sheets) {
            append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun packageRels(): String =
        HEAD + "<Relationships xmlns=\"$PKG_REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private fun workbook(sheetNames: List<String>): String = buildString {
        append(HEAD)
        append("<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\"><sheets>")
        sheetNames.forEachIndexed { i, name ->
            append("<sheet name=\"").append(xml(name)).append("\" sheetId=\"").append(i + 1)
                .append("\" r:id=\"rId").append(i + 1).append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheets: Int): String = buildString {
        append(HEAD)
        append("<Relationships xmlns=\"$PKG_REL_NS\">")
        for (i in 1..sheets) {
            append("<Relationship Id=\"rId$i\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        // The styles part comes after the sheets so the sheet ids stay 1..n.
        append("<Relationship Id=\"rId${sheets + 1}\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    /**
     * Fonts, fills and borders are the smallest set Excel accepts (fill 0 must be `none` and fill 1 `gray125`,
     * or Excel calls the workbook corrupt). Two custom number formats: ₹ with Indian grouping, and a date-time.
     */
    private fun styles(): String = buildString {
        append(HEAD)
        append("<styleSheet xmlns=\"$MAIN_NS\">")
        append("<numFmts count=\"2\">")
        // #,##,##0 is the Indian grouping pattern (last three digits, then pairs).
        append("<numFmt numFmtId=\"164\" formatCode=\"&quot;₹&quot;\\ #,##,##0\"/>")
        append("<numFmt numFmtId=\"165\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm\"/>")
        append("</numFmts>")
        append("<fonts count=\"2\">")
        append("<font><sz val=\"11\"/><name val=\"Calibri\"/></font>")
        append("<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>")
        append("</fonts>")
        append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>")
        append("<fill><patternFill patternType=\"gray125\"/></fill></fills>")
        append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
        append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        append("<cellXfs count=\"4\">")
        append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>")
        append("<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        append("<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        append("</cellXfs>")
        append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        append("</styleSheet>")
    }

    private fun sheet(table: ExportTable, options: ExportOptions): String = buildString {
        append(HEAD)
        append("<worksheet xmlns=\"$MAIN_NS\">")
        append("<sheetViews><sheetView workbookViewId=\"0\">")
        append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        append("</sheetView></sheetViews>")
        append("<sheetData>")
        append("<row r=\"1\">")
        table.columns.forEachIndexed { c, name ->
            append(inlineCell(ref(c, 1), name, STYLE_HEADER))
        }
        append("</row>")
        table.rows.forEachIndexed { r, row ->
            val rowNumber = r + 2
            append("<row r=\"").append(rowNumber).append("\">")
            row.forEachIndexed { c, cell ->
                append(cellXml(ref(c, rowNumber), cell, options))
            }
            append("</row>")
        }
        append("</sheetData>")
        append("</worksheet>")
    }

    // ---- cells ----

    private fun cellXml(ref: String, cell: Cell, options: ExportOptions): String = when (cell) {
        Cell.Blank -> ""
        is Cell.Text -> inlineCell(ref, cell.value, STYLE_GENERAL)
        is Cell.Num -> numberCell(ref, ExportRows.fixed(cell.value, cell.decimals), STYLE_GENERAL)
        is Cell.Count -> numberCell(ref, cell.value.toString(), STYLE_GENERAL)
        is Cell.Money -> numberCell(ref, cell.amount.toString(), STYLE_MONEY)
        is Cell.Stamp -> numberCell(ref, serialDate(cell.epochMillis, options.utcOffsetMinutes), STYLE_DATETIME)
    }

    private fun inlineCell(ref: String, value: String, style: Int): String =
        "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + xml(value) + "</t></is></c>"

    private fun numberCell(ref: String, value: String, style: Int): String =
        if (value.isEmpty()) "" else "<c r=\"$ref\" s=\"$style\"><v>$value</v></c>"

    /**
     * Excel's day number: 1899-12-30 is 0, so the Unix epoch is 25569. Written with 8 decimals, which is well
     * below a second and keeps the text short and stable for the golden test.
     */
    internal fun serialDate(epochMillis: Long, utcOffsetMinutes: Int): String {
        val local = epochMillis + utcOffsetMinutes * 60_000L
        return ExportRows.fixed(25569.0 + local / 86_400_000.0, 8)
    }

    /** `A1`, `Z1`, `AA1`, … for a zero-based column and a one-based row. */
    internal fun ref(column: Int, row: Int): String {
        var letters = ""
        var n = column
        while (true) {
            letters = ('A' + (n % 26)).toString() + letters
            n = n / 26 - 1
            if (n < 0) break
        }
        return letters + row
    }

    /**
     * Sheet names: Excel forbids `[ ] : * ? / \`, caps the name at 31 characters and refuses duplicates, so a
     * translated table title is sanitised, truncated and, if needed, numbered.
     */
    internal fun sheetNames(tables: List<ExportTable>): List<String> {
        val used = HashSet<String>()
        return tables.map { table ->
            val cleaned = table.title.map { if (it in "[]:*?/\\") ' ' else it }
                .joinToString("").trim().ifEmpty { table.name }
            var name = if (cleaned.length > 31) cleaned.substring(0, 31) else cleaned
            var n = 2
            while (!used.add(name.lowercase())) {
                val suffix = " $n"
                val head = if (cleaned.length > 31 - suffix.length) cleaned.substring(0, 31 - suffix.length) else cleaned
                name = head + suffix
                n++
            }
            name
        }
    }

    /**
     * XML text escaping. Characters XML 1.0 does not allow at all (most control codes) are dropped rather than
     * escaped — a `\u0000` that somehow reached a note would otherwise make the whole workbook unreadable.
     */
    internal fun xml(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch == '&' -> out.append("&amp;")
                ch == '<' -> out.append("&lt;")
                ch == '>' -> out.append("&gt;")
                ch == '"' -> out.append("&quot;")
                ch == '\'' -> out.append("&apos;")
                ch == '\t' || ch == '\n' || ch == '\r' -> out.append(ch)
                ch.code < 0x20 || (ch.code in 0x7F..0x9F) -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
