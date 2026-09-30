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

import app.doorprints.data.RecordEntity
import app.doorprints.data.toBroker
import app.doorprints.shared.records.RecordRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The broker record (docs/11 5.25, slice 1b): its payload, its coercion on read and the rows that are skipped. */
class BrokerTest {
    private val full = Broker(
        name = "Ravi Kumar", phone = "+91 98400 11111", agency = "Adyar Homes", feeTerms = "15 days' rent, once",
        notes = "Replies fast", rating = 4,
    )

    @Test
    fun thePayloadKeysAreTheFormatsAndInItsOrderAndAbsentMeansUnknown() {
        assertEquals(
            "{\"name\":\"Ravi Kumar\",\"phone\":\"+91 98400 11111\",\"agency\":\"Adyar Homes\"," +
                "\"feeTerms\":\"15 days' rent, once\",\"notes\":\"Replies fast\",\"rating\":4}",
            BrokerType.encode(full),
        )
        // Nothing but the name: no null is ever written.
        assertEquals("{\"name\":\"Meena\"}", BrokerType.encode(Broker(name = "Meena")))
        assertEquals("broker", BrokerType.name)
    }

    @Test
    fun aPayloadRoundTripsAndKeepsWhatItDoesNotKnowOnlyUntilItIsEditedHere() {
        val text = BrokerType.encode(full)
        assertEquals(full, RecordRules.json.decodeFromString(BrokerType.serializer, text))
        val fromNewer = RecordRules.json.decodeFromString(BrokerType.serializer, "{\"name\":\"A\",\"nickname\":\"x\"}")
        assertEquals(Broker(name = "A"), fromNewer)
    }

    @Test
    fun aValueOutOfRangeReadsAsUnknownAndTheRestIsKept() {
        val odd = Broker(
            name = "  Ravi  ", phone = "9".repeat(51), agency = "  ", feeTerms = "x".repeat(501), notes = "n".repeat(2001),
            rating = 6,
        )
        assertEquals(Broker(name = "Ravi"), odd.coerced())
        assertEquals(Broker(name = "Ravi", rating = 1), Broker(name = "Ravi", rating = 1).coerced())
        assertNull(Broker(name = "Ravi", rating = 0).coerced()!!.rating)
        assertEquals(full, full.coerced())
    }

    @Test
    fun aBlankOrOversizedNameIsSkippedAsUntrusted() {
        assertNull(Broker(name = "   ").coerced())
        assertNull(Broker(name = "n".repeat(201)).coerced())
        assertEquals(200, Broker(name = "n".repeat(200)).coerced()!!.name.length)
        val row = RecordEntity("broker", "b1", "{\"name\":\" \"}", updatedAt = 1)
        assertNull(row.toBroker())
        assertNull(RecordEntity("broker", "b2", "{\"phone\":\"1\"}", updatedAt = 1).toBroker())
        assertNull(RecordEntity("broker", "b3", "not json", updatedAt = 1).toBroker())
        assertEquals(Broker(name = "Ravi"), RecordEntity("broker", "b4", "{\"name\":\"Ravi\",\"rating\":9}", updatedAt = 1).toBroker())
    }

    @Test
    fun theBackupsCheckIsStricterThanTheReadersCoercion() {
        assertTrue(full.isValid)
        assertFalse(Broker(name = "").isValid)
        assertFalse(Broker(name = "x", rating = 0).isValid)
        assertFalse(Broker(name = "x", rating = 6).isValid)
        assertFalse(Broker(name = "x", feeTerms = "x".repeat(501)).isValid)
        assertTrue(Broker(name = "x", rating = 5).isValid)
    }

    @Test
    fun theLabelAndTheSearchText() {
        assertEquals("Ravi Kumar (Adyar Homes)", full.label)
        assertEquals("Meena", Broker(name = "Meena").label)
        assertEquals("Meena", Broker(name = "Meena", agency = " ").label)
        assertEquals("Ravi Kumar Adyar Homes 15 days' rent, once", full.searchText)
        assertEquals("Meena", Broker(name = "Meena", phone = "1").searchText)
    }
}
