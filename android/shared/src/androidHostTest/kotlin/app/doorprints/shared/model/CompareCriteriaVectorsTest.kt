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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compare per kind (kinds PR 27, docs/03 ADR-36 section 18.21), the twin of the website's `compare-criteria.spec.ts`, against
 * `docs/schemas/kinds/compare-vectors.json`: each kind's resolved criteria with direction and label key (the kind file's own),
 * lists of ids that must resolve alike, the scoring (the house's is the person's own object) and the sample outcomes worked by
 * hand. Not in commonTest: it reads repository files.
 */
class CompareCriteriaVectorsTest {
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
    private val vectors = json("docs/schemas/kinds/compare-vectors.json")
    private val files = mapOf("house" to json("docs/schemas/kinds/house.json"), "school" to json("docs/schemas/kinds/school.json"))
    private val kinds = mapOf("house" to BuiltInKind.HOUSE, "school" to DevKinds.of("school", on = true))

    private fun JsonElement.str() = jsonPrimitive.content
    private fun JsonElement.strings() = jsonArray.map { it.str() }
    private fun JsonElement.orNull(): String? = if (this is JsonNull) null else str()

    private fun shape(c: CompareCriterion) = listOf(c.id, c.source.name.lowercase(), c.better)
    private fun shape(o: JsonObject) = listOf(o.getValue("id").str(), o.getValue("source").str(), o.getValue("better").orNull())

    @Test
    fun theFormatAndTheKindsAreTheVectors() {
        assertEquals("doorprints-compare-vectors/1", vectors.getValue("format").str())
        assertEquals(setOf("house", "school", "unknown"), vectors.getValue("kinds").jsonObject.keys)
    }

    @Test
    fun eachKindsCriteriaAreTheVectorsInTheKindFilesOrderWithTheDirectionAndTheLabelKey() {
        for ((id, kind) in kinds) {
            val got = CompareCriteria.of(kind)
            val want = vectors.getValue("kinds").jsonObject.getValue(id).jsonObject.getValue("criteria").jsonArray.map { it.jsonObject }
            assertEquals(id, want.map(::shape), got.map(::shape))
            assertEquals("$id follows the file", files.getValue(id).getValue("capabilities").jsonObject.getValue("compare").jsonObject.getValue("criteria").strings(), got.map { it.id })
            for ((i, w) in want.withIndex()) {
                val android = w.getValue("labelKey").jsonObject.getValue("android").str()
                assertEquals("$id ${got[i].id}", android, got[i].label)
                val file = files.getValue(id)
                val entry = if (w.getValue("source").str() == "check") {
                    file.getValue("checklist").jsonArray.single { it.jsonObject.getValue("key").str() == got[i].id }
                } else {
                    file.getValue("extras").jsonArray.single { it.jsonObject.getValue("id").str() == got[i].id }
                }
                assertEquals("$id ${got[i].id} file", android, entry.jsonObject.getValue("label").jsonObject.getValue("android").str())
            }
        }
    }

    @Test
    fun theHousesCriteriaAreItsTenChecklistKeysHigherBetter() {
        assertEquals(Checklist.keys, CompareCriteria.of(BuiltInKind.HOUSE).map { it.id })
        assertTrue(CompareCriteria.of(BuiltInKind.HOUSE).all { it.source == CompareCriterion.Source.CHECK && it.better == "higher" })
    }

    @Test
    fun aLowerFigureIsBetterForTheFeesAndTheCommuteBecauseTheKindFileSaysSo() {
        val extras = files.getValue("school").getValue("extras").jsonArray.map { it.jsonObject }
        for (c in vectors.getValue("kinds").jsonObject.getValue("school").jsonObject.getValue("criteria").jsonArray.map { it.jsonObject }.filter { it.getValue("source").str() == "extra" }) {
            assertEquals(c.getValue("id").str(), c.getValue("better").str(), extras.single { it.getValue("id") == c.getValue("id") }["better"]?.str())
        }
    }

    @Test
    fun listsOfIdsResolveAsTheVectorsSay() {
        for (case in vectors.getValue("resolve").jsonArray.map { it.jsonObject }) {
            val kind = kinds.getValue(case.getValue("kind").str())
            val got = CompareCriteria.resolve(case.getValue("ids").strings(), kind)
            assertEquals(case.getValue("name").str(), case.getValue("resolved").jsonArray.map { shape(it.jsonObject) }, got.map(::shape))
        }
    }

    @Test
    fun aKindThisBuildDoesNotKnowHasNoCriteria() {
        // The unknown ids resolve to the default capabilities, which have no Compare.
        for (id in vectors.getValue("unknown").strings()) assertNull(id, KindCapabilities.of(id).compare)
    }

    @Test
    fun theHousesScoringIsThePersonsOwnObjectAndTheSchoolsIsItsEightItemsMediumEach() {
        val mine = Scoring(listOf(Criterion(key = "water", mustHave = true)), 0.25)
        assertSame(mine, CompareCriteria.scoringFor(BuiltInKind.HOUSE, mine))
        assertEquals(vectors.getValue("scoring").jsonObject.getValue("house").jsonObject.getValue("keys").strings(), Checklist.keys)
        val school = CompareCriteria.scoringFor(kinds.getValue("school"), mine)
        val want = vectors.getValue("scoring").jsonObject.getValue("school").jsonObject.getValue("criteria").jsonArray.map { it.jsonObject }
        assertEquals(want.map { Triple(it.getValue("key").str(), it.getValue("weight").jsonPrimitive.int, it.getValue("mustHave").jsonPrimitive.boolean) },
            school.criteria.map { Triple(it.key, it.weight, it.mustHave) })
        assertEquals((0 until 8).toList(), school.criteria.map { it.sort })
        assertEquals(0.25, school.ratingShare, 0.0)
        assertFalse(CompareCriteria.scoringFor(kinds.getValue("school"), Scoring.DEFAULT).criteria.any { it.key == "water" })
    }

