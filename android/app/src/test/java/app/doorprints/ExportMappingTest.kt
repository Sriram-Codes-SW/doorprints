package app.doorprints

import app.doorprints.data.HouseEntity
import app.doorprints.data.PhotoEntity
import app.doorprints.data.VisitEntity
import app.doorprints.data.toEntity
import app.doorprints.data.toExport
import app.doorprints.export.Exporters
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android glue for the offline copy (Sprint 4a): Room entity <-> export model. The export *content* is tested
 * in `:shared`'s commonTest (the golden files); this is only about the round trip through Room's types.
 */
class ExportMappingTest {

    private val house = HouseEntity(
        id = "h1", label = "Flat", address = "12, 5th Cross", street = "5th Cross", locality = "Indiranagar",
        lat = 12.978321, lon = 77.640812, status = HouseStatus.SHORTLISTED, price = 32_000, priceType = "RENT",
        bedrooms = 2, rating = 4, contactName = "Owner", contactPhone = "+91 98450 00000",
        listingUrl = "https://example.com/l/1", notes = "Water 24x7", checklist = mapOf("water" to 5, "noise" to 2),
        createdAt = 1_790_072_130_000, updatedAt = 1_790_072_130_120, deleted = false, dirty = false,
    )

    private val visit = VisitEntity(
        id = "v1", houseId = "h1", lat = 12.978, lon = 77.64, street = "5th Cross",
        arrivedAt = 1_790_003_000_000, leftAt = 1_790_003_600_000, source = VisitSource.AUTO,
        updatedAt = 1_790_003_600_000, deleted = false, dirty = false,
    )

    @Test
    fun aHouseSurvivesTheRoundTripFieldForField() {
        val back = house.toExport().toEntity(dirty = false)
        assertEquals(house, back)
    }

    @Test
    fun aVisitSurvivesTheRoundTripFieldForField() {
        assertEquals(visit, visit.toExport().toEntity(dirty = false))
    }

    @Test
    fun anImportedRowIsLocalAndDirtySoItSyncs() {
        val imported = house.toExport().toEntity()
        assertTrue("an imported row must be pushed on the next sync", imported.dirty)
        assertFalse("an import never writes a tombstone", imported.deleted)
        // The timestamps stay the backup's, so the server reaches the same last-write-wins answer.
        assertEquals(house.updatedAt, imported.updatedAt)
        assertEquals(house.createdAt, imported.createdAt)
    }

    @Test
    fun anUnknownStatusOrSourceFallsBackLikeTheApiMapper() {
        val odd = house.toExport().copy(status = "ARCHIVED")
        assertEquals(HouseStatus.NEW, odd.toEntity().status)
        val oddVisit = visit.toExport().copy(source = "IMPORTED")
        assertEquals(VisitSource.MANUAL, oddVisit.toEntity().source)
    }

    @Test
    fun aPhotoIsNamedAfterItsIdInsideTheCopy() {
        val photo = PhotoEntity("p1", "h1", "/data/photos/p1.jpg", uploaded = true, createdAt = 1)
        assertEquals("p1.jpg", photo.toExport().fileName)
        assertEquals("h1", photo.toExport().houseId)
    }

    @Test
    fun sha256HexIsLowerCaseAndZeroPadded() {
        // Known value: SHA-256 of the empty input.
        val empty = java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Exporters.hex(empty))
        assertEquals("000f", Exporters.hex(byteArrayOf(0, 15)))
    }
}
