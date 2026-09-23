package com.househunt.shared.export

/**
 * The readable HTML copy (docs/11 section 5.2).
 *
 * Self-contained and inert: inline CSS, **no JavaScript**, photos as `data:` URIs, and a CSP `<meta>` of
 * `default-src 'none'; img-src data:; style-src 'unsafe-inline'`, so a copy that is mailed on or opened years
 * later cannot fetch anything, phone home, or run a script smuggled into a house note (threat model: the export is
 * a document, not an application). `@media print` puts one house on a page, which is also what the PDF does.
 *
 * **Streaming.** The document is written into an [Appendable] as it is built, never assembled as one String
 * first: a shortlist of 150 photos is roughly 35 MB of base64, which as a `StringBuilder` (70 MB of UTF-16) plus
 * its `toString()` copy plus the UTF-8 bytes is an `OutOfMemoryError` on a phone's heap — and the HTML copy is
 * both the default format and an entry inside every JSON backup, so that one mistake would take out both. Android
 * hands this an `OutputStreamWriter` over the destination (the SAF document the user picked, or the backup ZIP's
 * entry), so the largest thing resident at any moment is one photo's `data:` URI: about 270 KB for the 1024 px
 * q70 JPEG the exporter makes. The String-returning overload is kept for the golden tests and for callers whose
 * copies are small.
 *
 * [photoSrc] returns the `data:` URI of a photo, or null when the platform could not read or shrink it; it is a
 * parameter because encoding a JPEG is platform work (Android `Bitmap`, web `Canvas`) and because the golden test
 * can then pin the markup without a real image.
 */
object HtmlWriter {

    /** The whole document as one String. Only for small copies and the golden tests; see the class note. */
    fun write(bundle: ExportBundle, photoSrc: (ExportPhoto) -> String? = { null }): String {
        val out = StringBuilder()
        write(out, bundle, photoSrc)
        return out.toString()
    }

    /** Writes the document into [out] as it is built; nothing larger than one photo's URI is ever held. */
    fun write(out: Appendable, bundle: ExportBundle, photoSrc: (ExportPhoto) -> String? = { null }) {
        val s = bundle.strings
        val o = bundle.options

        out.append("<!DOCTYPE html>\n")
        out.append("<html lang=\"").append(esc(o.language)).append("\">\n<head>\n")
        out.append("<meta charset=\"utf-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\">\n")
        out.append("<title>").append(esc(s["doc.title"])).append("</title>\n")
        out.append("<style>\n").append(CSS).append("</style>\n")
        out.append("</head>\n<body>\n")

        // Cover
        out.append("<header class=\"cover\">\n")
        out.append("<h1>").append(esc(s["doc.title"])).append("</h1>\n")
        out.append("<p class=\"sub\">").append(esc(s["doc.subtitle"])).append("</p>\n")
        out.append("<dl>\n")
        row(out, s["cover.exported"], ExportTime.dateTime(o.exportedAtMillis, o.utcOffsetMinutes) +
            " (" + s["cover.times"] + " " + ExportTime.offsetLabel(o.utcOffsetMinutes) + ")")
        row(out, s["cover.houses"], bundle.houses.size.toString())
        row(out, s["cover.visits"], bundle.visits.size.toString())
        row(out, s["cover.photos"], bundle.photos.size.toString())
        row(out, s["cover.scope"], s["scope.${o.scope.name}"])
        row(out, s["cover.rejected"], s[if (o.includeRejected) "yes" else "no"])
        row(out, s["cover.photoScope"], s["photoScope.${o.photos.name}"])
        row(out, s["cover.contacts"], s[if (o.includeContacts) "yes" else "no"])
        row(out, s["cover.language"], ExportLanguages.nativeName(o.language))
        out.append("</dl>\n")
        out.append("<p class=\"note\">").append(esc(s["cover.privacy"])).append("</p>\n")
        if (o.includeContacts) {
            out.append("<p class=\"warn\">").append(esc(s["cover.contactWarning"])).append("</p>\n")
        }
        out.append("</header>\n")

        // Ranking table
        out.append("<section class=\"page\">\n<h2>").append(esc(s["section.ranking"])).append("</h2>\n")
        out.append("<table>\n<thead><tr>")
        for (h in listOf(s["col.rank"], s["col.label"], s["col.score"], s["col.price"], s["col.status"])) {
            out.append("<th scope=\"col\">").append(esc(h)).append("</th>")
        }
        out.append("</tr></thead>\n<tbody>\n")
        for (h in bundle.ranked) {
            out.append("<tr>")
            out.append("<td>").append(bundle.rankOf(h).toString()).append("</td>")
            out.append("<th scope=\"row\">").append(esc(h.label)).append("</th>")
            out.append("<td>").append(esc(h.score?.let { ExportRows.fixed(it, 1) } ?: s["none"])).append("</td>")
            out.append("<td>").append(esc(h.price?.let { ExportRows.rupees(it) } ?: s["none"])).append("</td>")
            out.append("<td>").append(esc(s.status(h.status))).append("</td>")
            out.append("</tr>\n")
        }
        out.append("</tbody>\n</table>\n</section>\n")

        for (h in bundle.houses) house(out, bundle, h, photoSrc)

        out.append("</body>\n</html>\n")
    }

