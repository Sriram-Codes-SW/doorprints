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

import app.doorprints.shared.model.Scoring
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.export.ExportPhoto
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
        areaSqft = 1150, locationSource = "GPS",
        cost = HouseCost(deposit = 64_000, depositMonths = 2, maintenance = 2_500, maintenanceIncluded = false, brokerage = 16_000,
            brokerageMonths = 1, lockInMonths = 11, noticeMonths = 3, availableFrom = "2026-10-15", myOffer = 30_000, agreedPrice = 31_000),
        rooms = listOf(
            HouseRoom("r1", "BEDROOM", "Master bedroom", 396, 366, 4, "Damp patch", 0),
            HouseRoom("r2", "KITCHEN", lengthCm = 300, widthCm = 244, sort = 1),
        ),
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
        assertEquals(1150, dto.areaSqft); assertEquals("GPS", dto.locationSource); assertEquals(house.cost, dto.cost)
        assertEquals(house.rooms, dto.rooms); assertEquals(house.rooms, house.toExport().rooms)
    }

    @Test
    fun answersSurviveEveryMappingAndAreCoercedOnTheWayIn() {
        // Slice 3a: the answers after the rooms, the same list on the wire, in a copy and back.
        val answers = listOf(
            HouseAnswer("a1", "qd_water", "Where does the water come from?", "Borewell", "ANSWERED", 0),
            HouseAnswer("a2", text = "Is the terrace open?", sort = 1),
        )
        val h = house.copy(answers = answers)
        assertEquals(answers, h.toDto().answers)
        assertEquals(answers, h.toExport().answers)
        assertEquals(answers, h.toDto().toEntity().answers)
        assertEquals(answers, h.toExport().toEntity().answers)
        // A typed answer with OPEN reads ANSWERED, a repeated id is dropped; [] reads as none and none is never [].
        val odd = listOf(HouseAnswer("a1", text = "Q", answer = "Yes"), HouseAnswer("a1", text = "Again", sort = 1))
        assertEquals(listOf(HouseAnswer("a1", text = "Q", answer = "Yes", status = "ANSWERED")), h.toDto().copy(answers = odd).toEntity().answers)
        assertNull(h.toDto().copy(answers = emptyList()).toEntity().answers)
        assertNull(h.copy(answers = emptyList()).toDto().answers)
        assertNull(h.copy(answers = emptyList()).toExport().answers)
        // Room's column: JSON text in the format's key order, and back.
        val converters = Converters()
        val text = converters.answersToJson(answers)
        assertEquals(
            "[{\"id\":\"a1\",\"questionId\":\"qd_water\",\"text\":\"Where does the water come from?\",\"answer\":\"Borewell\"," +
                "\"status\":\"ANSWERED\",\"sort\":0},{\"id\":\"a2\",\"text\":\"Is the terrace open?\",\"status\":\"OPEN\",\"sort\":1}]",
            text,
        )
        assertEquals(answers, converters.jsonToAnswers(text))
        assertNull(converters.answersToJson(emptyList()))
        assertNull(converters.jsonToAnswers("not json"))
    }

    @Test
    fun theMoveInAndThePhotoMetaSurviveEveryMappingAndAreCoercedOnTheWayIn() {
        // Slice 5: the move-in after the answers on the wire, in a copy and back; a bad item is dropped on the way in.
        val moveIn = MoveIn(1_790_812_800_000L, "Keys", listOf(MoveInItem("mi_keys", "Keys received", true, 0)))
        val h = house.copy(status = HouseStatus.TAKEN, moveIn = moveIn)
        assertEquals(moveIn, h.toDto().moveIn)
        assertEquals("TAKEN", h.toDto().status)
        assertEquals(moveIn, h.toExport().moveIn)
        assertEquals(h.copy(dirty = false), h.toDto().toEntity())
        assertEquals(moveIn, h.toExport().toEntity().moveIn)
        val odd = moveIn.copy(items = moveIn.items!! + MoveInItem("a/b", "Bad id"), date = 0)
        assertEquals(MoveIn(notes = "Keys", items = moveIn.items), h.toDto().copy(moveIn = odd).toEntity().moveIn)
        assertNull(h.toDto().copy(moveIn = MoveIn()).toEntity().moveIn)
        assertEquals(HouseStatus.NOT_CHOSEN, h.toDto().copy(status = "NOT_CHOSEN").toEntity().status)
        // Room's column: the move-in as JSON text, and back.
        val converters = Converters()
        assertEquals(moveIn, converters.jsonToMoveIn(converters.moveInToJson(moveIn)))
        assertNull(converters.moveInToJson(MoveIn()))
        assertEquals(listOf("MOVE_IN", "damp corner"), converters.jsonToTags(converters.tagsToJson(listOf("MOVE_IN", "damp corner"))))
        assertNull(converters.tagsToJson(emptyList()))
        assertNull(converters.jsonToTags("not json"))
        // A photo's meta: pulled coerced, written into the row, sent as the PUT body, and exported only when set.
        val change = PhotoChangeDto(id = "p1", houseId = "h1", roomId = "r1", tags = listOf("leak", "LEAK", "corner"), caption = "Tap", metaUpdatedAt = 9)
        assertEquals(PhotoMeta("r1", listOf("LEAK", "corner"), "Tap", 9), change.meta())
        val photo = PhotoEntity("p1", "h1", "/p/p1.jpg", uploaded = true, createdAt = 1).withMeta(change.meta(), dirty = true)
        assertEquals(PhotoMetaDto("r1", listOf("LEAK", "corner"), "Tap", 9), photo.toMetaDto())
        assertTrue(photo.metaDirty)
        assertEquals(ExportPhoto("p1", "h1", "p1.jpg", 1, "r1", listOf("LEAK", "corner"), "Tap", 9), photo.toExport())
        assertEquals(ExportPhoto("p1", "h1", "p1.jpg", 1), PhotoEntity("p1", "h1", "/p/p1.jpg", createdAt = 1).toExport())
        // A file's photo with meta is written with it, marked to be sent once uploaded.
        val imported = photo.toExport().toEntity("/p/p1.jpg")
        assertEquals(photo.meta, imported.meta)
        assertTrue(imported.metaDirty)
        assertFalse(imported.uploaded)
    }

    @Test
    fun aServerThatWritesMetaUpdatedAtAsAnInstantStillSyncs() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val iso = json.decodeFromString(PhotoChangeDto.serializer(), "{\"id\":\"p1\",\"houseId\":\"h1\",\"metaUpdatedAt\":\"2026-09-21T14:13:20Z\"}")
        assertEquals(1_790_000_000_000L, iso.metaUpdatedAt)
        val number = json.decodeFromString(PhotoChangeDto.serializer(), "{\"id\":\"p1\",\"houseId\":\"h1\",\"metaUpdatedAt\":5}")
        assertEquals(5L, number.metaUpdatedAt)
        val absent = json.decodeFromString(PhotoChangeDto.serializer(), "{\"id\":\"p1\",\"houseId\":\"h1\",\"metaUpdatedAt\":null}")
        assertNull(absent.metaUpdatedAt)
        assertEquals(0L, absent.meta().metaUpdatedAt)
    }

    @Test
    fun pulledAndImportedRoomsAreCoercedAndNoneIsNeverAnEmptyList() {
        // Slice 1c: an unknown type is OTHER, a size out of range unknown, a duplicate id dropped; [] reads as none.
        val odd = listOf(HouseRoom("r1", "GARAGE", lengthCm = 9_999, condition = 7), HouseRoom("r1", "HALL", sort = 1))
        val expected = listOf(HouseRoom("r1", "OTHER"))
        assertEquals(expected, house.toDto().copy(rooms = odd).toEntity().rooms)
        assertEquals(expected, house.toExport().copy(rooms = odd).toEntity().rooms)
        assertNull(house.toDto().copy(rooms = emptyList()).toEntity().rooms)
        assertNull(house.copy(rooms = emptyList()).toDto().rooms)
        assertNull(house.copy(rooms = emptyList()).toExport().rooms)
        // Room's column: JSON text in the format's key order, and back.
        val converters = Converters()
        val text = converters.roomsToJson(house.rooms)
        assertEquals(
            "[{\"id\":\"r1\",\"type\":\"BEDROOM\",\"name\":\"Master bedroom\",\"lengthCm\":396,\"widthCm\":366," +
                "\"condition\":4,\"notes\":\"Damp patch\",\"sort\":0},{\"id\":\"r2\",\"type\":\"KITCHEN\",\"lengthCm\":300," +
                "\"widthCm\":244,\"sort\":1}]",
            text,
        )
        assertEquals(house.rooms, converters.jsonToRooms(text))
        assertNull(converters.roomsToJson(emptyList()))
        assertNull(converters.jsonToRooms("not json"))
    }

    @Test
    fun aPulledRowsValuesAreCoercedNotRefused() {
        // Slice 1a: a field outside its range reads as unknown, an empty cost as none, the rest of the house stays.
        val odd = house.toDto().copy(areaSqft = 0, locationSource = "somewhere", cost = HouseCost(deposit = -5, noticeMonths = 2))
        val entity = odd.toEntity()
        assertNull(entity.areaSqft); assertNull(entity.locationSource)
        assertEquals(HouseCost(noticeMonths = 2), entity.cost)
        assertEquals("Flat", entity.label)
        assertNull(house.toDto().copy(cost = HouseCost()).toEntity().cost)
        // An empty cost on this phone is written absent, never as {}.
        assertNull(house.copy(cost = HouseCost()).toDto().cost)
        assertNull(house.copy(cost = HouseCost()).toExport().cost)
        assertEquals(house.copy(dirty = false), house.toExport().toEntity(dirty = false))
        assertNull(house.toExport().copy(areaSqft = 100_001, cost = HouseCost(availableFrom = "soon")).toEntity().areaSqft)
        assertNull(house.toExport().copy(cost = HouseCost(availableFrom = "soon")).toEntity().cost)
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
        assertEquals(3.5, house.copy(rating = 4, checklist = mapOf("water" to 2, "parking" to 4)).score(Scoring.DEFAULT)!!, 1e-9)
        assertNull(house.copy(rating = null, checklist = emptyMap()).score(Scoring.DEFAULT))
        assertTrue(SyncRules.keepLocal(house.copy(updatedAt = 2_000, dirty = true), house.copy(updatedAt = 1_000)))
        assertFalse(SyncRules.keepLocal(null, house))
        val visit = VisitEntity(id = "v1", lat = 0.0, lon = 0.0, arrivedAt = 0, updatedAt = 1_000, dirty = false)
        assertFalse(SyncRules.keepLocal(visit.copy(updatedAt = 9_000), visit))
    }
}
