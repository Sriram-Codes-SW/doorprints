package app.doorprints.shared.export

/**
 * The Markdown copy (docs/11 section 5.2): headings per house, tables for the ranking, details, scores and visits,
 * photos listed by file name (no images embedded), Markdown control characters escaped so a note that contains
 * `*`, `|` or `#` reads as the user typed it.
 */
object MarkdownWriter {

    fun write(bundle: ExportBundle): String {
        val s = bundle.strings
        val o = bundle.options
        val out = StringBuilder()

        out.append("# ").append(text(s["doc.title"])).append("\n\n")
        out.append(text(s["doc.subtitle"])).append("\n\n")

        // Cover
        out.append("- **").append(text(s["cover.exported"])).append("**: ")
            .append(ExportTime.dateTime(o.exportedAtMillis, o.utcOffsetMinutes))
            .append(" (").append(text(s["cover.times"])).append(' ')
            .append(ExportTime.offsetLabel(o.utcOffsetMinutes)).append(")\n")
        out.append("- **").append(text(s["cover.houses"])).append("**: ").append(bundle.houses.size).append('\n')
        out.append("- **").append(text(s["cover.visits"])).append("**: ").append(bundle.visits.size).append('\n')
        out.append("- **").append(text(s["cover.photos"])).append("**: ").append(bundle.photos.size).append('\n')
        out.append("- **").append(text(s["cover.scope"])).append("**: ")
            .append(text(s["scope.${o.scope.name}"])).append('\n')
        out.append("- **").append(text(s["cover.rejected"])).append("**: ")
            .append(text(s[if (o.includeRejected) "yes" else "no"])).append('\n')
        out.append("- **").append(text(s["cover.photoScope"])).append("**: ")
            .append(text(s["photoScope.${o.photos.name}"])).append('\n')
        out.append("- **").append(text(s["cover.contacts"])).append("**: ")
            .append(text(s[if (o.includeContacts) "yes" else "no"])).append('\n')
        out.append("- **").append(text(s["cover.language"])).append("**: ")
            .append(text(ExportLanguages.nativeName(o.language))).append("\n\n")
        out.append("> ").append(text(s["cover.privacy"])).append('\n')
        if (o.includeContacts) out.append("> ").append(text(s["cover.contactWarning"])).append('\n')
        out.append('\n')

        // Ranking
        out.append("## ").append(text(s["section.ranking"])).append("\n\n")
        table(
            out,
            listOf(s["col.rank"], s["col.label"], s["col.score"], s["col.price"], s["col.status"]),
            bundle.ranked.map { h ->
                listOf(
                    bundle.rankOf(h).toString(),
                    h.label,
                    h.score?.let { ExportRows.fixed(it, 1) } ?: s["none"],
                    h.price?.let { ExportRows.rupees(it) } ?: s["none"],
                    s.status(h.status),
                )
            },
        )

        // One section per house, in the copy's fixed order (createdAt, id).
        for (h in bundle.houses) {
            out.append("\n## ").append(text(h.label)).append("\n\n")

            out.append("### ").append(text(s["section.details"])).append("\n\n")
            val details = buildList {
                add(s["col.status"] to s.status(h.status))
                add(s["col.score"] to (h.score?.let { ExportRows.fixed(it, 1) } ?: s["none"]))
                add(s["col.price"] to (h.price?.let { ExportRows.rupees(it) } ?: s["none"]))
                if (h.price != null) add(s["col.priceType"] to s.priceType(h.priceType))
                add(s["col.bedrooms"] to (h.bedrooms?.toString() ?: s["none"]))
                add(s["col.rating"] to (h.rating?.toString() ?: s["none"]))
                add(s["col.address"] to (h.address ?: s["none"]))
                add(s["col.street"] to (h.street ?: s["none"]))
                add(s["col.locality"] to (h.locality ?: s["none"]))
                add(s["col.lat"] to ExportRows.fixed(h.lat, 6))
                add(s["col.lon"] to ExportRows.fixed(h.lon, 6))
                if (!h.listingUrl.isNullOrEmpty()) add(s["col.listingUrl"] to h.listingUrl)
                add(s["col.createdAt"] to ExportTime.dateTime(h.createdAt, o.utcOffsetMinutes))
                add(s["col.updatedAt"] to ExportTime.dateTime(h.updatedAt, o.utcOffsetMinutes))
            }
            table(out, listOf("", ""), details.map { listOf(it.first, it.second) })

            val keys = ExportRows.orderedChecklistKeys(h)
            if (keys.isNotEmpty()) {
                out.append("\n### ").append(text(s["section.checklist"])).append("\n\n")
                table(
                    out,
                    listOf(s["col.itemLabel"], s["col.score"]),
                    keys.map { listOf(s.check(it), h.checklist.getValue(it).toString() + "/5") },
                )
            }

            val visits = bundle.visitsOf(h)
            if (visits.isNotEmpty()) {
                out.append("\n### ").append(text(s["section.visits"])).append("\n\n")
                table(
                    out,
                    listOf(s["col.arrivedAt"], s["col.leftAt"], s["col.minutes"], s["col.source"]),
                    visits.map { v ->
                        listOf(
                            ExportTime.dateTime(v.arrivedAt, o.utcOffsetMinutes),
                            v.leftAt?.let { ExportTime.dateTime(it, o.utcOffsetMinutes) } ?: s["none"],
                            v.minutes?.toString() ?: s["none"],
                            s.source(v.source),
                        )
                    },
                )
            }

            if (o.includeContacts && (!h.contactName.isNullOrEmpty() || !h.contactPhone.isNullOrEmpty())) {
                out.append("\n### ").append(text(s["section.contact"])).append("\n\n")
                if (!h.contactName.isNullOrEmpty()) out.append("- ").append(text(h.contactName)).append('\n')
                if (!h.contactPhone.isNullOrEmpty()) out.append("- ").append(text(h.contactPhone)).append('\n')
            }

            if (!h.notes.isNullOrEmpty()) {
                out.append("\n### ").append(text(s["section.notes"])).append("\n\n")
                out.append(text(h.notes)).append('\n')
            }

            val photos = bundle.photosOf(h)
            if (photos.isNotEmpty()) {
                out.append("\n### ").append(text(s["section.photos"])).append("\n\n")
                for (p in photos) out.append("- `").append(p.fileName.replace('`', '\'')).append("`\n")
                out.append('\n').append(text(s["photos.inBackup"])).append('\n')
            }
        }
        return out.toString()
    }

