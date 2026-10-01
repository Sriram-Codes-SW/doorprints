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

import app.doorprints.shared.api.IsoTime

/** A copy's photos as the platform reads them (iOS: the photo files and UIKit's JPEG encoder). */
interface CopyPhotos {
    /** The stored photo's bytes as they are, for a backup; null when its file is gone. */
    fun original(photoId: String): ByteArray?

    /** The photo shrunk for a readable copy (1024 px, JPEG q70, docs/11 5.2) as a `data:` URI; null when unreadable. */
    fun dataUri(photoId: String): String?
}

/**
 * Turns an [ExportBundle] into one of the copies in common code (S4b-BL-81, the iPhone's *Save a copy*), with the
 * content, order and entry names of Android's `Exporters` in `:app`: HTML, Markdown, CSV (a ZIP of tables with a BOM
 * each), XLSX and the backup (`data.json`, the HTML copy, `photos/`, and `manifest.json` last with every entry's
 * SHA-256). The ZIPs are STORED ([ZipWriter]); Android's are deflated, and both read the same. **No PDF**: Android's
 * is drawn on `android.graphics.pdf`, so [ExportFormat.PDF] is refused here and the iPhone does not offer it.
 *
 * The HTML streams into [sink] ([Utf8Out]), so the largest thing held is one photo; [onProgress] is the stop point
 * (a cancellation thrown from it unwinds the writer), and must not be called inside a `catch (Exception)`.
 */
object CopyWriter {

    /** The formats this writer makes: all but [ExportFormat.PDF]. */
    val formats: List<ExportFormat> = ExportFormat.entries.filter { it != ExportFormat.PDF }

    fun write(
        bundle: ExportBundle,
        format: ExportFormat,
        sink: CopySink,
        photos: CopyPhotos,
        tools: ArchiveTools,
        appVersion: String = "",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        when (format) {
            ExportFormat.HTML -> html(bundle, sink, photos, onProgress)
            ExportFormat.MARKDOWN -> {
                sink.write(MarkdownWriter.write(bundle).encodeToByteArray())
                onProgress(1, 1)
            }
            ExportFormat.CSV -> {
                val tables = ExportRows.tables(bundle)
                val zip = zipFor(sink, bundle)
                tables.forEachIndexed { i, table ->
                    // The BOM, so Excel on Windows reads Hindi, Tamil and Telugu as UTF-8.
                    zip.text("${table.name}.csv", CsvWriter.BOM + CsvWriter.write(table, bundle.options))
                    onProgress(i + 1, tables.size)
                }
                zip.finish()
            }
            ExportFormat.XLSX -> {
                val parts = XlsxWriter.parts(bundle)
                val zip = zipFor(sink, bundle)
                parts.forEachIndexed { i, part ->
                    zip.text(part.path, part.xml)
                    onProgress(i + 1, parts.size)
                }
                zip.finish()
            }
            ExportFormat.BACKUP -> backup(bundle, sink, photos, tools, appVersion, onProgress)
            ExportFormat.PDF -> throw IllegalArgumentException("PDF is drawn by the platform")
        }
    }

    private fun zipFor(sink: CopySink, bundle: ExportBundle) =
        ZipWriter(sink, bundle.options.exportedAtMillis, bundle.options.utcOffsetMinutes)

    private fun html(bundle: ExportBundle, sink: CopySink, photos: CopyPhotos, onProgress: (Int, Int) -> Unit) {
        val total = bundle.photos.size + 1
        var done = 0
        val out = Utf8Out { sink.write(it) }
        HtmlWriter.write(out, bundle) { photo ->
            val uri = photos.dataUri(photo.id)
            onProgress(++done, total)
            uri
        }
        out.flush()
        onProgress(total, total)
    }

    /** As Android's: `data.json`, the HTML copy, the photos at full size, then `manifest.json` with their hashes. */
    private fun backup(
        bundle: ExportBundle,
        sink: CopySink,
        photos: CopyPhotos,
        tools: ArchiveTools,
        appVersion: String,
        onProgress: (Int, Int) -> Unit,
    ) {
        // Each photo counts twice: embedded in the HTML copy, then stored whole.
        val total = bundle.photos.size * 2 + 3
        var done = 0
        val zip = zipFor(sink, bundle)
        val files = mutableListOf<BackupFile>()

        val rows = BackupData.of(bundle)
        files += hashed(zip, tools, BackupFormat.DATA_ENTRY) { out ->
            out(BackupFormat.json.encodeToString(BackupData.serializer(), rows).encodeToByteArray())
        }
        onProgress(++done, total)

        var embedded = 0
        files += hashed(zip, tools, ExportFormat.HTML.fileName(bundle)) { out ->
            val text = Utf8Out(out)
            HtmlWriter.write(text, bundle) { photo ->
                val uri = photos.dataUri(photo.id)
                if (embedded < bundle.photos.size) embedded++
                onProgress(done + embedded, total)
                uri
            }
            text.flush()
        }
        done += bundle.photos.size + 1
        onProgress(done, total)

        for (photo in bundle.photos) {
            val bytes = photos.original(photo.id)
            if (bytes != null) files += hashed(zip, tools, BackupFormat.photoEntry(photo.fileName)) { out -> out(bytes) }
            onProgress(++done, total)
        }

        val manifest = BackupManifest(
            format = rows.format,
            appVersion = appVersion,
            createdAt = IsoTime.format(bundle.options.exportedAtMillis),
            language = bundle.options.language,
            scope = bundle.options.scope.name,
            includeRejected = bundle.options.includeRejected,
            photoScope = bundle.options.photos.name,
            includeContacts = bundle.options.includeContacts,
            counts = BackupCounts.of(rows),
            files = files.toList(),
            sharedSince = bundle.options.since?.let(IsoTime::format),
            sharedTo = bundle.options.sharedTo,
        )
        zip.text(BackupFormat.MANIFEST_ENTRY, BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest))
        onProgress(total, total)
        zip.finish()
    }

    /** One entry, hashed and counted while it is written; its manifest row. */
    private fun hashed(zip: ZipWriter, tools: ArchiveTools, path: String, write: ((ByteArray) -> Unit) -> Unit): BackupFile {
        val digest = tools.sha256()
        var size = 0L
        zip.stream(path) { entry ->
            write { bytes ->
                digest.update(bytes)
                size += bytes.size
                entry.write(bytes)
            }
        }
        return BackupFile(path, size, hexOf(digest.digest()))
    }
}