    @Test
    fun theComparedRowsAreTheItemsCompareNamesForASchoolAndEveryActiveCriterionOfThePersonForTheHouse() {
        val school = kinds.getValue("school")
        assertEquals(
            vectors.getValue("scoring").jsonObject.getValue("school").jsonObject.getValue("criteria").jsonArray.map { it.jsonObject.getValue("key").str() },
            CompareCriteria.comparedRows(school, CompareCriteria.scoringFor(school, Scoring.DEFAULT)).map { it.key },
        )
        val mine = Scoring(listOf(Criterion(key = "water", weight = 0), Criterion(key = "power", archived = true), Criterion(key = "parking")), 0.5)
        assertEquals(listOf("water", "parking"), CompareCriteria.comparedRows(BuiltInKind.HOUSE, mine).map { it.key })
    }

    @Test
    fun theSampleOutcomesAreTheVectorsRankingScoresAndMarks() {
        for (o in vectors.getValue("outcomes").jsonArray.map { it.jsonObject }) {
            val kind = kinds.getValue(o.getValue("kind").str())
            val base = Scoring(Scoring.DEFAULT.criteria, o.getValue("ratingShare").jsonPrimitive.double)
            val entries = o.getValue("entries").jsonArray.map { it.jsonObject }.map { e ->
                CompareCandidate(
                    id = e.getValue("id").str(),
                    rating = (e.getValue("rating") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.int,
                    checklist = e.getValue("checklist").jsonObject.mapValues { it.value.jsonPrimitive.int },
                    extrasOther = e.getValue("extras").jsonObject,
                )
            }
            val name = o.getValue("name").str()
            val got = CompareCriteria.outcome(kind, entries, base)
            assertEquals(name, o.getValue("ranking").strings(), got.ranking)
            assertEquals(name, o.getValue("overall").jsonObject.mapValues { if (it.value is JsonNull) null else it.value.jsonPrimitive.double }, got.overall)
            assertEquals(name, o.getValue("best").jsonObject.mapValues { it.value.strings() }, got.best)
            assertEquals("$name, reversed", o.getValue("ranking").strings(), CompareCriteria.outcome(kind, entries.reversed(), base).ranking)
        }
    }

    /** A made-up kind (the built-ins have no extra that is better higher, none hidden and no item left uncounted). */
    private val madeUpLabels = KindLabels(
        kind = "school",
        keys = mapOf(
            "section.details" to null, "check.safety" to "k_safety", "check.extraitem" to "k_extra", "field.rank" to "k_rank", "field.plain" to "k_plain",
            "field.hiddenx" to "k_hidden",
        ),
        byCadence = emptyMap(),
        sections = mapOf("rank" to "details", "plain" to "details", "hiddenx" to "rooms"),
    )
    private val madeUp = BuiltInKind(
        madeUpLabels,
        KindFields(
            madeUpLabels,
            listOf(
                KindExtra("rank", "number", "details", 1, better = "higher"),
                KindExtra("plain", "number", "details", 2),
                KindExtra("hiddenx", "number", "rooms", 3, better = "lower"),
            ),
        ),
        extracts = false,
    )

    @Test
    fun theDirectionIsTheExtrasOwnAndAnExtraTheKindHidesIsLeftOut() {
        assertEquals(
            listOf(listOf("rank", "extra", "higher"), listOf("plain", "extra", null), listOf("extraitem", "check", "higher")),
            CompareCriteria.resolve(listOf("rank", "plain", "hiddenx", "extraitem"), madeUp).map(::shape),
        )
    }

    @Test
    fun anItemIsCountedOnlyWhenTheKindsCompareNamesIt() {
        val scoring = CompareCriteria.scoringFor(madeUp, Scoring.DEFAULT)
        assertEquals(listOf("safety" to 2, "extraitem" to 0), scoring.criteria.map { it.key to it.weight })
        assertEquals(listOf("safety"), CompareCriteria.comparedRows(madeUp, scoring).map { it.key })
    }

    @Test
    fun theBestIsMarkedByTheDirection() {
        assertEquals(setOf(0), CompareCriteria.bestOfExtra(listOf(20L, 35L, null), "lower"))
        assertEquals(setOf(1), CompareCriteria.bestOfExtra(listOf(20L, 35L, null), "higher"))
        assertEquals(emptySet<Int>(), CompareCriteria.bestOfExtra(listOf(20L, 20L, null), "lower"))
        assertEquals(emptySet<Int>(), CompareCriteria.bestOfExtra(listOf(20L, null, null), "lower"))
        assertEquals(emptySet<Int>(), CompareCriteria.bestOfExtra(listOf(20L, 35L), null))
        assertEquals(emptySet<Int>(), CompareCriteria.bestOfExtra(listOf(null, null), "lower"))
        assertEquals(setOf(1), CompareCriteria.bestOf(listOf(3, 5, null)))
        assertEquals(emptySet<Int>(), CompareCriteria.bestOf(listOf(4, 4)))
        assertEquals(setOf(0), CompareCriteria.bestOf(listOf(4)))
    }
}
