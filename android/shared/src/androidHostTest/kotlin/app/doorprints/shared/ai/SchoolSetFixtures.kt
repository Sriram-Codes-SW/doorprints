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
package app.doorprints.shared.ai

import app.doorprints.data.toEntity
import app.doorprints.shared.api.HouseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant

/**
 * The school set of the AI evals (`docs/ai/evals/school-set.json`, S4b-BL-268) as the phone's harness reads it: its own
 * fixtures in the candidate shape, seeded as [AiHouse]s with their kind and extras, the way the repository hands a stored
 * candidate to the AI (`CommonRepository.aiHouses`); the visits of the set as [AiVisit]s. The kinds are on for such a run:
 * [OnDeviceAi] takes `devKinds = true`.
 */
object SchoolSetFixtures {
    private val wire = Json { ignoreUnknownKeys = true }

    private fun repoFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, relative) }.first { it.exists() }

    val file: JsonObject = Json.parseToJsonElement(repoFile("docs/ai/evals/school-set.json").readText()).jsonObject

    val cases: List<JsonObject> get() = file["cases"]!!.jsonArray.map { it.jsonObject }

    fun case(id: String): JsonObject = cases.first { it["id"]!!.jsonPrimitive.content == id }

    /** A wire candidate as the phone stores it, handed to the AI with its kind and its other extras. */
    fun aiHouse(candidate: JsonElement, visits: List<AiVisit> = emptyList()): AiHouse {
        val h = wire.decodeFromJsonElement(HouseDto.serializer(), candidate).toEntity()
        return AiHouse(
            id = h.id, label = h.label, address = h.address, street = h.street, locality = h.locality, lat = h.lat, lon = h.lon,
            status = h.status.name, price = h.price, priceType = h.priceType, bedrooms = h.bedrooms, rating = h.rating,
            contactName = h.contactName, contactPhone = h.contactPhone, listingUrl = h.listingUrl, notes = h.notes,
            areaSqft = h.areaSqft, cost = h.cost, rooms = h.rooms, answers = h.answers, moveIn = h.moveIn, floor = h.floor,
            checklist = h.checklist, visits = visits, kind = h.kind, extras = h.extrasOther,
        )
    }

    /** Every fixture school with its visits (the set's `houseId` is the candidate). */
    fun houses(): List<AiHouse> {
        val visits = file["fixtureVisits"]!!.jsonArray.map { it.jsonObject }.groupBy({ it["houseId"]!!.jsonPrimitive.content }) { v ->
            AiVisit(Instant.parse(v["arrivedAt"]!!.jsonPrimitive.content).toEpochMilli(), v["leftAt"]?.jsonPrimitive?.content?.let { Instant.parse(it).toEpochMilli() })
        }
        return file["fixtureCandidates"]!!.jsonArray.map { c -> aiHouse(c, visits[c.jsonObject["id"]!!.jsonPrimitive.content].orEmpty()) }
    }
}
