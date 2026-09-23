package com.househunt.app.export

import com.househunt.shared.api.IsoTime
import com.househunt.shared.export.BackupCounts
import com.househunt.shared.export.BackupData
import com.househunt.shared.export.BackupFile
import com.househunt.shared.export.BackupFormat
import com.househunt.shared.export.BackupManifest
import com.househunt.shared.export.CsvWriter
import com.househunt.shared.export.ExportBundle
import com.househunt.shared.export.ExportFormat
import com.househunt.shared.export.ExportRows
import com.househunt.shared.export.HtmlWriter
import com.househunt.shared.export.MarkdownWriter
import com.househunt.shared.export.XlsxWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.MessageDigest

/**
 * Turns an [ExportBundle] into one of the six files (S4-02). Everything the *content* depends on lives in
 * `:shared`, so the web app produces the same bytes; what is here is the Android side of it — JPEG encoding, the
 * ZIP container, the PDF canvas and SHA-256.
 *
 * Every exporter writes straight into the stream it is given (the `Uri` the user picked with the Storage Access
 * Framework, or a file in `cache/exports/` for the share sheet). **Nothing that carries photo bytes is buffered
 * whole in memory**: the HTML copy is streamed through an [OutputStreamWriter] ([HtmlWriter.write] with an
 * `Appendable`) rather than built as a String, so a backup of several hundred photos works on a phone with a
 * small heap — the largest thing resident is one photo's `data:` URI.
 *
 * The Markdown, CSV and XLSX writers, and a backup's `data.json`, do still go through a String, on purpose and
 * with the same reasoning: none of them holds photo bytes, and each is bounded — a copy of 5 000 houses is a few
 * megabytes of text, and the read side refuses a `data.json` over [BackupFormat.MAX_DATA_JSON_BYTES] (16 MiB), so
 * nothing bigger can round-trip in any case. Say it exactly, because an overstated guarantee is how the next
 * change ends up putting something large through here. `Json.encodeToStream` would remove even that copy, but it
 * is a JVM-only kotlinx-serialization API and [BackupData] has to stay shared with the web importer.
 *
 * [onProgress] is called with (done, total) in arbitrary units so the screen can show a bar; callers must accept
 * being called often and from a background thread. It is also the **stop point**: the workers throw
 * `CancellationException` from it once their run has been stopped, and that has to unwind the writer. So no
 * exporter may call [onProgress] inside `runCatching` or a `catch (Exception)` — that would swallow the stop and
 * keep writing into a document that is about to be deleted.
 */
object Exporters {

    fun write(
        bundle: ExportBundle,
        format: ExportFormat,
        out: OutputStream,
        photoFile: (String) -> File,
        appVersion: String = "",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        // The ZIP formats own the stream: closing the ZipOutputStream is what writes the central directory, so
        // they must not be flushed again afterwards (a closed BufferedOutputStream throws). The plain formats
        // flush themselves and leave the stream open for the caller's `use` to close.
        when (format) {
            ExportFormat.HTML -> {
                html(bundle, out, photoFile, onProgress)
                out.flush()
            }
            ExportFormat.PDF -> {
                PdfExporter.write(bundle, photoFile, out, onProgress)
                out.flush()
            }
            ExportFormat.MARKDOWN -> {
                out.write(MarkdownWriter.write(bundle).toByteArray(Charsets.UTF_8))
                out.flush()
                onProgress(1, 1)
            }
            ExportFormat.CSV -> csvZip(bundle, out, onProgress)
            ExportFormat.XLSX -> xlsx(bundle, out, onProgress)
            ExportFormat.BACKUP -> backup(bundle, out, photoFile, appVersion, onProgress)
        }
    }

    // ---- HTML ----

    private fun html(
        bundle: ExportBundle,
        out: OutputStream,
        photoFile: (String) -> File,
        onProgress: (Int, Int) -> Unit,
    ) {
        val total = bundle.photos.size + 1
        var done = 0
        // Flushed, never closed: the caller owns the stream (see the note on `write`).
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        HtmlWriter.write(writer, bundle) { photo ->
            val uri = PhotoBytes.dataUri(photoFile(photo.id))
            onProgress(++done, total)
            uri
        }
        writer.flush()
        onProgress(total, total)
    }

    // ---- CSV ----

