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
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.model.DevKinds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Kind-aware AI on the phone and the iPhone (kinds PR 7, S4b-BL-211; docs/03 ADR-36 §18.11), `:shared`: [KindAi] and
 * [OnDeviceAi] against `docs/schemas/kinds/kind-ai-vectors.json`, which the server and the website read too, and the copy
 * of `school.json`'s `ai` slots. A house's output is pinned apart ([HouseAiPinsTest]).
 */
class KindAiVectorsTest {
    private fun repoFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, relative) }.first { it.exists() }

    private val v = Json.parseToJsonElement(repoFile("docs/schemas/kinds/kind-ai-vectors.json").readText()).jsonObject
    private val school = Json.parseToJsonElement(repoFile("docs/schemas/kinds/school.json").readText()).jsonObject
    private val wire = Json { ignoreUnknownKeys = true }

    private fun strings(e: JsonElement?) = e!!.jsonArray.map { it.jsonPrimitive.content }

    /** A wire candidate as the phone stores it and its repository hands it to the AI (`CommonRepository.aiHouses`). */
    private fun aiHouse(candidate: JsonElement): AiHouse {
        val h = wire.decodeFromJsonElement(HouseDto.serializer(), candidate).toEntity()
        return AiHouse(
            id = h.id, label = h.label, address = h.address, street = h.street, locality = h.locality, lat = h.lat, lon = h.lon,
            status = h.status.name, price = h.price, priceType = h.priceType, bedrooms = h.bedrooms, rating = h.rating,
            contactName = h.contactName, contactPhone = h.contactPhone, listingUrl = h.listingUrl, notes = h.notes,
            areaSqft = h.areaSqft, cost = h.cost, rooms = h.rooms, answers = h.answers, moveIn = h.moveIn, floor = h.floor,
            checklist = h.checklist, kind = h.kind, extras = h.extrasOther,
        )
    }

    private fun doc(i: Int) = v["documents"]!!.jsonArray[i].jsonObject

    @Test
    fun theCopyIsTheKindFilesSlotsAndTheVectorsWords() {
        val ai = school["ai"]!!.jsonObject
        val k = KindAi.SCHOOL
        assertEquals(listOf(strings(ai["extract"]), strings(ai["ask"]), strings(ai["plan"]), strings(ai["never"])), listOf(k.extract, k.ask, k.plan, k.never))
        val named = (k.extract + k.ask + k.plan).toSet()
        val fileExtras = school["extras"]!!.jsonArray.map { it.jsonObject }.filter { it["id"]!!.jsonPrimitive.content in named }
        assertEquals(fileExtras.map { it["id"]!!.jsonPrimitive.content }, k.extras.map { it.id })
        val words = v["kinds"]!!.jsonArray[0].jsonObject
        assertEquals(words["noun"]!!.jsonPrimitive.content, k.noun)
        for (e in fileExtras) {
            val copy = k.extra(e["id"]!!.jsonPrimitive.content)!!
            assertEquals(e["type"]!!.jsonPrimitive.content, copy.type)
            assertEquals(e["maxLength"]?.jsonPrimitive?.int, copy.maxLength)
            assertEquals(e["max"]?.jsonPrimitive?.content?.toLong(), copy.max)
            e["cadences"]?.let { assertEquals(strings(it), listOf(copy.cadence)) }
            e["options"]?.let { o -> assertEquals(o.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }, copy.options.keys.toList()) }
            val w = words["extras"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == copy.id }
            assertEquals(w["words"]!!.jsonPrimitive.content, copy.words)
            (w["options"] as? JsonObject)?.forEach { (id, word) -> assertEquals(word.jsonPrimitive.content, copy.options[id]) }
        }
        for (id in k.never) assertFalse(id in k.ask + k.plan, id)
        assertTrue(DevKinds.of("school", true).extracts)
    }

    @Test
    fun withTheFlagOffForTheHouseAndForAKindNotBuiltInTheAiReadsAHouse() {
        assertNull(KindAi.of("school", false))
        assertSame(KindAi.SCHOOL, KindAi.of("school", true))
        for (k in listOf("house", "clinic", null)) assertNull(KindAi.of(k, true))
    }

    @Test
    fun everyDocumentHasTheVectorsLinesAndTheAskPromptStripsATag() {
        for (c in v["documents"]!!.jsonArray.map { it.jsonObject }) {
            val on = c["devKinds"]!!.jsonPrimitive.boolean
            assertEquals(c["expected"]!!.jsonPrimitive.content, KindAi.aiText(KindAi.askView(aiHouse(c["candidate"]!!), on), on), c["name"].toString())
        }
        val ask = v["ask"]!!.jsonObject
        val docs = ask["documents"]!!.jsonArray.map { i -> aiHouse(doc(i.jsonPrimitive.int)["candidate"]!!).let { it.id to KindAi.aiText(KindAi.askView(it, true), true) } }
        assertEquals(ask["user"]!!.jsonPrimitive.content, AiPrompts.ask(ask["question"]!!.jsonPrimitive.content, docs, ask["nonce"]!!.jsonPrimitive.content).user)
    }

    @Test
    fun anAskFilterOnAFieldTheKindHidesNeverKeepsTheSchool() {
        for (c in v["filters"]!!.jsonArray.map { it.jsonObject }) {
            val on = c["devKinds"]!!.jsonPrimitive.boolean
            val f = c["filter"]!!.jsonObject
            val filters = AskFilters(f["status"]?.jsonPrimitive?.content, f["priceType"]?.jsonPrimitive?.content,
                f["maxPrice"]?.jsonPrimitive?.content?.toLong(), f["minBedrooms"]?.jsonPrimitive?.int, f["minRating"]?.jsonPrimitive?.int)
            val h = aiHouse(doc(c["candidate"]!!.jsonPrimitive.int)["candidate"]!!)
            val kept = OnDeviceSelection.forAsk(listOf(KindAi.askView(h, on)), "school", filters) { KindAi.aiText(it, on) }
            assertEquals(c["matches"]!!.jsonPrimitive.boolean, kept.size == 1, c.toString())
        }
    }

    @Test
    fun aPlanCandidateHasOnlyWhatThePlanSlotNames() {
        for (c in v["plan"]!!.jsonArray.map { it.jsonObject }) {
            val on = c["devKinds"]!!.jsonPrimitive.boolean
            val h = aiHouse(doc(c["candidate"]!!.jsonPrimitive.int)["candidate"]!!)
            val start = c["start"]!!.jsonArray.map { it.jsonPrimitive.double }
            val candidate = OnDeviceSelection.forPlan(listOf(KindAi.planView(h, on)), start[0], start[1]).single()
            assertEquals(c["line"]!!.jsonPrimitive.content, OnDeviceSelection.candidateLines(listOf(candidate)))
            val s = c["summary"]!!.jsonObject
            assertEquals(s["price"]?.jsonPrimitive?.contentOrNull?.toLong(), candidate.price)
            assertEquals(s["priceType"]?.jsonPrimitive?.contentOrNull, candidate.priceType)
            assertNull(candidate.bedrooms)
        }
    }

    @Test
    fun neverWinsOverAskAndAValueOutsideItsTypeIsNoLine() {
        val pine = aiHouse(doc(0)["candidate"]!!)
        val strict = KindAi.SCHOOL.copy(never = listOf("contactName", "contactPhone", "notes", "board"))
        val text = KindAi.text(pine, strict)
        assertFalse("Notes:" in text || "Board:" in text, text)
        val r = ContactRedactor.forContact(null, null)
        val fees = KindAi.SCHOOL.extra("fees")!!
        fun money(amount: Number, cadence: String) = buildJsonObject { put("amount", amount); put("cadence", cadence) }
        assertEquals("Rs 96000", KindAi.extraValue(fees, money(96000, "year"), r))
        for (bad in listOf(money(96000, "month"), money(-1, "year"), money(10_000_000_000_000L, "year"), JsonPrimitive(96000), JsonNull)) {
            assertNull(KindAi.extraValue(fees, bad, r), bad.toString())
        }
    }

    private fun draftJson(d: HouseDraftDto): JsonElement {
        val o = Json { encodeDefaults = true; explicitNulls = true }.encodeToJsonElement(HouseDraftDto.serializer(), d).jsonObject
        return JsonObject(o.filterKeys { it != "areaSqft" && (it != "extras" || o[it] !is JsonNull) })
    }

    @Test
    fun theSchoolsExtractPromptSchemaAndDraftsAreTheVectors() {
        val e = v["extract"]!!.jsonObject
        val p = KindAi.extractionPrompt(KindAi.SCHOOL, e["listing"]!!.jsonPrimitive.content, e["nonce"]!!.jsonPrimitive.content)
        assertEquals(e["system"]!!.jsonPrimitive.content, p.system)
        assertEquals(e["user"]!!.jsonPrimitive.content, p.user)
        val props = KindAi.SCHOOL_LISTING_SCHEMA["properties"]!!.jsonObject
        assertEquals(e["schema"]!!.jsonObject.keys.toList(), props.keys.toList())
        e["schema"]!!.jsonObject.forEach { (k, d) -> assertEquals(d.jsonPrimitive.content, props[k]!!.jsonObject["description"]!!.jsonPrimitive.content) }
        assertEquals("listing", SchemaDialect.nameOf(KindAi.SCHOOL_LISTING_SCHEMA))
        for (c in e["sanitize"]!!.jsonArray.map { it.jsonObject }) {
            val raw = c["raw"] as? JsonObject
            val got = KindAi.sanitize(KindAi.SCHOOL, raw, c["source"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(c["expected"].toString()), draftJson(got), c["name"].toString())
        }
    }

    /** A model that answers each call with [reply] and keeps what it was sent. */
    private class FakeModel(private val reply: String) : JsonChatModel {
        val sent = mutableListOf<Triple<String, String, JsonObject>>()
        override suspend fun generateJson(system: String, user: String, schema: JsonObject, temperature: Double): String {
            sent += Triple(system, user, schema)
            return reply
        }
        override suspend fun ping() = Unit
    }

    @Test
    fun onDeviceAskSendsASchoolsLinesOnlyWithTheFlagAndNeverAnUnknownExtra(): Unit = runBlocking {
        for (on in listOf(true, false)) {
            val model = FakeModel("""{"answer":"none"}""")
            OnDeviceAi(model, { listOf(aiHouse(doc(2)["candidate"]!!)) }, devKinds = on).ask("Which school runs a bus?")
            val user = model.sent.single().second
            assertTrue(doc(if (on) 2 else 3)["expected"]!!.jsonPrimitive.content.replace(Regex("(?i)</?\\s*houses[^>]*>"), "") in user, user)
            assertFalse("99001" in user || "secretNote" in user || "Ramesh" in user, user)
        }
    }

    @Test
    fun onDeviceExtractReadsASchoolListingWithTheFlagAndRefusesTheKindWithout(): Unit = runBlocking {
        val e = v["extract"]!!.jsonObject
        val case = e["sanitize"]!!.jsonArray[0].jsonObject
        val model = FakeModel(case["raw"].toString())
        val draft = OnDeviceAi(model, { emptyList() }, devKinds = true).extractListing(e["listing"]!!.jsonPrimitive.content, "school")
        val (system, _, schema) = model.sent.single()
        assertEquals(e["system"]!!.jsonPrimitive.content, system.replace(Regex("listing-[0-9a-f]{6}"), "listing-abc123"))
        assertSame(KindAi.SCHOOL_LISTING_SCHEMA, schema)
        assertEquals(Json.parseToJsonElement(case["expected"].toString()), draftJson(draft))
        assertFailsWith<IllegalArgumentException> {
            OnDeviceAi(FakeModel("{}"), { emptyList() }).extractListing(e["listing"]!!.jsonPrimitive.content, "school")
        }
    }
}
