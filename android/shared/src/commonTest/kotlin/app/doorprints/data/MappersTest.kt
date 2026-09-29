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

package app.doorprints.data

import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import app.doorprints.shared.sync.SyncRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room entities <-> the shared DTOs (`Mappers.kt`), in common code since the readiness review of 2026-09-29 (moved
 * from `:app`'s `ModelMappingTest`, which keeps the Android label test): the wire format, unknown or missing values,
 * and an all-fields round trip that a swapped argument in a mapper would fail (docs/14 §8 finding 4; TC-U-93).
 */
class MappersTest {

    private val house = HouseEntity(
        id = "h1", label = "Flat", address = "12, 5th Cross", street = "5th Cross", locality = "Indiranagar",
        lat = 12.978321, lon = 77.640812, status = HouseStatus.SHORTLISTED, price = 32_000, priceType = "RENT",
        bedrooms = 2, rating = 4, contactName = "Owner", contactPhone = "+91 98450 00000",
        listingUrl = "https://example.com/l/1", notes = "Water 24x7", checklist = mapOf("water" to 5, "noise" to 2),
        createdAt = 1_790_072_130_000, updatedAt = 1_790_072_130_120, deleted = false, dirty = true,
    )

    @Test
    fun houseMapsToTheSameWireFormatAsBefore() {
        val dto = house.toDto()
        assertEquals("SHORTLISTED", dto.status)
        assertEquals("2026-09-22T10:15:30Z", dto.createdAt)
        assertEquals("2026-09-22T10:15:30.120Z", dto.updatedAt)
        assertEquals(0L, dto.syncVersion)
        // Pulled rows are clean; everything else survives the round trip.
        assertEquals(house.copy(dirty = false), dto.toEntity())
    }

    @Test
    fun everyHouseFieldLandsInItsOwnDtoField() {
        // Field by field, with values that differ from each other, so a swapped pair of same-typed arguments in
        // HouseEntity.toDto() cannot pass as a round trip.
        val dto = house.toDto()
        assertEquals("h1", dto.id); assertEquals("Flat", dto.label); assertEquals("12, 5th Cross", dto.address)
        assertEquals("5th Cross", dto.street); assertEquals("Indiranagar", dto.locality)
        assertEquals(12.978321, dto.lat); assertEquals(77.640812, dto.lon)
        assertEquals(32_000L, dto.price); assertEquals("RENT", dto.priceType); assertEquals(2, dto.bedrooms)
        assertEquals(4, dto.rating); assertEquals("Owner", dto.contactName); assertEquals("+91 98450 00000", dto.contactPhone)
        assertEquals("https://example.com/l/1", dto.listingUrl); assertEquals("Water 24x7", dto.notes)
        assertEquals(mapOf("water" to 5, "noise" to 2), dto.checklist); assertFalse(dto.deleted)
    }

    @Test
    fun serverRowsWithUnknownOrMissingValuesStillMap() {
        val before = IsoTime.nowMillis()
        val entity = HouseDto(id = "h2", label = "x", lat = 0.0, lon = 0.0, status = "ARCHIVED").toEntity()
        assertEquals(HouseStatus.NEW, entity.status)
        assertTrue(entity.createdAt >= before && entity.updatedAt >= before)
        assertFalse(entity.dirty)
        assertEquals(1_790_072_130_123, HouseDto(id = "h3", label = "x", lat = 0.0, lon = 0.0,
            updatedAt = "2026-09-22T10:15:30.123456Z").toEntity().updatedAt)
    }

    @Test
    fun visitMapsBothWays() {
        val visit = VisitEntity(
            id = "v1", houseId = "h1", lat = 1.5, lon = 2.5, street = "MG Road", arrivedAt = 1_790_072_130_000,
            leftAt = 1_790_073_000_500, source = VisitSource.AUTO, updatedAt = 1_790_073_000_501, dirty = true,
        )
        val dto = visit.toDto()
        assertEquals("AUTO", dto.source)
        assertEquals("2026-09-22T10:30:00.500Z", dto.leftAt)
        assertEquals(1.5, dto.lat); assertEquals(2.5, dto.lon); assertEquals("h1", dto.houseId); assertEquals("MG Road", dto.street)
        assertEquals(visit.copy(dirty = false), dto.toEntity())
        assertNull(visit.copy(leftAt = null).toDto().leftAt)
        assertEquals(VisitSource.MANUAL,
            VisitDto(id = "v2", lat = 0.0, lon = 0.0, arrivedAt = "2026-09-22T10:15:30Z", source = null).toEntity().source)
    }

    @Test
    fun scoreAndSyncRulesApplyToRoomEntities() {
        assertEquals(3.5, house.copy(rating = 4, checklist = mapOf("water" to 2, "parking" to 4)).score!!, 1e-9)
        assertNull(house.copy(rating = null, checklist = emptyMap()).score)
        assertTrue(SyncRules.keepLocal(house.copy(updatedAt = 2_000, dirty = true), house.copy(updatedAt = 1_000)))
        assertFalse(SyncRules.keepLocal(null, house))
        val visit = VisitEntity(id = "v1", lat = 0.0, lon = 0.0, arrivedAt = 0, updatedAt = 1_000, dirty = false)
        assertFalse(SyncRules.keepLocal(visit.copy(updatedAt = 9_000), visit))
    }
}
