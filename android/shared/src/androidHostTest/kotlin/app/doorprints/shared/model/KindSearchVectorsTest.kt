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

package app.doorprints.shared.model

import app.doorprints.shared.export.CsvWriter
import app.doorprints.shared.export.ExportBundle
import app.doorprints.shared.export.ExportHouse
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ExportRows
import app.doorprints.shared.export.ExportStrings
import app.doorprints.shared.export.HtmlWriter
import app.doorprints.shared.export.MarkdownWriter
import app.doorprints.shared.export.XlsxWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Typed extras in search and in the readable copies on the phone and the iPhone (kinds PR 6, S4b-BL-210; docs/03 ADR-36
 * §18.10), the twin of the website's `kind-search.spec.ts`, against `docs/schemas/kinds/kind-search-vectors.json`: a
 * school's extras add the vectors' words to the list search in en, hi, ta and te, appear in the CSV, XLSX, HTML and
 * Markdown copies with the kind's labels (school-vectors.json's Android words) and their type's form, and nothing of it
 * happens while the developer flag is off. [KindWords] is the Android words of school-vectors.json. Not in commonTest:
 * it reads repository files.
 */
class KindSearchVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private fun json(relative: String) = Json.parseToJsonElement(repoFile(relative).readText()).jsonObject
    private val vectors = json("docs/schemas/kinds/kind-search-vectors.json")
    private val schoolVectors = json("docs/schemas/kinds/school-vectors.json")
    private val school = json("docs/schemas/kinds/school.json")
    private val house = json("docs/schemas/kinds/house.json")
    private val languages = listOf("en", "hi", "ta", "te")
    private val labels = schoolVectors.getValue("kind").jsonObject.getValue("labels").jsonObject.getValue("android").jsonObject
    private fun label(language: String, ref: String) = labels.getValue(language).jsonObject.getValue(ref).jsonPrimitive.content
    private fun strings(o: JsonObject, key: String) = o.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private val extras = schoolVectors.getValue("candidate").jsonObject.getValue("extras").jsonObject
    private val fields = DevKinds.of("school", on = true).fields

    private fun schoolRow(other: JsonObject = extras) = ExportHouse(
        id = "s1", label = "Pine Hill School", lat = 26.1433, lon = 91.7898, createdAt = 1_790_000_000_000,
        updatedAt = 1_790_000_000_000, kind = "school", extrasOther = other,
    )

    private fun copy(language: String, on: Boolean, houses: List<ExportHouse> = listOf(schoolRow())) =
        ExportBundle.build(ExportOptions(language = language, exportedAtMillis = 1_790_072_130_000, devKinds = on), houses, emptyList(), emptyList())

    private data class Case(val id: String, val text: String, val cell: String, val xlsx: String)

    private fun cases(language: String) = vectors.getValue("copies").jsonObject.getValue(language).jsonArray.map {
        val o = it.jsonObject
        Case(o.getValue("id").jsonPrimitive.content, o.getValue("text").jsonPrimitive.content, o.getValue("cell").jsonPrimitive.content, o.getValue("xlsx").jsonPrimitive.content)
    }

    @Test
    fun theKindFilesNameWhatSearchReadsAndTheWordsAreTheVectors() {
        assertEquals("doorprints-kind-search-vectors/1", vectors.getValue("format").jsonPrimitive.content)
        val extraIds = school.getValue("extras").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(strings(vectors, "searched"), strings(school, "search").filter { it in extraIds })
        assertEquals(strings(school, "search"), fields.search)
        assertEquals(strings(vectors, "searched"), fields.others(searched = true).map { it.id })
        assertEquals(strings(house, "search"), KindFields.HOUSE.search)
        assertEquals(emptyList<KindExtra>(), KindFields.HOUSE.others())
        // Every Android key the extras show has the vectors' words in the four languages, and no other key is held.
        val refs = fields.extras.flatMap { f -> listOf(fields.label(f) to "field.${f.id}") + f.options.map { it.label to "option.${f.id}.${it.id}" } }
        assertEquals(refs.map { it.first }.toSet(), KindWords.keys)
        for (language in languages) for ((key, ref) in refs) assertEquals("$language $ref", label(language, ref), KindWords.of(key, language))
    }

    @Test
    fun aSchoolAddsTheVectorsWordsToTheSearchInEveryLanguage() {
        for (language in languages) {
            assertEquals(language, strings(vectors.getValue("search").jsonObject, language), KindValues.searchWords(fields, extras, language))
        }
    }

    @Test
    fun aWrongValueAnOptionNotOfferedAnEmptyTextAndASwitchThatIsOffAddNoWord() {
        val invalid = vectors.getValue("invalid").jsonObject
        assertEquals(strings(invalid, "words"), KindValues.searchWords(fields, invalid.getValue("extras").jsonObject, "en"))
        assertEquals(emptyList<String>(), KindValues.searchWords(KindFields.HOUSE, extras, "ta"))
        assertEquals(emptyList<String>(), KindValues.searchWords(DevKinds.of("school", on = false).fields, extras, "hi"))
    }

    @Test
    fun aSchoolsExtrasAreInEveryCopyWithItsLabelsAndTheirTypesForm() {
        for (language in languages) {
            val bundle = copy(language, on = true)
            val want = cases(language)
            val names = want.map { label(language, "field.${it.id}") }
            val h = bundle.houses.single()
            assertEquals(language, want.map { listOf(names[want.indexOf(it)], it.text, it.cell) }, bundle.extrasOf(h).map { listOf(it.label, it.text, ExportRows.plain(it.cell, bundle.options)) })

            // A column per extra right after the floor, then the visits; the CSV and the workbook have the same.
            val s = ExportStrings.of(language)
            val table = ExportRows.houses(bundle)
            val start = table.columns.indexOf(s["col.floor"]) + 1
            assertEquals(names + s["col.visits"], table.columns.subList(start, start + names.size + 1))
            assertEquals(want.map { it.cell }, table.rows.single().subList(start, start + want.size).map { ExportRows.plain(it, bundle.options) })
            val csv = CsvWriter.write(table, bundle.options).removePrefix("﻿").substringBefore("\r\n")
            assertEquals(table.columns, csv.split(','))
            val sheet = XlsxWriter.parts(bundle).first { it.path == "xl/worksheets/sheet1.xml" }.xml
            for (c in want) {
                val value = Regex.escape(if (c.xlsx == "text") c.text else c.cell)
                val found = Regex("<c r=\"[A-Z]+2\" s=\"(\\d)\"( t=\"inlineStr\")?>(?:<is><t xml:space=\"preserve\">|<v>)$value<").find(sheet)
                assertTrue("$language ${c.id} in the sheet", found != null)
                val kind = when { found!!.groupValues[2].isNotEmpty() -> "text"; found.groupValues[1] == "2" -> "money"; else -> "number" }
                assertEquals("$language ${c.id}", c.xlsx, kind)
            }

            // The readable copies: each in order on the Details block, before *Added*.
            val html = HtmlWriter.write(bundle)
            val md = MarkdownWriter.write(bundle)
            var last = -1
            for ((i, c) in want.withIndex()) {
                val at = html.indexOf("<dt>${HtmlWriter.esc(names[i])}</dt><dd>${HtmlWriter.esc(c.text)}</dd>")
                assertTrue("$language ${c.id} in the HTML", at > last)
                last = at
                assertTrue("$language ${c.id} in the Markdown", md.contains("| ${MarkdownWriter.text(names[i])} | ${MarkdownWriter.text(c.text)} |"))
            }
            assertTrue(html.indexOf("<dt>${HtmlWriter.esc(s["col.createdAt"])}</dt>") > last)
        }
    }

    @Test
    fun withTheFlagOffASchoolIsCopiedAsAHouseAndAHouseBesideASchoolHasBlankCells() {
        val off = copy("en", on = false)
        assertEquals(emptyList<Any>(), off.extrasOf(off.houses.single()))
        assertEquals(0, off.extraColumns.size)
        assertEquals(ExportRows.houses(copy("en", on = false, houses = emptyList())).columns, ExportRows.houses(off).columns)
        val invalid = vectors.getValue("invalid").jsonObject
        val wrong = copy("en", on = true, houses = listOf(schoolRow(invalid.getValue("extras").jsonObject)))
        val wantWrong = invalid.getValue("copies").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
        assertEquals(wantWrong, wrong.extrasOf(wrong.houses.single()).map { it.text })
        val houseRow = ExportHouse(id = "h1", label = "Lake View", lat = 13.0, lon = 80.0, floor = 2, createdAt = 1_789_000_000_000, updatedAt = 1_789_000_000_000)
        val mixed = copy("en", on = true, houses = listOf(houseRow, schoolRow()))
        val table = ExportRows.houses(mixed)
        val start = table.columns.indexOf(ExportStrings.of("en")["col.floor"]) + 1
        val row = table.rows.first { ExportRows.plain(it.last(), mixed.options) == "h1" }
        assertEquals(List(cases("en").size) { "" }, row.subList(start, start + cases("en").size).map { ExportRows.plain(it, mixed.options) })
        assertEquals(emptyList<Any>(), mixed.extrasOf(houseRow))
    }
}
