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

import app.doorprints.shared.model.KindCapabilities
import app.doorprints.shared.model.KindFileKinds
import app.doorprints.shared.model.SpaceView
import app.doorprints.shared.model.TestOnlyKinds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The order of a copy's blocks follows the kind file (docs/03 section 18.29), the twin of the website's `copy-order.spec.ts`: the
 * synthetic kind of the conformance suite (registered for the run only) lists `visits`, `notes`, `cost` first, and its Markdown and
 * readable copy write those three in that order; without a `copyOrder` the fixed order is back (cost, visits, notes). The house's
 * copies are byte for byte what they were (`HouseCopyPinsTest`). Not in commonTest: it reads a repository file.
 */
@OptIn(TestOnlyKinds::class)
class KindCopyOrderTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val file = repoFile("docs/schemas/kinds/test-all.json").readText()
    private val space = SpaceView("zz_all", multi = true, default = "house", available = listOf("house", "zz_all"))

    private fun place() = ExportHouse(
        id = "z1", label = "Lake Place", lat = 13.0, lon = 80.25, createdAt = 1_790_000_000_000, updatedAt = 1_790_000_000_000,
        kind = "zz_all", notes = "Bring the file.",
        extrasOther = buildJsonObject {
            put("fees", buildJsonObject { put("amount", 96000); put("cadence", "year") })
            put("seats", 40)
        },
    )

    private fun bundle() = ExportBundle.build(
        ExportOptions(language = "en", exportedAtMillis = 1_790_072_130_000, devKinds = true, space = space),
        listOf(place()),
        listOf(ExportVisit(id = "v1", houseId = "z1", lat = 13.0, lon = 80.25, arrivedAt = 1_790_003_000_000, updatedAt = 1)),
        emptyList(),
    )

    private fun headings(markdown: String) = Regex("^### (.+)$", RegexOption.MULTILINE).findAll(markdown).map { it.groupValues[1] }.toList()
    private fun htmlHeadings(html: String) = Regex("<h3>(.+?)</h3>").findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun theSyntheticKindsBlocksAreWrittenInItsOrderInTheMarkdownAndTheReadableCopy() {
        val undo = KindFileKinds.register(file)
        try {
            val b = bundle()
            assertEquals("zz_all", b.voice?.kind)
            assertEquals(listOf("visits", "notes", "cost", "rooms", "questions", "viewings", "areaNotes", "distances", "moveIn", "checklist", "photos"), b.blockOrder(place()))
            val s = b.stringsOf(place())
            val want = listOf(s["section.visits"], s["section.notes"], s["section.cost"])
            assertEquals("Markdown", want, headings(MarkdownWriter.write(b)).filter { it in want })
            assertEquals("HTML", want, htmlHeadings(HtmlWriter.write(b)).filter { it in want })
        } finally {
            undo()
        }
    }

    @Test
    fun withoutACopyOrderTheFixedOrderIsBack() {
        val undo = KindFileKinds.register(file)
        val plain = KindCapabilities.registerForTest("zz_all", KindCapabilities.of("zz_all").copy(copyOrder = emptyList()))
        try {
            val b = bundle()
            assertEquals(KindCapabilities.COPY_BLOCKS, b.blockOrder(place()))
            val s = b.stringsOf(place())
            val want = listOf(s["section.cost"], s["section.visits"], s["section.notes"])
            assertEquals("Markdown", want, headings(MarkdownWriter.write(b)).filter { it in want })
            assertEquals("HTML", want, htmlHeadings(HtmlWriter.write(b)).filter { it in want })
        } finally {
            plain()
            undo()
        }
    }

    @Test
    fun aHousesCopyKeepsTheFixedOrder() {
        val b = ExportBundle.build(ExportOptions(language = "en", exportedAtMillis = 1_790_072_130_000), listOf(ExportFixture.house1), emptyList(), emptyList())
        assertEquals(KindCapabilities.COPY_BLOCKS, b.blockOrder(ExportFixture.house1))
    }

    @Test
    fun theTestSeamIsNotUsedByAnyMainSourceSet() {
        val android = repoFile("android/settings.gradle.kts").parentFile
        val own = setOf("TestOnlyKinds.kt", "KindCapabilities.kt", "SchoolKind.kt")
        val offenders = listOf("shared/src/commonMain", "shared/src/androidMain", "shared/src/iosMain", "ui/src/commonMain", "ui/src/androidMain", "ui/src/iosMain", "app/src/main")
            .map { File(android, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in own }.toList() }
            .filter { f -> Regex("TestOnlyKinds|registerForTest|KindFileKinds").containsMatchIn(f.readText()) }
        assertEquals("a main source set uses the test seam: $offenders", emptyList<File>(), offenders)
    }
}