    private fun house(
        out: Appendable,
        bundle: ExportBundle,
        h: ExportHouse,
        photoSrc: (ExportPhoto) -> String?,
    ) {
        val s = bundle.strings
        val o = bundle.options
        out.append("<section class=\"page house\">\n")
        out.append("<h2>").append(esc(h.label)).append("</h2>\n")
        out.append("<p class=\"rank\">").append(esc(s["col.rank"])).append(' ').append(bundle.rankOf(h).toString())
            .append(" · ").append(esc(s.status(h.status))).append("</p>\n")

        out.append("<h3>").append(esc(s["section.details"])).append("</h3>\n<dl>\n")
        row(out, s["col.score"], h.score?.let { ExportRows.fixed(it, 1) } ?: s["none"])
        row(out, s["col.price"], h.price?.let { ExportRows.rupees(it) } ?: s["none"])
        if (h.price != null) row(out, s["col.priceType"], s.priceType(h.priceType))
        row(out, s["col.bedrooms"], h.bedrooms?.toString() ?: s["none"])
        row(out, s["col.rating"], h.rating?.toString() ?: s["none"])
        row(out, s["col.address"], h.address ?: s["none"])
        row(out, s["col.street"], h.street ?: s["none"])
        row(out, s["col.locality"], h.locality ?: s["none"])
        row(out, s["col.lat"], ExportRows.fixed(h.lat, 6))
        row(out, s["col.lon"], ExportRows.fixed(h.lon, 6))
        // The link is shown as text, never as <a href>: the copy must not be a way to reach the network.
        if (!h.listingUrl.isNullOrEmpty()) row(out, s["col.listingUrl"], h.listingUrl)
        row(out, s["col.createdAt"], ExportTime.dateTime(h.createdAt, o.utcOffsetMinutes))
        row(out, s["col.updatedAt"], ExportTime.dateTime(h.updatedAt, o.utcOffsetMinutes))
        out.append("</dl>\n")

        val keys = ExportRows.orderedChecklistKeys(h)
        if (keys.isNotEmpty()) {
            out.append("<h3>").append(esc(s["section.checklist"])).append("</h3>\n")
            out.append("<table>\n<thead><tr><th scope=\"col\">").append(esc(s["col.itemLabel"]))
                .append("</th><th scope=\"col\">").append(esc(s["col.score"])).append("</th></tr></thead>\n<tbody>\n")
            for (key in keys) {
                out.append("<tr><th scope=\"row\">").append(esc(s.check(key))).append("</th><td>")
                    .append(h.checklist.getValue(key).toString()).append("/5</td></tr>\n")
            }
            out.append("</tbody>\n</table>\n")
        }

        val visits = bundle.visitsOf(h)
        if (visits.isNotEmpty()) {
            out.append("<h3>").append(esc(s["section.visits"])).append("</h3>\n")
            out.append("<table>\n<thead><tr>")
            for (c in listOf(s["col.arrivedAt"], s["col.leftAt"], s["col.minutes"], s["col.source"])) {
                out.append("<th scope=\"col\">").append(esc(c)).append("</th>")
            }
            out.append("</tr></thead>\n<tbody>\n")
            for (v in visits) {
                out.append("<tr><td>").append(esc(ExportTime.dateTime(v.arrivedAt, o.utcOffsetMinutes)))
                    .append("</td><td>")
                    .append(esc(v.leftAt?.let { ExportTime.dateTime(it, o.utcOffsetMinutes) } ?: s["none"]))
                    .append("</td><td>").append(esc(v.minutes?.toString() ?: s["none"]))
                    .append("</td><td>").append(esc(s.source(v.source))).append("</td></tr>\n")
            }
            out.append("</tbody>\n</table>\n")
        }

        if (o.includeContacts && (!h.contactName.isNullOrEmpty() || !h.contactPhone.isNullOrEmpty())) {
            out.append("<h3>").append(esc(s["section.contact"])).append("</h3>\n<dl>\n")
            if (!h.contactName.isNullOrEmpty()) row(out, s["col.contactName"], h.contactName)
            if (!h.contactPhone.isNullOrEmpty()) row(out, s["col.contactPhone"], h.contactPhone)
            out.append("</dl>\n")
        }

        if (!h.notes.isNullOrEmpty()) {
            out.append("<h3>").append(esc(s["section.notes"])).append("</h3>\n")
            out.append("<p class=\"notes\">").append(esc(h.notes)).append("</p>\n")
        }

        val photos = bundle.photosOf(h)
        if (photos.isNotEmpty()) {
            out.append("<h3>").append(esc(s["section.photos"])).append("</h3>\n<div class=\"photos\">\n")
            photos.forEachIndexed { i, p ->
                val src = photoSrc(p)
                if (src == null) {
                    out.append("<p class=\"missing\">").append(esc(p.fileName)).append("</p>\n")
                } else {
                    // docs/05 section 5: the house's name plus the photo's position, so a screen-reader user
                    // can tell six photos of one house apart ("Sunrise Apartments, Photos 2/6").
                    val alt = "${h.label}, ${s["section.photos"]} ${i + 1}/${photos.size}"
                    out.append("<figure><img src=\"").append(src).append("\" alt=\"")
                        .append(esc(alt)).append("\"><figcaption>").append(esc(p.fileName))
                        .append("</figcaption></figure>\n")
                }
            }
            out.append("</div>\n")
        }
        out.append("</section>\n")
    }

