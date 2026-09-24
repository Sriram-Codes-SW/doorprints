package app.doorprints.data

import app.doorprints.shared.export.ExportBundle
import app.doorprints.shared.export.ExportHouse
import app.doorprints.shared.export.ExportOptions
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

/**
 * What a copy made with [options] holds, from rows already read (`Repository.localRows`). Pure CPU work (mapping and
 * filtering every row): the workers build their file from it (`:app`'s `ExportBuilder.build`), and the Export screen's
 * live count calls it on `Dispatchers.Default` with rows it read once, instead of re-reading the database and mapping
 * on the main thread for every option tap. Common since ADR-23 CMP-6 P6b.
 */
fun Repository.LocalRows.toBundle(options: ExportOptions): ExportBundle = ExportBundle.build(
    options,
    houses.map { it.toExport() },
    visits.map { it.toExport() },
    photos.map { it.toExport() },
)
