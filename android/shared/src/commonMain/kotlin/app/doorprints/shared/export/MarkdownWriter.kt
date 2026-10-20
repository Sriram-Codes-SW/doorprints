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
            .append(text(ExportLanguages.nativeName(o.language))).append('\n')
        // Slice 2: how much the star rating counts, when the copy's scoring is not the default one.
        ExportRows.ratingShareText(bundle)?.let {
            out.append("- **").append(text(s["col.ratingShare"])).append("**: ").append(text(it)).append('\n')
        }
        out.append('\n')
        out.append("> ").append(text(s["cover.privacy"])).append('\n')
        if (o.includeContacts) out.append("> ").append(text(s["cover.contactWarning"])).append('\n')
        out.append('\n')

        // Ranking
        out.append("## ").append(text(s["section.ranking"])).append("\n\n")
        table(
            out,
            ExportRows.rankingColumns(bundle),
            bundle.ranked.map { h -> ExportRows.rankingRow(h, bundle) },
        )

        // One section per house, in the copy's fixed order (createdAt, id).
        for (h in bundle.houses) {
            val s = bundle.stringsOf(h)
            out.append("\n## ").append(text(h.label)).append("\n\n")

            out.append("### ").append(text(s["section.details"])).append("\n\n")
            val details = listOf(s["col.status"] to s.status(h.status)) + ExportRows.detailLines(h, bundle)
            table(out, listOf("", ""), details.map { listOf(it.first, it.second) })

            // The blocks after the details, each its own text; the kind's `copyOrder` says which comes first (docs/03 section 18.28), the house's fixed order is the default.
            val costBlock = StringBuilder().also { b ->
                val cost = ExportRows.costBlock(h, bundle)
                if (cost.isNotEmpty()) {
                    b.append("\n### ").append(text(s["section.cost"])).append("\n\n")
                    table(b, listOf("", ""), cost.map { listOf(it.first, it.second) })
                }
            }

            val roomsBlock = StringBuilder().also { b ->
                // The rooms (slice 1c), after the cost and before the checklist.
                val rooms = ExportRows.roomRows(h, bundle)
                if (rooms.isNotEmpty()) {
                    b.append("\n### ").append(text(s["table.rooms"])).append("\n\n")
                    table(b, ExportRows.roomColumns(bundle), rooms)
                }
            }

            val questionsBlock = StringBuilder().also { b ->
                // The questions asked (slice 3a), after the rooms and before the checklist.
                val answers = ExportRows.answerRows(h, bundle)
                if (answers.isNotEmpty()) {
                    b.append("\n### ").append(text(s["section.questions"])).append("\n\n")
                    table(b, ExportRows.answerColumns(bundle), answers)
                }
            }

            val viewingsBlock = StringBuilder().also { b ->
                // The viewings (slice 3b-1), after the questions and before the checklist.
                val viewings = ExportRows.viewingRows(h, bundle)
                if (viewings.isNotEmpty()) {
                    b.append("\n### ").append(text(s["section.viewings"])).append("\n\n")
                    table(b, ExportRows.viewingColumns(bundle), viewings)
                }
            }

            // The area notes that reach the house and its distances to my places (slice 4a).
            val areaNotesBlock = StringBuilder().also { b ->
                areaTable(b, s["section.areaNotes"], ExportRows.areaNoteColumns(bundle), ExportRows.areaNoteRows(h, bundle))
            }

            val distancesBlock = StringBuilder().also { b ->
                areaTable(b, s["section.distances"], ExportRows.distanceColumns(bundle), ExportRows.distanceRows(h, bundle))
            }

            val moveInBlock = StringBuilder().also { b ->
                // Moving in (slice 5), after the distances and before the checklist: the date, the notes, the ticked list.
                if (h.moveIn != null && bundle.shows(h, "section.moveIn")) {
                    b.append("\n### ").append(text(s["section.movingIn"])).append("\n\n")
                    val lines = ExportRows.moveInLines(h, bundle)
                    if (lines.isNotEmpty()) table(b, listOf("", ""), lines.map { listOf(it.first, it.second) })
                    val items = ExportRows.moveInItems(h)
                    if (items.isNotEmpty()) {
                        if (lines.isNotEmpty()) b.append('\n')
                        for (item in items) b.append("- ").append(text(item).replace("\n", " ")).append('\n')
                    }
                }
            }

            val checklistBlock = StringBuilder().also { b ->
                val keys = ExportRows.checklistKeys(h, bundle)
                if (keys.isNotEmpty()) {
                    b.append("\n### ").append(text(s["section.checklist"])).append("\n\n")
                    table(
                        b,
                        listOf(s["col.itemLabel"], s["col.score"]),
                        keys.map { listOf(bundle.criterionLabel(it), h.checklist.getValue(it).toString() + "/5") },
                    )
                }
            }

            val visitsBlock = StringBuilder().also { b ->
                val visits = bundle.visitsOf(h)
                if (visits.isNotEmpty()) {
                    b.append("\n### ").append(text(s["section.visits"])).append("\n\n")
                    table(
                        b,
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
            }

            val notesBlock = StringBuilder().also { b ->
                val broker = bundle.brokerOf(h)
                if (o.includeContacts &&
                    (!h.contactName.isNullOrEmpty() || !h.contactPhone.isNullOrEmpty() || broker != null)
                ) {
                    b.append("\n### ").append(text(s["section.contact"])).append("\n\n")
                    if (!h.contactName.isNullOrEmpty()) b.append("- ").append(text(h.contactName)).append('\n')
                    if (!h.contactPhone.isNullOrEmpty()) b.append("- ").append(text(h.contactPhone)).append('\n')
                    if (broker != null) {
                        b.append("- ").append(text(s["col.broker"])).append(": ").append(text(broker.label)).append('\n')
                    }
                }

                if (!h.notes.isNullOrEmpty()) {
                    b.append("\n### ").append(text(s["section.notes"])).append("\n\n")
                    b.append(text(h.notes)).append('\n')
                }
            }

            val photosBlock = StringBuilder().also { b ->
                val photos = bundle.photosOf(h)
                if (photos.isNotEmpty()) {
                    b.append("\n### ").append(text(s["section.photos"])).append("\n\n")
                    for (p in photos) {
                        b.append("- `").append(p.fileName.replace('`', '\'')).append('`')
                        // Its room, tags and caption (slice 5), those it has.
                        ExportRows.photoLine(p, bundle)?.let { b.append(": ").append(text(it).replace('\n', ' ')) }
                        b.append('\n')
                    }
                    b.append('\n').append(text(s["photos.inBackup"])).append('\n')
                }
            }

            val blocks = mapOf(
                "cost" to costBlock, "rooms" to roomsBlock, "questions" to questionsBlock, "viewings" to viewingsBlock,
                "areaNotes" to areaNotesBlock, "distances" to distancesBlock, "moveIn" to moveInBlock, "checklist" to checklistBlock,
                "visits" to visitsBlock, "notes" to notesBlock, "photos" to photosBlock,
            )
            for (name in bundle.blockOrder(h)) out.append(blocks.getValue(name))
        }

        // The brokers (slice 1b), after the houses.
        if (bundle.brokers.isNotEmpty() && bundle.shows(null, "field.brokerId")) {
            out.append("\n## ").append(text(s["section.brokers"])).append("\n")
            for (b in bundle.brokers) {
                out.append("\n### ").append(text(b.name)).append("\n\n")
                table(out, listOf("", ""), ExportRows.brokerLines(b, bundle).map { listOf(it.first, it.second) })
            }
        }
        return out.toString()
    }

    private fun areaTable(b: StringBuilder, title: String, columns: List<String>, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        b.append("\n### ").append(text(title)).append("\n\n")
        table(b, columns, rows)
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