    private fun table(out: StringBuilder, header: List<String>, rows: List<List<String>>) {
        out.append(header.joinToString(" | ", "| ", " |") { cell(it) }).append('\n')
        out.append(header.joinToString(" | ", "| ", " |") { "---" }).append('\n')
        for (row in rows) out.append(row.joinToString(" | ", "| ", " |") { cell(it) }).append('\n')
    }

    /** A table cell: escaped, and with line breaks folded, because a Markdown table row is one line. */
    private fun cell(value: String): String =
        text(value).replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    /**
     * Escapes the Markdown characters that would otherwise turn user text into formatting. Escaping every
     * character in the CommonMark list would litter ordinary prose with backslashes; this covers the ones that
     * actually change the rendering of a note, plus a leading marker that would start a list, heading or quote.
     */
    fun text(value: String): String {
        val escaped = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\', '`', '*', '_', '[', ']', '<', '>' -> escaped.append('\\').append(ch)
                else -> escaped.append(ch)
            }
        }
        // A line that starts with #, -, +, > or "1." would become a heading, list item or quote.
        return escaped.toString().split("\n").joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val marker = trimmed.firstOrNull()
            when {
                marker == '#' || marker == '+' -> line.replaceFirst(marker.toString(), "\\$marker")
                marker == '-' && (trimmed.length == 1 || trimmed[1] == ' ') -> line.replaceFirst("-", "\\-")
                else -> line
            }
        }
    }
}
