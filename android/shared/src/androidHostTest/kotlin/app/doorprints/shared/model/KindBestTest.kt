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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which way a number or an amount is better (`better` of the kind file, S4b-BL-292; docs/03 §18.16), the twin of the
 * website's `compare-space.spec.ts`: the school's commute and both fees are better lower, nothing else says, and the best is
 * marked only when two candidates have a value and they do not tie. [SchoolKindVectorsTest] holds [SchoolKind] to the file.
 */
class KindBestTest {
    private val extra = SchoolKind.FIELDS.extras.associateBy { it.id }
    private fun money(id: String, amount: Long?) = amount?.let { KindValue.Money(extra.getValue(id), it) }
    private fun count(id: String, v: Long?) = v?.let { KindValue.Count(extra.getValue(id), it) }

    @Test
    fun theSchoolsCommuteAndFeesAreBetterLowerAndNothingElseSays() {
        assertEquals(
            listOf("commuteMinutes" to "lower", "fees" to "lower", "admissionFee" to "lower"),
            SchoolKind.FIELDS.extras.filter { it.better != null }.map { it.id to it.better },
        )
        assertNull(KindFields.HOUSE.extras.firstOrNull { it.better != null })
    }

    @Test
    fun theLowestIsMarkedAndAllOfAnEqualLowest() {
        assertEquals(setOf(1), KindValues.best(extra.getValue("commuteMinutes"), listOf(count("commuteMinutes", 30), count("commuteMinutes", 12), count("commuteMinutes", 45))))
        assertEquals(setOf(0, 2), KindValues.best(extra.getValue("fees"), listOf(money("fees", 90_000), money("fees", 120_000), money("fees", 90_000))))
        assertEquals("a candidate with none is not best", setOf(1), KindValues.best(extra.getValue("admissionFee"), listOf(null, money("admissionFee", 5_000), money("admissionFee", 8_000))))
    }

    @Test
    fun aTieOneValueOrNoneMarksNothing() {
        val fees = extra.getValue("fees")
        assertEquals(emptySet<Int>(), KindValues.best(fees, listOf(money("fees", 90_000), money("fees", 90_000))))
        assertEquals("one value is a tie with itself", emptySet<Int>(), KindValues.best(fees, listOf(money("fees", 90_000), null)))
        assertEquals(emptySet<Int>(), KindValues.best(fees, listOf(null, null)))
        assertEquals(emptySet<Int>(), KindValues.best(fees, emptyList()))
        assertEquals("zero is a value", setOf(0), KindValues.best(extra.getValue("commuteMinutes"), listOf(count("commuteMinutes", 0), count("commuteMinutes", 5))))
    }

    @Test
    fun anExtraWithNoHintOrOfAnotherTypeHasNoBestAndHigherIsTheOtherWay() {
        val grades = extra.getValue("grades")
        assertEquals(emptySet<Int>(), KindValues.best(grades, listOf(KindValue.Text(grades, "1-5"), KindValue.Text(grades, "1-8"))))
        val bus = extra.getValue("busService")
        assertEquals(emptySet<Int>(), KindValues.best(bus, listOf(KindValue.Bool(bus, true), KindValue.Bool(bus, false))))
        val higher = extra.getValue("commuteMinutes").copy(better = "higher")
        assertEquals(setOf(2), KindValues.best(higher, listOf(count("commuteMinutes", 30), count("commuteMinutes", 12), count("commuteMinutes", 45))))
        assertEquals(emptySet<Int>(), KindValues.best(extra.getValue("commuteMinutes").copy(better = "sideways"), listOf(count("commuteMinutes", 30), count("commuteMinutes", 12))))
    }
}
