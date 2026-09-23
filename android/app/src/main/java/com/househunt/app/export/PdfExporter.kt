package com.househunt.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import com.househunt.shared.export.ExportBundle
import com.househunt.shared.export.ExportHouse
import com.househunt.shared.export.ExportLanguages
import com.househunt.shared.export.ExportRows
import com.househunt.shared.export.ExportTime
import java.io.File
import java.io.OutputStream

// Sizes are PostScript points (1/72 inch), the unit PdfDocument's canvas uses: A4 is 595 x 842.
private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 40f
private const val FOOTER = 24f
private const val PHOTO_WIDTH = 200f
// Doorprints --muted (docs/05 section 4.1), the same grey the HTML copy uses for secondary text.
private val GREY = Color.rgb(0x5F, 0x6B, 0x67)

/**
 * The A4 PDF copy (docs/11 section 5.2), one house per page.
 *
 * **Why `android.graphics.pdf.PdfDocument` and not the print framework.** Both shape Devanagari, Tamil and Telugu
 * correctly, because both go through the platform's text stack (Minikin/HarfBuzz) and embed the font subsets they
 * use. That is also why neither a PDF library nor a hand-written PDF writer is an option: it would have to
 * implement OpenType shaping — reordered Devanagari matras, Tamil ligatures, Telugu conjuncts — itself, and the
 * small JVM and JS PDF libraries get those wrong.
 *
 * The print framework (`PrintManager` with a `PrintDocumentAdapter`, or `WebView.createPrintDocumentAdapter()`)
 * would lay the page out for us — it could print the HTML copy directly — but it costs a system print dialog the
 * user has to drive, it runs from an `Activity` on the main thread, and the result comes back through the print
 * spooler instead of as a stream. An export that runs in a `WorkManager` job, shows its own progress and writes
 * into the file the user picked with the Storage Access Framework cannot use it. `PdfDocument` is a plain,
 * off-main-thread API that writes to an `OutputStream`, so that is what this uses. The price is that the layout
 * is done by hand here, which is why each table is rendered as labelled rows rather than a ruled grid; the
 * content and its order are the same as the HTML copy's.
 *
 * **Two memory risks here can only be settled on a device, and are on the release checklist** (see
 * `android/shared/README.md` section 8, next to the HTML streaming note). First, each photo's `Bitmap` is
 * recycled as soon as it has been drawn, but a [PdfDocument.Page] canvas is a *recording* canvas — pixels are
 * serialised at `doc.writeTo`, and whether the recorded image keeps the pixel ref alive past `recycle()` is a
 * Skia/HWUI detail, not a documented contract. Second, `PdfDocument` holds every page until `writeTo`, so a large
 * export with photos has the peak-allocation shape the HTML path was streamed to avoid. Neither can be reproduced
 * in CI, so both are device tests, not comments.
 */
object PdfExporter {

