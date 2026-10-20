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

package app.doorprints.shared.country

import app.doorprints.shared.ai.ContactRedactor
import app.doorprints.shared.ai.DraftSanitizer
import app.doorprints.shared.export.ExportRows
import app.doorprints.shared.listing.ListingText
import app.doorprints.shared.location.PlaceLookup
import app.doorprints.shared.model.PhoneKey
import app.doorprints.shared.voice.VoiceInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TC-U-289 and TC-U-290 (docs/06): the country pack's characterization vectors (WP-C1) and the synthetic second pack (WP-C2),
 * read as the website's `country-pack.spec.ts` reads them (`docs/schemas/country-pack-vectors.json`). Section `in` is what
 * the code answered before the pack existed; section `zz` is the same call sites under a pack that shares nothing with
 * India's, which only works when every call site reads the pack.
 */
class CountryPackVectorsTest {
    private var restore: (() -> Unit)? = null

    @After
    fun putTheShippedPackBack() {
        restore?.invoke()
        restore = null
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val root: JsonObject =
        Json.parseToJsonElement(repoFile("docs/schemas/country-pack-vectors.json").readText()).jsonObject

    private fun str(e: JsonElement): String? = if (e is JsonNull) null else e.jsonPrimitive.content
    private fun long(e: JsonElement): Long? = if (e is JsonNull) null else e.jsonPrimitive.content.toLong()

    private fun sections(): List<Triple<String, CountryPack, JsonObject>> = listOf(
        Triple("in", IndiaPack, root.getValue("in").jsonObject),
        Triple("zz", ZzPack, root.getValue("zz").jsonObject),
    )

    private inline fun eachSection(check: (id: String, pack: CountryPack, v: JsonObject) -> Unit) {
        for ((id, pack, v) in sections()) {
            restore = Country.use(pack)
            try {
                assertSame(id, pack, Country.pack)
                check(id, pack, v)
            } finally {
                restore?.invoke()
                restore = null
            }
        }
    }

    @Test
    fun groupsDigitsWritesMoneyAndReadsAmountWordsAsTheVectorsSay() = eachSection { id, pack, v ->
        for (row in v.getValue("group").jsonArray) {
            val (digits, grouped) = row.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$id $digits", grouped, pack.money.group(digits))
        }
        for (m in v.getValue("money").jsonArray) {
            val o = m.jsonObject
            assertEquals("$id ${o["amount"]}", str(o.getValue("text")), ExportRows.rupees(long(o.getValue("amount"))!!))
        }
        for (a in v.getValue("amounts").jsonArray) {
            val o = a.jsonObject
            val text = str(o.getValue("text"))!!
            assertEquals("$id $text", long(o.getValue("value")), DraftSanitizer.price(text, mutableListOf()))
        }
        assertEquals(id, str(v.getValue("xlsxFormat").jsonObject.getValue("kotlin")), pack.money.xlsxFormat)
    }

    @Test
    fun comparesPhoneNumbersReadsThemOutOfAListingAndRedactsThemAsTheVectorsSay() = eachSection { id, _, v ->
        for (p in v.getValue("phoneKey").jsonArray) {
            val o = p.jsonObject
            val phone = str(o.getValue("phone"))
            assertEquals("$id $phone", str(o.getValue("key")), PhoneKey.of(phone))
        }
        for (l in v.getValue("listing").jsonArray) {
            val o = l.jsonObject
            val text = str(o.getValue("text"))!!
            val draft = ListingText.parse(text)
            assertEquals("$id $text", listOf(long(o.getValue("price")), str(o.getValue("priceType")), str(o.getValue("phone"))),
                listOf(draft.price, draft.priceType, draft.contactPhone))
        }
        for (r in v.getValue("redact").jsonArray) {
            val o = r.jsonObject
            val text = str(o.getValue("text"))!!
            assertEquals("$id $text", str(o.getValue("out")), ContactRedactor.redactGeneric(text))
        }
    }

    @Test
    fun asksTheGeocoderForThePacksCountryAndKeepsOnlyItsOwnPoints() = eachSection { id, pack, v ->
        val g = v.getValue("geocoder").jsonObject
        assertEquals(id, str(g.getValue("countryCode")), pack.geo.countryCode)
        assertEquals(id, str(g.getValue("queryName")), pack.geo.queryName)
        val box = g.getValue("box").jsonObject
        assertEquals(id, listOf("south", "west", "north", "east").map { box.getValue(it).jsonPrimitive.content.toDouble() },
            listOf(pack.geo.box.south, pack.geo.box.west, pack.geo.box.north, pack.geo.box.east))
        assertEquals(id, box.getValue("south").jsonPrimitive.content.toDouble(), PlaceLookup.INDIA.south, 0.0)
        val view = g.getValue("defaultView").jsonObject
        assertEquals(id, listOf("lat", "lon", "zoom").map { view.getValue(it).jsonPrimitive.content.toDouble() },
            listOf(pack.geo.defaultLat, pack.geo.defaultLon, pack.geo.defaultZoom))
        for (q in g.getValue("queries").jsonArray) {
            val o = q.jsonObject
            assertEquals("$id $o", str(o.getValue("query")), PlaceLookup.query(str(o.getValue("locality")), str(o.getValue("address"))))
        }
    }

    @Test
    fun namesTheCurrencyTheAreaUnitAndTheKeyTermsInTheAiWords() = eachSection { id, pack, v ->
        val p = v.getValue("prompt").jsonObject
        val line = p.getValue("moneyLine").jsonObject
        assertEquals(id, str(line.getValue("text")), Country.promptMoney(long(line.getValue("amount"))!!))
        assertEquals(id, str(p.getValue("houseKeyTerms")), VoiceInput.keyTerms("house"))
        assertEquals(id, str(p.getValue("name")), pack.name)
        assertEquals(id, str(p.getValue("word")), pack.money.word)
        assertEquals(id, str(p.getValue("currencyName")), pack.money.name)
        assertEquals(id, str(p.getValue("areaUnit")), pack.areaUnit)
        val locale = v.getValue("locale").jsonObject
        assertEquals(id, str(locale.getValue("fallback")), pack.locale.fallback)
        assertEquals(id, str(locale.getValue("fallback")), VoiceInput.langHint("fr"))
        for (pair in locale.getValue("speech").jsonArray) {
            val (language, tag) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$id $language", tag, VoiceInput.langHint(language))
        }
    }

    @Test
    fun indiaAnswersDifferentlyFromZedlandOnTheSameInputAndTheSwapIsUndone() {
        assertEquals("₹12,34,567", ExportRows.rupees(1_234_567))
        restore = Country.use(ZzPack)
        assertEquals("$1,234,567", ExportRows.rupees(1_234_567))
        restore?.invoke()
        restore = null
        assertEquals("₹12,34,567", ExportRows.rupees(1_234_567))
    }

    @Test
    fun aFoundPlaceOutsideThePacksBorderIsRefusedOneInsideIsKept() {
        // India's outline: a point near the equator in the Gulf of Guinea is not India; under Zedland's own test it is inside.
        assertNull(PlaceLookup.pick(listOf(3.0 to 4.0)))
        restore = Country.use(ZzPack)
        assertEquals(PlaceLookup.Found(3.0, 4.0), PlaceLookup.pick(listOf(3.0 to 4.0)))
        assertNull(PlaceLookup.pick(listOf(8.0 to 4.0)))
    }

    @Test
    fun theDerivedPatternsAreBuiltOncePerPackAndFromThatPackOnly() {
        val india = Derived(IndiaPack)
        val zz = Derived(ZzPack)
        assertTrue(india.listingPrice.containsMatchIn("Rs 25,000"))
        assertTrue(!zz.listingPrice.containsMatchIn("Rs 25,000"))
        assertTrue(zz.listingPrice.containsMatchIn("Zd 25,000"))
        val first = Country.derived
        assertSame(first, Country.derived)
        restore = Country.use(ZzPack)
        assertNotNull(Country.derived)
        assertTrue(Country.derived !== first)
    }
}
