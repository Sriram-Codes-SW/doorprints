package app.doorprints.data

import app.doorprints.shared.export.ExportHouse
import app.doorprints.shared.export.ExportPhoto
import app.doorprints.shared.export.ExportVisit
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource

// Room entity <-> the platform-neutral export model (Sprint 4a, S4-02/S4-04; common code since CMP-4 P4c). The
// export model is deliberately *not* the API DTO: a backup is a copy of the local store, so it keeps epoch
// milliseconds and leaves out the server-only fields (syncVersion) and the local-only ones (dirty, deleted, the
// photo's file path). Everything a round trip needs is here; everything else is re-derived on import.

fun HouseEntity.toExport() = ExportHouse(
    id = id, label = label, address = address, street = street, locality = locality, lat = lat, lon = lon,
    status = status.name, price = price, priceType = priceType, bedrooms = bedrooms, rating = rating,
    contactName = contactName, contactPhone = contactPhone, listingUrl = listingUrl, notes = notes,
    checklist = checklist, createdAt = createdAt, updatedAt = updatedAt,
)

/**
 * An imported row is **local and dirty**, so the next sync pushes it; its timestamps stay the ones the backup
 * carried, so the server reaches the same last-write-wins answer as the phone did.
 */
fun ExportHouse.toEntity(dirty: Boolean = true) = HouseEntity(
    id = id, label = label, address = address, street = street, locality = locality, lat = lat, lon = lon,
    status = HouseStatus.fromWire(status), price = price, priceType = priceType, bedrooms = bedrooms,
    rating = rating, contactName = contactName, contactPhone = contactPhone, listingUrl = listingUrl,
    notes = notes, checklist = checklist, createdAt = createdAt, updatedAt = updatedAt,
    deleted = false, dirty = dirty,
)

fun VisitEntity.toExport() = ExportVisit(
    id = id, houseId = houseId, lat = lat, lon = lon, street = street, arrivedAt = arrivedAt, leftAt = leftAt,
    source = source.name, updatedAt = updatedAt,
)

fun ExportVisit.toEntity(dirty: Boolean = true) = VisitEntity(
    id = id, houseId = houseId, lat = lat, lon = lon, street = street, arrivedAt = arrivedAt, leftAt = leftAt,
    source = VisitSource.fromWire(source), updatedAt = updatedAt, deleted = false, dirty = dirty,
)

/**
 * The file name a photo gets inside a copy. The row id is a UUID, so `<id>.jpg` is unique, is safe on every file
 * system and needs no escaping — and it is what an import looks for in the ZIP.
 */
fun PhotoEntity.toExport() = ExportPhoto(id = id, houseId = houseId, fileName = "$id.jpg", createdAt = createdAt)
