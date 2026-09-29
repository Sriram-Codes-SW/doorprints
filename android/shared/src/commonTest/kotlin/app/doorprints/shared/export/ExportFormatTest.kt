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
