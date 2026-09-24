package app.doorprints.shared.export

/**
 * The six offline copies of docs/11 section 5.2, with the file name and media type each one gets. Shared so the
 * Android and web apps hand the user identically named files.
 */
enum class ExportFormat(val mimeType: String) {
    /** Self-contained, readable, photos inside the file. */
    HTML("text/html"),

    /** The same content and order as [HTML], A4, one house per page. */
    PDF("application/pdf"),

    /** A ZIP of `houses.csv`, `scores.csv`, `visits.csv`, `photos.csv`. */
    CSV("application/zip"),

    /** One workbook, one sheet per CSV table. */
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    MARKDOWN("text/markdown"),

    /** The exact, re-importable backup: `manifest.json`, `data.json`, `photos/`, and the HTML copy. */
    BACKUP("application/zip"),
    ;

    /** Whether the user's "include photos" choice changes anything for this format. */
    val usesPhotos: Boolean get() = this == HTML || this == PDF || this == BACKUP || this == CSV || this == XLSX

    /** `Doorprints-2026-09-22.html`, `Doorprints-backup-2026-09-22.zip`, … */
    fun fileName(dateStem: String): String = when (this) {
        HTML -> "$dateStem.html"
        PDF -> "$dateStem.pdf"
        CSV -> "$dateStem-csv.zip"
        XLSX -> "$dateStem.xlsx"
        MARKDOWN -> "$dateStem.md"
        // "Doorprints-2026-09-22" -> "Doorprints-backup-2026-09-22.zip"
        BACKUP -> dateStem.replaceFirst("Doorprints-", "Doorprints-backup-") + ".zip"
    }

    fun fileName(bundle: ExportBundle): String = fileName(bundle.fileStem)

    fun fileName(options: ExportOptions): String = fileName(stem(options))

    companion object {
        /**
         * `Doorprints-2026-09-22` — the stem every file name is built on, from the options alone. The export
         * screen needs it before the bundle exists, to suggest a name in the system "Save as" dialog.
         */
        fun stem(options: ExportOptions): String =
            "Doorprints-" + ExportTime.date(options.exportedAtMillis, options.utcOffsetMinutes)
    }
}
