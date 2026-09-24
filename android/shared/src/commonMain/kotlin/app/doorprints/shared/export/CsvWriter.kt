package app.doorprints.shared.export

/**
 * RFC 4180 CSV of an [ExportTable] (docs/11 section 5.2: UTF-8 with BOM, RFC 4180, formula-injection guard).
 *
 * The BOM is not added here — the platform writes it once in front of the stream ([BOM]) — so a golden test can
 * compare plain text.
 */
object CsvWriter {
    /** UTF-8 byte-order mark; Excel on Windows needs it to read UTF-8 CSV, other tools ignore it. */
    const val BOM = "﻿"

    private const val CRLF = "\r\n"

    fun write(table: ExportTable, options: ExportOptions): String {
        val out = StringBuilder()
        out.append(table.columns.joinToString(",") { field(guard(it)) }).append(CRLF)
        for (row in table.rows) {
            out.append(
                row.joinToString(",") { cell ->
                    // The guard is for text only: a number's leading minus is part of the number, and quoting or
                    // prefixing it would stop a spreadsheet from reading -12.978321 as a coordinate.
                    val value = ExportRows.plain(cell, options)
                    field(if (cell is Cell.Text) guard(value) else value)
                }
            ).append(CRLF)
        }
        return out.toString()
    }

    /**
     * CSV-injection guard (OWASP): a cell a spreadsheet would run as a formula gets a leading apostrophe, which
     * Excel, LibreOffice and Sheets all treat as "this is text". A house note really can start with "=" or "+91".
     *
     * The trigger set is exactly OWASP's — `=`, `+`, `-`, `@`, tab, carriage return — and **must stay identical
     * to the web's `csvCell` regex `/^[=+\-@\t\r]/`** (web/src/app/export/deterministic.ts), or the two apps
     * write different bytes for the same note. A leading line feed is deliberately not in it: it is not a formula
     * trigger, and the cell is quoted by [field] anyway. `ExportRowsTest.csvGuardCharacterSetMatchesTheWeb` pins
     * the set; the mirror cases for web's `deterministic.spec.ts` are with the Web team (Sprint 4a round 4).
     */
    fun guard(value: String): String {
        val first = value.firstOrNull() ?: return value
        return if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            "'$value"
        } else {
            value
        }
    }

    /** Quotes when the value contains a comma, a quote, a line break or edge whitespace; doubles inner quotes. */
    fun field(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value.startsWith(' ') || value.endsWith(' ')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
