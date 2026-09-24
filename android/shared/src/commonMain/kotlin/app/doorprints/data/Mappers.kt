package app.doorprints.data

import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource

// Room entity <-> API DTO, common code since CMP-4 P4c (were :app's; the entities moved in P4a). Behaviour is
// unchanged from the v0.1 mappers in data/Api.kt: ISO-8601 instants on the wire, unknown status/source fall back to
// NEW/MANUAL, a missing server timestamp becomes "now" (IsoTime.nowMillis, the same wall clock as
// System.currentTimeMillis), pulled rows are clean.

fun HouseEntity.toDto() = HouseDto(
    id, label, address, street, locality, lat, lon, status.name, price, priceType, bedrooms, rating,
    contactName, contactPhone, listingUrl, notes, checklist, IsoTime.format(createdAt), IsoTime.format(updatedAt),
    deleted,
)

fun HouseDto.toEntity() = HouseEntity(
    id = id, label = label, address = address, street = street, locality = locality, lat = lat, lon = lon,
    status = HouseStatus.fromWire(status),
    price = price, priceType = priceType, bedrooms = bedrooms, rating = rating, contactName = contactName,
    contactPhone = contactPhone, listingUrl = listingUrl, notes = notes, checklist = checklist,
    createdAt = createdAt?.let(IsoTime::parseMillis) ?: IsoTime.nowMillis(),
    updatedAt = updatedAt?.let(IsoTime::parseMillis) ?: IsoTime.nowMillis(),
    deleted = deleted, dirty = false,
)

fun VisitEntity.toDto() = VisitDto(
    id, houseId, lat, lon, street, IsoTime.format(arrivedAt), leftAt?.let(IsoTime::format), source.name,
    IsoTime.format(updatedAt), deleted,
)

fun VisitDto.toEntity() = VisitEntity(
    id = id, houseId = houseId, lat = lat, lon = lon, street = street, arrivedAt = IsoTime.parseMillis(arrivedAt),
    leftAt = leftAt?.let(IsoTime::parseMillis),
    source = VisitSource.fromWire(source),
    updatedAt = updatedAt?.let(IsoTime::parseMillis) ?: IsoTime.nowMillis(), deleted = deleted, dirty = false,
)
