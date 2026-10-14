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

import app.doorprints.shared.model.SpaceView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A school's readable copies speak in the school's words and leave out what a school hides (kinds PR 16, phone half;
 * S4b-BL-256 copies part and S4b-BL-266; docs/03 §18.9; docs/06 TC-U-230), the twin of the website's
 * `kind-copy-voice.spec.ts`: no *House*, *Price*, *Score* or *Cost* in the cover, the ranking, the tables and the section
 * headings; the fees under *Fees*; no Scores table; a copy of houses, of houses beside a school, and a school copied as a
 * house (the flag off) are as they were (`HouseCopyPinsTest` holds the house's bytes). The expected words are written out
 * here, from the website's, not read back from the code under test. Not in commonTest: it reads a repository file.
 */
class SchoolCopyVoiceTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val extras: JsonObject = Json.parseToJsonElement(repoFile("docs/schemas/kinds/school-vectors.json").readText())
        .jsonObject.getValue("candidate").jsonObject.getValue("extras").jsonObject
    private val languages = listOf("en", "hi", "ta", "te")

    private fun school(rating: Int? = 4) = ExportHouse(
        id = "s1", label = "Pine Hill School", lat = 26.1433, lon = 91.7898, createdAt = 1_790_000_000_000,
        updatedAt = 1_790_000_000_000, kind = "school", extrasOther = extras, rating = rating, street = "Ganeshguri Road",
        listingUrl = "https://pinehill.example/", contactName = "Office", contactPhone = "+91 361 000 0000",
    )

    private val schoolSpace = SpaceView("school", multi = true, default = "house", available = listOf("house", "school"))

    private fun copy(language: String, houses: List<ExportHouse>, space: SpaceView? = schoolSpace, on: Boolean = true) =
        ExportBundle.build(
            ExportOptions(language = language, exportedAtMillis = 1_790_072_130_000, devKinds = on, space = space),
            houses, listOf(ExportVisit(id = "v1", houseId = "s1", lat = 26.1, lon = 91.7, arrivedAt = 1_790_003_000_000, updatedAt = 1)),
            emptyList(),
        )

    /** The words of a house in each language: a school's copy must carry none of them. */
    private val houseWord = mapOf(
        "en" to Regex("house|BHK|\\bflat\\b|\\bcost\\b|\\bprice\\b|\\bscore\\b", RegexOption.IGNORE_CASE),
        "hi" to Regex("मकान|घर"),
        "ta" to Regex("வீடு|வீட்"),
        "te" to Regex("ఇల్లు|ఇళ్లు|ఇంటి"),
    )

    private fun readable(bundle: ExportBundle): List<String> =
        listOf(HtmlWriter.write(bundle).replace(Regex("<style>[\\s\\S]*</style>"), "").replace(Regex("<[^>]+>"), " "), MarkdownWriter.write(bundle))

    @Test
    fun aSchoolsCopyHasNoHouseWordInAnyLanguageNotInTheCoverTheRankingTheHeadingsOrTheTables() {
        for (language in languages) {
            for (bundle in listOf(copy(language, listOf(school())), copy(language, emptyList()))) {
                for (text in readable(bundle)) assertNull("$language: ${houseWord.getValue(language).find(text)?.value}", houseWord.getValue(language).find(text))
                for (t in ExportRows.tables(bundle)) {
                    val heads = (listOf(t.title) + t.columns).joinToString(" | ")
                    assertNull("$language ${t.name}: $heads", houseWord.getValue(language).find(heads))
                }
            }
        }
    }

    @Test
    fun theCoverTheRankingAndTheFeesAreTheSchoolsInEnglish() {
        val bundle = copy("en", listOf(school()))
        val md = MarkdownWriter.write(bundle)
        assertTrue(md.startsWith("# Doorprints — my schools\n"))
        assertTrue(md.contains("- **Schools**: 1\n"))
        assertTrue(md.contains("- **Schools included**: All\n"))
        assertTrue(md.contains("- **Rejected schools included**: Yes\n"))
        assertTrue(md.contains("| Rank | School | Status |\n| --- | --- | --- |\n| 1 | Pine Hill School | New |"))
        assertTrue(md.contains("### Fees\n\n|  |  |\n| --- | --- |\n| Fees per year (₹) | ₹96,000 |\n| Admission fee (₹) | ₹25,000 |"))
        // The Details: the school's own labels (a website, not a listing link), no price, score, BHK or carpet area.
        assertTrue(md.contains("| Website | https://pinehill.example/ |"))
        for (absent in listOf("Listing link", "Score", "Price", "Bedrooms", "Carpet area", "Cost", "Broker", "Overall")) {
            assertFalse(absent, md.contains(absent))
        }
        assertTrue(md.contains("| Stars | 4 |"))
        val html = HtmlWriter.write(bundle)
        assertTrue(html.contains("<title>Doorprints — my schools</title>"))
        assertTrue(html.contains("<thead><tr><th scope=\"col\">Rank</th><th scope=\"col\">School</th><th scope=\"col\">Status</th></tr></thead>"))
        assertTrue(html.contains("<h3>Fees</h3>\n<dl>\n<dt>Fees per year (₹)</dt><dd>₹96,000</dd>"))
    }

    @Test
    fun aSpaceOfSchoolsWithNoSchoolIsStillASchoolCopyAndAStoreOfHousesAndTheFlagOffAreAsTheyWere() {
        assertEquals("school", copy("en", emptyList()).voice?.kind)
        assertNull(copy("en", emptyList(), space = null).voice)
        assertNull(copy("en", listOf(ExportFixture.house1), space = null).voice)
        // The flag off: a school is copied as a house, with the house's words.
        val off = copy("en", listOf(school()), space = null, on = false)
        assertNull(off.voice)
        assertTrue(MarkdownWriter.write(off).contains("- **Houses**: 1\n"))
        // Without a space, candidates that are all schools speak as schools too (a store of one kind).
        assertEquals("school", copy("en", listOf(school()), space = null).voice?.kind)
    }

    @Test
    fun aSchoolBesideAHouseKeepsTheHousesWordsForTheCopyAndTheSchoolsOwnForItsSection() {
        val mixed = copy("en", listOf(ExportFixture.house1, school()), space = null)
        assertNull(mixed.voice)
        val md = MarkdownWriter.write(mixed)
        assertTrue(md.contains("- **Houses**: 2\n"))
        assertTrue(md.contains("| Rank | House | Score | Price | Status |"))
        val sections = md.split("\n## ")
        val own = sections.first { it.startsWith("Pine Hill School") }
        assertTrue(own.contains("### Fees"))
        assertTrue(own.contains("| Website |"))
        assertFalse(own.contains("Listing link"))
        assertFalse(own.contains("Price"))
        val house = sections.first { it.startsWith("Sunrise Apartments") }
        assertTrue(house.contains("Listing link"))
        assertTrue(house.contains("### Cost") || house.contains("| Price |"))
    }

    @Test
    fun theSchoolsTableHasTheSchoolsOwnColumnsAndTheWorkbookAndTheCsvNoScoresTable() {
        val en = ExportRows.tables(copy("en", listOf(school())))
        assertEquals(listOf("houses", "visits", "photos"), en.map { it.name })
        assertEquals("Schools", en[0].title)
        val columns = en[0].columns
        val head = listOf("Rank", "School", "Status", "Stars", "Address", "Street", "Locality", "Latitude", "Longitude", "Contact name", "Phone", "Website", "Notes")
        assertEquals(head, columns.take(head.size))
        assertEquals(
            listOf(
                "Location source", "Board", "Medium of instruction", "Grades offered", "Commute (minutes)", "School bus", "Fees per year (₹)",
                "Admission fee (₹)", "Visits", "Photos", "Added", "Last changed", "Id",
            ),
            columns.drop(head.size),
        )
        assertEquals(columns.size, en[0].rows.single().size)
        assertEquals(
            listOf("houses", "scores", "visits", "photos"),
            ExportRows.tables(copy("en", listOf(ExportFixture.house1), space = null)).map { it.name }.take(4),
        )
    }

    @Test
    fun theVisitsAndPhotosTablesSaySchoolNotHouseInEveryLanguage() {
        val nouns = mapOf(
            "en" to ("School" to "School id"), "hi" to ("स्कूल" to "स्कूल आईडी"),
            "ta" to ("பள்ளி" to "பள்ளி அடையாளம்"), "te" to ("పాఠశాల" to "పాఠశాల ఐడీ"),
        )
        for (language in languages) {
            val tables = ExportRows.tables(copy(language, listOf(school())))
            for (t in tables.filter { it.name == "visits" || it.name == "photos" }) {
                assertTrue("$language ${t.name}", nouns.getValue(language).first in t.columns)
                assertTrue("$language ${t.name}", nouns.getValue(language).second in t.columns)
            }
            val s = ExportStrings.of(language, "school")
            assertEquals(s["table.houses"], tables.first { it.name == "houses" }.title)
            assertFalse(s["table.houses"] == ExportStrings.of(language)["table.houses"])
            // Everything the overlay does not name is the house's word, key for key.
            assertEquals(ExportStrings.of(language)["col.visits"], s["col.visits"])
        }
    }
}