    private fun row(out: Appendable, term: String, value: String) {
        out.append("<dt>").append(esc(term)).append("</dt><dd>").append(esc(value)).append("</dd>\n")
    }

    /** HTML text escaping. `'` is escaped too, so the output is safe inside single-quoted attributes as well. */
    fun esc(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Presentation only (the golden test cuts this block out). Aligned with the web copy's stylesheet
     * (`web/src/app/export/html-export.ts`) and the Doorprints tokens in docs/05 section 4.1: `--text` #1c2421,
     * `--muted` #5f6b67, `--primary` #1f6f5c, `--primary-soft` #e3f0ec, `--border` #d9e0dd. A 46rem reading
     * measure, line-height 1.7 for Devanagari, Tamil and Telugu (their stacked marks need the room), a two-up
     * photo grid (three-up on paper) and A4 page boxes. The contact warning is amber, not error pink: docs/05
     * section 14.2 asks for "no scare styling" on a note the user chose to include.
     */
    private val CSS = listOf(
        ":root{color-scheme:light}",
        "*{box-sizing:border-box}",
        "body{margin:0 auto;padding:24px 16px 48px;max-width:46rem;background:#fff;color:#1c2421;" +
            "font-family:system-ui,-apple-system,'Segoe UI',Roboto,'Noto Sans','Noto Sans Devanagari'," +
            "'Noto Sans Tamil','Noto Sans Telugu','Nirmala UI',sans-serif;line-height:1.6}",
        ":lang(hi),:lang(ta),:lang(te){line-height:1.7}",
        "h1{font-size:1.75rem;margin:0 0 8px}",
        "h2{font-size:1.375rem;margin:32px 0 8px}",
        "h3{font-size:1rem;margin:20px 0 6px;color:#1f6f5c}",
        ".cover{border-bottom:3px solid #1f6f5c;padding-bottom:16px}",
        ".sub{font-size:1.125rem;margin:0 0 16px}",
        ".rank{margin:0 0 12px;color:#5f6b67}",
        "dl{display:grid;grid-template-columns:minmax(8rem,max-content) 1fr;gap:4px 16px;margin:0 0 12px}",
        "dt{font-weight:600;color:#5f6b67}",
        "dd{margin:0;overflow-wrap:anywhere}",
        "table{border-collapse:collapse;width:100%;margin:0 0 16px}",
        "th,td{border:1px solid #d9e0dd;padding:6px 10px;text-align:start;vertical-align:top}",
        "thead th{background:#eef2f0}",
        ".note,.warn{padding:8px 12px;border-radius:6px;margin:12px 0 0}",
        ".note{background:#e3f0ec;border-inline-start:4px solid #1f6f5c}",
        ".warn{background:#fbe7c2;border-inline-start:4px solid #a86a00}",
        ".notes{white-space:pre-wrap;overflow-wrap:anywhere}",
        ".photos{display:flex;flex-wrap:wrap;gap:8px}",
        ".photos figure{margin:0;width:calc(50% - 4px)}",
        ".photos img{width:100%;height:auto;border:1px solid #d9e0dd;border-radius:6px}",
        "figcaption,.missing{font-size:.75rem;color:#5f6b67;overflow-wrap:anywhere}",
        ".house{border-top:1px solid #d9e0dd;padding-top:8px}",
        "@page{size:A4;margin:14mm}",
        "@media print{body{max-width:none;padding:0}.cover,.page{break-after:page}" +
            ".page:last-child{break-after:auto}.photos figure{width:calc(33% - 6px)}}",
    ).joinToString("\n", postfix = "\n")
}