    fun write(
        bundle: ExportBundle,
        photoFile: (String) -> File,
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val s = bundle.strings
        val o = bundle.options
        val doc = PdfDocument()
        val sheet = Sheet(doc)
        val title = pdfPaint(18f, bold = true)
        val heading = pdfPaint(13f, bold = true)
        val sub = pdfPaint(10f, color = GREY)
        val body = pdfPaint(10f)

        try {
            sheet.newPage()
            sheet.paragraph(s["doc.title"], title, spaceAfter = 4f)
            sheet.paragraph(s["doc.subtitle"], sub, spaceAfter = 14f)
            sheet.labelled(
                s["cover.exported"],
                ExportTime.dateTime(o.exportedAtMillis, o.utcOffsetMinutes) +
                    " (" + s["cover.times"] + " " + ExportTime.offsetLabel(o.utcOffsetMinutes) + ")",
                body,
            )
            sheet.labelled(s["cover.houses"], bundle.houses.size.toString(), body)
            sheet.labelled(s["cover.visits"], bundle.visits.size.toString(), body)
            sheet.labelled(s["cover.photos"], bundle.photos.size.toString(), body)
            sheet.labelled(s["cover.scope"], s["scope.${o.scope.name}"], body)
            sheet.labelled(s["cover.rejected"], s[if (o.includeRejected) "yes" else "no"], body)
            sheet.labelled(s["cover.photoScope"], s["photoScope.${o.photos.name}"], body)
            sheet.labelled(s["cover.contacts"], s[if (o.includeContacts) "yes" else "no"], body)
            sheet.labelled(s["cover.language"], ExportLanguages.nativeName(o.language), body)
            sheet.space(10f)
            sheet.paragraph(s["cover.privacy"], sub, spaceAfter = 6f)
            if (o.includeContacts) sheet.paragraph(s["cover.contactWarning"], sub, spaceAfter = 6f)

            sheet.space(10f)
            sheet.paragraph(s["section.ranking"], heading, spaceAfter = 6f)
            for (house in bundle.ranked) {
                val score = house.score?.let { ExportRows.fixed(it, 1) } ?: s["none"]
                val price = house.price?.let { ExportRows.rupees(it) } ?: s["none"]
                sheet.labelled(
                    "${bundle.rankOf(house)}. ${house.label}",
                    "$score · $price · ${s.status(house.status)}",
                    body,
                    spaceAfter = 2f,
                )
            }

            bundle.houses.forEachIndexed { index, house ->
                sheet.newPage()
                housePage(sheet, bundle, house, heading, sub, body, photoFile)
                onProgress(index + 1, bundle.houses.size)
            }
            sheet.finishPage()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun housePage(
        sheet: Sheet,
        bundle: ExportBundle,
        house: ExportHouse,
        heading: TextPaint,
        sub: TextPaint,
        body: TextPaint,
        photoFile: (String) -> File,
    ) {
        val s = bundle.strings
        val o = bundle.options
        sheet.paragraph(house.label, heading, spaceAfter = 2f)
        sheet.paragraph("${s["col.rank"]} ${bundle.rankOf(house)} · ${s.status(house.status)}", sub, 10f)

        sheet.labelled(s["col.score"], house.score?.let { ExportRows.fixed(it, 1) } ?: s["none"], body)
        sheet.labelled(s["col.price"], house.price?.let { ExportRows.rupees(it) } ?: s["none"], body)
        if (house.price != null) sheet.labelled(s["col.priceType"], s.priceType(house.priceType), body)
        sheet.labelled(s["col.bedrooms"], house.bedrooms?.toString() ?: s["none"], body)
        sheet.labelled(s["col.rating"], house.rating?.toString() ?: s["none"], body)
        sheet.labelled(s["col.address"], house.address ?: s["none"], body)
        sheet.labelled(s["col.street"], house.street ?: s["none"], body)
        sheet.labelled(s["col.locality"], house.locality ?: s["none"], body)
        sheet.labelled(
            "${s["col.lat"]} / ${s["col.lon"]}",
            "${ExportRows.fixed(house.lat, 6)}, ${ExportRows.fixed(house.lon, 6)}",
            body,
        )
        house.listingUrl?.takeIf { it.isNotEmpty() }?.let { sheet.labelled(s["col.listingUrl"], it, body) }
        sheet.labelled(s["col.createdAt"], ExportTime.dateTime(house.createdAt, o.utcOffsetMinutes), body)
        sheet.labelled(s["col.updatedAt"], ExportTime.dateTime(house.updatedAt, o.utcOffsetMinutes), body)

        val keys = ExportRows.orderedChecklistKeys(house)
        if (keys.isNotEmpty()) {
            sheet.section(s["section.checklist"], heading)
            for (key in keys) sheet.labelled(s.check(key), "${house.checklist.getValue(key)}/5", body, 2f)
        }

        val visits = bundle.visitsOf(house)
        if (visits.isNotEmpty()) {
            sheet.section(s["section.visits"], heading)
            for (visit in visits) {
                val left = visit.leftAt?.let { ExportTime.dateTime(it, o.utcOffsetMinutes) } ?: s["none"]
                val minutes = visit.minutes?.toString() ?: s["none"]
                sheet.labelled(
                    ExportTime.dateTime(visit.arrivedAt, o.utcOffsetMinutes),
                    "$left · $minutes ${s["col.minutes"]} · ${s.source(visit.source)}",
                    body,
                    2f,
                )
            }
        }

        if (o.includeContacts && (!house.contactName.isNullOrEmpty() || !house.contactPhone.isNullOrEmpty())) {
            sheet.section(s["section.contact"], heading)
            house.contactName?.takeIf { it.isNotEmpty() }?.let { sheet.labelled(s["col.contactName"], it, body, 2f) }
            house.contactPhone?.takeIf { it.isNotEmpty() }?.let { sheet.labelled(s["col.contactPhone"], it, body, 2f) }
        }

        house.notes?.takeIf { it.isNotEmpty() }?.let {
            sheet.section(s["section.notes"], heading)
            sheet.paragraph(it, body, spaceAfter = 6f)
        }

        val photos = bundle.photosOf(house)
        if (photos.isNotEmpty()) {
            sheet.section(s["section.photos"], heading)
            for (photo in photos) {
                // 300 px into a 200 pt box is about 1.5x, so the photo is still sharp on paper.
                val bitmap = PhotoBytes.bitmap(photoFile(photo.id), 300) ?: continue
                try {
                    val height = PHOTO_WIDTH * bitmap.height / bitmap.width.coerceAtLeast(1)
                    sheet.ensure(height + 6f)
                    val canvas = sheet.canvas ?: continue
                    canvas.drawBitmap(
                        bitmap,
                        Rect(0, 0, bitmap.width, bitmap.height),
                        RectF(MARGIN, sheet.y, MARGIN + PHOTO_WIDTH, sheet.y + height),
                        null,
                    )
                    sheet.advance(height + 6f)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
}

private fun pdfPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint().apply {
    // Typeface.DEFAULT keeps the platform's font-fallback chain, which is what shapes Devanagari, Tamil and
    // Telugu and what PdfDocument then embeds. Naming a font family here would break exactly that.
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    textSize = size
    this.color = color
    isAntiAlias = true
    // API 28+: pick the non-UI ("elegant") Noto Indic faces, which are the right faces for printed body text.
    // They are taller than the compact UI faces, which is safe only together with the fallback line spacing in
    // Sheet.paragraph (also API 28+). On 26-27 neither is available, so both stay off and the compact UI faces
    // are used with the padded, 1.15x line box.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isElegantTextHeight = true
}

/**
 * The page cursor: starts pages, keeps the y position and breaks a paragraph across pages line by line, so a long
 * note is never cut off.
 */
private class Sheet(private val doc: PdfDocument) {

    var canvas: Canvas? = null
        private set

    var y = MARGIN
        private set

    private var page: PdfDocument.Page? = null
    private var number = 0

    private val bodyBottom get() = PAGE_HEIGHT - MARGIN - FOOTER
    private val isFresh get() = y <= MARGIN + 0.01f

    fun newPage() {
        finishPage()
        number++
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create())
        canvas = page?.canvas
        y = MARGIN
        footer()
    }

    fun finishPage() {
        page?.let { doc.finishPage(it) }
        page = null
        canvas = null
    }

    private fun footer() {
        val c = canvas ?: return
        val p = Paint().apply {
            color = GREY
            textSize = 8f
            isAntiAlias = true
        }
        c.drawText(number.toString(), PAGE_WIDTH - MARGIN - 10f, PAGE_HEIGHT - MARGIN + 8f, p)
    }

    fun space(amount: Float) {
        if (canvas == null) newPage()
        y += amount
    }

    /** Moves the cursor down after something was drawn by hand (a photo). */
    fun advance(amount: Float) {
        y += amount
    }

    /** Makes sure [height] points are free on the current page, starting a new one if they are not. */
    fun ensure(height: Float) {
        if (canvas == null || (y + height > bodyBottom && !isFresh)) newPage()
    }

    fun section(title: String, paint: TextPaint) {
        space(8f)
        paragraph(title, paint, spaceAfter = 4f)
    }

    fun labelled(label: String, value: String, paint: TextPaint, spaceAfter: Float = 3f) {
        val text = SpannableStringBuilder(label).append(": ")
        text.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append(value)
        paragraph(text, paint, spaceAfter)
    }

    fun paragraph(text: CharSequence, paint: TextPaint, spaceAfter: Float = 4f) {
        if (text.isEmpty()) return
        val width = (PAGE_WIDTH - 2 * MARGIN).toInt()
        // Indic marks reach past Roboto's ascent and descent: reph, candrabindu and top matras above, Telugu
        // vattulu and vowel signs below. StaticLayout (unlike TextView) sizes lines from the primary font only
        // unless told otherwise, and each chunk below is drawn under a clip at exactly its line bounds, so those
        // marks would be cut off. Include the font padding, leave 15% extra leading, and on API 28+ let the
        // fallback (Noto Devanagari/Tamil/Telugu) fonts' extents size the line: then the clip is always outside
        // the glyphs. TC-M-12 checks a te/hi/ta house name at 400% zoom.
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setIncludePad(true)
            .setLineSpacing(0f, 1.15f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
        val layout = builder.build()
        var line = 0
        while (line < layout.lineCount) {
            if (canvas == null) newPage()
            val available = bodyBottom - y
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= available) {
                last++
            }
            if (last == line) {
                // Nothing fits. On a page that already has content, turn over; on an empty page this single line
                // is taller than the whole body, so draw it anyway rather than loop for ever.
                if (!isFresh) {
                    newPage()
                    continue
                }
                last = line + 1
            }
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(last - 1).toFloat()
            val c = canvas ?: return
            c.save()
            c.translate(MARGIN, y - top)
            c.clipRect(0f, top, width.toFloat(), bottom)
            layout.draw(c)
            c.restore()
            y += bottom - top
            line = last
        }
        y += spaceAfter
    }
}
