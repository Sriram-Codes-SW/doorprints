package com.househunt.app

import com.househunt.app.data.ChecklistLabels
import com.househunt.app.data.HouseEntity
import com.househunt.app.data.VisitEntity
import com.househunt.app.data.labelRes
import com.househunt.app.data.toDto
import com.househunt.app.data.toEntity
import com.househunt.shared.api.HouseDto
import com.househunt.shared.api.VisitDto
import com.househunt.shared.model.Checklist
import com.househunt.shared.model.HouseStatus
import com.househunt.shared.model.VisitSource
import com.househunt.shared.sync.SyncRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android glue around :shared (Sprint 3.5): Room entities <-> shared DTOs, translated labels for the shared
 * enums and checklist keys, and the shared rules applied to Room entities. The rules themselves are tested in
 * :shared's commonTest.
 */
class ModelMappingTest {

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
    fun serverRowsWithUnknownOrMissingValuesStillMap() {
        val before = System.currentTimeMillis()
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

    @Test
    fun everySharedKeyAndStatusHasATranslatedLabel() {
        assertEquals(Checklist.keys, ChecklistLabels.items.keys.toList())
        assertEquals(Checklist.keys.size, ChecklistLabels.items.values.toSet().size)
        assertEquals(HouseStatus.entries.size, HouseStatus.entries.map { it.labelRes }.toSet().size)
        assertEquals(R.string.status_NEW, HouseStatus.NEW.labelRes)
    }
}
