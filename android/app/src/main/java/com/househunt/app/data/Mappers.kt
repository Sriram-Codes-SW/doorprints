package com.househunt.app.data

import com.househunt.shared.api.HouseDto
import com.househunt.shared.api.IsoTime
import com.househunt.shared.api.VisitDto
import com.househunt.shared.model.HouseStatus
import com.househunt.shared.model.VisitSource

// Room entity <-> API DTO. The DTOs and the time/enum parsing live in :shared; the entities stay here with Room
// until Phase 2. Behaviour is unchanged from the v0.1 mappers in data/Api.kt: ISO-8601 instants on the wire,
// unknown status/source fall back to NEW/MANUAL, a missing server timestamp becomes "now", pulled rows are clean.

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
    createdAt = createdAt?.let(IsoTime::parseMillis) ?: System.currentTimeMillis(),
    updatedAt = updatedAt?.let(IsoTime::parseMillis) ?: System.currentTimeMillis(),
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
    updatedAt = updatedAt?.let(IsoTime::parseMillis) ?: System.currentTimeMillis(), deleted = deleted, dirty = false,
)
