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
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.records.RecordRules
import app.doorprints.shared.records.decode
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.BrokerType
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.HouseValues
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.model.VisitSource
import kotlinx.serialization.json.JsonObject

// Room entity <-> API DTO, common code since CMP-4 P4c (were :app's; the entities moved in P4a). Behaviour is
// unchanged from the v0.1 mappers in data/Api.kt: ISO-8601 instants on the wire, unknown status/source fall back to
// NEW/MANUAL, a missing server timestamp becomes "now" (IsoTime.nowMillis, the same wall clock as
// System.currentTimeMillis), pulled rows are clean.

// Named arguments on purpose (readiness review 2026-09-29, docs/14 §8 finding 4): Sprint 4c adds eight same-typed
// numbers to the house, and a positional call would let a swapped pair compile and mis-map in silence.
fun HouseEntity.toDto() = HouseDto(
    id = id, label = label, address = address, street = street, locality = locality, lat = lat, lon = lon,
    status = status.name, price = price, priceType = priceType, bedrooms = bedrooms, rating = rating,
    contactName = contactName, contactPhone = contactPhone, listingUrl = listingUrl, notes = notes,
    areaSqft = areaSqft, locationSource = locationSource, cost = cost?.orNull(), rooms = rooms?.takeIf { it.isNotEmpty() },
    answers = answers?.takeIf { it.isNotEmpty() }, brokerId = brokerId, checklist = checklist,
    createdAt = IsoTime.format(createdAt), updatedAt = IsoTime.format(updatedAt),
    deleted = deleted,
)

// A pulled row's values are coerced, not refused (slice 1a): a field outside its range becomes unknown and the rest
// of the house is kept, as the web's reader does; the server refuses such a row on its own PUT.

fun HouseDto.toEntity() = HouseEntity(
    id = id, label = label, address = address, street = street, locality = locality, lat = lat, lon = lon,
    status = HouseStatus.fromWire(status),
    price = price, priceType = priceType, bedrooms = bedrooms, rating = rating, contactName = contactName,
    contactPhone = contactPhone, listingUrl = listingUrl, notes = notes,
    areaSqft = HouseValues.areaSqft(areaSqft), locationSource = LocationSource.orNull(locationSource),
    cost = cost?.coerced(), rooms = HouseRooms.coerced(rooms), answers = HouseAnswers.coerced(answers),
    brokerId = brokerId?.takeIf(RecordRules::isValidId),
    checklist = checklist, createdAt = createdAt?.let(IsoTime::parseMillis) ?: IsoTime.nowMillis(),
    updatedAt = updatedAt?.let(IsoTime::parseMillis) ?: IsoTime.nowMillis(),
    deleted = deleted, dirty = false,
)

fun VisitEntity.toDto() = VisitDto(
    id = id, houseId = houseId, lat = lat, lon = lon, street = street, arrivedAt = IsoTime.format(arrivedAt),
    leftAt = leftAt?.let(IsoTime::format), source = source.name, updatedAt = IsoTime.format(updatedAt),
    deleted = deleted,
)

fun VisitDto.toEntity() = VisitEntity(
    id = id, houseId = houseId, lat = lat, lon = lon, street = street, arrivedAt = IsoTime.parseMillis(arrivedAt),
    leftAt = leftAt?.let(IsoTime::parseMillis),
    source = VisitSource.fromWire(source),
    updatedAt = updatedAt?.let(IsoTime::parseMillis) ?: IsoTime.nowMillis(), deleted = deleted, dirty = false,
)

/** The stored JSON text goes out as the object it is; a payload that is not one (never written here) becomes `{}`. */
fun RecordEntity.toDto() = RecordDto(
    type = type, id = id,
    payload = runCatching { RecordRules.json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap()),
    updatedAt = IsoTime.format(updatedAt), deleted = deleted,
)

/**
 * Null for a row this phone cannot use (a type or id outside the rules, a payload over the cap): the pull skips it
 * and the cursor still moves past it, as the web does ("a row that cannot become usable does not hold the cursor").
 */
fun RecordDto.toEntity(): RecordEntity? {
    if (!RecordRules.isValidType(type) || !RecordRules.isValidId(id)) return null
    val text = payload.toString()
    if (!RecordRules.fitsPayload(text)) return null
    return RecordEntity(
        type = type, id = id, payload = text,
        updatedAt = updatedAt?.let(IsoTime::parseMillis) ?: IsoTime.nowMillis(),
        deleted = deleted, dirty = false,
    )
}

/** The row's broker with its values coerced; null when it does not decode or has no usable name (skipped as untrusted). */
fun RecordEntity.toBroker(): Broker? = decode(BrokerType)?.coerced()
