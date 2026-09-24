package app.doorprints.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals

/** File names and media types are part of what the user sees, so they are pinned like any other output. */
class ExportFormatTest {

    private val bundle = ExportFixture.bundle()

    @Test
    fun fileStemIsTheLocalDateOfTheExport() {
        assertEquals("Doorprints-2026-09-22", bundle.fileStem)
    }

    @Test
    fun everyFormatHasTheNameTheSpecGives() {
        assertEquals("Doorprints-2026-09-22.html", ExportFormat.HTML.fileName(bundle))
        assertEquals("Doorprints-2026-09-22.pdf", ExportFormat.PDF.fileName(bundle))
        assertEquals("Doorprints-2026-09-22-csv.zip", ExportFormat.CSV.fileName(bundle))
        assertEquals("Doorprints-2026-09-22.xlsx", ExportFormat.XLSX.fileName(bundle))
        assertEquals("Doorprints-2026-09-22.md", ExportFormat.MARKDOWN.fileName(bundle))
        assertEquals("Doorprints-backup-2026-09-22.zip", ExportFormat.BACKUP.fileName(bundle))
    }

    @Test
    fun mediaTypesAreTheOnesTheStorageAccessFrameworkExpects() {
        assertEquals("text/html", ExportFormat.HTML.mimeType)
        assertEquals("application/pdf", ExportFormat.PDF.mimeType)
        assertEquals("application/zip", ExportFormat.CSV.mimeType)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ExportFormat.XLSX.mimeType,
        )
        assertEquals("text/markdown", ExportFormat.MARKDOWN.mimeType)
        assertEquals("application/zip", ExportFormat.BACKUP.mimeType)
    }
}