    private fun csvZip(bundle: ExportBundle, out: OutputStream, onProgress: (Int, Int) -> Unit) {
        val tables = ExportRows.tables(bundle)
        Zip(out, bundle.options.exportedAtMillis).use { zip ->
            tables.forEachIndexed { index, table ->
                // The BOM goes in front of every CSV: without it Excel on Windows reads UTF-8 as Latin-1 and
                // Hindi, Tamil and Telugu come out as mojibake.
                zip.text("${table.name}.csv", CsvWriter.BOM + CsvWriter.write(table, bundle.options))
                onProgress(index + 1, tables.size)
            }
            zip.finish()
        }
    }

    // ---- XLSX ----

    private fun xlsx(bundle: ExportBundle, out: OutputStream, onProgress: (Int, Int) -> Unit) {
        val parts = XlsxWriter.parts(bundle)
        Zip(out, bundle.options.exportedAtMillis).use { zip ->
            parts.forEachIndexed { index, part ->
                zip.text(part.path, part.xml)
                onProgress(index + 1, parts.size)
            }
            zip.finish()
        }
    }

    // ---- JSON backup ----

    /**
     * `manifest.json` is written **last**, because its SHA-256 list is built while the other entries stream past.
     * ZIP has no required entry order, and an importer reads the central directory first, so a reader finds it
     * straight away regardless.
     */
    private fun backup(
        bundle: ExportBundle,
        out: OutputStream,
        photoFile: (String) -> File,
        appVersion: String,
        onProgress: (Int, Int) -> Unit,
    ) {
        // Each photo is counted twice — once as it is embedded in the readable HTML copy, once as it is stored
        // whole — so the bar moves (and a stop is noticed) during the HTML entry too, which with every photo
        // base64-encoded into it is the longest single step of a backup.
        val total = bundle.photos.size * 2 + 3
        var done = 0
        Zip(out, bundle.options.exportedAtMillis).use { zip ->
            val files = mutableListOf<BackupFile>()

            // Built once: the manifest counts the rows data.json actually holds (unlinked visits included).
            val rows = BackupData.of(bundle)
            val data = BackupFormat.json.encodeToString(BackupData.serializer(), rows)
            files += hashed(zip, BackupFormat.DATA_ENTRY) { target -> writing(target) { it.write(data) } }
            onProgress(++done, total)

            // The readable copy travels with the backup, so the ZIP is useful even without the app installed.
            // It is written into the ZIP entry as it is built, for the same reason as the standalone HTML copy:
            // with photos embedded as base64 this is the largest thing the app ever produces.
            val htmlName = ExportFormat.HTML.fileName(bundle)
            var embedded = 0
            files += hashed(zip, htmlName) { target ->
                writing(target) { writer ->
                    HtmlWriter.write(writer, bundle) { photo ->
                        val uri = PhotoBytes.dataUri(photoFile(photo.id))
                        if (embedded < bundle.photos.size) embedded++
                        onProgress(done + embedded, total)
                        uri
                    }
                }
            }
            done += bundle.photos.size + 1
            onProgress(done, total)

            for (photo in bundle.photos) {
                val file = photoFile(photo.id)
                // Photos go in at full size: this is the copy an import restores from, not a readable summary.
                if (file.isFile) {
                    files += hashed(zip, BackupFormat.photoEntry(photo.fileName)) { target ->
                        file.inputStream().use { it.copyTo(target) }
                    }
                }
                onProgress(++done, total)
            }

            val manifest = BackupManifest(
                appVersion = appVersion,
                createdAt = IsoTime.format(bundle.options.exportedAtMillis),
                language = bundle.options.language,
                scope = bundle.options.scope.name,
                includeRejected = bundle.options.includeRejected,
                photoScope = bundle.options.photos.name,
                includeContacts = bundle.options.includeContacts,
                counts = BackupCounts.of(rows),
                files = files.toList(),
            )
            zip.text(
                BackupFormat.MANIFEST_ENTRY,
                BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest),
            )
            onProgress(total, total)
            zip.finish()
        }
    }

    /** UTF-8 text into a stream the caller still owns: flushed at the end, never closed. */
    private fun writing(out: OutputStream, block: (Writer) -> Unit) {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        block(writer)
        writer.flush()
    }

    /** Writes one entry while hashing and counting its bytes, and returns its manifest row. */
    private fun hashed(zip: Zip, path: String, write: (OutputStream) -> Unit): BackupFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        zip.stream(path) { target ->
            val counting = object : OutputStream() {
                override fun write(b: Int) {
                    digest.update(b.toByte())
                    size++
                    target.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    digest.update(b, off, len)
                    size += len
                    target.write(b, off, len)
                }
            }
            write(counting)
            counting.flush()
        }
        return BackupFile(path, size, hex(digest.digest()))
    }

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
